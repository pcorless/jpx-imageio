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
 * Provenance: PORTED from OpenJPEG src/lib/openjp2/jp2.c @ 6c4a29b0 (BSD-2):
 *   opj_jp2_apply_color_postprocessing, opj_jp2_check_color, opj_jp2_apply_pclr,
 *   opj_jp2_apply_cdef.
 *   Changed: works on copies (the header's cmap and cdef are not modified); the cdef type
 *   lands in Component.channelType rather than opj_image_comp_t.alpha, and travels with its
 *   channel when channels are reordered (D-16); signed palette values are sign-extended from
 *   their declared depth (D-17).
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
package io.github.pcorless.jpx.jp2;

import io.github.pcorless.jpx.Jpeg2000Decoder.Component;
import io.github.pcorless.jpx.JpxException;

import java.util.List;

/** Turns decoded codestream components into JP2 channels: palette lookup, then channel definitions. */
public final class Jp2Channels {

    private Jp2Channels() {
    }

    /** opj_jp2_apply_color_postprocessing (with ignore_pclr_cmap_cdef false). */
    public static Component[] apply(Jp2Header h, Component[] comps, List<String> warnings) throws JpxException {
        Jp2Header.ComponentMapping[] cmap = h.palette != null ? copy(h.componentMapping) : null;
        check(h, comps, cmap, warnings);
        if (cmap != null) { // I.5.3.4: either both or none
            comps = applyPalette(h.palette, cmap, comps);
        }
        if (h.channelDefinitions != null) {
            comps = applyChannelDefinitions(h.channelDefinitions, comps, new int[comps.length], warnings);
        }
        return comps;
    }

    private static Jp2Header.ComponentMapping[] copy(Jp2Header.ComponentMapping[] m) {
        return m == null ? null : m.clone();
    }

    /** opj_jp2_check_color. May rewrite {@code cmap} (issue 235/447). */
    static void check(Jp2Header h, Component[] comps, Jp2Header.ComponentMapping[] cmap, List<String> warnings)
            throws JpxException {
        int numcomps = comps.length;
        /* testcase 4149.pdf.SIGSEGV.cf7.3501 */
        if (h.channelDefinitions != null) {
            Jp2Header.ChannelDefinition[] info = h.channelDefinitions;
            int n = info.length;
            int channels = numcomps;
            /* cdef applies to cmap channels if any */
            if (cmap != null) channels = h.palette.columns;
            for (Jp2Header.ChannelDefinition d : info) {
                if (d.channel >= channels) {
                    throw new JpxException("Invalid component index " + d.channel + " (>= " + channels + ").");
                }
                if (d.association == 65535) continue;
                if (d.association > 0 && d.association - 1 >= channels) {
                    throw new JpxException("Invalid component index " + (d.association - 1) + " (>= " + channels + ").");
                }
            }
            /* issue 397: if cdef is present, it shall contain a complete list of channel definitions. */
            while (channels > 0) {
                int i;
                for (i = 0; i < n; i++) {
                    if (info[i].channel == channels - 1) break;
                }
                if (i == n) throw new JpxException("Incomplete channel definitions.");
                channels--;
            }
        }
        /* testcases 451.pdf.SIGSEGV.f4c.3723, 451.pdf.SIGSEGV.5b5.3723 and
           66ea31acbb0f23a2bbc91f64d69a03f5_signal_sigsegv_13937c0_7030_5725.pdf */
        if (cmap != null) {
            int channels = h.palette.columns;
            boolean sane = true;
            StringBuilder why = new StringBuilder();
            for (int i = 0; i < channels; i++) {
                if (cmap[i].component >= numcomps) {
                    why.append("Invalid component index ").append(cmap[i].component).append(" (>= ").append(numcomps)
                            .append("). ");
                    sane = false;
                }
            }
            boolean[] used = new boolean[channels];
            /* verify that no component is targeted more than once */
            for (int i = 0; i < channels; i++) {
                int mtyp = cmap[i].mappingType;
                int pcol = cmap[i].paletteColumn;
                /* See ISO 15444-1 Table I.14 - MTYPi field values */
                if (mtyp != 0 && mtyp != 1) {
                    why.append("Invalid value for cmap[").append(i).append("].mtyp = ").append(mtyp).append(". ");
                    sane = false;
                } else if (pcol >= channels) {
                    why.append("Invalid component/palette index for direct mapping ").append(pcol).append(". ");
                    sane = false;
                } else if (used[pcol] && mtyp == 1) {
                    why.append("Component ").append(pcol).append(" is mapped twice. ");
                    sane = false;
                } else if (mtyp == 0 && pcol != 0) {
                    /* I.5.3.5 PCOL: If the value of the MTYP field for this channel is 0, then
                     * the value of this field shall be 0. */
                    why.append("Direct use at #").append(i).append(" however pcol=").append(pcol).append(". ");
                    sane = false;
                } else if (mtyp == 1 && pcol != i) {
                    /* OpenJPEG implementation limitation. See assert(i == pcol); */
                    /* in opj_jp2_apply_pclr() */
                    why.append("Implementation limitation: for palette mapping, pcol[").append(i)
                            .append("] should be equal to ").append(i).append(", but is equal to ").append(pcol)
                            .append(". ");
                    sane = false;
                } else {
                    used[pcol] = true;
                }
            }
            /* verify that all components are targeted at least once */
            for (int i = 0; i < channels; i++) {
                if (!used[i] && cmap[i].mappingType != 0) {
                    why.append("Component ").append(i).append(" doesn't have a mapping. ");
                    sane = false;
                }
            }
            /* Issue 235/447 weird cmap */
            if (sane && numcomps == 1) {
                for (int i = 0; i < channels; i++) {
                    if (!used[i]) {
                        sane = false;
                        warnings.add("Component mapping seems wrong. Trying to correct.");
                        break;
                    }
                }
                if (!sane) {
                    sane = true;
                    for (int i = 0; i < channels; i++) {
                        cmap[i] = new Jp2Header.ComponentMapping(cmap[i].component, 1, i);
                    }
                }
            }
            if (!sane) throw new JpxException(why.toString().trim());
        }
    }

    /* file9.jp2 */
    /** opj_jp2_apply_pclr. */
    static Component[] applyPalette(Jp2Header.Palette pclr, Jp2Header.ComponentMapping[] cmap, Component[] old) {
        int channels = pclr.columns;
        Component[] out = new Component[channels];
        int topK = pclr.entries - 1;
        for (int i = 0; i < channels; i++) {
            /* Palette mapping: */
            Component src = old[cmap[i].component];
            int pcol = cmap[i].paletteColumn;
            int max = src.width * src.height;
            int[] dst = new int[max];
            if (cmap[i].mappingType == 0) {
                /* Direct use: */
                System.arraycopy(src.samples, 0, dst, 0, max);
            } else {
                for (int j = 0; j < max; j++) {
                    /* The index */
                    int k = src.samples[j];
                    if (k < 0) k = 0;
                    else if (k > topK) k = topK;
                    /* The colour */
                    dst[j] = pclr.values[k * channels + pcol];
                }
                int size = pclr.sizes[i];
                if (pclr.signed[i] && size < 32) { // D-17: two's complement in its declared depth
                    for (int j = 0; j < max; j++) dst[j] = (dst[j] << (32 - size)) >> (32 - size);
                }
            }
            // a direct-use channel takes the palette column's depth as well, as in OpenJPEG
            out[i] = new Component(src.width, src.height, pclr.sizes[i], pclr.signed[i], dst);
        }
        return out;
    }

    /**
     * opj_jp2_apply_cdef. {@code alpha} holds each component's channel type so far. OpenJPEG swaps
     * the type along with the component and then overwrites it with the colour's type, so an
     * opacity channel that is moved loses its flag; here each channel's type comes from its own
     * definition, by the channel's original index (D-16). The sample order is OpenJPEG's.
     */
    static Component[] applyChannelDefinitions(Jp2Header.ChannelDefinition[] defs, Component[] comps, int[] alpha,
                                               List<String> warnings) {
        int numcomps = comps.length;
        comps = comps.clone();
        int n = defs.length;
        int[] original = new int[numcomps];
        for (int c = 0; c < numcomps; c++) original[c] = c;
        int[] cnOf = new int[n];
        for (int i = 0; i < n; i++) cnOf[i] = defs[i].channel;
        for (int i = 0; i < n; i++) {
            /* WATCH: acn = asoc - 1 ! */
            int asoc = defs[i].association;
            int cn = cnOf[i];
            int typ = defs[i].type;
            if (cn >= numcomps) {
                warnings.add("opj_jp2_apply_cdef: cn=" + cn + ", numcomps=" + numcomps);
                continue;
            }
            if (asoc == 0 || asoc == 65535) {
                continue;
            }
            int acn = asoc - 1;
            if (acn >= numcomps) {
                warnings.add("opj_jp2_apply_cdef: acn=" + acn + ", numcomps=" + numcomps);
                continue;
            }
            /* Swap only if color channel */
            if (cn != acn && typ == 0) {
                Component saved = comps[cn];
                comps[cn] = comps[acn];
                comps[acn] = saved;
                int savedIndex = original[cn];
                original[cn] = original[acn];
                original[acn] = savedIndex;
                /* Swap channels in following channel definitions, don't bother with j <= i that are already processed */
                for (int j = i + 1; j < n; j++) {
                    if (cnOf[j] == cn) cnOf[j] = acn;
                    else if (cnOf[j] == acn) cnOf[j] = cn;
                    /* asoc is related to color index. Do not update. */
                }
            }
        }
        // D-16: each channel keeps the type of its own (first) definition
        boolean[] seen = new boolean[numcomps];
        for (Jp2Header.ChannelDefinition d : defs) {
            if (d.channel < numcomps && !seen[d.channel]) {
                alpha[d.channel] = d.type;
                seen[d.channel] = true;
            }
        }
        for (int c = 0; c < numcomps; c++) {
            comps[c] = comps[c].withChannelType(alpha[original[c]]);
        }
        return comps;
    }
}
