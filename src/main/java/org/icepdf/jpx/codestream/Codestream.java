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
 * Provenance: ADAPTED from OpenJPEG src/lib/openjp2/j2k.h, openjpeg.h @ 6c4a29b0 (BSD-2):
 *   the decoder-side fields of opj_image_comp_t, opj_cp_t, opj_tcp_t, opj_tccp_t,
 *   opj_stepsize_t and opj_poc_t, and the default precinct exponent (15).
 *   Changed: plain Java holders; per-tile parameters are deep copies of the main-header
 *   defaults (as opj_j2k_copy_default_tcp_and_create_tcd does); tile data is recorded as
 *   ranges into the source array instead of being copied; PPM data is kept per tile-part.
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
 * Copyright (c) 2008, Jerome Fimes, Communications & Systemes <jerome.fimes@c-s.fr>
 * Copyright (c) 2011-2012, Centre National d'Etudes Spatiales (CNES), France
 * Copyright (c) 2012, CS Systemes d'Information, France
 *
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
* Copyright (c) 2006-2007, Parvatha Elangovan
* Copyright (c) 2008, Jerome Fimes, Communications & Systemes <jerome.fimes@c-s.fr>
* Copyright (c) 2010-2011, Kaori Hagihara
* Copyright (c) 2011-2012, Centre National d'Etudes Spatiales (CNES), France
* Copyright (c) 2012, CS Systemes d'Information, France
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
package org.icepdf.jpx.codestream;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A parsed JPEG 2000 codestream: image geometry (SIZ), coding parameters per tile, and where
 * each tile's packet data lives. Produced by {@link CodestreamReader}; nothing is decoded yet.
 */
public final class Codestream {

    /** OPJ_J2K_MAXRLVLS: up to 32 decomposition levels, so 33 resolutions. */
    public static final int MAX_RESOLUTIONS = 33;
    /** OPJ_J2K_MAXBANDS. */
    public static final int MAX_BANDS = 3 * MAX_RESOLUTIONS - 2;
    /** J2K_MAX_POCS. */
    public static final int MAX_POCS = 32;

    /** SIZ: one image component (opj_image_comp_t). */
    public static final class Component {
        /** Bit depth, 1..31 (Ssiz + 1). */
        public final int precision;
        public final boolean signed;
        /** Horizontal and vertical subsampling, 1..255 (XRsiz, YRsiz). */
        public final int dx;
        public final int dy;

        Component(int precision, boolean signed, int dx, int dy) {
            this.precision = precision;
            this.signed = signed;
            this.dx = dx;
            this.dy = dy;
        }
    }

    /** POC entry (opj_poc_t): resolution and component ranges, layer end, progression. */
    public static final class Poc {
        public final int resStart;
        public final int compStart;
        public final int layerEnd;
        public final int resEnd;
        public final int compEnd;
        public final int progression;

        Poc(int resStart, int compStart, int layerEnd, int resEnd, int compEnd, int progression) {
            this.resStart = resStart;
            this.compStart = compStart;
            this.layerEnd = layerEnd;
            this.resEnd = resEnd;
            this.compEnd = compEnd;
            this.progression = progression;
        }
    }

    /** Per-component coding and quantization (opj_tccp_t). */
    public static final class ComponentCoding implements Cloneable {
        /** Scoc: precinct flag. */
        public int csty;
        public int numResolutions;
        /** Code-block width and height exponents (cblkw, cblkh), already +2. */
        public int cblkWidthExp;
        public int cblkHeightExp;
        public int cblkStyle;
        /** 1 = reversible 5/3, 0 = irreversible 9/7. */
        public int transform;
        public int quantStyle;
        public int guardBits;
        /** Step sizes per sub-band: exponent and mantissa. */
        public int[] stepExponent = new int[MAX_BANDS];
        public int[] stepMantissa = new int[MAX_BANDS];
        public int roiShift;
        /** Precinct width and height exponents per resolution (default 15). */
        public int[] precinctWidthExp = new int[MAX_RESOLUTIONS];
        public int[] precinctHeightExp = new int[MAX_RESOLUTIONS];
        /** 2^(prec-1) for unsigned components, else 0. */
        public int dcLevelShift;

        ComponentCoding() {
            java.util.Arrays.fill(precinctWidthExp, 15);
            java.util.Arrays.fill(precinctHeightExp, 15);
        }

        @Override
        public ComponentCoding clone() {
            try {
                ComponentCoding c = (ComponentCoding) super.clone();
                c.stepExponent = stepExponent.clone();
                c.stepMantissa = stepMantissa.clone();
                c.precinctWidthExp = precinctWidthExp.clone();
                c.precinctHeightExp = precinctHeightExp.clone();
                return c;
            } catch (CloneNotSupportedException e) {
                throw new AssertionError(e);
            }
        }

        /** Copies the COD-level fields (opj_j2k_copy_tile_component_parameters). */
        void copyCodingFrom(ComponentCoding ref) {
            numResolutions = ref.numResolutions;
            cblkWidthExp = ref.cblkWidthExp;
            cblkHeightExp = ref.cblkHeightExp;
            cblkStyle = ref.cblkStyle;
            transform = ref.transform;
            System.arraycopy(ref.precinctWidthExp, 0, precinctWidthExp, 0, MAX_RESOLUTIONS);
            System.arraycopy(ref.precinctHeightExp, 0, precinctHeightExp, 0, MAX_RESOLUTIONS);
        }

