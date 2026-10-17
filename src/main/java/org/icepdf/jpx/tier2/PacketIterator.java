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
 * Provenance: ADAPTED from OpenJPEG src/lib/openjp2/pi.c @ 6c4a29b0 (BSD-2):
 *   opj_pi_next_lrcp, opj_pi_next_rlcp, opj_pi_next_rpcl, opj_pi_next_pcrl, opj_pi_next_cprl,
 *   opj_pi_create_decode (step sizes, the include table shared by all POC entries),
 *   opj_pi_update_decode_poc, opj_pi_update_decode_not_poc, opj_get_all_encoding_parameters
 *   (the per-resolution dx/dy minimum).
 *   Changed: the resumable goto-based iterators became plain nested loops that emit the whole
 *   packet sequence up front; resolution precinct counts come from TileLayout (same formulas).
 *   Progressions: ITU-T T.800 B.12.
 */
/*
 * The copyright in this software is being made available under the 2-clauses
 * BSD License, included below. This software may be subject to other third
 * party and contributor rights, including patent rights, and no such rights
 * are granted under this license.
 *
 * Copyright (c) 2002-2014, Universite catholique de Louvain (UCL), Belgium
 * Copyright (c) 2002-2014, Professor Benoit Macq
 * Copyright (c) 2001-2003, David Janssens
 * Copyright (c) 2002-2003, Yannick Verschueren
 * Copyright (c) 2003-2007, Francois-Olivier Devaux
 * Copyright (c) 2003-2014, Antonin Descampe
 * Copyright (c) 2005, Herve Drolon, FreeImage Team
 * Copyright (c) 2006-2007, Parvatha Elangovan
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS `AS IS'
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package org.icepdf.jpx.tier2;

import org.icepdf.jpx.JpxException;
import org.icepdf.jpx.codestream.Codestream;
import org.icepdf.jpx.codestream.Codestream.Poc;
import org.icepdf.jpx.codestream.Codestream.TileCoding;
import org.icepdf.jpx.codestream.Markers;
import org.icepdf.jpx.tile.TileLayout;

/** The order of a tile's packets: (layer, resolution, component, precinct) per packet. */
public final class PacketIterator {

    /** Packets as 4 ints each: layer, resolution, component, precinct. */
    private int[] packets = new int[64];
    private int count;

    private final int numComps;
    private final int[] dx;
    private final int[] dy;
    private final int[] numRes;
    private final int[][] pdx, pdy, pw, ph;
    private final int tx0, ty0, tx1, ty1;
    private final boolean[] include;
    private final int stepC, stepR, stepL;

    /** Cap on the include table, against hostile layer/precinct counts. */
    private static final long MAX_INCLUDE = 1L << 28;

    private PacketIterator(Codestream cs, TileLayout tile) throws JpxException {
        TileCoding tcp = tile.coding;
        numComps = tile.components.length;
        dx = new int[numComps];
        dy = new int[numComps];
        numRes = new int[numComps];
        pdx = new int[numComps][];
        pdy = new int[numComps][];
        pw = new int[numComps][];
        ph = new int[numComps][];
        int maxRes = 0, maxPrec = 0;
        for (int c = 0; c < numComps; c++) {
            TileLayout.Component tc = tile.components[c];
            dx[c] = cs.components[c].dx;
            dy[c] = cs.components[c].dy;
            numRes[c] = tc.numResolutions;
            maxRes = Math.max(maxRes, tc.numResolutions);
            pdx[c] = new int[tc.numResolutions];
            pdy[c] = new int[tc.numResolutions];
            pw[c] = new int[tc.numResolutions];
            ph[c] = new int[tc.numResolutions];
            for (int r = 0; r < tc.numResolutions; r++) {
                pdx[c][r] = tcp.components[c].precinctWidthExp[r];
                pdy[c][r] = tcp.components[c].precinctHeightExp[r];
                pw[c][r] = tc.resolutions[r].pw;
                ph[c][r] = tc.resolutions[r].ph;
                maxPrec = Math.max(maxPrec, pw[c][r] * ph[c][r]);
            }
        }
        tx0 = tile.x0;
        ty0 = tile.y0;
        tx1 = tile.x1;
        ty1 = tile.y1;
        stepC = maxPrec;
        stepR = numComps * stepC;
        stepL = maxRes * stepR;
        long size = (long) (tcp.numLayers + 1) * stepL;
        if (size > MAX_INCLUDE) throw new JpxException("tile " + tile.tileIndex + ": too many packets");
        include = new boolean[(int) size];
    }

