# Performance

_A dated record: measurements and plans as made, with ICEpdf as the first host._

How fast jpx-imageio decodes, against what, and where its time goes. Tools: `tools/bench/`.

## Method

- **Corpus:** every JPEG 2000 stream extracted from the ICEpdf QA PDFs (`~/dev/jpx-corpus`,
  3,744 files, 299 MB, 4,247 megapixels). 1,947 files are under 0.1 MP, but the 876 files of
  1 MP or more hold 88% of the pixels, so totals are dominated by large photos (9/7, 8-bit,
  mostly RGB, 32x32 or 64x64 code-blocks, 1-6 layers).
- **One thread, one core** (`taskset`), whole-image decode to component samples, files read into
  memory first. JVM: OpenJDK 21, `-Xmx8g`, the first files decoded once untimed for JIT warm-up.
- **OpenJPEG 2.5.4** in-process (`tools/bench/opjbench.c`, `opj_codec_set_threads(1)`), built two
  ways: strict (RelWithDebInfo, `-O2`, the oracle build) and stock Release (`-O3 -ffast-math`).
- **JJ2000** (jai-imageio-jpeg2000 1.4.0) through ImageIO `readRaster`, as ICEpdf uses it.
- **Iteration:** every 4th corpus file (936 files) for A/B runs (`tools/bench/ab.sh`); run-to-run
  noise is about ±3%.

## Baseline (2026-10-17, commit 4500077)

| Decoder | Corpus time | MP/s | < 0.1 MP | 0.1-1 MP | 1-4 MP | > 4 MP |
|---|---|---|---|---|---|---|
| jpx-imageio | 175.8 s | 24.2 | 19.3 | 27.2 | 19.7 | 28.3 |
| OpenJPEG strict `-O2` | 138.5 s | 30.7 | 24.8 | 34.6 | 25.8 | 34.8 |
| OpenJPEG Release `-O3 -ffast-math` | 114.2 s | 37.2 | 29.9 | 43.9 | 30.4 | 43.0 |
| JJ2000 1.4.0 (3,732 files both decode) | 1,618 s | 2.6 | | | | |

jpx-imageio ran at 0.79x strict OpenJPEG and 0.65x its stock build, and 9.6x faster than
JJ2000 on the 3,732 files both decode (169 s against 1,618 s). JJ2000 also timed out, at 20 s
each, on 10 files.

## Where the time goes

JFR (`settings=profile`) over the full corpus, self time:

| Share | Where |
|---|---|
| 53% | 9/7 inverse DWT: `interleave` 18, `step2` 19, `decode97` 12, `step1` 4 |
| 24% | MQ decoder (`decode` 21, `renormalize` 3) |
| 9% | DC level shift + clamp (`Math.rint`, long arithmetic) |
| 8% | tier-1 passes and coefficient placement |
| 1% | ICT, tier-2, everything else |

The DWT's vertical pass worked one column at a time with a stride of the tile width, so nearly
every access missed the cache: OpenJPEG does that pass 8 columns at a time for the same reason.

## Changes

