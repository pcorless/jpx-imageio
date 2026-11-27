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
 * Provenance: ADAPTED from OpenJPEG src/lib/openjp2/dwt.c @ 6c4a29b0 (BSD-2):
 *   5/3 opj_dwt_encode_and_deinterleave_h_one_row and _v (even case), 9/7
 *   opj_dwt_encode_1_real, opj_dwt_encode_step2, opj_dwt_encode_step1_combined,
 *   opj_dwt_encode_and_deinterleave_h_one_row_real / _v_real, opj_dwt_deinterleave_h/_v,
 *   the level loop of opj_dwt_encode_procedure (vertical pass, then horizontal), opj_K, opj_invK.
 *   Changed: only the even ("cas 0") case, as the writer's image, tile and resolutions all start
 *   at 0; the vertical pass takes 16 columns at a time (lanes, as InverseDwt), and both passes
 *   spread over threads as InverseDwt's do. Same operations in the same order per sample.
 *   Algorithm: ITU-T T.800 Annex F.4.
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
 * Copyright (c) 2007, Jonathan Ballard <dzonatas@dzonux.net>
 * Copyright (c) 2007, Callum Lerwick <seg@haxxed.com>
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
package io.github.pcorless.jpx.transform;

import io.github.pcorless.jpx.Jpeg2000Threads;
import io.github.pcorless.jpx.JpxException;
import io.github.pcorless.jpx.internal.Parallel;

import static io.github.pcorless.jpx.transform.InverseDwt.ALPHA;
import static io.github.pcorless.jpx.transform.InverseDwt.BETA;
import static io.github.pcorless.jpx.transform.InverseDwt.COLUMNS_PER_ITEM;
import static io.github.pcorless.jpx.transform.InverseDwt.DELTA;
import static io.github.pcorless.jpx.transform.InverseDwt.GAMMA;
import static io.github.pcorless.jpx.transform.InverseDwt.K;
import static io.github.pcorless.jpx.transform.InverseDwt.LANES;
import static io.github.pcorless.jpx.transform.InverseDwt.PARALLEL_MIN_AREA;
import static io.github.pcorless.jpx.transform.InverseDwt.ROWS_PER_ITEM;

/**
 * Forward discrete wavelet transforms over a {@code width x height} tile-component at the
 * origin, in place, row stride {@code width}: after {@code levels} decompositions each level's
 * bands sit as the decoder expects them (LL top left, HL right, LH below, HH diagonal).
 */
public final class ForwardDwt {

    static final float INV_K = (float) (1.0 / 1.230174105);

    private ForwardDwt() {
    }

    private static int items(int n, int per) {
        return (n + per - 1) / per;
    }

    private static boolean parallel(Jpeg2000Threads threads, int rw, int rh) {
        return Parallel.worthIt(threads, (long) rw * rh >= PARALLEL_MIN_AREA);
    }

    // ------------------------------------------------------------------------------------------
    // 5/3 reversible

    /** opj_dwt_encode (5/3): {@code levels} decompositions of {@code a}. */
    public static void encode53(int[] a, int width, int height, int levels, Jpeg2000Threads threads)
            throws JpxException {
        int rw = width, rh = height;
        int max = Math.max(width, height);
        for (int l = 0; l < levels; l++) {
            int lw = rw, lh = rh;
            if (parallel(threads, rw, rh)) {
                Parallel.forEach(threads, items(lw, COLUMNS_PER_ITEM), () -> {
                    int[] ln = new int[lh * LANES];
                    int[] tmp = new int[lh * LANES];
                    return item -> {
                        for (int i = item * COLUMNS_PER_ITEM, e = Math.min(lw, i + COLUMNS_PER_ITEM); i < e; i += LANES) {
                            columns53(a, i, width, ln, tmp, lh, Math.min(LANES, e - i));
                        }
                    };
                });
                Parallel.forEach(threads, items(lh, ROWS_PER_ITEM), () -> {
                    int[] x = new int[lw];
                    return item -> {
                        for (int j = item * ROWS_PER_ITEM, e = Math.min(lh, j + ROWS_PER_ITEM); j < e; j++) {
                            row53(a, j * width, x, lw);
                        }
                    };
                });
            } else {
                int[] ln = new int[max * LANES];
                int[] tmp = new int[max * LANES];
                for (int i = 0; i < rw; i += LANES) columns53(a, i, width, ln, tmp, rh, Math.min(LANES, rw - i));
                int[] x = new int[max];
                for (int j = 0; j < rh; j++) row53(a, j * width, x, rw);
            }
            rw = (rw + 1) >> 1;
            rh = (rh + 1) >> 1;
        }
    }

