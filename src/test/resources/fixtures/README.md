# Fixtures

Copied from icepdf `core/core-awt/src/test/resources/jpx` at 1bdd9515d (GH-613), where
`core/core-awt/tools/jpx-fixtures/generate.py` builds them; see its README for the matrix and
`manifest.json` for each fixture's encode command. All generated from synthesised images: ours,
Apache 2.0.

`dump/<name>.txt`: `opj_dump -i <name>.j2k` (OpenJPEG v2.5.4, 6c4a29b0), the main-header coding
parameters, used by CodestreamReaderTest. Regenerate with `tools/vectors/dumps.sh`.
