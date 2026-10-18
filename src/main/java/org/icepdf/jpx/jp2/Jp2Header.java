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
 * Provenance: ADAPTED from OpenJPEG src/lib/openjp2/jp2.h @ 6c4a29b0 (BSD-2): the decoder
 *   fields of opj_jp2_t, opj_jp2_color_t, opj_jp2_pclr_t, opj_jp2_cmap_comp_t and
 *   opj_jp2_cdef_info_t, and the box type constants.
 *   Changed: every colr box is kept (OpenJPEG keeps only the first it accepts, which is
 *   still the one colour() returns); the res box (T.800 I.5.3.7, which OpenJPEG skips) is
 *   kept as SPEC-derived resolution fields; the codestream's position in the file is kept.
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
 * Copyright (c) 2010-2011, Kaori Hagihara
 * Copyright (c) 2008, 2011-2012, Centre National d'Etudes Spatiales (CNES), FR
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
/*
 * The copyright in this software is being made available under the 2-clauses
 * BSD License, included below. This software may be subject to other third
 * party and contributor rights, including patent rights, and no such rights
 * are granted under this license.
 *
 * Copyright (c) 2002-2014, Universite catholique de Louvain (UCL), Belgium
 * Copyright (c) 2002-2014, Professor Benoit Macq
 * Copyright (c) 2002-2003, Yannick Verschueren
 * Copyright (c) 2005, Herve Drolon, FreeImage Team
 * Copyright (c) 2008, 2011-2012, Centre National d'Etudes Spatiales (CNES), FR
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
package org.icepdf.jpx.jp2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What the boxes of a JP2 file say about its image, before any decoding. */
public final class Jp2Header {

    public static final int JP = 0x6a502020;   // 'jP  '
    public static final int FTYP = 0x66747970; // 'ftyp'
    public static final int JP2H = 0x6a703268; // 'jp2h'
    public static final int IHDR = 0x69686472; // 'ihdr'
    public static final int COLR = 0x636f6c72; // 'colr'
    public static final int JP2C = 0x6a703263; // 'jp2c'
    public static final int PCLR = 0x70636c72; // 'pclr'
    public static final int CMAP = 0x636d6170; // 'cmap'
    public static final int CDEF = 0x63646566; // 'cdef'
    public static final int BPCC = 0x62706363; // 'bpcc'
    // T.800 I.5.3.7
    public static final int RES = 0x72657320;  // 'res '
    public static final int RESC = 0x72657363; // 'resc'
    public static final int RESD = 0x72657364; // 'resd'

    /** cdef Typ values (T.800 Table I.16). */
    public static final int CHANNEL_COLOUR = 0, CHANNEL_OPACITY = 1, CHANNEL_PREMULTIPLIED_OPACITY = 2,
            CHANNEL_UNSPECIFIED = 65535;

    /** One colr box (T.800 I.5.3.3). */
    public static final class Colour {
        /** METH: 1 enumerated, 2 restricted ICC; other values are kept but never chosen. */
        public final int method;
        public final int precedence;
        public final int approximation;
        /** EnumCS when {@code method == 1}, else -1. */
        public final int enumeratedColourSpace;
        /** The ICC profile when {@code method == 2}, else null. */
        public final byte[] iccProfile;
        /**
         * CIELab (EnumCS 14) parameters as OpenJPEG lays them out: enumcs, 'DEF ' or 0, rl, ol,
         * ra, oa, rb, ob, il. Null for other colour spaces.
         */
        public final long[] cielab;

        Colour(int method, int precedence, int approximation, int enumeratedColourSpace, byte[] iccProfile,
               long[] cielab) {
            this.method = method;
            this.precedence = precedence;
            this.approximation = approximation;
            this.enumeratedColourSpace = enumeratedColourSpace;
            this.iccProfile = iccProfile;
            this.cielab = cielab;
        }
    }

    /** One cmap entry: the codestream component, 0 direct / 1 palette, and the palette column. */
    public static final class ComponentMapping {
        public final int component;
        public final int mappingType;
        public final int paletteColumn;

