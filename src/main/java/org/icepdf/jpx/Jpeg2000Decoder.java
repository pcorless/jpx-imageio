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
 * Provenance: ADAPTED from OpenJPEG src/lib/openjp2/j2k.c @ 6c4a29b0 (BSD-2):
 *   the component geometry of opj_j2k_update_image_dimensions (ceildiv by dx/dy, then
 *   ceildivpow2 by the reduce factor) and the tile copy of opj_j2k_update_image_data.
 *   Changed: tiles are decoded one after another into whole-image component arrays; no
 *   decode-area window yet.
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
 * Copyright (c) 2008, Jerome Fimes, Communications & Systemes <jerome.fimes@c-s.fr>
 * Copyright (c) 2006-2007, Parvatha Elangovan
 * Copyright (c) 2010-2011, Kaori Hagihara
 * Copyright (c) 2011-2012, Centre National d'Etudes Spatiales (CNES), France
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
package org.icepdf.jpx;

import org.icepdf.jpx.codestream.Codestream;
import org.icepdf.jpx.codestream.CodestreamReader;
import org.icepdf.jpx.tile.TileDecoder;
import org.icepdf.jpx.tile.TileDecoder.DecodedComponent;

/** Decodes a JPEG 2000 codestream (J2K) to its components' samples. */
public final class Jpeg2000Decoder {

    /** A decoded component: samples row-major, no colour conversion, no upsampling. */
    public static final class Component {
        public final int width;
        public final int height;
        public final int precision;
        public final boolean signed;
        public final int[] samples;

        Component(int width, int height, int precision, boolean signed, int[] samples) {
            this.width = width;
            this.height = height;
            this.precision = precision;
            this.signed = signed;
            this.samples = samples;
        }
    }

    private Jpeg2000Decoder() {
    }

    static int ceilDiv(long a, long b) {
        return (int) ((a + b - 1) / b);
    }

    static int ceilDivPow2(long a, int b) {
        return (int) ((a + (1L << b) - 1) >> b);
    }

    /** Decodes the codestream in {@code data[offset, offset + length)} with {@code reduce} levels discarded. */
    public static Component[] decode(byte[] data, int offset, int length, int reduce) throws JpxException {
        return decode(CodestreamReader.read(data, offset, length), reduce);
    }

    public static Component[] decode(Codestream cs, int reduce) throws JpxException {
        int nc = cs.components.length;
        int[] cx0 = new int[nc], cy0 = new int[nc], cw = new int[nc], ch = new int[nc];
        int[][] samples = new int[nc][];
        for (int c = 0; c < nc; c++) {
            Codestream.Component ic = cs.components[c];
            int x0 = ceilDivPow2(ceilDiv(cs.x0, ic.dx), reduce);
            int y0 = ceilDivPow2(ceilDiv(cs.y0, ic.dy), reduce);
            int x1 = ceilDivPow2(ceilDiv(cs.x1, ic.dx), reduce);
            int y1 = ceilDivPow2(ceilDiv(cs.y1, ic.dy), reduce);
            cx0[c] = x0;
            cy0[c] = y0;
            cw[c] = x1 - x0;
            ch[c] = y1 - y0;
            long size = (long) cw[c] * ch[c];
            if (size > Integer.MAX_VALUE - 8) throw JpxException.unsupported("component of " + size + " samples");
            samples[c] = new int[(int) size];
        }
        TileDecoder decoder = new TileDecoder();
        for (int t = 0; t < cs.numTiles(); t++) {
            if (cs.tile(t) == null) {
                cs.warningsSink("tile " + t + " has no tile-parts; left empty");
                continue;
            }
            DecodedComponent[] tile = decoder.decode(cs, t, reduce);
            for (int c = 0; c < nc; c++) {
                DecodedComponent dc = tile[c];
                // opj_j2k_update_image_data: copy the tile's area into the component
                int ox = dc.x0 - cx0[c], oy = dc.y0 - cy0[c];
                int w = Math.min(dc.width(), cw[c] - ox);
                for (int j = 0; j < dc.height() && oy + j < ch[c]; j++) {
                    if (oy + j < 0 || w <= 0 || ox < 0) continue;
                    System.arraycopy(dc.samples, j * dc.width(), samples[c], (oy + j) * cw[c] + ox, w);
                }
            }
        }
        Component[] out = new Component[nc];
        for (int c = 0; c < nc; c++) {
            out[c] = new Component(cw[c], ch[c], cs.components[c].precision, cs.components[c].signed, samples[c]);
        }
        return out;
    }
}
