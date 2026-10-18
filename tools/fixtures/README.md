# Fixture generator (ORIGINAL)

Builds the licence-clean JPEG 2000 fixtures in `src/test/resources/fixtures`: streams encoded with
OpenJPEG's `opj_compress` from images synthesised here (gradients, a fixed-seed noise field, a
blocky glyph pattern), derived streams (PPM/PPT, COC/QCC, JP2 palette/ICC/cdef), damaged
"hostile" streams, and reference decodes. All of it is ours (Apache 2.0); no corpus or
conformance-suite file is committed.

| File | What |
|---|---|
| `generate.py` | the fixture matrix; checks every lossless case round-trips; deterministic (same bytes run to run) |
| `jpxref.c` | reference decoder: libopenjp2's public API, no colour handling, one PGX per component |

Outputs, in `src/test/resources/fixtures`:

| Path | What |
|---|---|
| `*.j2k`, `*.jp2` | fixtures |
| `ref/*.pgx` | reference components (`PG ML <+/-> prec w h`, big-endian samples) |
| `hostile/` | damaged / malicious streams derived from `rev53-rgb.j2k` |
| `manifest.json` | per fixture: encode command, oracle, hashes, notes |
| `references.tsv`, `hostile.txt` | what the tests iterate |

`dump/` (opj_dump output, `tools/vectors/dumps.sh`) and the folder's README are left alone.

## Rebuilding

Only needed when the matrix changes.

```
git clone --branch v2.5.4 https://github.com/uclouvain/openjpeg.git ~/dev/git/openjpeg   # 6c4a29b0
cd ~/dev/git/openjpeg && mkdir build-strict && cd build-strict
cmake .. -DCMAKE_BUILD_TYPE=RelWithDebInfo -DBUILD_SHARED_LIBS=OFF -DBUILD_THIRDPARTY=OFF && make -j8
gcc -O2 -std=c99 -o bin/jpxref <this repo>/tools/fixtures/jpxref.c \
    -I../src/lib/openjp2 -Isrc/lib/openjp2 bin/libopenjp2.a -lm -lpthread
cd <this repo> && python3 -I tools/fixtures/generate.py      # OPJ_BIN overrides the tool dir
tools/vectors/dumps.sh                                       # then the opj_dump oracles
```

**Not `Release`:** OpenJPEG's Release build adds `-ffast-math`. The float reordering it allows
changes 9/7 encodes and moves some decoded samples by one, and the result depends on the
compiler version (`docs/DECISIONS.md` D-13). RelWithDebInfo keeps strict IEEE float.

Two strings written into fixtures (`comment.j2k`'s COM marker, `jp2-icc-gray.jp2`'s ICC
description) still say "GH-613", the issue the fixtures were first made for; they are kept so
the generator reproduces the committed bytes.

## Provenance

`ORIGINAL`. `jpxref.c` links OpenJPEG (BSD-2) through its public API and copies no OpenJPEG
code; it's a dev tool and never ships. OpenJPEG pinned at v2.5.4 (`6c4a29b0`).