        ComponentMapping(int component, int mappingType, int paletteColumn) {
            this.component = component;
            this.mappingType = mappingType;
            this.paletteColumn = paletteColumn;
        }
    }

    /**
     * The pclr box. {@code values[entry * columns + column]}, each the uint32 OpenJPEG reads (at
     * most 4 bytes per value, so wider palette columns are misread exactly as OpenJPEG misreads them).
     */
    public static final class Palette {
        public final int entries;
        public final int columns;
        public final int[] sizes;
        public final boolean[] signed;
        public final int[] values;

        Palette(int entries, int columns, int[] sizes, boolean[] signed, int[] values) {
            this.entries = entries;
            this.columns = columns;
            this.sizes = sizes;
            this.signed = signed;
            this.values = values;
        }
    }

    /** One cdef entry: channel, type, association (0 whole image, 65535 none, else colour i). */
    public static final class ChannelDefinition {
        public final int channel;
        public final int type;
        public final int association;

        ChannelDefinition(int channel, int type, int association) {
            this.channel = channel;
            this.type = type;
            this.association = association;
        }
    }

    /** A resc or resd box: grid points per metre = num / den * 10^exp, vertical and horizontal. */
    public static final class Resolution {
        public final int verticalNum, verticalDen, horizontalNum, horizontalDen;
        public final int verticalExp, horizontalExp;

        Resolution(int vn, int vd, int hn, int hd, int ve, int he) {
            verticalNum = vn;
            verticalDen = vd;
            horizontalNum = hn;
            horizontalDen = hd;
            verticalExp = ve;
            horizontalExp = he;
        }

        public double verticalPerMetre() {
            return verticalDen == 0 ? 0 : (double) verticalNum / verticalDen * Math.pow(10, verticalExp);
        }

        public double horizontalPerMetre() {
            return horizontalDen == 0 ? 0 : (double) horizontalNum / horizontalDen * Math.pow(10, horizontalExp);
        }
    }

    // ftyp
    public long brand, minorVersion;
    public long[] compatibility = new long[0];
    public boolean hasJp2h;
    // ihdr
    public boolean hasIhdr;
    public long height, width;
    public int components;
    /** BPC: 255 means per-component depths are in bpcc. */
    public int bitsPerComponent;
    public int compressionType, unknownColourspace, intellectualProperty;
    /** bpcc, one byte per component (sign bit 0x80, depth-1 in the low 7 bits); null if absent. */
    public int[] bitsPerComponents;

    final List<Colour> colours = new ArrayList<>();
    /** Index into colours of the box OpenJPEG would use (the first with METH 1 or 2), or -1. */
    int chosenColour = -1;
    public Palette palette;
    /** The cmap box, or null. OpenJPEG ignores a pclr without one (I.5.3.4). */
    public ComponentMapping[] componentMapping;
    public ChannelDefinition[] channelDefinitions;
    public Resolution captureResolution, displayResolution;

    /** Where the codestream starts in the file, and how many bytes it has up to the end of the file. */
    public int codestreamOffset, codestreamLength;
    /** The jp2c box's own payload length, or -1 when the box ran to the end of the file. */
    public long codestreamBoxLength = -1;

    final List<String> warnings = new ArrayList<>();

    public List<Colour> colours() {
        return Collections.unmodifiableList(colours);
    }

    /** The colour specification a conforming reader uses (I.5.3.3: the first; METH > 2 ignored), or null. */
    public Colour colour() {
        return chosenColour < 0 ? null : colours.get(chosenColour);
    }

    /** EnumCS of the chosen colour box, or -1. */
    public int enumeratedColourSpace() {
        Colour c = colour();
        return c == null || c.method != 1 ? -1 : c.enumeratedColourSpace;
    }

    /** The ICC profile of the chosen colour box, or null. */
    public byte[] iccProfile() {
        Colour c = colour();
        return c == null ? null : c.iccProfile;
    }

    public List<String> warnings() {
        return Collections.unmodifiableList(warnings);
    }
}
