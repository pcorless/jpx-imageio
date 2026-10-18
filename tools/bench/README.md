# Benchmarks (ORIGINAL)

Results and method: `docs/PERFORMANCE.md`.

- `opjbench.c`: libopenjp2 in-process, one thread, best of N passes per file.
  `gcc -O2 -o opjbench opjbench.c -I$OPJ/src/lib/openjp2 -I$OPJ/<build>/src/lib/openjp2 $OPJ/<build>/bin/libopenjp2.a -lm -lpthread`
  then `opjbench 1 file.jp2 ... > opj.tsv` (path, ok|fail, ms).
- `JpxBench.java`: jpx-imageio (`ours`) or JJ2000 (`jj2000`) in one JVM, single-threaded.
  `java -Xmx8g -cp <jpx-imageio classes or jar>[:jai-imageio jars] JpxBench.java ours <warmupFiles> list.txt > ours.tsv`
  (path, ok|fail|timeout, ms, megapixels).
- `ab.sh`: one A/B run on a file list, pinned to cores 10-13.

Pin runs to cores (`taskset`) and don't run two benchmarks on the same cores at once.