    /** opj_dwt_encode_and_deinterleave_h_one_row, even: lows to [0, sn), highs to [sn, n). */
    static void row53(int[] a, int off, int[] x, int n) {
        if (n <= 1) return;
        System.arraycopy(a, off, x, 0, n);
        int sn = (n + 1) >> 1, dn = n >> 1;
        int d = off + sn;
        int i;
        for (i = 0; i < sn - 1; i++) a[d + i] = x[2 * i + 1] - ((x[2 * i] + x[2 * i + 2]) >> 1);
        if ((n & 1) == 0) a[d + i] = x[2 * i + 1] - x[2 * i];
        a[off] = x[0] + ((a[d] + a[d] + 2) >> 2);
        for (i = 1; i < dn; i++) a[off + i] = x[2 * i] + ((a[d + i - 1] + a[d + i] + 2) >> 2);
        if ((n & 1) == 1) a[off + i] = x[2 * i] + ((a[d + i - 1] + a[d + i - 1] + 2) >> 2);
    }

    /** opj_dwt_encode_and_deinterleave_v over {@code nl} columns from {@code col}, height {@code n}. */
    private static void columns53(int[] a, int col, int w, int[] x, int[] out, int n, int nl) {
        if (n <= 1) return;
        for (int k = 0, o = col; k < n; k++, o += w) System.arraycopy(a, o, x, k * LANES, nl);
        int sn = (n + 1) >> 1, dn = n >> 1;
        int d = sn * LANES;
        int i;
        for (i = 0; i < sn - 1; i++) {
            int t = d + i * LANES, s0 = 2 * i * LANES, x1 = s0 + LANES, s1 = s0 + 2 * LANES;
            for (int l = 0; l < nl; l++) out[t + l] = x[x1 + l] - ((x[s0 + l] + x[s1 + l]) >> 1);
        }
        if ((n & 1) == 0) {
            int t = d + i * LANES, s0 = 2 * i * LANES, x1 = s0 + LANES;
            for (int l = 0; l < nl; l++) out[t + l] = x[x1 + l] - x[s0 + l];
        }
        for (int l = 0; l < nl; l++) out[l] = x[l] + ((out[d + l] + out[d + l] + 2) >> 2);
        for (i = 1; i < dn; i++) {
            int t = i * LANES, s = 2 * i * LANES, d0 = d + (i - 1) * LANES, d1 = d0 + LANES;
            for (int l = 0; l < nl; l++) out[t + l] = x[s + l] + ((out[d0 + l] + out[d1 + l] + 2) >> 2);
        }
        if ((n & 1) == 1) {
            int t = i * LANES, s = 2 * i * LANES, d0 = d + (i - 1) * LANES;
            for (int l = 0; l < nl; l++) out[t + l] = x[s + l] + ((out[d0 + l] + out[d0 + l] + 2) >> 2);
        }
        for (int k = 0, o = col; k < n; k++, o += w) System.arraycopy(out, k * LANES, a, o, nl);
    }

    // ------------------------------------------------------------------------------------------
    // 9/7 irreversible

    /** opj_dwt_encode_real: {@code levels} decompositions of {@code a}. */
    public static void encode97(float[] a, int width, int height, int levels, Jpeg2000Threads threads)
            throws JpxException {
        int rw = width, rh = height;
        int max = Math.max(width, height);
        for (int l = 0; l < levels; l++) {
            int lw = rw, lh = rh;
            if (parallel(threads, rw, rh)) {
                Parallel.forEach(threads, items(lw, COLUMNS_PER_ITEM), () -> {
                    float[] ln = new float[lh * LANES];
                    return item -> {
                        for (int i = item * COLUMNS_PER_ITEM, e = Math.min(lw, i + COLUMNS_PER_ITEM); i < e; i += LANES) {
                            columns97(a, i, width, ln, lh, Math.min(LANES, e - i));
                        }
                    };
                });
                Parallel.forEach(threads, items(lh, ROWS_PER_ITEM), () -> {
                    float[] x = new float[lw];
                    return item -> {
                        for (int j = item * ROWS_PER_ITEM, e = Math.min(lh, j + ROWS_PER_ITEM); j < e; j++) {
                            row97(a, j * width, x, lw);
                        }
                    };
                });
            } else {
                float[] ln = new float[max * LANES];
                for (int i = 0; i < rw; i += LANES) columns97(a, i, width, ln, rh, Math.min(LANES, rw - i));
                float[] x = new float[max];
                for (int j = 0; j < rh; j++) row97(a, j * width, x, rw);
            }
            rw = (rw + 1) >> 1;
            rh = (rh + 1) >> 1;
        }
    }

