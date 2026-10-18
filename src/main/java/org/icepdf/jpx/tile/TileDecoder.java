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
 * Provenance: ADAPTED from OpenJPEG src/lib/openjp2/tcd.c, t1.c @ 6c4a29b0 (BSD-2):
 *   opj_tcd_decode_tile (whole-tile path: t2, t1, dwt, mct, dc level shift), opj_tcd_t1_decode,
 *   opj_tcd_dwt_decode, opj_tcd_mct_decode (RCT/ICT checks), opj_tcd_dc_level_shift_decode,
 *   and the placement and dequantisation of opj_t1_clbl_decode_processor (x/y of the
 *   code-block in the tile buffer; /2 for 5/3, x 0.5 * step size for 9/7) plus the chunk
 *   concatenation of opj_t1_decode_cblk.
 *   Changed: the ICT (opj_mct_decode_real's expressions) runs fused with the level shift and
 *   clamp, one pass over the three components;
 *   separate int[] / float[] buffers instead of one buffer reinterpreted as float;
 *   always the whole tile (no window / sparse-array partial decode yet); the DWT always runs
 *   to the decoded resolution count (OpenJPEG lowers it when no packet of the top resolution
 *   was read at all).
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
 * Copyright (c) 2008, 2011-2012, Centre National d'Etudes Spatiales (CNES), FR
 * Copyright (c) 2012, CS Systemes d'Information, France
 * Copyright (c) 2017, IntoPIX SA <support@intopix.com>
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
 * Copyright (c) 2007, Callum Lerwick <seg@haxxed.com>
 * Copyright (c) 2012, Carl Hetherington
 * Copyright (c) 2017, IntoPIX SA <support@intopix.com>
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
package org.icepdf.jpx.tile;

import org.icepdf.jpx.JpxException;
import org.icepdf.jpx.codestream.Codestream;
import org.icepdf.jpx.codestream.Codestream.ComponentCoding;
import org.icepdf.jpx.tier1.CodeBlockDecoder;
import org.icepdf.jpx.tier2.PacketDecoder;
import org.icepdf.jpx.tier2.PacketIterator;
import org.icepdf.jpx.tile.TileLayout.Band;
import org.icepdf.jpx.tile.TileLayout.CodeBlock;
import org.icepdf.jpx.tile.TileLayout.Precinct;
import org.icepdf.jpx.tile.TileLayout.Resolution;
import org.icepdf.jpx.transform.InverseDwt;
import org.icepdf.jpx.transform.InverseMct;

/** Decodes one tile to reconstructed component samples. */
public final class TileDecoder {

    /** A decoded tile-component: samples of its top decoded resolution, row-major. */
    public static final class DecodedComponent {
        /** Area on the (reduced) component grid. */
        public final int x0, y0, x1, y1;
        public final int[] samples;

        DecodedComponent(int x0, int y0, int x1, int y1, int[] samples) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.samples = samples;
        }

        public int width() {
            return x1 - x0;
        }

        public int height() {
            return y1 - y0;
        }
    }

    private final CodeBlockDecoder t1 = new CodeBlockDecoder();
    private byte[] concat = new byte[0];
    private int[] segLength = new int[16];
    private int[] segPasses = new int[16];

    /** opj_tcd_decode_tile. */
    public DecodedComponent[] decode(Codestream cs, int tileIndex, int reduce) throws JpxException {
        TileLayout tile = TileLayout.build(cs, tileIndex, reduce);
        PacketIterator pi = PacketIterator.of(cs, tile);
        PacketDecoder.decode(cs, tile, pi);
        int nc = tile.components.length;
        int[][] ints = new int[nc][];
        float[][] floats = new float[nc][];
        for (int c = 0; c < nc; c++) {
            TileLayout.Component tc = tile.components[c];
            ComponentCoding tccp = tile.coding.components[c];
            Resolution top = tc.top();
            int w = top.width(), h = top.height();
            long size = (long) w * h;
            if (size > Integer.MAX_VALUE - 8) throw JpxException.unsupported("tile-component of " + size + " samples");
            if (tccp.transform == 1) ints[c] = new int[(int) size];
            else floats[c] = new float[(int) size];
            decodeCodeBlocks(tc, tccp, w, ints[c], floats[c]);
            // opj_tcd_dwt_decode
            if (tccp.transform == 1) {
                InverseDwt.decode53(ints[c], w, tc.resolutions, tc.minimumNumResolutions);
            } else {
                InverseDwt.decode97(floats[c], w, tc.resolutions, tc.minimumNumResolutions);
            }
        }
        int kind = mct(tile, ints, floats);
        // ICT, level shift and clamp in one pass over the three components (same float
        // expressions, so the result is unchanged)
        int[][] fused = kind == ICT ? ictLevelShift(cs, tile, floats) : null;
        DecodedComponent[] out = new DecodedComponent[nc];
        for (int c = 0; c < nc; c++) {
            TileLayout.Component tc = tile.components[c];
            Resolution top = tc.top();
            int[] samples = fused != null && c < 3 ? fused[c]
                    : dcLevelShift(cs.components[c], tile.coding.components[c], ints[c], floats[c], top.width() * top.height());
            out[c] = new DecodedComponent(top.x0, top.y0, top.x1, top.y1, samples);
        }
        return out;
    }

    /**
     * opj_mct_decode_real then opj_tcd_dc_level_shift_decode for components 0-2, fused: each
     * sample's RGB is computed as InverseMct.decodeIrreversible computes it, then rounded,
     * shifted and clamped as dcLevelShift does.
     */
    private static int[][] ictLevelShift(Codestream cs, TileLayout tile, float[][] floats) {
        int n = tile.components[0].top().width() * tile.components[0].top().height();
        float[] c0 = floats[0], c1 = floats[1], c2 = floats[2];
        int[] o0 = new int[n], o1 = new int[n], o2 = new int[n];
        int[] min = new int[3], max = new int[3], shift = new int[3];
        for (int c = 0; c < 3; c++) {
            Codestream.Component ic = cs.components[c];
            if (ic.signed) {
                min[c] = -(1 << (ic.precision - 1));
                max[c] = (1 << (ic.precision - 1)) - 1;
            } else {
                min[c] = 0;
                max[c] = (int) ((1L << ic.precision) - 1);
            }
            shift[c] = tile.coding.components[c].dcLevelShift;
        }
        int min0 = min[0], max0 = max[0], s0 = shift[0];
        int min1 = min[1], max1 = max[1], s1 = shift[1];
        int min2 = min[2], max2 = max[2], s2 = shift[2];
        for (int i = 0; i < n; ++i) {
            float y = c0[i];
            float u = c1[i];
            float v = c2[i];
            o0[i] = levelShift(y + (v * 1.402f), s0, min0, max0);
            o1[i] = levelShift(y - (u * 0.34413f) - (v * (0.71414f)), s1, min1, max1);
            o2[i] = levelShift(y + (u * 1.772f), s2, min2, max2);
        }
        return new int[][]{o0, o1, o2};
    }

    /** One sample of the float branch of dcLevelShift. */
    private static int levelShift(float value, int shift, int min, int max) {
        if (value > (float) Integer.MAX_VALUE) return max;
        if (value < Integer.MIN_VALUE) return min;
        // opj_lrintf: round half to even
        long v = (long) Math.rint(value) + shift;
        return (int) Math.max(min, Math.min(max, v));
    }

    /** opj_tcd_t1_decode + opj_t1_clbl_decode_processor for one tile-component. */
    private void decodeCodeBlocks(TileLayout.Component tc, ComponentCoding tccp, int tileW, int[] ints, float[] floats)
            throws JpxException {
        for (int r = 0; r < tc.minimumNumResolutions; r++) {
            Resolution res = tc.resolutions[r];
            for (Band band : res.bands) {
                if (band.isEmpty()) continue;
                for (Precinct prc : band.precincts) {
                    for (CodeBlock cblk : prc.codeBlocks) {
                        int cw = cblk.x1 - cblk.x0, ch = cblk.y1 - cblk.y0;
                        if (cw <= 0 || ch <= 0) continue;
                        decodeCodeBlock(cblk, band, tccp, cw, ch);
                        int x = cblk.x0 - band.x0, y = cblk.y0 - band.y0;
                        if ((band.bandNo & 1) != 0) x += tc.resolutions[r - 1].width();
                        if ((band.bandNo & 2) != 0) y += tc.resolutions[r - 1].height();
                        int[] d = t1.data();
                        if (ints != null) {
                            for (int j = 0; j < ch; j++) {
                                int o = (y + j) * tileW + x;
                                for (int i = 0; i < cw; i++) ints[o + i] = d[j * cw + i] / 2;
                            }
                        } else {
                            float step = 0.5f * band.stepSize;
                            for (int j = 0; j < ch; j++) {
                                int o = (y + j) * tileW + x;
                                for (int i = 0; i < cw; i++) floats[o + i] = (float) d[j * cw + i] * step;
                            }
                        }
                    }
                }
            }
        }
    }

    private void decodeCodeBlock(CodeBlock cblk, Band band, ComponentCoding tccp, int cw, int ch) throws JpxException {
        int numSegs = cblk.corrupted || cblk.numChunks == 0 ? 0 : cblk.realNumSegs;
        byte[] buf;
        int off;
        if (cblk.numChunks == 1) {
            buf = cblk.chunkData[0];
            off = cblk.chunkOffset[0];
        } else {
            int len = 0;
            for (int i = 0; i < cblk.numChunks; i++) len += cblk.chunkLength[i];
            if (concat.length < len) concat = new byte[len];
            int o = 0;
            for (int i = 0; i < cblk.numChunks; i++) {
                System.arraycopy(cblk.chunkData[i], cblk.chunkOffset[i], concat, o, cblk.chunkLength[i]);
                o += cblk.chunkLength[i];
            }
            buf = concat;
            off = 0;
        }
        if (segLength.length < numSegs) {
            segLength = new int[numSegs];
            segPasses = new int[numSegs];
        }
        for (int s = 0; s < numSegs; s++) {
            segLength[s] = cblk.segs[s].length;
            segPasses[s] = cblk.segs[s].realNumPasses;
        }
        t1.decode(cw, ch, buf, off, segLength, segPasses, numSegs, cblk.numBitPlanes, band.bandNo, tccp.roiShift,
                tccp.cblkStyle);
    }

    static final int NO_MCT = 0, RCT = 1, ICT = 2;

    /**
     * opj_tcd_mct_decode. The RCT is applied here; for the ICT only the checks are, and the
     * caller fuses the transform with the level shift (ictLevelShift).
     */
    private static int mct(TileLayout tile, int[][] ints, float[][] floats) throws JpxException {
        if (tile.coding.mct == 0) return NO_MCT;
        if (tile.components.length < 3) return NO_MCT; // OpenJPEG: warning, MCT skipped
        int n = tile.components[0].top().width() * tile.components[0].top().height();
        for (int c = 1; c < 3; c++) {
            Resolution r = tile.components[c].top();
            if (tile.components[c].minimumNumResolutions != tile.components[0].minimumNumResolutions
                    || r.width() * r.height() != n) {
                throw new JpxException("multiple component transform over components of different sizes");
            }
        }
        if (tile.coding.components[0].transform == 1) {
            if (ints[1] == null || ints[2] == null) throw new JpxException("RCT over components with different transforms");
            InverseMct.decodeReversible(ints[0], ints[1], ints[2], n);
            return RCT;
        } else {
            if (floats[1] == null || floats[2] == null) throw new JpxException("ICT over components with different transforms");
            return ICT;
        }
    }

    /** opj_tcd_dc_level_shift_decode: add the DC offset, round (9/7) and clamp to the precision. */
    private static int[] dcLevelShift(Codestream.Component ic, ComponentCoding tccp, int[] ints, float[] floats, int n) {
        int min, max;
        if (ic.signed) {
            min = -(1 << (ic.precision - 1));
            max = (1 << (ic.precision - 1)) - 1;
        } else {
            min = 0;
            max = (int) ((1L << ic.precision) - 1);
        }
        int shift = tccp.dcLevelShift;
        if (ints != null) {
            for (int i = 0; i < n; i++) {
                long v = (long) ints[i] + shift;
                ints[i] = (int) Math.max(min, Math.min(max, v));
            }
            return ints;
        }
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            float value = floats[i];
            if (value > (float) Integer.MAX_VALUE) {
                out[i] = max;
            } else if (value < Integer.MIN_VALUE) {
                out[i] = min;
            } else {
                // opj_lrintf: round half to even
                long v = (long) Math.rint(value) + shift;
                out[i] = (int) Math.max(min, Math.min(max, v));
            }
        }
        return out;
    }
}
