/*
 * Copyright 2026 Patrick Corless
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Provenance: ORIGINAL. Times tier-1 alone: every code-block of the given files is parsed and
 * packet-decoded once, its inputs kept, then CodeBlockDecoder.decode runs over all of them for
 * <rounds> timed rounds after <warmup> untimed ones, on one thread.
 *   java -cp <jpx-imageio classes or jar> tools/bench/T1Bench.java <warmup> <rounds> file...
 * Prints each round, then the median and the best (Msamples/s of code-block area), and a checksum
 * of every decoded coefficient, which must not change between variants of the decoder.
 */
import io.github.pcorless.jpx.Jpeg2000Headers;
import io.github.pcorless.jpx.codestream.Codestream;
import io.github.pcorless.jpx.tier1.CodeBlockDecoder;
import io.github.pcorless.jpx.tier2.PacketDecoder;
import io.github.pcorless.jpx.tier2.PacketIterator;
import io.github.pcorless.jpx.tile.TileLayout;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class T1Bench {

    static final class Job {
        int w, h, numSegs, numBitPlanes, band, roiShift, cblkStyle;
        byte[] buf;
        int[] segLength, segPasses;
    }

    public static void main(String[] a) throws Exception {
        int warmup = Integer.parseInt(a[0]), rounds = Integer.parseInt(a[1]);
        List<Job> jobs = new ArrayList<>();
        for (int f = 2; f < a.length; f++) {
            byte[] d = Files.readAllBytes(Paths.get(a[f]));
            Codestream cs = Jpeg2000Headers.read(d, 0, d.length).codestream;
            for (int t = 0; t < cs.numTiles(); t++) {
                if (cs.tile(t) == null) continue;
                TileLayout tile = TileLayout.build(cs, t, 0);
                PacketDecoder.decode(cs, tile, PacketIterator.of(cs, tile));
                for (int c = 0; c < tile.components.length; c++) {
                    TileLayout.Component tc = tile.components[c];
                    Codestream.ComponentCoding tccp = tile.coding.components[c];
                    for (int r = 0; r < tc.minimumNumResolutions; r++) {
                        for (TileLayout.Band band : tc.resolutions[r].bands) {
                            if (band.isEmpty()) continue;
                            for (TileLayout.Precinct prc : band.precincts) {
                                for (TileLayout.CodeBlock cb : prc.codeBlocks) {
                                    if (cb.x1 - cb.x0 <= 0 || cb.y1 - cb.y0 <= 0) continue;
                                    Job j = new Job();
                                    j.w = cb.x1 - cb.x0;
                                    j.h = cb.y1 - cb.y0;
                                    j.numSegs = cb.corrupted || cb.numChunks == 0 ? 0 : cb.realNumSegs;
                                    int len = 0;
                                    for (int i = 0; i < cb.numChunks; i++) len += cb.chunkLength[i];
                                    j.buf = new byte[len + 2];
                                    for (int i = 0, o = 0; i < cb.numChunks; i++) {
                                        System.arraycopy(cb.chunkData[i], cb.chunkOffset[i], j.buf, o, cb.chunkLength[i]);
                                        o += cb.chunkLength[i];
                                    }
                                    j.segLength = new int[Math.max(1, j.numSegs)];
                                    j.segPasses = new int[Math.max(1, j.numSegs)];
                                    for (int s = 0; s < j.numSegs; s++) {
                                        j.segLength[s] = cb.segs[s].length;
                                        j.segPasses[s] = cb.segs[s].realNumPasses;
                                    }
                                    j.numBitPlanes = cb.numBitPlanes;
                                    j.band = band.bandNo;
                                    j.roiShift = tccp.roiShift;
                                    j.cblkStyle = tccp.cblkStyle;
                                    jobs.add(j);
                                }
                            }
                        }
                    }
                }
            }
        }
        long area = 0;
        for (Job j : jobs) area += (long) j.w * j.h;
        System.out.printf("%d code-blocks, %.1f Msamples%n", jobs.size(), area / 1e6);
        CodeBlockDecoder t1 = new CodeBlockDecoder();
        double[] rate = new double[rounds];
        long check = 0;
        for (int round = -warmup; round < rounds; round++) {
            long sum = 0;
            long t0 = System.nanoTime();
            for (Job j : jobs) {
                t1.decode(j.w, j.h, j.buf, 0, j.segLength, j.segPasses, j.numSegs, j.numBitPlanes, j.band,
                        j.roiShift, j.cblkStyle);
                int[] d = t1.data();
                for (int i = 0, n = j.w * j.h; i < n; i++) sum = sum * 31 + d[i];
            }
            long ns = System.nanoTime() - t0;
            check = sum;
            if (round >= 0) {
                rate[round] = area / (ns / 1e3);
                System.out.printf("round %d: %.1f Msamples/s%n", round, rate[round]);
            }
        }
        Arrays.sort(rate);
        System.out.printf("median %.1f, best %.1f Msamples/s, checksum %016x%n", rate[rounds / 2], rate[rounds - 1], check);
    }
}