    /** The packet sequence of a tile (opj_pi_create_decode + opj_pi_next until false). */
    public static PacketIterator of(Codestream cs, TileLayout tile) throws JpxException {
        PacketIterator pi = new PacketIterator(cs, tile);
        TileCoding tcp = tile.coding;
        int maxRes = 0;
        for (int n : pi.numRes) maxRes = Math.max(maxRes, n);
        if (tcp.pocs.isEmpty()) {
            // opj_pi_update_decode_not_poc
            pi.run(tcp.progression, 0, 0, 0, maxRes, pi.numComps, tcp.numLayers);
        } else {
            // opj_pi_update_decode_poc: each entry in turn, sharing the include table
            for (Poc poc : tcp.pocs) {
                pi.run(poc.progression, poc.resStart, poc.compStart, 0, poc.resEnd, poc.compEnd,
                        Math.min(poc.layerEnd, tcp.numLayers));
            }
        }
        return pi;
    }

    public int count() {
        return count;
    }

    public int layer(int i) {
        return packets[4 * i];
    }

    public int resolution(int i) {
        return packets[4 * i + 1];
    }

    public int component(int i) {
        return packets[4 * i + 2];
    }

    public int precinct(int i) {
        return packets[4 * i + 3];
    }

    private void emit(int l, int r, int c, int p) throws JpxException {
        int index = l * stepL + r * stepR + c * stepC + p;
        if (index < 0 || index >= include.length) throw new JpxException("packet index out of range");
        if (include[index]) return;
        include[index] = true;
        if (4 * count + 4 > packets.length) packets = java.util.Arrays.copyOf(packets, packets.length * 2);
        packets[4 * count] = l;
        packets[4 * count + 1] = r;
        packets[4 * count + 2] = c;
        packets[4 * count + 3] = p;
        count++;
    }

    private void run(int prg, int res0, int comp0, int lay0, int res1, int comp1, int lay1) throws JpxException {
        if (comp0 >= numComps || comp1 > numComps) throw new JpxException("progression component range " + comp0 + ".." + comp1);
        switch (prg) {
            case Markers.LRCP:
                for (int l = lay0; l < lay1; l++)
                    for (int r = res0; r < res1; r++)
                        for (int c = comp0; c < comp1; c++) {
                            if (r >= numRes[c]) continue;
                            for (int p = 0, n = pw[c][r] * ph[c][r]; p < n; p++) emit(l, r, c, p);
                        }
                break;
            case Markers.RLCP:
                for (int r = res0; r < res1; r++)
                    for (int l = lay0; l < lay1; l++)
                        for (int c = comp0; c < comp1; c++) {
                            if (r >= numRes[c]) continue;
                            for (int p = 0, n = pw[c][r] * ph[c][r]; p < n; p++) emit(l, r, c, p);
                        }
                break;
            case Markers.RPCL: {
                long[] d = minStep(0, numComps);
                if (d == null) return;
                for (int r = res0; r < res1; r++)
                    for (long y = ty0; y < ty1; y += d[1] - (y % d[1]))
                        for (long x = tx0; x < tx1; x += d[0] - (x % d[0]))
                            for (int c = comp0; c < comp1; c++) {
                                if (r >= numRes[c]) continue;
                                int p = precinctAt(c, r, x, y);
                                if (p < 0) continue;
                                for (int l = lay0; l < lay1; l++) emit(l, r, c, p);
                            }
                break;
            }
            case Markers.PCRL: {
                long[] d = minStep(0, numComps);
                if (d == null) return;
                for (long y = ty0; y < ty1; y += d[1] - (y % d[1]))
                    for (long x = tx0; x < tx1; x += d[0] - (x % d[0]))
                        for (int c = comp0; c < comp1; c++)
                            for (int r = res0; r < Math.min(res1, numRes[c]); r++) {
                                int p = precinctAt(c, r, x, y);
                                if (p < 0) continue;
                                for (int l = lay0; l < lay1; l++) emit(l, r, c, p);
                            }
                break;
            }
            case Markers.CPRL:
                for (int c = comp0; c < comp1; c++) {
                    long[] d = minStep(c, c + 1);
                    if (d == null) return;
                    for (long y = ty0; y < ty1; y += d[1] - (y % d[1]))
                        for (long x = tx0; x < tx1; x += d[0] - (x % d[0]))
                            for (int r = res0; r < Math.min(res1, numRes[c]); r++) {
                                int p = precinctAt(c, r, x, y);
                                if (p < 0) continue;
                                for (int l = lay0; l < lay1; l++) emit(l, r, c, p);
                            }
                }
                break;
            default:
                throw new JpxException("unknown progression order " + prg);
        }
    }

