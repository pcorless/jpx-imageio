# icepdf-jpx

Pure-Java JPEG 2000 (ISO/IEC 15444-1) decoder with an ImageIO reader. No runtime dependencies,
Java 11, Apache 2.0, with portions derived from OpenJPEG 2.5.4 (BSD-2; see `NOTICE`).

Status: **Phase 1** (GH-613). J2K and JP2 decoding is bit-exact against OpenJPEG 2.5.4 on the
fixtures and on 3,742 of 3,744 real PDF streams (the other 2 are rejected by both); the ImageIO
reader meets `docs/IMAGEIO-CONTRACT.md`. Not yet tuned for speed (single-threaded) or fuzzed.

## Use

Put the jar on the class path (or module path: module `org.icepdf.jpx`) and ImageIO finds it:

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
- Without ImageIO: `Jpeg2000Decoder.decodeImage(bytes, 0, bytes.length, reduce)`.

- Requirements: `docs/IMAGEIO-CONTRACT.md`
- Provenance rules and the per-file record: `docs/PROVENANCE-RULES.md`, `PROVENANCE.md`
- Observations that drove decisions: `docs/DECISIONS.md`

## Build

```
./gradlew build          # or: mvn verify
```
