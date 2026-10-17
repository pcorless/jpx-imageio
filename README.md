# icepdf-jpx

Pure-Java JPEG 2000 (ISO/IEC 15444-1) decoder with an ImageIO reader. No runtime dependencies,
Java 11, Apache 2.0, with portions derived from OpenJPEG 2.5.4 (BSD-2; see `NOTICE`).

Status: **Phase 1 in progress** (GH-613): building the decoder bottom-up. Not usable yet.

- Requirements: `docs/IMAGEIO-CONTRACT.md`
- Provenance rules and the per-file record: `docs/PROVENANCE-RULES.md`, `PROVENANCE.md`
- Observations that drove decisions: `docs/DECISIONS.md`

## Build

```
./gradlew build          # or: mvn verify
```
