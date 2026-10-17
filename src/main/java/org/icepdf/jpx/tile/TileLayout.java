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
 * Provenance: PORTED from OpenJPEG src/lib/openjp2/tcd.c, tcd.h, opj_intmath.h @ 6c4a29b0 (BSD-2):
 *   the decoder half of opj_tcd_init_tile (tile, tile-component, resolution, band, precinct and
 *   code-block geometry; band step size and Mb, including the BUG_WEIRD_TWO_INVK log2 gain of
 *   0 for irreversible decoding), opj_tcd_is_band_empty, the decode fields of opj_tcd_seg_t /
 *   opj_tcd_cblk_dec_t / opj_tcd_precinct_t / opj_tcd_band_t / opj_tcd_resolution_t /
 *   opj_tcd_tilecomp_t, and opj_int_ceildiv / ceildivpow2 / floordivpow2.
 *   Changed: plain Java objects built fresh per tile (no buffer reuse); a code-block keeps its
 *   data chunks as (offset, length) into the source arrays; overflow checks use long.
 *   Geometry: ITU-T T.800 B.5-B.7.
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
package org.icepdf.jpx.tile;

import org.icepdf.jpx.JpxException;
import org.icepdf.jpx.codestream.Codestream;
import org.icepdf.jpx.codestream.Codestream.ComponentCoding;
import org.icepdf.jpx.codestream.Codestream.TileCoding;
import org.icepdf.jpx.tier2.TagTree;

/** The resolution / band / precinct / code-block structure of one tile (T.800 Annex B). */
public final class TileLayout {

    /** A coding segment of a code-block (opj_tcd_seg_t). */
    public static final class Segment {
        public int length;
        public int numPasses;
        public int realNumPasses;
        public int maxPasses;
        public int numNewPasses;
        public int newLength;
    }

    /** A code-block (opj_tcd_cblk_dec_t). */
    public static final class CodeBlock {
        public final int x0, y0, x1, y1;
        public int numBitPlanes;
        public int numLenBits;
        public int numNewPasses;
        public int numSegs;
        public int realNumSegs;
        public Segment[] segs = new Segment[0];
        /** Data chunks: source array and offset/length, in order. */
        public byte[][] chunkData = new byte[2][];
        public int[] chunkOffset = new int[2];
        public int[] chunkLength = new int[2];
        public int numChunks;
        public boolean corrupted;

        CodeBlock(int x0, int y0, int x1, int y1) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
        }

        public Segment segment(int index) {
            if (index >= segs.length) {
                Segment[] n = java.util.Arrays.copyOf(segs, Math.max(index + 1, segs.length + 10));
                for (int i = segs.length; i < n.length; i++) n[i] = new Segment();
                segs = n;
            }
            return segs[index];
        }

