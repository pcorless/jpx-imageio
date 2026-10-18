<!-- Copied from icepdf core/core-awt/tools/jpx-fixtures (GH-613, 1bdd9515d); this copy is now the master. -->

# JPEG 2000 decisions and observations (GH-613)

The `INFERRED` evidence log required by `PROVENANCE-RULES.md`. Each entry: what we observed, how to
reproduce it, and what it decided. This file moves to the decoder repository when that exists.

Oracle versions: OpenJPEG v2.5.4 (`6c4a29b0`, built locally), jai-imageio-jpeg2000 1.4.0 (JJ2000).

---

## D-1 opj_decompress is not a raw-sample oracle (2026-10-17)

- **Observed:** `opj_decompress` RAW output is little-endian whatever the docs say
  (`imagetoraw_common` ignores its `big_endian` flag). It refuses components with different
  subsampling. For a 3-component J2K with subsampled chroma it guesses sYCC and writes upsampled,
  colour-converted RGB.
- **Repro:** `opj_decompress -i subsampled-420.j2k -o x.raw` reports three 64x64 components; the
  stream's components 1-2 are 32x32.
- **Decision:** references come from `jpxref.c` (libopenjp2 API directly, no application colour
  handling, PGX per component at its own size).

## D-2 OpenJPEG rejects a tile whose packet data is empty after PPT lifting (2026-10-17)

- **Observed:** the first PPT fixture, from a low-rate source, had a tile whose packet bodies were all
  empty once the headers moved into PPT, so its tile-part carried zero bytes after SOD. OpenJPEG
  2.5.4: "Failed to decode tile 2/6".
- **Repro:** `sop-eph-tiles` with `-r 40,20,10` on the default "mixed" source, then `lift_headers(.., "ppt")`.
- **Decision:** the fixture source switched to noise at `-r 8,4,2`, so every tile has data.
  **Open:** whether a zero-length tile-part body is legal (T.800 A.4.2) needs a reading of the spec
  and a second decoder. JJ2000 can't judge it, because it hangs on every PPT stream (D-4). It's a
  candidate hostile/edge fixture.

## D-3 tiles-offset: OpenJPEG encoder writes a bad 4-px edge tile (2026-10-17)

- **Observed:** lossless `-t 32,32 -T 5,7 -d 5,7` on 100x70 doesn't round-trip in columns 96..99,
  the 4-px-wide partial tile at canvas x 101..105. The two independent decoders, OpenJPEG and JJ2000,
  return the same wrong values (e.g. 183 for 247; JJ2000 within 1 of OpenJPEG). The same image
  without offsets (`tiles`) round-trips in both.
- **Conclusion:** the bug is on the encoder side (`opj_compress` 2.5.4), not in either decoder.
- **Decision:** the fixture is kept. The reference is the stream's content, and the generator records
  it in `KNOWN_ROUNDTRIP_FAILURES`; the case is scored as lossy. **Open:** report upstream, and find
  out why the two decoders differ by 1 inside that tile.

## D-4 JJ2000 hangs on PPM and PPT (2026-10-17)

- **Observed:** `ppm.j2k` and `ppt.j2k` loop forever in `PktDecoder.readPktHead`. Both are derived
  from `sop-eph-tiles` by moving the packet headers out. OpenJPEG decodes both to exactly the
  source decode, which the generator asserts.
- **Conclusion:** most likely a JJ2000 bug, but not confirmed by a third decoder.
- **Decision:** recorded as TIMEOUT in JJ2000's baseline. The tests time out at 10 s rather than
  hang the build.

## D-5 Subsampled read: resolution levels, not point sampling (2026-10-17)

- **Observed:** JJ2000 `setSourceSubsampling(2^k)` point-samples the full-resolution decode. The
  `reduce.reduceK` references are OpenJPEG's reduced-resolution decode (the LL band), and JJ2000
  differs from them by up to 189.
