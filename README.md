# jpx-imageio

Pure-Java JPEG 2000 (ISO/IEC 15444-1) decoder with an ImageIO reader. No runtime dependencies,
Java 11, Apache 2.0, with portions derived from OpenJPEG 2.5.4 (BSD-2; see `NOTICE`).

Status: J2K and JP2 decoding is bit-exact against OpenJPEG 2.5.4 on the
fixtures and on 3,742 of 3,744 JPEG 2000 streams taken from real PDFs (the other 2 are rejected by both); the ImageIO
reader meets `docs/IMAGEIO-CONTRACT.md`. Single-threaded it runs at about 0.8x OpenJPEG; with its default threads about 2x OpenJPEG's own
threaded decode (`docs/PERFORMANCE.md`). Hostile input fails with a `JpxException` (an `IOException`); a mutation fuzz of the fixtures found nothing else escaping, but no coverage-guided fuzzing yet.

## Get it

Maven:

```xml
<dependency>
    <groupId>com.github.pcorless</groupId>
    <artifactId>jpx-imageio</artifactId>
    <version>0.1.0</version>
</dependency>
```

Gradle:

```groovy
implementation 'com.github.pcorless:jpx-imageio:0.1.0'
```

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
- Size limit: a decode that would hold more than 2^28 samples (output or one tile, summed over
  channels) is refused before anything is allocated, as a JPEG 2000 header of a few hundred bytes
  can declare billions. `-Dio.github.pcorless.jpx.maxSamples=N`, `Jpeg2000ImageReadParam.setMaxSamples`
  or `Jpeg2000Decoder.Options.maxSamples` change it.
- Reading from a stream, `ImageIO.setUseCache(false)` keeps ImageIO from first copying it to a
  temporary file (about 5% faster on the corpus); this reader keeps the stream's bytes in memory either way.
- Threads: on by default (a shared pool of up to `min(cores, 8)` threads, the caller included).
  `-Dio.github.pcorless.jpx.parallelism=1` turns them off; `Jpeg2000Threads.setDefaults(...)` or a
  `Jpeg2000ImageReadParam` choose per process or per read. Output never depends on the count.
- Without ImageIO: `Jpeg2000Decoder.decodeImage(bytes, 0, bytes.length, reduce)`, or with
  `Jpeg2000Decoder.Options` for an area, progress and threads.

## Performance

Decoding all 3,744 JPEG 2000 streams found in a corpus of real-world PDFs (4,247 megapixels,
mostly 9/7 photos), whole images to samples, OpenJDK 21:

| Decoder | One thread | Threaded |
|---|---|---|
| **jpx-imageio** | **28.9 MP/s** | **129 MP/s** (8 threads, 4.5x) |
| OpenJPEG 2.5.4, stock Release build (`-O3 -ffast-math`) | 44.4 MP/s | 60.7 MP/s (8 threads) |
| JJ2000 (jai-imageio-jpeg2000 1.4.0) | 2.6 MP/s | n/a |

- About 10x faster than JJ2000, the usual pure-Java choice, on the files both decode. JJ2000 also
  times out or fails on files this reader decodes.
- Single-threaded it runs at about 0.65-0.8x native OpenJPEG. With threads, on these mostly lossy
  PDF images it is about 2x OpenJPEG's own threaded decode: OpenJPEG gains only 1.3-1.4x from 8
  threads here, even on images over 4 MP, where jpx-imageio gains 4-5x by running the inverse
  wavelet, code-blocks and colour transform in parallel, within a tile as well as across tiles.
- On large lossless images OpenJPEG's threading pays off and it stays ahead: on an 8000 x 6000 RGB
  5/3 image at 8 threads, 70 MP/s against 43 single-tile, and 51 against 48 with 1024 x 1024 tiles.
- Large tiled images scale best (6.8x on 16 cores); output is bit-identical whatever the thread count.
- Through ImageIO (`ImageIO.read` to a `BufferedImage`, all format work included) it reads the
  same corpus at about 105-110 MP/s with its default threads (about 26 MP/s on one), needing about
  6 bytes of heap per sample at the peak: the 5171 x 2923 x 4 largest image reads in a 363 MB heap.
- Lossless (5/3) images, the norm for archival, medical and GIS imagery, gain most from the
  cache-friendly wavelet passes and the tuned MQ decoder: on one thread, a smooth 8000 x 6000 RGB
  decodes 19% faster from the wavelet change and another 4% from the MQ one.
- A source region decodes only the tiles it meets, and power-of-two subsampling discards whole
  resolution levels instead of decoding and then shrinking.

Method, per-phase profiles and the changes tried: `docs/PERFORMANCE.md`.

## Documentation

- Requirements: `docs/IMAGEIO-CONTRACT.md`
- Provenance rules and the per-file record: `docs/PROVENANCE-RULES.md`, `PROVENANCE.md`
- Observations that drove decisions: `docs/DECISIONS.md`

## Build

```
./gradlew build          # or: mvn verify
```