        public void addChunk(byte[] data, int offset, int length) {
            if (numChunks == chunkOffset.length) {
                int n = numChunks * 2 + 1;
                chunkData = java.util.Arrays.copyOf(chunkData, n);
                chunkOffset = java.util.Arrays.copyOf(chunkOffset, n);
                chunkLength = java.util.Arrays.copyOf(chunkLength, n);
            }
            chunkData[numChunks] = data;
            chunkOffset[numChunks] = offset;
            chunkLength[numChunks] = length;
            numChunks++;
        }
    }

    /** A precinct of one band (opj_tcd_precinct_t). */
    public static final class Precinct {
        public final int x0, y0, x1, y1;
        /** Code-blocks across and down. */
        public final int cw, ch;
        public final CodeBlock[] codeBlocks;
        public final TagTree inclusion;
        public final TagTree zeroBitPlanes;

        Precinct(int x0, int y0, int x1, int y1, int cw, int ch, CodeBlock[] codeBlocks) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.cw = cw;
            this.ch = ch;
            this.codeBlocks = codeBlocks;
            this.inclusion = cw > 0 && ch > 0 ? new TagTree(cw, ch) : null;
            this.zeroBitPlanes = cw > 0 && ch > 0 ? new TagTree(cw, ch) : null;
        }
    }

    /** A sub-band (opj_tcd_band_t). */
    public static final class Band {
        /** 0 LL, 1 HL, 2 LH, 3 HH. */
        public final int bandNo;
        public final int x0, y0, x1, y1;
        /** Quantization step (Delta_b, E.1). */
        public final float stepSize;
        /** Mb - 1 (E-2): exponent + guard bits - 1. */
        public final int numBitPlanes;
        public final Precinct[] precincts;

        Band(int bandNo, int x0, int y0, int x1, int y1, float stepSize, int numBitPlanes, Precinct[] precincts) {
            this.bandNo = bandNo;
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.stepSize = stepSize;
            this.numBitPlanes = numBitPlanes;
            this.precincts = precincts;
        }

        /** opj_tcd_is_band_empty. */
        public boolean isEmpty() {
            return x1 - x0 == 0 || y1 - y0 == 0;
        }
    }

    /** A resolution level of a tile-component (opj_tcd_resolution_t). */
    public static final class Resolution {
        public final int x0, y0, x1, y1;
        /** Precincts across and down. */
        public final int pw, ph;
        public final Band[] bands;

        Resolution(int x0, int y0, int x1, int y1, int pw, int ph, Band[] bands) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.pw = pw;
            this.ph = ph;
            this.bands = bands;
        }

        public int width() {
            return x1 - x0;
        }

        public int height() {
            return y1 - y0;
        }
    }

    /** A tile-component (opj_tcd_tilecomp_t). */
    public static final class Component {
        public final int x0, y0, x1, y1;
        public final int numResolutions;
        /** Resolutions to decode: numResolutions - reduce, at least 1. */
        public final int minimumNumResolutions;
        public final Resolution[] resolutions;

        Component(int x0, int y0, int x1, int y1, int numResolutions, int minimumNumResolutions, Resolution[] resolutions) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.numResolutions = numResolutions;
            this.minimumNumResolutions = minimumNumResolutions;
            this.resolutions = resolutions;
        }

        /** The highest resolution decoded. */
        public Resolution top() {
            return resolutions[minimumNumResolutions - 1];
        }
    }

    public final int tileIndex;
    /** Tile area on the reference grid. */
    public final int x0, y0, x1, y1;
    public final TileCoding coding;
    public final Component[] components;

    private TileLayout(int tileIndex, int x0, int y0, int x1, int y1, TileCoding coding, Component[] components) {
        this.tileIndex = tileIndex;
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
        this.coding = coding;
        this.components = components;
    }

    // opj_intmath.h
    static int ceilDiv(int a, int b) {
        return (int) (((long) a + b - 1) / b);
    }

    static int ceilDivPow2(long a, int b) {
        return (int) ((a + (1L << b) - 1) >> b);
    }

    static int floorDivPow2(int a, int b) {
        return a >> b;
    }

    /** Guard against hostile parameters: total code-blocks and precincts per tile. */
    private static final long MAX_ELEMENTS = 1L << 24;

    /** opj_tcd_init_tile (decoder half), with {@code reduce} resolution levels discarded. */
    public static TileLayout build(Codestream cs, int tileIndex, int reduce) throws JpxException {
        TileCoding tcp = cs.coding(tileIndex);
        int p = tileIndex % cs.tilesX;
        int q = tileIndex / cs.tilesX;
        long tx0 = (long) cs.tileX0 + (long) p * cs.tileWidth;
        int tileX0 = (int) Math.max(tx0, cs.x0);
        int tileX1 = (int) Math.min(tx0 + cs.tileWidth, cs.x1);
        long ty0 = (long) cs.tileY0 + (long) q * cs.tileHeight;
        int tileY0 = (int) Math.max(ty0, cs.y0);
        int tileY1 = (int) Math.min(ty0 + cs.tileHeight, cs.y1);
        if (tileX0 < 0 || tileX1 <= tileX0 || tileY0 < 0 || tileY1 <= tileY0) {
            throw new JpxException("tile " + tileIndex + " is empty");
        }
        long elements = 0;
        Component[] comps = new Component[cs.components.length];
        for (int c = 0; c < comps.length; c++) {
            Codestream.Component ic = cs.components[c];
            ComponentCoding tccp = tcp.components[c];
            if (tccp.numResolutions == 0) throw new JpxException("component " + c + " has no resolutions");
            int cx0 = ceilDiv(tileX0, ic.dx), cy0 = ceilDiv(tileY0, ic.dy);
            int cx1 = ceilDiv(tileX1, ic.dx), cy1 = ceilDiv(tileY1, ic.dy);
            int numRes = tccp.numResolutions;
            int minRes = numRes < reduce ? 1 : Math.max(1, numRes - reduce);
            Resolution[] resolutions = new Resolution[numRes];
            int levelNo = numRes;
            int stepIndex = 0;
            for (int r = 0; r < numRes; r++) {
                --levelNo;
                int rx0 = ceilDivPow2(cx0, levelNo), ry0 = ceilDivPow2(cy0, levelNo);
                int rx1 = ceilDivPow2(cx1, levelNo), ry1 = ceilDivPow2(cy1, levelNo);
                int pdx = tccp.precinctWidthExp[r], pdy = tccp.precinctHeightExp[r];
                int tlPrcX = floorDivPow2(rx0, pdx) << pdx;
                int tlPrcY = floorDivPow2(ry0, pdy) << pdy;
                long brPrcX = (long) ceilDivPow2(rx1, pdx) << pdx;
                long brPrcY = (long) ceilDivPow2(ry1, pdy) << pdy;
                if (brPrcX > Integer.MAX_VALUE || brPrcY > Integer.MAX_VALUE) throw new JpxException("precinct grid overflow");
                int pw = rx0 == rx1 ? 0 : (int) ((brPrcX - tlPrcX) >> pdx);
                int ph = ry0 == ry1 ? 0 : (int) ((brPrcY - tlPrcY) >> pdy);
                long numPrecincts = (long) pw * ph;
                elements += numPrecincts;
                if (elements > MAX_ELEMENTS) throw new JpxException("tile " + tileIndex + ": too many precincts");
                int tlCbgX, tlCbgY, cbgWidthExp, cbgHeightExp, numBands;
                if (r == 0) {
                    tlCbgX = tlPrcX;
                    tlCbgY = tlPrcY;
                    cbgWidthExp = pdx;
                    cbgHeightExp = pdy;
                    numBands = 1;
                } else {
                    tlCbgX = ceilDivPow2(tlPrcX, 1);
                    tlCbgY = ceilDivPow2(tlPrcY, 1);
                    cbgWidthExp = pdx - 1;
                    cbgHeightExp = pdy - 1;
                    numBands = 3;
                }
                int cblkWidthExp = Math.min(tccp.cblkWidthExp, cbgWidthExp);
                int cblkHeightExp = Math.min(tccp.cblkHeightExp, cbgHeightExp);
                Band[] bands = new Band[numBands];
                for (int b = 0; b < numBands; b++, stepIndex++) {
                    int bandNo, bx0, by0, bx1, by1;
                    if (r == 0) {
                        bandNo = 0;
                        bx0 = ceilDivPow2(cx0, levelNo);
                        by0 = ceilDivPow2(cy0, levelNo);
                        bx1 = ceilDivPow2(cx1, levelNo);
                        by1 = ceilDivPow2(cy1, levelNo);
                    } else {
                        bandNo = b + 1;
                        int x0b = bandNo & 1;
                        int y0b = bandNo >> 1;
                        bx0 = ceilDivPow2(cx0 - ((long) x0b << levelNo), levelNo + 1);
                        by0 = ceilDivPow2(cy0 - ((long) y0b << levelNo), levelNo + 1);
                        bx1 = ceilDivPow2(cx1 - ((long) x0b << levelNo), levelNo + 1);
                        by1 = ceilDivPow2(cy1 - ((long) y0b << levelNo), levelNo + 1);
                    }
                    // Table E-1 sub-band gains; BUG_WEIRD_TWO_INVK: 0 for irreversible decoding
                    int log2Gain = tccp.transform == 0 ? 0 : bandNo == 0 ? 0 : bandNo == 3 ? 2 : 1;
                    int rb = ic.precision + log2Gain;
                    int expn = tccp.stepExponent[Math.min(stepIndex, Codestream.MAX_BANDS - 1)];
                    int mant = tccp.stepMantissa[Math.min(stepIndex, Codestream.MAX_BANDS - 1)];
                    float stepSize = (float) ((1.0 + mant / 2048.0) * Math.pow(2.0, rb - expn));
                    int numBps = expn + tccp.guardBits - 1;
                    Precinct[] precincts = new Precinct[(int) numPrecincts];
                    for (int pr = 0; pr < precincts.length; pr++) {
                        int cbgX = tlCbgX + (pr % pw) * (1 << cbgWidthExp);
                        int cbgY = tlCbgY + (pr / pw) * (1 << cbgHeightExp);
                        int px0 = Math.max(cbgX, bx0), py0 = Math.max(cbgY, by0);
                        int px1 = Math.min(cbgX + (1 << cbgWidthExp), bx1);
                        int py1 = Math.min(cbgY + (1 << cbgHeightExp), by1);
                        int tlCblkX = floorDivPow2(px0, cblkWidthExp) << cblkWidthExp;
                        int tlCblkY = floorDivPow2(py0, cblkHeightExp) << cblkHeightExp;
                        int brCblkX = ceilDivPow2(px1, cblkWidthExp) << cblkWidthExp;
                        int brCblkY = ceilDivPow2(py1, cblkHeightExp) << cblkHeightExp;
                        int cw = Math.max(0, (brCblkX - tlCblkX) >> cblkWidthExp);
                        int ch = Math.max(0, (brCblkY - tlCblkY) >> cblkHeightExp);
                        elements += (long) cw * ch;
                        if (elements > MAX_ELEMENTS) throw new JpxException("tile " + tileIndex + ": too many code-blocks");
                        CodeBlock[] blocks = new CodeBlock[cw * ch];
                        for (int k = 0; k < blocks.length; k++) {
                            int cbx = tlCblkX + (k % cw) * (1 << cblkWidthExp);
                            int cby = tlCblkY + (k / cw) * (1 << cblkHeightExp);
                            blocks[k] = new CodeBlock(Math.max(cbx, px0), Math.max(cby, py0),
                                    Math.min(cbx + (1 << cblkWidthExp), px1), Math.min(cby + (1 << cblkHeightExp), py1));
                        }
                        precincts[pr] = new Precinct(px0, py0, px1, py1, cw, ch, blocks);
                    }
                    bands[b] = new Band(bandNo, bx0, by0, bx1, by1, stepSize, numBps, precincts);
                }
                resolutions[r] = new Resolution(rx0, ry0, rx1, ry1, pw, ph, bands);
            }
            comps[c] = new Component(cx0, cy0, cx1, cy1, numRes, minRes, resolutions);
        }
        return new TileLayout(tileIndex, tileX0, tileY0, tileX1, tileY1, tcp, comps);
    }
}
