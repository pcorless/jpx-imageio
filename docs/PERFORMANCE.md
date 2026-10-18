# Performance

How fast icepdf-jpx decodes, against what, and where its time goes. Tools: `tools/bench/`.

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
| icepdf-jpx | 175.8 s | 24.2 | 19.3 | 27.2 | 19.7 | 28.3 |
| OpenJPEG strict `-O2` | 138.5 s | 30.7 | 24.8 | 34.6 | 25.8 | 34.8 |
| OpenJPEG Release `-O3 -ffast-math` | 114.2 s | 37.2 | 29.9 | 43.9 | 30.4 | 43.0 |
| JJ2000 1.4.0 (first 833 files) | | 2.6 | | | | |

icepdf-jpx ran at 0.79x strict OpenJPEG and 0.65x its stock build, about 10x faster than
JJ2000 (which also timed out, at 20 s, on 4 of those 833 files).

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

Every change is checked bit-exact: fixtures, and the full corpus A/B (3,742 of 3,744 EXACT).

After the vertical change (every 4th file): MQ decoder 30%, 9/7 DWT 39% (horizontal ~22%,
vertical ~17%), ICT 9%, tier-1 passes and placement 11%, level shift 3%.

## Next, by expected gain

1. **Threads.** OpenJPEG decodes code-blocks and DWT strips in parallel; so far everything here
   is on one thread. Code-blocks are independent, and the DWT splits into row and column strips.
   Largest lever by far for a single large image; ICEpdf already renders pages in parallel, so
   the pool must be shared and bounded.
2. **Tier-1 state in locals.** The MQ registers (C, A, CT, position) are fields written on every
   decision; OpenJPEG keeps them in registers inside each pass (its `DOWNLOAD/UPLOAD_MQC_VARIABLES`
   macros). Moving them into the pass loops is a mechanical port of that form.
3. **Fuse ICT, level shift and clamp** into one pass over the three components, with float
   rounding instead of `Math.rint` on doubles.
4. 5/3 vertical pass in column blocks (280 corpus files use 5/3).
