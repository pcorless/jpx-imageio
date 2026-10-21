# About This Release
jpx-imageio 0.1.0 is the first public release: a pure-Java JPEG 2000 (ISO/IEC 15444-1) decoder with an ImageIO reader. It has no runtime dependencies, needs Java 11, and is licensed Apache 2.0, with portions derived from OpenJPEG 2.5.4 (BSD-2; see `NOTICE`).

Decoding is bit-exact against OpenJPEG 2.5.4 on the test fixtures and on 3,742 of 3,744 JPEG 2000 streams taken from real-world PDFs. Both decoders reject the other 2. Threads are on by default. With them it decodes about 2x faster than OpenJPEG's own threaded decode on that corpus, and about 10x faster than JJ2000 (jai-imageio-jpeg2000). Single-threaded it runs at about 0.65-0.8x native OpenJPEG.

Put the jar on the class or module path (module `io.github.pcorless.jpx`) and `ImageIO.read` picks up `.jp2` and `.j2k` files.

```xml
<dependency>
    <groupId>com.github.pcorless</groupId>
    <artifactId>jpx-imageio</artifactId>
    <version>0.1.0</version>
</dependency>
```

## Decoder
* J2K codestreams end to end: tier-1/tier-2, tiles, 5/3 and 9/7 DWT, MCT
* JP2 files: boxes, palettes (`pclr`/`cmap`), channel definitions (`cdef`), `colr` colour (sRGB, grey, CMYK, ICC)
* Multithreaded decode across tiles, code-blocks, DWT strips and MCT; output is bit-identical at any thread count
* Region decode touches only the tiles it meets; power-of-two subsampling drops resolution levels
* Standalone API: `Jpeg2000Decoder.decodeImage(...)` / `Jpeg2000Decoder.Options`

## ImageIO Reader
* `Jpeg2000ImageReader` / SPI with `read` and `readRaster`, source region, subsampling, band selection and destination offset
* `Jpeg2000ImageReaderSpi.preferOverOtherReaders()` puts this reader ahead of JJ2000 when both are installed
* A broken `pclr`/`cmap` or `cdef` box is skipped with a warning instead of failing the read

## Security
* Refuses decompression bombs: more than 2^28 samples is refused before allocation (configurable via `-Dio.github.pcorless.jpx.maxSamples`)
* An ICC profile is validated before it is trusted
* Hostile input fails with `JpxException` (an `IOException`); see `SECURITY.md` for private reporting

## Build & Bundle
* Published to Maven Central as `com.github.pcorless:jpx-imageio`
* Maven and Gradle builds; CI on JDK 11 and 21

**Full Changelog**: https://github.com/pcorless/jpx-imageio/commits/v0.1.0