    /** opj_dwt_encode_and_deinterleave_h_one_row_real, even. */
    static void row97(float[] a, int off, float[] x, int n) {
        if (n <= 1) return;
        System.arraycopy(a, off, x, 0, n);
        encode1(x, n);
        int sn = (n + 1) >> 1;
        // opj_dwt_deinterleave_h
        for (int i = 0; i < sn; i++) a[off + i] = x[2 * i];
        for (int i = 0; i < n - sn; i++) a[off + sn + i] = x[2 * i + 1];
    }

    /** opj_dwt_encode_1_real, cas 0, on one interleaved line of {@code n >= 2} samples. */
    static void encode1(float[] w, int n) {
        int sn = (n + 1) >> 1, dn = n >> 1;
        step2(w, 0, 2, dn, Math.min(dn, sn - 1), ALPHA);
        step2(w, 1, 1, sn, Math.min(sn, dn), BETA);
        step2(w, 0, 2, dn, Math.min(dn, sn - 1), GAMMA);
        step2(w, 1, 1, sn, Math.min(sn, dn), DELTA);
        // opj_dwt_encode_step1_combined: lows times 1/K, highs times K
        for (int i = 0; i < sn; i++) w[2 * i] *= INV_K;
        for (int i = 0; i < dn; i++) w[2 * i + 1] *= K;
    }

    /**
     * opj_dwt_encode_step2: w[base - 1 + 2i] += (left + w[base + 2i]) * c for i below
     * min(end, m), left being w[l] for i = 0 and w[base + 2(i - 1)] after; past m the right
     * neighbour is missing and the left one counts twice (symmetric extension).
     */
    private static void step2(float[] w, int l, int base, int end, int m, float c) {
        int imax = Math.min(end, m);
        int fl = l;
        int fw = base;
        for (int i = 0; i < imax; i++) {
            w[fw - 1] += (w[fl] + w[fw]) * c;
            fl = fw;
            fw += 2;
        }
        if (m < end) {
            w[fw - 1] += (2 * w[fl]) * c;
        }
    }

    /** opj_dwt_encode_and_deinterleave_v_real over {@code nl} columns from {@code col}, height {@code n}. */
    private static void columns97(float[] a, int col, int w, float[] x, int n, int nl) {
        if (n <= 1) return;
        for (int k = 0, o = col; k < n; k++, o += w) System.arraycopy(a, o, x, k * LANES, nl);
        int sn = (n + 1) >> 1, dn = n >> 1;
        step2Lanes(x, 0, 2, dn, Math.min(dn, sn - 1), ALPHA, nl);
        step2Lanes(x, 1, 1, sn, Math.min(sn, dn), BETA, nl);
        step2Lanes(x, 0, 2, dn, Math.min(dn, sn - 1), GAMMA, nl);
        step2Lanes(x, 1, 1, sn, Math.min(sn, dn), DELTA, nl);
        for (int i = 0; i < sn; i++) {
            int o = 2 * i * LANES;
            for (int l = 0; l < nl; l++) x[o + l] *= INV_K;
        }
        for (int i = 0; i < dn; i++) {
            int o = (2 * i + 1) * LANES;
            for (int l = 0; l < nl; l++) x[o + l] *= K;
        }
        // opj_dwt_deinterleave_v: lows to rows [0, sn), highs to [sn, n)
        int o = col;
        for (int i = 0; i < sn; i++, o += w) System.arraycopy(x, 2 * i * LANES, a, o, nl);
        for (int i = 0; i < dn; i++, o += w) System.arraycopy(x, (2 * i + 1) * LANES, a, o, nl);
    }

    private static void step2Lanes(float[] w, int l0, int base, int end, int m, float c, int nl) {
        int imax = Math.min(end, m);
        int fl = l0 * LANES;
        int fw = base * LANES;
        for (int i = 0; i < imax; i++) {
            int t = fw - LANES;
            for (int l = 0; l < nl; l++) w[t + l] += (w[fl + l] + w[fw + l]) * c;
            fl = fw;
            fw += 2 * LANES;
        }
        if (m < end) {
            int t = fw - LANES;
            for (int l = 0; l < nl; l++) w[t + l] += (2 * w[fl + l]) * c;
        }
    }
}
