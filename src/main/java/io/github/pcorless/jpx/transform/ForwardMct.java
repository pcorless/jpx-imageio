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
 * Provenance: ADAPTED from OpenJPEG src/lib/openjp2/mct.c @ 6c4a29b0 (BSD-2):
 *   opj_mct_encode (RCT), opj_mct_encode_real (ICT, scalar path); the DC level shift is
 *   T.800 G.1.
 *   Changed: the level shift and the colour transform are one pass from interleaved pixel
 *   samples into the component planes (as TileDecoder fuses the inverse ones), and a
 *   component without the colour transform is shifted in the same pass.
 *   Algorithm: ITU-T T.800 G.1, G.2, G.3.
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
package io.github.pcorless.jpx.transform;

/** DC level shift and forward component transforms, from interleaved samples into planes. */
public final class ForwardMct {

    private ForwardMct() {
    }

    /**
     * Level shift and opj_mct_encode (RCT) for {@code n} pixels of three interleaved samples
     * {@code px[3i..3i+2]}, into {@code c0/c1/c2[off + i]}.
     */
    public static void reversible(int[] px, int n, int shift, int[] c0, int[] c1, int[] c2, int off) {
        for (int i = 0, p = 0; i < n; i++, p += 3) {
            int r = px[p] - shift;
            int g = px[p + 1] - shift;
            int b = px[p + 2] - shift;
            c0[off + i] = (r + (g * 2) + b) >> 2;
            c1[off + i] = b - g;
            c2[off + i] = r - g;
        }
    }

    /** Level shift and opj_mct_encode_real (ICT), as {@link #reversible}. */
    public static void irreversible(int[] px, int n, int shift, float[] c0, float[] c1, float[] c2, int off) {
        for (int i = 0, p = 0; i < n; i++, p += 3) {
            float r = px[p] - shift;
            float g = px[p + 1] - shift;
            float b = px[p + 2] - shift;
            c0[off + i] = 0.299f * r + 0.587f * g + 0.114f * b;
            c1[off + i] = -0.16875f * r - 0.331260f * g + 0.5f * b;
            c2[off + i] = 0.5f * r - 0.41869f * g - 0.08131f * b;
        }
    }

    /** Level shift only: sample {@code comp} of {@code numComps} interleaved, into {@code c[off + i]}. */
    public static void shift(int[] px, int n, int numComps, int comp, int shift, int[] c, int off) {
        for (int i = 0, p = comp; i < n; i++, p += numComps) c[off + i] = px[p] - shift;
    }

    /** As {@link #shift}, into floats. */
    public static void shift(int[] px, int n, int numComps, int comp, int shift, float[] c, int off) {
        for (int i = 0, p = comp; i < n; i++, p += numComps) c[off + i] = px[p] - shift;
    }
}
