# Provenance

Where every source file came from. Rules: `docs/PROVENANCE-RULES.md`. `INFERRED` evidence:
`docs/DECISIONS.md`. Checked by `ProvenanceTest`: each `src/main` file has a `Provenance:` header
line, is listed here with the same origin tag, and keeps the upstream copyright block when
it's `PORTED`/`ADAPTED`.

## Pinned upstreams

| Upstream | Revision | Licence | Local checkout |
|---|---|---|---|
| OpenJPEG | v2.5.4, `6c4a29b00211eb0430fa0e5e890f1ce5c80f409f` | BSD-2 | `~/dev/git/openjpeg` |
| pdf.js `src/core/jpx.js` | `674e7ee38` (last pure-JS revision) | Apache-2.0 | `~/dev/git/pdf.js` |
| ITU-T T.800 (ISO/IEC 15444-1) | edition cited per entry | spec | n/a |

JJ2000 / jai-imageio-jpeg2000 is a black-box oracle only; its source is never consulted.

## Files

| File | Origin | Upstream (file @ rev: functions) | Licence | Notes |
|---|---|---|---|---|
| `src/main/java/io/github/pcorless/jpx/tier1/MqDecoder.java` | ADAPTED | OpenJPEG `mqc.c`, `mqc_inl.h` @ 6c4a29b0: `mqc_states`, `opj_mqc_init_dec`, `opj_mqc_raw_init_dec`, `opj_mqc_resetstates`, `opj_mqc_setstate`, `opj_mqc_decode_macro`, `opj_mqc_bytein_macro`, `opj_mqc_raw_decode` | BSD-2 | virtual FF FF instead of writing past the buffer; state pointers → indices; DECODE exchange as masks, RENORMD several bits at a time (same results) |
| `src/main/java/io/github/pcorless/jpx/tier2/PacketBitReader.java` | PORTED | OpenJPEG `bio.c` @ 6c4a29b0: `opj_bio_init_dec`, `opj_bio_bytein`, `opj_bio_getbit`, `opj_bio_read`, `opj_bio_inalign`, `opj_bio_numbytes` | BSD-2 | adds an overrun flag |
| `src/main/java/io/github/pcorless/jpx/tier2/TagTree.java` | PORTED | OpenJPEG `tgt.c` @ 6c4a29b0: `opj_tgt_create`, `opj_tgt_reset`, `opj_tgt_decode` | BSD-2 | nodes as parallel arrays |
| `src/main/java/io/github/pcorless/jpx/JpxException.java` | ORIGINAL | | Apache-2.0 | |
| `src/main/java/io/github/pcorless/jpx/codestream/Markers.java` | SPEC | T.800 (06/2019) Table A.2, T.814, T.801 | Apache-2.0 | marker and flag constants |
| `src/main/java/io/github/pcorless/jpx/codestream/Codestream.java` | ADAPTED | OpenJPEG `j2k.h`, `openjpeg.h` @ 6c4a29b0: decoder fields of `opj_image_comp_t`, `opj_cp_t`, `opj_tcp_t`, `opj_tccp_t`, `opj_poc_t` | BSD-2 | ranges instead of copied tile data; PPM per tile-part (D-11) |
| `src/main/java/io/github/pcorless/jpx/codestream/CodestreamReader.java` | ADAPTED | OpenJPEG `j2k.c` @ 6c4a29b0: `opj_j2k_read_header_procedure`, `read_unk`, `read_siz`, `read_cod`, `read_coc`, `read_SPCod_SPCoc`, `read_qcd`, `read_qcc`, `read_SQcd_SQcc`, `read_poc`, `read_rgn`, `read_com`, `read_ppm`, `merge_ppm`, `read_ppt`, `merge_ppt`, `get_sot_values`, `read_sot`, `read_sod`, `read_tile_header` | BSD-2 | D-9 COC precedence, D-10 TNsot informational, D-11 PPM per tile-part, D-12 tile POC replaces main |
| `src/main/java/io/github/pcorless/jpx/tier1/CodeBlockDecoder.java` | PORTED | OpenJPEG `t1.c`, `t1.h`, `t1_generate_luts.c` @ 6c4a29b0: decode passes, flag layout, context/sign LUT generators, `opj_t1_decode_cblk`, ROI de-shift | BSD-2 | generic loops instead of 64x64/vsc specialisations; LUTs computed (CRC-checked against `t1_luts.h`) |
| `src/main/java/io/github/pcorless/jpx/tile/TileLayout.java` | PORTED | OpenJPEG `tcd.c`, `tcd.h` @ 6c4a29b0: decoder half of `opj_tcd_init_tile`, `opj_tcd_is_band_empty`, decode structs | BSD-2 | fresh objects per tile; chunk references |
| `src/main/java/io/github/pcorless/jpx/tile/TileDecoder.java` | ADAPTED | OpenJPEG `tcd.c`, `t1.c` @ 6c4a29b0: `opj_tcd_decode_tile`, `t1_decode`, `dwt_decode`, `mct_decode`, `dc_level_shift_decode`, `mct.c` `opj_mct_decode_real` (fused with the level shift), placement/dequantisation of `opj_t1_clbl_decode_processor` | BSD-2 | whole-tile only; separate int/float buffers |
| `src/main/java/io/github/pcorless/jpx/tier2/PacketIterator.java` | ADAPTED | OpenJPEG `pi.c` @ 6c4a29b0: `opj_pi_next_{lrcp,rlcp,rpcl,pcrl,cprl}`, `opj_pi_create_decode`, `opj_pi_update_decode_{poc,not_poc}`, `opj_get_all_encoding_parameters` | BSD-2 | loops emitting the packet list instead of resumable gotos |
| `src/main/java/io/github/pcorless/jpx/tier2/PacketDecoder.java` | PORTED | OpenJPEG `t2.c` @ 6c4a29b0: `opj_t2_decode_packets`, `decode_packet`, `skip_packet`, `read_packet_header`, `read_packet_data`, `skip_packet_data`, `init_seg`, `getcommacode`, `getnumpasses` | BSD-2 | non-strict truncation handling as OpenJPEG |
| `src/main/java/io/github/pcorless/jpx/transform/InverseDwt.java` | ADAPTED | OpenJPEG `dwt.c` @ 6c4a29b0: 5/3 `opj_dwt_interleave_h/v`, `opj_dwt_decode_1_` (reference lifting); 9/7 constants, `opj_v8dwt_interleave_h/v`, `opj_v8dwt_decode`, `decode_step1/2`; level loops | BSD-2 | rows one lane, columns 16 lanes (both 5/3 and 9/7), same operation order |
| `src/main/java/io/github/pcorless/jpx/transform/InverseMct.java` | PORTED | OpenJPEG `mct.c` @ 6c4a29b0: `opj_mct_decode` | BSD-2 | `opj_mct_decode_real` is fused into `TileDecoder` |
| `src/main/java/io/github/pcorless/jpx/Jpeg2000Decoder.java` | ADAPTED + ORIGINAL | OpenJPEG `j2k.c` @ 6c4a29b0: geometry of `opj_j2k_update_image_dimensions` and `opj_j2k_set_decode_area`, tile copy of `opj_j2k_update_image_data` | BSD-2 | sequential tiles; a decode area decodes the tiles it meets, whole, then crops; `decodeImage` glue and the Progress hook are ORIGINAL |
| `src/main/java/io/github/pcorless/jpx/Jpeg2000Headers.java` | ADAPTED + ORIGINAL | OpenJPEG `j2k.c` @ 6c4a29b0: IHDR/SIZ check and BPC warning of `opj_j2k_read_siz`, reduce limit of `opj_j2k_read_SPCod_SPCoc` | BSD-2 | format sniff as `jpxref.c` is ORIGINAL |
| `src/main/java/io/github/pcorless/jpx/Jpeg2000Image.java` | ORIGINAL | (colour-space table as `opj_jp2_read_header`, T.800 Table I.10) | Apache-2.0 | |
| `src/main/java/io/github/pcorless/jpx/jp2/Jp2Header.java` | ADAPTED + SPEC | OpenJPEG `jp2.h` @ 6c4a29b0: decoder fields of `opj_jp2_t`, `opj_jp2_color_t`, `opj_jp2_pclr_t`, `opj_jp2_cmap_comp_t`, `opj_jp2_cdef_info_t`, box constants; T.800 I.5.3.7 (res) | BSD-2 | keeps every colr box; res fields |
| `src/main/java/io/github/pcorless/jpx/jp2/Jp2Reader.java` | ADAPTED + SPEC | OpenJPEG `jp2.c` @ 6c4a29b0: `opj_jp2_read_header_procedure`, `read_boxhdr`, `read_boxhdr_char`, `read_jp`, `read_ftyp`, `read_jp2h`, `read_ihdr`, `read_bpcc`, `read_colr`, `read_pclr`, `read_cmap`, `read_cdef`, checks of `opj_jp2_read_header`; T.800 I.5.3.7 (res) | BSD-2 | byte range not stream; later colr boxes kept, never fatal; res read leniently |
| `src/main/java/io/github/pcorless/jpx/jp2/Jp2Channels.java` | PORTED | OpenJPEG `jp2.c` @ 6c4a29b0: `opj_jp2_apply_color_postprocessing`, `opj_jp2_check_color`, `opj_jp2_apply_pclr`, `opj_jp2_apply_cdef` | BSD-2 | works on copies; cdef type → `Component.channelType` |
| `src/main/java/io/github/pcorless/jpx/Jpeg2000Threads.java` | ORIGINAL | | Apache-2.0 | thread settings, default pool |
| `src/main/java/io/github/pcorless/jpx/internal/Parallel.java` | ORIGINAL | | Apache-2.0 | caller-helps parallel loops |
| `src/main/java/io/github/pcorless/jpx/imageio/Jpeg2000ImageReaderSpi.java` | ORIGINAL | (javax.imageio SPI contract; T.800 A.4.1, I.5.1 signatures) | Apache-2.0 | |
| `src/main/java/io/github/pcorless/jpx/imageio/Jpeg2000ImageReadParam.java` | ORIGINAL | | Apache-2.0 | threads per read |
| `src/main/java/io/github/pcorless/jpx/imageio/Jpeg2000ImageReader.java` | ORIGINAL | (javax.imageio ImageReader contract) | Apache-2.0 | |
| `src/main/java/io/github/pcorless/jpx/imageio/Jpeg2000Layout.java` | ORIGINAL | (T.800 I.5.3.3, I.5.3.6) | Apache-2.0 | colour-space guesses: D-19 |
| `src/main/java/io/github/pcorless/jpx/imageio/Jpeg2000ColorSpaces.java` | ORIGINAL | | Apache-2.0 | uncalibrated CMYK and n-channel spaces |
| `src/main/java/io/github/pcorless/jpx/imageio/Jpeg2000Metadata.java` | ORIGINAL | (javax_imageio_1.0 DTD; T.800 I.5.3.7) | Apache-2.0 | |
| `src/main/java/module-info.java` | ORIGINAL | | Apache-2.0 | `provides` the ImageReaderSpi |
| `src/main/resources/META-INF/services/javax.imageio.spi.ImageReaderSpi` | ORIGINAL | | Apache-2.0 | service registration |
