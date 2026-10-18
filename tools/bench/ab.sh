#!/bin/sh
# Provenance: ORIGINAL. One A/B run: ab.sh <classpath> <file list> <out.tsv>
# Cores 10-13, 150 warm-up files; prints total seconds and MP/s.
JAVA=${JAVA:-java}
HERE=$(dirname "$0")
taskset -c 10-13 "$JAVA" -Xmx8g -cp "$1" "$HERE/JpxBench.java" ours 150 "$2" > "$3"
awk -F'\t' '{t+=$3; p+=$4} END {printf "%.1f s, %.1f MP/s\n", t/1000, p/(t/1000)}' "$3"