- **Decision:** the new reader maps power-of-two subsampling onto discarded resolution levels
  (`IMAGEIO-CONTRACT.md`), so it's expected to score EXACT. For JJ2000, MISMATCH is the recorded baseline.
- **ICEpdf impact:** really-big JPX images will render smoother (low-pass instead of aliasing).
  This needs a page-level A/B before switch-over.

## D-6 JJ2000 returns signed samples as two's-complement bits (2026-10-17)

- **Observed:** for `signed8-gray`, JJ2000's raster holds `value & 0xFF` in an unsigned byte
  raster, neither level-shifted nor signed.
- **Decision:** the harness accepts this as EQUIVALENT and names it.
- **Open:** what ICEpdf should hand `JpxDecoder` for signed components. The PDF spec's JPXDecode
  rules need checking before the new reader picks a representation.

## D-7 JJ2000 throws unchecked exceptions on damaged input (2026-10-17)

- **Observed:** 14/18 hostile streams end in RuntimeException, AIOOBE, NPE, IOOBE, and once
  `java.lang.ThreadDeath` (`truncated-mid-packet`). None hangs or OOMs, and four packet-garbage
  streams "decode".
- **ICEpdf impact:** `JpxDecoder.decode` catches only IOException. Runtime exceptions are caught
  further up by the `catch (Exception)` blocks in the image references. `ThreadDeath` is an
  `Error`, so it isn't.
- **Decision:** the new reader throws IIOException only (`IMAGEIO-CONTRACT.md`). No core change on this branch;
  revisit when switching over.

## D-8 Corpus census: the corpus exercises very little of Part 1 (2026-10-17)

- **Observed:** an extractor run over all 5,264 QA PDFs found 3,744 unique JPX streams (8,864
  references) in about 194 documents, written to `~/dev/jpx-corpus/` (local only). A marker census
  (T.800 Annex A/I walker) gave the table below. Both tools, `JpxExtract.java` and `JpxCensus.java`,
  and the full `census.csv` are local scratch in `<repo>/scratchpad/jpx/`, not committed.

  | Feature | Corpus |
  |---|---|
  | container | JP2 100% (no raw codestreams) |
  | colr | enum sRGB 2,691 · CMYK (12) 574 · gray (17) 410 · ICC 67 |
  | palette (`pclr`) | 161 (RGB and CMYK palettes, up to 256 entries) |
  | components | 3: 2,638 · 1: 573 · 4: 533 |
  | precision / sign / subsampling | **all 8-bit unsigned, none subsampled** |
  | transform | 9/7 3,464 · 5/3 280 |
  | progression | RLCP 3,654 · LRCP 90 |
  | levels | 5: 3,689 · 6: 55 |
  | tiled (>1 tile) | 1,799 |
  | TNsot under-count (6 vs 5) | **1,312 (35%)** |
  | code-block style | **0 everywhere** |
  | COC / POC / PPM / PPT / RGN / SOP / EPH / precincts | **none** |
  | QCC / TLM / COM | 498 / 55 / 571 |
  | encoders (COM) | LuraTech 481, Kakadu 88, JasPer 2, unmarked 3,173 |
- **Decision:** corpus parity is necessary but tells us little about a decoder's completeness.
  The synthetic fixtures are the completeness bar. Option B (port OpenJPEG) stands; jpx.js parity
  would pass the corpus while failing much of the fixture matrix.

## D-9 COC/QCC win over COD/QCD whatever their order (2026-10-17)

- **Observed:** OpenJPEG's `opj_j2k_read_cod`/`read_qcd` copy component 0's values to every
  component, so a COC or QCC that comes *before* the COD/QCD in the same header is overwritten.
  T.800 A.6 gives the precedence as tile-part COC > tile-part COD > main COC > main COD, with no
  ordering condition.
