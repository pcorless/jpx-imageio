#!/bin/sh
# Copyright 2026 Patrick Corless
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Provenance: ORIGINAL. Writes opj_dump output for every .j2k fixture (CodestreamReaderTest oracle).
# Usage: OPJ_BIN=~/dev/git/openjpeg/build/bin tools/vectors/dumps.sh
set -e
BIN=${OPJ_BIN:-$HOME/dev/git/openjpeg/build-strict/bin}
cd "$(dirname "$0")/../../src/test/resources/fixtures"
mkdir -p dump
for f in *.j2k; do
    "$BIN/opj_dump" -i "$f" 2>/dev/null | sed -n '/^Image info/,$p' > "dump/${f%.j2k}.txt"
done