| Change | Subset MP/s | Kept |
|---|---|---|
| baseline | 23.2 | |
| 9/7 vertical pass 16 columns at a time (opj_v8dwt layout, scalar) | 25.7 / 26.5 (+11-14%) | yes |
| + 9/7 horizontal pass 16 rows at a time (transposed into lanes) | 23.9 | no: the transpose costs more |
| + 9/7 horizontal lifting on split low/high arrays | 24.9 / 26.0 | no: within noise |
| ICT fused with level shift and clamp (one pass over the three components) | 26.4 / 26.7 vs 26.3 / 25.3 (about +3%, edge of noise) | yes |
| MQ registers (C, A, CT) in locals in the MQ passes (OpenJPEG's DOWNLOAD/UPLOAD_MQC_VARIABLES), decode inlined by hand at each site | 25.2 / 25.1 vs 25.9 / 26.2 (-3.5%) | no |

Every change is checked bit-exact: fixtures, and the full corpus A/B (3,742 of 3,744 EXACT).

After the vertical change (every 4th file): MQ decoder 30%, 9/7 DWT 39% (horizontal ~22%,
vertical ~17%), ICT 9%, tier-1 passes and placement 11%, level shift 3%.

## Threads (2026-10-17)

`Jpeg2000Threads` / `THREADING-PLAN.md`. Every 4th corpus file, pinned to the 16 physical cores
(CPUs 0-15), wall-clock per file, `-Dio.github.pcorless.jpx.parallelism=N`:

| Images | 1 | 2 | 4 | 8 | 16 threads |
|---|---|---|---|---|---|
| all (936 files) | 28.7 | 52.9 | 90.8 | 123.2 (4.3x) | 135.9 (4.7x) MP/s |
| single-tile, >= 1 MP | 41.6 | 74.1 | 122.0 | 147.5 (3.5x) | 153.2 MP/s |
| tiled, >= 1 MP | 22.0 | 41.9 | 77.4 | 122.4 (5.6x) | 149.0 (6.8x) MP/s |
| < 0.1 MP | 22.0 | 29.1 | 32.0 | 30.1 | 29.3 MP/s |

OpenJPEG 2.5.4 on the same files and cores, `opj_codec_set_threads`: strict build 34.8 MP/s at
1 thread, 46.3 at 8; stock Release 44.4 at 1, 60.7 at 8.

Single-tile photos, phase times for 60 of them (s), after removing the tile-to-output copy:

| | layout + tier-2 | code-blocks | DWT | MCT + level shift | outside the tile |
|---|---|---|---|---|---|
| 1 thread | 0.27 | 1.10 | 6.52 | 1.69 | 0.01 |
| 8 threads | 0.27 | 0.43 | 1.20 | 0.70 | 0.01 |

What's left at 8 threads: tier-2 is serial by nature (as in OpenJPEG); MCT + level shift scales
2.4x because it is memory-bound (allocating the three output arrays on several threads changed
nothing). Before the fix, copying a single tile into fresh whole-image arrays cost 0.79 s of
3.30 s; a tile that is exactly the output is now handed over as is.

Tried and dropped: exact float rounding by adding 1.5 x 2^23 instead of `Math.rint` (2.5x faster
in a micro-benchmark, no change end to end: the pass is memory-bound).

## Next, by expected gain

1. ~~Threads~~: done, above.
2. ~~Tier-1 state in locals~~: tried, 3.5% slower (above). C2 compiled the passes normally;
   the likely reason (not proven) is that once `MqDecoder.decode` is inlined into a pass loop,
   C2 already keeps the MQ fields in registers (fields and `int[]` elements are separate alias
   classes, so array stores don't force them back to memory), while the hand-inlined version
   loses the constant-`ci` unrolling of the old step calls. Unrolling it by hand would push the
   cleanup pass toward C2's 8,000-byte huge-method limit. Don't retry without a JMH
   micro-benchmark of tier-1 alone.
3. ~~Fuse ICT, level shift and clamp~~: done, about +3%. `Math.rint` stays: it is exact,
   and the level shift no longer shows as a separate cost in the profile worth chasing.
4. ~~5/3 vertical pass in column blocks~~: done (2026-10-20), below.

## 5/3 vertical pass in column blocks (2026-10-20)

The 5/3 vertical pass worked one column at a time, striding the tile width per sample, as the
9/7 one did before its change. It now takes 16 columns at a time (`columns53`, the integer
counterpart of `columns97`), each lane getting the reference lifting's operations in its order.

| Files, one thread unless noted | Before | After |
|---|---|---|
| the 280 corpus streams that use 5/3 (178 MP) | 23.0 MP/s | 25.4 MP/s (+10%) |
| synthetic 8000 x 6000 RGB, smooth (28.5 MB lossless), single-tile and 1024 tiles | 7.6 MP/s | 9.05 MP/s (+19%) |
| the same at 8 threads | 53.8 MP/s | 53.3 MP/s (within noise) |

Bit-identical on all of them and on the fixtures. The gain grows with image size (corpus 5/3
images are mostly modest), which is where lossless archival, medical and GIS images sit; with
threads the vertical pass is already spread across cores and the cache effect disappears in the
noise. A near-incompressible synthetic image (96 MB for 144 MB raw) is MQ-bound and showed +3-6%.

## ImageIO reads without the per-channel planes (2026-10-20)

`Jpeg2000ImageReader` copied the decoded components into a full `int[]` plane per channel
(stored values, subsampled) and then into the destination raster: three copies alive at once,
about 9-10 bytes a sample at the peak of an 8-bit read. It now writes each output row straight
from the component into the raster through one reusable row buffer, and lets a component go
once the last band that needs it is written.

Smallest `-Xmx` (SerialGC, one thread, 16 MB steps) for `ImageReader.read`:

| Image | Before | After |
|---|---|---|
| synthetic 8000 x 6000 RGB 5/3, single tile | 1402 MB | 867 MB (-38%) |
| the same, 1024 x 1024 tiles | 1402 MB | 851 MB (-39%) |
| largest corpus stream (5171 x 2923 x 4, 9/7) | 615 MB | 363 MB (-41%) |
| second largest (9/7) | 520 MB | 300 MB (-42%) |
| third largest (9/7) | 426 MB | 426 MB |

The third is bound by the decode itself (the 9/7 tile's float buffers alongside the integer
output of the colour transform), not by the copy. One fewer pass over the samples also makes
reads faster: every 4th corpus file (936), default threads, `read` 46.8 -> 57.4 MP/s (+23%),
`readRaster` 48.7 -> 57.2 MP/s. Identical output from both on all 936 files and the fixtures.

## README figures re-measured (2026-10-20, commit after eb2e010)

Same method as the Threads table: every 4th corpus file (936), JVM on CPUs 0-15, decoder threads
set by `-Dio.github.pcorless.jpx.parallelism`. `JpxBench` (one decode thread): 28.7 / 29.0 MP/s
(was 28.7). Eight threads: 128.9 / 129.0 MP/s (was 123.2). Through ImageIO `read` with the reader
change above: 55.1 / 54.2 MP/s at 8 threads. Pinning the whole JVM to one core (GC included) gives
about 27 MP/s for one decode thread, so keep the 0-15 pinning when comparing.

## OpenJPEG's threaded decode, double-checked (2026-10-20)

`opjbench` now exits if `opj_codec_set_threads` fails (it doesn't: libopenjp2 is built with
`OPJ_USE_THREAD=ON`). Every 4th corpus file, CPUs 0-15, MP/s:

| Images | jpx-imageio 1 / 8 threads | OpenJPEG Release 1 / 8 threads |
|---|---|---|
| < 0.1 MP (495 files) | 21.0 / 28.8 (1.4x) | 29.3 / 23.5 (0.8x) |
| 0.1-1 MP (228) | 31.0 / 105.7 (3.4x) | 50.4 / 58.0 (1.2x) |
| 1-4 MP (150) | 22.3 / 116.8 (5.2x) | 35.8 / 51.9 (1.4x) |
| > 4 MP (63) | 35.4 / 149.0 (4.2x) | 52.9 / 70.5 (1.3x) |

OpenJPEG overall: 44.8 / 52.0 / 56.6 / 60.1 / 47.6 MP/s at 1 / 2 / 4 / 8 / 16 threads, matching
the earlier 44.4 and 60.7. Its own `opj_decompress -threads 8` agrees (largest stream 0.99 s ->
0.88 s, 164% CPU). On these lossy 9/7 images its SIMD wavelet is already fast and what it doesn't
thread dominates (not profiled; the scaling is what was measured).

Large lossless images are the other way round: synthetic 8000 x 6000 RGB 5/3, 1 / 8 threads,
OpenJPEG 14.7 / 69.6 MP/s single-tile and 14.1 / 51.3 with 1024 x 1024 tiles; jpx-imageio
9.3 / 42.5 and 9.5 / 48.3. Code-blocks dominate there and OpenJPEG threads them well; it stays
ahead at 8 threads, 1.6x single-tile and about even tiled.
