# Threading plan (proposal, 2026-10-17)

Goal: decode one large image on several cores, without oversubscribing a host (ICEpdf) that
already decodes several images at once, and without changing a single output sample.

## Facts it rests on

- Single-threaded: ~26 MP/s (`PERFORMANCE.md`). Serial parts are small: codestream parsing
  and tier-2 (packet headers) are about 1-2% of the time; everything else splits.
- Corpus images of 1 MP or more: 876, holding 88% of the pixels. 608 of them are tiled
  (35, 70 or 80 tiles is common); 268 are one tile.
- ICEpdf decodes images on its own bounded pools (`Library` common pool and image pool,
  `org.icepdf.core.library.threadPoolCap`), several images at once. A decode call already runs
  on an ICEpdf worker.
- ICEpdf sees the decoder only through ImageIO (no compile-time dependency); plain `ImageIO.read`
  users must get the speed-up without writing code.

## Where the parallel work is

| Level | Work items | Joins | Covers |
|---|---|---|---|
| Tiles | each tile: t2, t1, DWT, MCT, level shift, then a copy into a disjoint output area | none until the end | tiled images (608 of 876 large) |
| Code-blocks (within a tile-component) | each code-block decodes into its own disjoint area of the tile buffer | before the DWT | single-tile images |
| DWT (per level) | horizontal pass: strips of rows; vertical pass: blocks of 16 columns | between passes and between levels | single-tile images |
| ICT + level shift | ranges of samples | before the tile copy | all |

Every work item writes disjoint memory and the per-sample arithmetic is unchanged: results
are bit-identical whatever the thread count (tested by running the corpus A/B with 1 and N
threads).

## The one hard rule: the caller always helps

A decode running on an ICEpdf pool thread must never block waiting for a task queued on a
bounded pool: if every worker is a decode waiting on queued subtasks, nothing runs (deadlock).
So all parallel loops use **caller-helps** scheduling:

```
parallelFor(n items):
    next = AtomicInteger(0); done = CountDownLatch-like counter of *claimed* items
    submit up to (parallelism - 1) helpers to the executor; each: while ((i = next.getAndIncrement()) < n) run(i)
    the caller runs the same loop
    when next >= n, the caller waits only for items already *claimed* (running), never for helpers
    still in the queue: a helper that starts late finds no work and returns at once
```

This degrades gracefully: with a saturated or zero-size executor the caller does all the work
alone, at today's speed. It's the pattern `ForkJoinPool` gives for free with `join()`, but works
with any `Executor` (ICEpdf's are `ThreadPoolExecutor`s).

## Proposed API

```java
/** How a decode may use threads. Immutable; one instance can be shared. */
public final class Jpeg2000Threads {
    /** Caller thread only (today's behaviour). */
    public static final Jpeg2000Threads NONE;

    /** Up to `parallelism` threads in total, the caller included, helpers from `executor`. */
    public static Jpeg2000Threads of(Executor executor, int parallelism);

    /** The process-wide default: see below. */
    public static Jpeg2000Threads defaults();
    public static void setDefaults(Jpeg2000Threads threads);
}
```

- **Decoder:** the `decodeImage(...)` overloads gain a `Jpeg2000Threads` argument (an options
  object would be tidier once there are more knobs; reduce, area, progress and threads are
  four already: proposed `Jpeg2000Decoder.Options` builder, old overloads kept).
- **ImageIO:** `getDefaultReadParam()` returns a `Jpeg2000ImageReadParam extends
  ImageReadParam` with `setThreads(Jpeg2000Threads)`; a plain `ImageReadParam` means
  `Jpeg2000Threads.defaults()`.
- **Default:** a lazily started, shared, daemon pool owned by icepdf-jpx with parallelism
  `min(availableProcessors, 8)` (8 because past that tier-2 and memory bandwidth flatten the
  curve; to be measured), overridable with system property `org.icepdf.jpx.parallelism`
  (`1` = no threads). Being one shared, bounded pool, nested use from many callers can never
  run more than `parallelism` helper threads in total, plus the callers themselves.
- **ICEpdf** (no compile dependency needed): either leave the default, or set
  `-Dorg.icepdf.jpx.parallelism=N` from its `threadPoolCap` logic. If tighter integration is
  wanted later, ICEpdf can compile against icepdf-jpx optionally and call
  `Jpeg2000Threads.setDefaults(of(Library.getImageThreadPool(), n))` when the class is present,
  so decode helpers share ICEpdf's own cap.
- **Small images stay serial:** parallel loops only start above a work threshold (first guess:
  a tile-component of 256K samples, code-block lists of 16+, DWT levels of 128+ rows), so the
  1,947 corpus images under 0.1 MP pay no overhead.
- **Abort and progress** keep working: the progress hook is called from whichever thread
  finished a tile (callers already must not assume a thread), and abort is checked per work
  item, which also makes it finer-grained than today's per-tile check.
- **Memory:** one `CodeBlockDecoder` (two small buffers) per worker, held in the work loop,
  not a `ThreadLocal`, so pool threads don't keep buffers after a decode. Tile parallelism keeps
  up to `parallelism` tiles' buffers alive at once instead of one: bounded, and those tiles are
  small when there are many of them.

## Order of work

1. `parallelFor` with caller-helps, and its tests (saturated executor, zero helpers, exception
   in an item, abort).
2. Tiles in parallel (the biggest share of large images, no change inside the tile decoder).
3. Code-blocks in parallel, then DWT strips, then ICT ranges (single-tile photos).
4. Measure 1/2/4/8/16 threads on the large-image subset, set the default cap and thresholds from
   that, record in `PERFORMANCE.md`; corpus A/B at N threads must stay 3,742/3,744 EXACT.
5. ICEpdf: run the viewer and the render QA with the default, then decide whether ICEpdf sets
   the property or shares its pool.

## Open questions for review

- Default on or off for plain ImageIO users? Proposed **on** (bounded shared pool), as
  OpenJPEG's own tools default to threads; `org.icepdf.jpx.parallelism=1` turns it off.
- Own pool vs `ForkJoinPool.commonPool()`: proposed own pool, because the common pool is shared
  with parallel streams and its size is set elsewhere.
- Is an options object worth it now, or keep adding overloads until a third knob arrives?