    /** The smallest precinct step on the reference grid over components [c0, c1) (pi->dx, pi->dy). */
    private long[] minStep(int c0, int c1) {
        long mdx = 0, mdy = 0;
        for (int c = c0; c < c1; c++) {
            for (int r = 0; r < numRes[c]; r++) {
                int ex = pdx[c][r] + numRes[c] - 1 - r;
                int ey = pdy[c][r] + numRes[c] - 1 - r;
                if (ex < 32 && (long) dx[c] << ex <= 0xFFFFFFFFL) {
                    long v = (long) dx[c] << ex;
                    mdx = mdx == 0 ? v : Math.min(mdx, v);
                }
                if (ey < 32 && (long) dy[c] << ey <= 0xFFFFFFFFL) {
                    long v = (long) dy[c] << ey;
                    mdy = mdy == 0 ? v : Math.min(mdy, v);
                }
            }
        }
        return mdx == 0 || mdy == 0 ? null : new long[]{mdx, mdy};
    }

    private static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }

    /**
     * The precinct of resolution r of component c that starts at reference-grid position (x, y),
     * or -1 when none does (the position tests of opj_pi_next_rpcl/pcrl/cprl, B.12.1.3-5).
     */
    private int precinctAt(int c, int r, long x, long y) {
        int levelNo = numRes[c] - 1 - r;
        if (levelNo >= 32) return -1;
        long cdx = (long) dx[c] << levelNo, cdy = (long) dy[c] << levelNo;
        long trx0 = ceilDiv(tx0, cdx), try0 = ceilDiv(ty0, cdy);
        long trx1 = ceilDiv(tx1, cdx), try1 = ceilDiv(ty1, cdy);
        int rpx = pdx[c][r] + levelNo, rpy = pdy[c][r] + levelNo;
        if (rpx >= 63 || rpy >= 63) return -1;
        if (!(y % ((long) dy[c] << rpy) == 0 || (y == ty0 && ((try0 << levelNo) % (1L << rpy)) != 0))) return -1;
        if (!(x % ((long) dx[c] << rpx) == 0 || (x == tx0 && ((trx0 << levelNo) % (1L << rpx)) != 0))) return -1;
        if (pw[c][r] == 0 || ph[c][r] == 0) return -1;
        if (trx0 == trx1 || try0 == try1) return -1;
        long prci = (ceilDiv(x, cdx) >> pdx[c][r]) - (trx0 >> pdx[c][r]);
        long prcj = (ceilDiv(y, cdy) >> pdy[c][r]) - (try0 >> pdy[c][r]);
        long p = prci + prcj * pw[c][r];
        return p < 0 || p >= (long) pw[c][r] * ph[c][r] ? -1 : (int) p;
    }
}