        /** Copies the QCD-level fields (opj_j2k_copy_tile_quantization_parameters). */
        void copyQuantizationFrom(ComponentCoding ref) {
            quantStyle = ref.quantStyle;
            guardBits = ref.guardBits;
            System.arraycopy(ref.stepExponent, 0, stepExponent, 0, MAX_BANDS);
            System.arraycopy(ref.stepMantissa, 0, stepMantissa, 0, MAX_BANDS);
        }
    }

    /** Tile coding parameters (opj_tcp_t). */
    public static final class TileCoding implements Cloneable {
        /** Scod. */
        public int csty;
        public int progression;
        public int numLayers;
        /** Multiple component transform: 0 none, 1 RCT/ICT on components 0-2. */
        public int mct;
        public List<Poc> pocs = new ArrayList<>();
        public ComponentCoding[] components;

        TileCoding(int numComponents) {
            components = new ComponentCoding[numComponents];
            for (int i = 0; i < numComponents; i++) components[i] = new ComponentCoding();
        }

        @Override
        public TileCoding clone() {
            try {
                TileCoding t = (TileCoding) super.clone();
                t.pocs = new ArrayList<>(pocs);
                t.components = components.clone();
                for (int i = 0; i < components.length; i++) t.components[i] = components[i].clone();
                return t;
            } catch (CloneNotSupportedException e) {
                throw new AssertionError(e);
            }
        }
    }

    /** One tile-part: where its packet data is in the source array. */
    public static final class TilePart {
        public final int index;
        /** TNsot as written (0 = not given). Informational only: never trusted for termination. */
        public final int declaredCount;
        public final int dataOffset;
        public final int dataLength;
        /** This tile-part's packet headers from the main-header PPM, or null. */
        public byte[] ppmHeaders;

        TilePart(int index, int declaredCount, int dataOffset, int dataLength) {
            this.index = index;
            this.declaredCount = declaredCount;
            this.dataOffset = dataOffset;
            this.dataLength = dataLength;
        }
    }

    /** A tile: its coding parameters and its tile-parts in codestream order. */
    public static final class Tile {
        public final int index;
        public final TileCoding coding;
        public final List<TilePart> parts = new ArrayList<>();
        /** PPT segments by Zppt (null entries = not present); merged by {@link #packedHeaders()}. */
        final List<byte[]> ppt = new ArrayList<>();
        /** True once a tile-part header has given this tile its own POC (it then replaces the main header's). */
        boolean tilePocRead;

        Tile(int index, TileCoding coding) {
            this.index = index;
            this.coding = coding;
        }

        /** Total packet data length over all tile-parts. */
        public int dataLength() {
            int n = 0;
            for (TilePart p : parts) n += p.dataLength;
            return n;
        }

        /**
         * The tile's packed packet headers (PPT segments in Zppt order, or its tile-parts' PPM
         * data in order), or null when the headers are in the packet data itself.
         */
        public byte[] packedHeaders() {
            List<byte[]> chunks = new ArrayList<>();
            for (byte[] b : ppt) if (b != null) chunks.add(b);
            if (chunks.isEmpty()) {
                for (TilePart p : parts) if (p.ppmHeaders != null) chunks.add(p.ppmHeaders);
                if (chunks.isEmpty()) return null;
            }
            int n = 0;
            for (byte[] b : chunks) n += b.length;
            byte[] out = new byte[n];
            int o = 0;
            for (byte[] b : chunks) {
                System.arraycopy(b, 0, out, o, b.length);
                o += b.length;
            }
            return out;
        }
    }

    // SIZ
    public final int rsiz;
    /** Reference grid: image area [x0, x1) x [y0, y1). */
    public final int x0, y0, x1, y1;
    /** Tile size and tile grid origin. */
    public final int tileWidth, tileHeight, tileX0, tileY0;
    /** Tiles across and down. */
    public final int tilesX, tilesY;
    public final Component[] components;

    final byte[] data;
    final Tile[] tiles;
    final TileCoding defaults;
    final List<String> comments = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();
    /** Capabilities from CAP (Pcap), 0 when absent. */
    int capabilities;

    Codestream(byte[] data, int rsiz, int x0, int y0, int x1, int y1, int tileWidth, int tileHeight,
               int tileX0, int tileY0, Component[] components) {
        this.data = data;
        this.rsiz = rsiz;
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
        this.tileWidth = tileWidth;
        this.tileHeight = tileHeight;
        this.tileX0 = tileX0;
        this.tileY0 = tileY0;
        this.components = components;
        this.tilesX = (int) ceilDiv((long) x1 - tileX0, tileWidth);
        this.tilesY = (int) ceilDiv((long) y1 - tileY0, tileHeight);
        this.tiles = new Tile[tilesX * tilesY];
        this.defaults = new TileCoding(components.length);
        for (int i = 0; i < components.length; i++) {
            if (!components[i].signed) {
                defaults.components[i].dcLevelShift = 1 << (components[i].precision - 1);
            }
        }
    }

    static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }

    public int width() {
        return x1 - x0;
    }

    public int height() {
        return y1 - y0;
    }

    public int numTiles() {
        return tiles.length;
    }

    /** The tile, or null when the codestream has no tile-part for it. */
    public Tile tile(int index) {
        return tiles[index];
    }

    /** Coding parameters of a tile: its own if it has tile-parts, else the main-header defaults. */
    public TileCoding coding(int tile) {
        return tiles[tile] != null ? tiles[tile].coding : defaults;
    }

    /** The source array that tile-part offsets refer to. */
    public byte[] data() {
        return data;
    }

    public List<String> comments() {
        return Collections.unmodifiableList(comments);
    }

    /** Recoverable problems found while parsing (truncation, out-of-spec values that were clamped). */
    public List<String> warnings() {
        return Collections.unmodifiableList(warnings);
    }

    public int capabilities() {
        return capabilities;
    }
}
