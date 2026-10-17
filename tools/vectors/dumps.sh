#!/bin/sh
# Provenance: ORIGINAL. Writes opj_dump output for every .j2k fixture (CodestreamReaderTest oracle).
# Usage: OPJ_BIN=~/dev/git/openjpeg/build/bin tools/vectors/dumps.sh
set -e
BIN=${OPJ_BIN:-$HOME/dev/git/openjpeg/build/bin}
cd "$(dirname "$0")/../../src/test/resources/fixtures"
mkdir -p dump
for f in *.j2k; do
    "$BIN/opj_dump" -i "$f" 2>/dev/null | sed -n '/^Image info/,$p' > "dump/${f%.j2k}.txt"
done
