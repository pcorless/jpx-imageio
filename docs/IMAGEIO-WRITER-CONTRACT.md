# JPEG 2000 writer: ImageIO contract

_GH-2. First use: ICEpdf redaction (pcorless/icepdf#697) writes a burned JPXDecode image back as
JPEG 2000 instead of Flate RGB._

A deliberately basic encoder behind a normal `javax.imageio` writer. It writes what PDF's
`JPXDecode` takes, a raw JPEG 2000 codestream (J2K); the colour space comes from the PDF
dictionary, so there are no JP2 boxes.

**What it writes**
- 8-bit unsigned grey (1 component) or RGB (3 components, with the multiple component transform).
- One tile, one quality layer, LRCP, 64 x 64 code-blocks, code-block style 0, no precincts,
  5 decomposition levels (fewer for an image under 32 pixels on a side; `setLevels` changes it).
- **Lossless** (the default): 5/3 wavelet and RCT. Decodes bit-exactly, and the bytes are the
  ones `opj_compress` 2.5.4 writes with its defaults, less its `COM` marker.
- **Lossy**: 9/7 wavelet and ICT, one scalar quantiser per sub-band. A quality `q` below 1 sets a
  base step of 2^(6 (1 - q)) sample units; each band's step is that over the band's 9/7 synthesis
  norm (OpenJPEG's `opj_dwt_calc_explicit_stepsizes` with its unit step scaled), so every band adds
  about the same error. No rate-distortion optimisation and no rate control: you choose a quality,
  not a size.
- Guard bits: 2 (OpenJPEG's default), more only if a code-block needs them (3-7 checked to
  decode the same in both decoders).
- No precincts are signalled, so a resolution wider or taller than 2^15 has several (the default
  precinct size); checked on 40000 x 8, 8 x 40000 and 70000 x 2 images in both decoders.
- Size limit: an image of more than 2^28 samples (width x height x components, after the source
  region and subsampling) is refused before a row is read, with a `JpxException` whose
  `isUnsupported()` is true. Same limit and property as the reader
  (`-Dio.github.pcorless.jpx.maxSamples`); `Jpeg2000ImageWriteParam.setMaxSamples` or
  `Jpeg2000Encoder.Options.maxSamples` change it. At the limit the encoder holds about 1 GB of
  samples (4 bytes each), plus the source image.
- Out of scope: more than one layer, rate control, ROI, precincts, tiles, 16-bit, palette, ICC,
  alpha, CMYK, a JP2 wrapper.

**Registration**
- `META-INF/services/javax.imageio.spi.ImageWriterSpi`, plus `provides` in `module-info`.
- Format names `jpeg2000`, `JPEG2000`, `jpeg 2000`, `JPEG 2000`, `j2k`, `J2K`; suffixes `j2k`,
  `j2c`. Not `jp2`: the output is a codestream, not a JP2 file. No MIME type (none is registered
  for a raw codestream).
- The reader and writer SPIs name each other, so `ImageIO.getImageWriter(reader)` and
  `getImageReader(writer)` work.
- `canEncodeImage` is true for the images below and false for the rest.

**Images**
- Any `RenderedImage` or `Raster` with 8-bit samples and 1 or 3 colour components: the
  `BufferedImage` types `BYTE_GRAY`, `3BYTE_BGR`, `INT_RGB`, `INT_BGR`, `INT_ARGB`, `4BYTE_ABGR`,
  and custom ones of the same shape. Three components that aren't RGB (YCbCr, Lab) are written as
  they are.
- Indexed (`IndexColorModel`, including `BYTE_BINARY`): written as grey when every palette entry
  is grey, else as RGB.
- Alpha is not written: the colour bands are, with a warning. Premultiplied alpha is refused
  (the colours would be wrong without it).
- Refused with an `IIOException` naming the reason: other bit depths (16-bit, 565/555), 2 or
  4+ colour components (CMYK), premultiplied alpha.
- A `Raster` (`canWriteRasters` is true) writes its bands as they are: 1 or 3 of them, or the
  ones `setSourceBands` picks.

**`ImageWriter` surface**
- `setOutput` takes an `ImageOutputStream`. One image per stream; `write(streamMetadata, image, param)`.
- `ImageWriteParam` (`getDefaultWriteParam` returns a `Jpeg2000ImageWriteParam`):
  - compression: always on. `MODE_EXPLICIT` with a quality below 1 is lossy, anything else is
    lossless. No compression types. `isCompressionLossless` is true at quality 1.
  - source region, source subsampling with offsets, source bands: honoured.
  - destination offset and destination type: don't apply to a codestream, ignored.
  - `Jpeg2000ImageWriteParam.setLevels`, `setThreads` and `setMaxSamples`; a plain
    `ImageWriteParam` works too.
- Metadata: none is written. `getDefault*Metadata` and `convert*Metadata` return null; metadata and
  thumbnails passed in are left out with a warning.
- Listeners: `IIOWriteProgressListener` (started, progress as code-blocks finish, complete) and
  `IIOWriteWarningListener`. `abort()` is checked between code-blocks; an aborted write writes
  nothing and reports `writeAborted`. The writer is reusable after a write or an abort.
- Threads: as the reader's (`Jpeg2000Threads.defaults()` unless the param sets them). Rows are
  read, level-shifted and colour-transformed in one pass over the threads, then the wavelet passes
  and the code-blocks are spread over them. Output never depends on the thread count.
- Rows come from `Raster.getDataElements`, one call per row, for byte-interleaved and int-packed
  rasters (the ones Java2D makes), else `getSamples`. The image's `DataBuffer` array is never
  taken, so a host's image keeps its Java2D acceleration. A `RenderedImage` with several tiles is
  copied once (`getData`) first.
- Without ImageIO: `Jpeg2000Encoder.encode(bytes, width, height, components, options)` for
  interleaved samples, or `encode(source, ..., out)` with a row source and an `OutputStream`.

**Tests:** `ImageWriterContractTest` (these checks), `Jpeg2000EncoderTest` (round trips at every
size, the opj_compress byte identity on the fixtures, lossy quality, threads, progress),
`MqEncoderTest` (byte-identical to OpenJPEG's MQ encoder on `vectors/mq.txt`) and
`CodeBlockEncoderTest`.

**Status (2026-11-26):** all of the above pass. Against OpenJPEG 2.5.4 (`opj_decompress`,
RelWithDebInfo), `tools/vectors/WriterCheck.java` on 14 synthetic images (1 x 1 to 1023 x 769,
grey and RGB) and 7 corpus photos decoded to 8-bit RGB (2.0-4.0 MP), at 9 qualities: all 189
files decode in both, the two decodes are identical, and every lossless one is bit-exact.

PSNR (dB, RGB, against the source; the same through either decoder) and bits per pixel for the
seven corpus photos:

| Quality | Base step | PSNR range | Median PSNR | bpp range |
|---|---|---|---|---|
| 1 (lossless) | | inf | inf | 0.76-9.08 |
| 0.95 | 1.23 | 49.5-63.2 | 57.2 | 0.71-3.62 |
| 0.9 | 1.52 | 48.3-61.7 | 56.0 | 0.66-3.35 |
| 0.8 | 2.30 | 46.4-58.6 | 54.5 | 0.57-2.77 |
| 0.7 | 3.48 | 44.9-55.3 | 52.5 | 0.49-2.37 |
| 0.6 | 5.28 | 43.4-52.0 | 49.4 | 0.42-2.12 |
| 0.5 | 8.00 | 41.9-49.0 | 47.5 | 0.34-1.86 |
| 0.4 | 12.1 | 36.7-45.1 | 39.9 | 0.19-1.66 |
| 0.3 | 18.4 | 34.5-41.8 | 38.2 | 0.09-1.39 |

Most corpus streams were lossy 9/7 to begin with (`PERFORMANCE.md`), which is why several
re-encode at over 55 dB at high qualities; the synthetic images (gradients plus Gaussian noise, sigma 6) are harder, at
49.6 dB / 11.2 bpp (q 0.95), 43.5 dB / 7.8 bpp (q 0.8) and 33.3 dB / 1.2 bpp (q 0.5) for
1023 x 769 RGB.