- **Decision:** `CodestreamReader` records which components a COC/QCC set in the header being read,
  and COD/QCD skip those. A tile's first tile-part header starts a new record, so a tile COD still
  overrides a main-header COC. Pinned by `CodestreamReaderTest.cocPrecedence`, which builds a COC
  placed before its COD. The two orders agree on every fixture OpenJPEG produced (it always writes
  COD first).

## D-10 TNsot is informational (2026-10-17)

- **Observed:** 1,312 of 3,744 corpus streams (35%) declare TNsot one short (icepdf GH-553).
  OpenJPEG errors on the extra tile-part unless its look-ahead correction
  (`opj_j2k_need_nb_tile_parts_correction`) kicks in. JJ2000 drops it silently. icepdf repairs
  the bytes before handing them to JJ2000.
- **Decision:** tile-parts are found by walking SOT/Psot to EOC. TPsot must still run 0, 1, 2…
  per tile (A.4.2, as in OpenJPEG). A TPsot at or beyond TNsot produces a warning, not an error.
- **Evidence:** `tnsot-undercount.j2k` parses to the same 6 tile-parts and data lengths as
  `tileparts-res.j2k`. The corpus sweep (`Sweep.java`, local scratch) parsed 3,744/3,744 with
  every tile present; the only warning was this one.

## D-11 PPM data kept per tile-part (2026-10-17)

- **Observed:** `opj_j2k_merge_ppm` concatenates all Ippm data into one buffer, and tier-2 reads it
  sequentially. That only works when tiles are decoded in codestream order.
- **Decision:** keep the Nppm boundaries: chunk *k* belongs to the *k*-th tile-part in codestream
  order (A.7.4). This allows region decode and decoding tiles in parallel. Nppm may span PPM
  markers here; OpenJPEG requires each Nppm to fit inside one marker.

## D-12 A tile-part POC replaces the main-header POC for that tile (2026-10-17)

- **Observed:** OpenJPEG copies the main-header POCs into every tile and appends tile-part POCs
  after them. T.800 A.6.6: a POC in a tile-part header overrides the main header's for that tile.
  Later tile-parts of the same tile add to it.
- **Decision:** the first tile-part POC of a tile clears the inherited list. **Open:** confirm the
  exact wording against the 2019 text, and find or build a fixture with POC in both headers
  (`opj_compress -POC` writes tile-part POCs only).

## D-13 References and fixtures come from a strict-IEEE OpenJPEG build (2026-10-17)

