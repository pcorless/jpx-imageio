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
 * Provenance: PORTED from OpenJPEG src/lib/openjp2/mct.c @ 6c4a29b0 (BSD-2):
 *   opj_mct_decode (scalar), opj_mct_decode_real (scalar; same constants and operation order
 *   as its SSE path).
 *   Algorithm: ITU-T T.800 G.2 (RCT) and G.3 (ICT).
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

/** Inverse multiple component transforms on the first three components, in place. */
public final class InverseMct {

    private InverseMct() {
    }

    /** opj_mct_decode: inverse reversible component transform (RCT). */
    public static void decodeReversible(int[] c0, int[] c1, int[] c2, int n) {
        decodeReversible(c0, c1, c2, 0, n);
    }

    /** decodeReversible over samples [from, to). */
    public static void decodeReversible(int[] c0, int[] c1, int[] c2, int from, int to) {
        for (int i = from; i < to; ++i) {
            int y = c0[i];
            int u = c1[i];
            int v = c2[i];
            int g = y - ((u + v) >> 2);
            int r = v + g;
            int b = u + g;
            c0[i] = r;
            c1[i] = g;
            c2[i] = b;
        }
    }

    /** opj_mct_decode_real: inverse irreversible component transform (ICT). */
    public static void decodeIrreversible(float[] c0, float[] c1, float[] c2, int n) {
        for (int i = 0; i < n; ++i) {
            float y = c0[i];
            float u = c1[i];
            float v = c2[i];
            float r = y + (v * 1.402f);
            float g = y - (u * 0.34413f) - (v * (0.71414f));
            float b = y + (u * 1.772f);
            c0[i] = r;
            c1[i] = g;
            c2[i] = b;
        }
    }
}
