# jpx-imageio

Pure-Java JPEG 2000 (ISO/IEC 15444-1) decoder with an ImageIO reader. No runtime dependencies,
Java 11, Apache 2.0, with portions derived from OpenJPEG 2.5.4 (BSD-2; see `NOTICE`).

Status: J2K and JP2 decoding is bit-exact against OpenJPEG 2.5.4 on the
fixtures and on 3,742 of 3,744 JPEG 2000 streams taken from real PDFs (the other 2 are rejected by both); the ImageIO
reader meets `docs/IMAGEIO-CONTRACT.md`. Single-threaded it runs at about 0.8x OpenJPEG; with its default threads about 2x OpenJPEG's own
threaded decode (`docs/PERFORMANCE.md`). Not fuzzed yet.

## Use

Put the jar on the class path (or module path: module `io.github.pcorless.jpx`) and ImageIO finds it:

```java
BufferedImage image = ImageIO.read(new File("photo.jp2"));
```

- `read` gives every channel after the JP2 palette (`pclr`/`cmap`) and channel definitions
  (`cdef`), in a colour model from the `colr` box (enumerated sRGB/grey/CMYK, or ICC), opacity
  last. Samples keep their depth; signed channels are shifted into unsigned bands.
- `readRaster` gives the same samples in channel order, with no colour model.
- `ImageReadParam`: source region (only the tiles it meets are decoded), subsampling (powers of
  two discard resolution levels), source/destination bands, destination and offset.
- With JJ2000 (`jai-imageio-jpeg2000`) also installed, call
  `Jpeg2000ImageReaderSpi.preferOverOtherReaders()` to be sure this reader is offered first.
- Threads: on by default (a shared pool of up to `min(cores, 8)` threads, the caller included).
  `-Dio.github.pcorless.jpx.parallelism=1` turns them off; `Jpeg2000Threads.setDefaults(...)` or a
  `Jpeg2000ImageReadParam` choose per process or per read. Output never depends on the count.
- Without ImageIO: `Jpeg2000Decoder.decodeImage(bytes, 0, bytes.length, reduce)`, or with
  `Jpeg2000Decoder.Options` for an area, progress and threads.

- Requirements: `docs/IMAGEIO-CONTRACT.md`
- Provenance rules and the per-file record: `docs/PROVENANCE-RULES.md`, `PROVENANCE.md`
- Observations that drove decisions: `docs/DECISIONS.md`

## Build

```
./gradlew build          # or: mvn verify
```
