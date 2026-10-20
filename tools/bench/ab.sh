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
# Provenance: ORIGINAL. One A/B run: ab.sh <classpath> <file list> <out.tsv>
# Cores 10-13, 150 warm-up files; prints total seconds and MP/s.
JAVA=${JAVA:-java}
HERE=$(dirname "$0")
taskset -c 10-13 "$JAVA" -Xmx8g -cp "$1" "$HERE/JpxBench.java" ours 150 "$2" > "$3"
awk -F'\t' '{t+=$3; p+=$4} END {printf "%.1f s, %.1f MP/s\n", t/1000, p/(t/1000)}' "$3"