- **Observed:** the new decoder matched every reference except 2 of 4,096 samples of
  `prec16-rgb-irr` component 1, which were off by one. Instrumenting OpenJPEG showed its float
  for one of them was 32766.502 (rounding to 32767 + 32768 = 65535, the new decoder's answer),
  yet the reference said 65534. The reference came from a CMake **Release** build, which adds
  `-ffast-math` (`CMakeLists.txt`: "only set for release"). The same source built without it gives
  65535. Fast-math lets GCC reorder float operations, so 9/7 encodes and decodes depend on the
  build, and can drift with the compiler version.
- **Measured:** regenerating everything with a RelWithDebInfo build (no fast-math) changed 4
  encoded fixtures (`irr97-rgb`, `irr97-cmyk`, `layers-quality`, `prec16-rgb-irr`) and their
  references; all integer (5/3) output was identical. Against the strict references, the new
  decoder is bit-exact on all 68. JJ2000's icepdf baseline outcomes didn't change.
- **Decision:** fixtures and references are built with OpenJPEG v2.5.4 RelWithDebInfo
  (`~/dev/git/openjpeg/build-strict`). Lossy 9/7 is compared bit-exactly against that build:
  the new decoder uses the same float operations in the same order, and Java float is strict IEEE.

## D-14 Corpus A/B: 400 of 400 real PDF streams bit-exact (2026-10-17)

- **Measured:** 400 streams drawn at random (seed 613) from the 3,744 in `~/dev/jpx-corpus`. Each
  codestream was cut out of its JP2 at SOC+SIZ and decoded by icepdf-jpx (`f460f61`) and by strict
  OpenJPEG (`jpxref`, RelWithDebInfo). Result: 400 EXACT, 0 differences, 0 exceptions. Decode time
  was 18.9 s in total, single-threaded and unoptimised, including JIT warm-up. Harness:
  `CorpusAB.java` (local scratch).
- **Meaning:** the J2K path is ready for the JP2 layer and the ImageIO reader. Performance has not
  been tuned yet: no parallel code-blocks, generic tier-1 loops.

## D-15 Nothing after the codestream is read (2026-10-17)

- **Observed:** `opj_end_decompress` runs the box reader again after the codestream's EOC, and a
  malformed box there (mutant `trailing-bad-colr`: a `colr` header claiming 1 MiB) fails the whole
  decode of an image that was already fully decoded.
- **Decision:** the JP2 reader stops at the `jp2c` box header and hands the rest of the input to
  the codestream reader, as OpenJPEG does on the way in; trailing boxes are never parsed. The
  mutant is listed as a divergence in `Jp2MutantTest`. PDF streams often carry trailing padding or
  junk, and nothing after the codestream changes the image.

## D-16 cdef channel types travel with their channels (2026-10-17)

- **Observed:** `opj_jp2_apply_cdef` swaps whole components (including `alpha`) to put colours in
  association order, then sets `comps[cn].alpha = typ` with the colour's type. When the opacity
  channel is not last (mutant `cdef-opacity-first`: channel 0 opacity, 1-3 colours 1-3) the samples
  end in the right order, R G B A, but every component reports type 0: the opacity flag is lost.
- **Decision:** the sample order is OpenJPEG's (checked against the oracle); each channel's
  `channelType` is the type of its own first cdef entry, looked up by its original channel index.
  OpenJPEG's `alpha` isn't in the PGX oracle, so this is checked by `channelTypesFollowCdef`
  only. It matters for PDF `SMaskInData`, which needs to know which channel is opacity.

## D-17 Signed palette values are sign-extended (2026-10-17)

- **Observed:** `opj_jp2_read_pclr` reads each palette value as an unsigned integer of
  ceil(B/8) bytes and never sign-extends it, so a 12-bit signed entry of -800 (stored FCE0)
  decodes as 64736, outside the channel's range.
- **Spec:** T.800 I.5.3.4: Bi gives the depth and signedness of the palette column; a signed
  value is two's complement at that depth.
- **Decision:** signed columns narrower than 32 bits are sign-extended from their declared
  depth after the lookup. Unsigned values are left as OpenJPEG reads them. The PGX oracle keeps
  only the low 16 bits, which are the same either way, so the mutant `pclr-wide-signed` matches
  OpenJPEG and `signedPaletteColumnsAreSignExtended` checks the range. Measured: all 161
  palettes in `~/dev/jpx-corpus` are 8-bit unsigned (Bi = 7), so real files are unaffected.

## D-18 Corpus A/B on whole JP2 files: 3,742 of 3,744 bit-exact (2026-10-17)

- **Measured:** every stream in `~/dev/jpx-corpus` decoded as a whole JP2 file by
  `Jpeg2000Decoder.decodeImage` and by strict OpenJPEG (`jpxref`): 3,742 EXACT (samples, sizes,
  precision and sign of every channel, after pclr/cmap/cdef), 0 differences, 0 one-sided
  failures. Harness: `CorpusJp2AB.java` (local scratch, 16 files in parallel).
- **Both fail:** two files whose `cmap` box is garbage (`0c7e1d2f…`: MTYP values 152/117/139;
  `c049d851…`: CMP 59472 on a 1-component image). OpenJPEG's `opj_jp2_check_color` rejects
  them and so do we. **Open:** in a PDF these may still be renderable as indices when the image
  dictionary supplies an /Indexed ColorSpace; decide when the ImageIO reader exposes the raw
  (pre-palette) components.

## D-19 Colour models for read(): what a JP2 says, else a guess by channel count (2026-10-17)

ImageIO needs a ColorModel; OpenJPEG has no equivalent (`opj_decompress` guesses in its app
code, which isn't ported). `Jpeg2000Layout` decides, ORIGINAL:
- **colr wins when it fits.** A usable ICC profile (meth 2) or EnumCS 16/17/12 is used when the
  number of colour channels matches (cdef type 0; without cdef, the surplus channels after the
  colour space's own become extras). EnumCS 18/24 (sYCC/eYCC) are read as RGB **without
  conversion**, with a warning; CIELab and other EnumCS fall to the guess. **Open:** YCC and Lab
  conversion (not in the corpus: 2,691 sRGB, 574 CMYK, 410 grey, 67 ICC).
- **Guess, for bare codestreams and misfits:** 1 grey, 2 grey + alpha, 3 sRGB, 4 CMYK (as pdf.js
  and Acrobat treat a bare 4-component codestream), otherwise a generic n-channel space.
- **Opacity:** the first cdef opacity (or premultiplied) channel becomes the alpha band, moved
  last (ComponentColorModel needs it there); without cdef, the first surplus channel. Any other
  extra channel turns the model generic so no band is dropped: ICEpdf reads every band of the
  raster and applies the PDF's own colour space.
- **Samples are never converted:** bands hold the decoded values at their own depth; signed
  channels are level-shifted by 2^(p-1). `readRaster` gives the same values in channel order.
- **Observed:** `jp2-cmyk.jp2` is named CMYK but `opj_compress` wrote EnumCS 16 (sRGB) and no
  cdef for its 4 raw components, so it reads as RGB + alpha. Its samples are what matter to the
  conformance tests and ICEpdf, which applies the PDF's /ColorSpace.

## D-20 Ordering ahead of JJ2000 is only automatic when JJ2000 registered first (2026-10-17)

- **Measured:** with both jars on the class path, `ImageIO.getImageReadersByFormatName("jpeg2000")`
  returns ours first when JJ2000's jar comes first, and JJ2000 first when ours does.
  `onRegistration` can order against providers that are already registered; ImageIO has no hook
  for ones registered later.
- **Decision:** keep the `onRegistration` ordering, and add
  `Jpeg2000ImageReaderSpi.preferOverOtherReaders()` for applications to call (after any
  `ImageIO.scanForPlugins()`). ICEpdf selects by SPI class name, as the contract already says.
  Deregistering JJ2000 was rejected: an application may want both.

## D-21 Threads on by default, own ForkJoinPool, capped at 8 (2026-10-17)

- **Decided with the maintainer:** threads on by default for plain `ImageIO.read` users, with
  overrides for small hardware: `-Dorg.icepdf.jpx.parallelism=N` (`1` = none),
  `Jpeg2000Threads.setDefaults(...)`, per decode `Options.threads` / `Jpeg2000ImageReadParam`.
- **Pool:** a `ForkJoinPool` owned by the library rather than `ForkJoinPool.commonPool()`: the
  common pool is shared with parallel streams and sized by another property, and on 1-2 core
  machines it degrades in ways the library doesn't control. Daemon threads named `icepdf-jpx-N`,
  `parallelism - 1` of them (the calling thread is the last worker).
- **Scheduling is caller-helps, not `join()`:** an ICEpdf worker (not a pool thread) that joins
  a ForkJoinTask can simply block; `Parallel.forEach` never waits for a queued helper.
- **Cap 8, measured:** on the corpus subset 8 threads give 4.3x and 16 give 4.7x (+10% for twice
  the threads), small images stop gaining at about 4; a host decoding several images at once
  already gets parallelism across them.
- **Unchanged output:** `ThreadedDecodeTest` (every reference, thresholds off) and the full corpus
  A/B with threads on and 16 concurrent decodes: 3,742 / 3,744 EXACT.
