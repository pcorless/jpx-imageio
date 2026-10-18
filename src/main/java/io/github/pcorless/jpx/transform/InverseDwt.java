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
 *   5/3: opj_dwt_interleave_h, opj_dwt_interleave_v, opj_dwt_decode_1_ (the
 *   STANDARD_SLOW_VERSION reference lifting, equivalent to the optimised opj_idwt53_h/v);
 *   9/7: the lifting constants (opj_dwt_alpha/beta/gamma/delta, opj_K, the BUG_WEIRD_TWO_INVK
 *   two_invK), opj_v8dwt_interleave_h/v, opj_v8dwt_decode, opj_v8dwt_decode_step1/2 (scalar);
 *   the level loop of opj_dwt_decode_tile / opj_dwt_decode_tile_97 (H pass on every row, then
 *   V pass on every column, per level). The 9/7 V pass runs 16 columns at a time
 *   (opj_v8dwt_decode_v does 8 for its SIMD lanes), with each lane's float operations in
 *   OpenJPEG's order, so the result is unchanged.
 *   Changed: one lane instead of 8 (or SIMD) columns per call, same float operations in the
 *   same order; no threading.
 *   Algorithm: ITU-T T.800 Annex F.
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
import io.github.pcorless.jpx.tile.TileLayout.Resolution;

/** Inverse discrete wavelet transforms over a tile-component, in place. */
public final class InverseDwt {

    static final float ALPHA = -1.586134342f;
    static final float BETA = -0.052980118f;
    static final float GAMMA = 0.882911075f;
    static final float DELTA = 0.443506852f;
    static final float K = 1.230174105f;
    /** BUG_WEIRD_TWO_INVK: "historic value for 2 / opj_invK"; tile step sizes compensate. */
    static final float TWO_INV_K = 1.625732422f;

    private InverseDwt() {
    }

    private static int maxResolutionSize(Resolution[] res, int numRes) {
        int m = 0;
        for (int i = 1; i < numRes; i++) {
            m = Math.max(m, Math.max(res[i].width(), res[i].height()));
        }
        return m;
    }

    // ------------------------------------------------------------------------------------------
    // 5/3 reversible

    /** opj_dwt_decode (whole tile): {@code numRes} resolutions of {@code a}, row stride {@code w}. */
    public static void decode53(int[] a, int w, Resolution[] res, int numRes) {
        try {
            decode53(a, w, res, numRes, Jpeg2000Threads.NONE);
        } catch (JpxException e) {
            throw new IllegalStateException(e); // the passes themselves never throw
        }
    }

    /** As decode53, with each level's row strips and column blocks spread over {@code threads}. */
    public static void decode53(int[] a, int w, Resolution[] res, int numRes, Jpeg2000Threads threads)
            throws JpxException {
        if (numRes <= 1 || w == 0) return;
        int[] mem = new int[maxResolutionSize(res, numRes)];
        int rw = res[0].width(), rh = res[0].height();
        for (int r = 1; r < numRes; r++) {
            int hsn = rw, vsn = rh;
            int lw = res[r].width(), lh = res[r].height();
            rw = lw;
            rh = lh;
            int hdn = rw - hsn;
            int hcas = res[r].x0 % 2;
            int vdn = rh - vsn;
            int vcas = res[r].y0 % 2;
            if (parallel(threads, rw, rh)) {
                Parallel.forEach(threads, items(rh, ROWS_PER_ITEM), () -> {
                    int[] m = new int[lw];
                    return item -> {
                        for (int j = item * ROWS_PER_ITEM, e = Math.min(lh, j + ROWS_PER_ITEM); j < e; j++) {
                            row53(a, j * w, m, hsn, hdn, hcas, lw);
                        }
                    };
                });
                Parallel.forEach(threads, items(rw, COLUMNS_PER_ITEM), () -> {
                    int[] m = new int[lh];
                    return item -> {
                        for (int i = item * COLUMNS_PER_ITEM, e = Math.min(lw, i + COLUMNS_PER_ITEM); i < e; i++) {
                            column53(a, i, w, m, vsn, vdn, vcas, lh);
                        }
                    };
                });
                continue;
            }
            for (int j = 0; j < rh; j++) row53(a, j * w, mem, hsn, hdn, hcas, rw);
            for (int i = 0; i < rw; i++) column53(a, i, w, mem, vsn, vdn, vcas, rh);
        }
    }

    private static void row53(int[] a, int row, int[] mem, int sn, int dn, int cas, int rw) {
        interleave(a, row, 1, mem, sn, dn, cas);
        decode1(mem, dn, sn, cas);
        System.arraycopy(mem, 0, a, row, rw);
    }

    private static void column53(int[] a, int i, int w, int[] mem, int sn, int dn, int cas, int rh) {
        interleave(a, i, w, mem, sn, dn, cas);
        decode1(mem, dn, sn, cas);
        for (int k = 0; k < rh; k++) a[i + k * w] = mem[k];
    }

    /** Rows of a horizontal pass, and columns of a vertical one, per work item. */
    static final int ROWS_PER_ITEM = 16, COLUMNS_PER_ITEM = 64;
    /** Smallest level (width x height) worth spreading over threads. */
    static final int PARALLEL_MIN_AREA = 128 * 128;

    private static boolean parallel(Jpeg2000Threads threads, int rw, int rh) {
        return Parallel.worthIt(threads, (long) rw * rh >= PARALLEL_MIN_AREA);
    }

    private static int items(int n, int per) {
        return (n + per - 1) / per;
    }

    /** opj_dwt_interleave_h / _v: lows to cas + 2i, highs to 1 - cas + 2i. */
    private static void interleave(int[] a, int start, int step, int[] mem, int sn, int dn, int cas) {
        int ai = start;
        for (int i = 0, bi = cas; i < sn; i++, bi += 2, ai += step) mem[bi] = a[ai];
        for (int i = 0, bi = 1 - cas; i < dn; i++, bi += 2, ai += step) mem[bi] = a[ai];
    }

    /** opj_dwt_decode_1_: OPJ_S(i) = a[2i], OPJ_D(i) = a[2i + 1], with clamped neighbours. */
    private static void decode1(int[] a, int dn, int sn, int cas) {
        if (cas == 0) {
            if (dn > 0 || sn > 1) {
                for (int i = 0; i < sn; i++) {
                    a[2 * i] -= (dClamp(a, i - 1, dn) + dClamp(a, i, dn) + 2) >> 2;
                }
                for (int i = 0; i < dn; i++) {
                    a[2 * i + 1] += (sClamp(a, i, sn) + sClamp(a, i + 1, sn)) >> 1;
                }
            }
        } else {
            if (sn == 0 && dn == 1) {
                a[0] /= 2;
            } else {
                // OPJ_SS_ clamps S by dn, OPJ_DD_ clamps D by sn
                for (int i = 0; i < sn; i++) {
                    a[2 * i + 1] -= (sClamp(a, i, dn) + sClamp(a, i + 1, dn) + 2) >> 2;
                }
                for (int i = 0; i < dn; i++) {
                    a[2 * i] += (dClamp(a, i, sn) + dClamp(a, i - 1, sn)) >> 1;
                }
            }
        }
    }

    private static int sClamp(int[] a, int i, int n) {
        return i < 0 ? a[0] : i >= n ? a[2 * (n - 1)] : a[2 * i];
    }

    private static int dClamp(int[] a, int i, int n) {
        return i < 0 ? a[1] : i >= n ? a[1 + 2 * (n - 1)] : a[1 + 2 * i];
    }

    // ------------------------------------------------------------------------------------------
    // 9/7 irreversible

    /** opj_dwt_decode_tile_97 (whole tile), in place on float coefficients. */
    public static void decode97(float[] a, int w, Resolution[] res, int numRes) {
        try {
            decode97(a, w, res, numRes, Jpeg2000Threads.NONE);
        } catch (JpxException e) {
            throw new IllegalStateException(e); // the passes themselves never throw
        }
    }

    /** As decode97, with each level's row strips and column blocks spread over {@code threads}. */
    public static void decode97(float[] a, int w, Resolution[] res, int numRes, Jpeg2000Threads threads)
            throws JpxException {
        if (numRes <= 1 || w == 0) return;
        float[] wav = new float[maxResolutionSize(res, numRes) + 1];
        float[] lanes = new float[(maxResolutionSize(res, numRes) + 1) * LANES];
        int rw = res[0].width(), rh = res[0].height();
        for (int r = 1; r < numRes; r++) {
            int hsn = rw, vsn = rh;
            int lw = res[r].width(), lh = res[r].height();
            rw = lw;
            rh = lh;
            int hdn = rw - hsn;
            int hcas = res[r].x0 % 2;
            int vdn = rh - vsn;
            int vcas = res[r].y0 % 2;
            if (parallel(threads, rw, rh)) {
                Parallel.forEach(threads, items(rh, ROWS_PER_ITEM), () -> {
                    float[] wv = new float[lw + 1];
                    return item -> {
                        for (int j = item * ROWS_PER_ITEM, e = Math.min(lh, j + ROWS_PER_ITEM); j < e; j++) {
                            row97(a, j * w, wv, hsn, hdn, hcas, lw);
                        }
                    };
                });
                Parallel.forEach(threads, items(rw, COLUMNS_PER_ITEM), () -> {
                    float[] ln = new float[(lh + 1) * LANES];
                    return item -> {
                        for (int i = item * COLUMNS_PER_ITEM, e = Math.min(lw, i + COLUMNS_PER_ITEM); i < e; i += LANES) {
                            columns97(a, i, w, ln, vsn, vdn, vcas, Math.min(LANES, e - i), lh);
                        }
                    };
                });
                continue;
            }
            for (int j = 0; j < rh; j++) row97(a, j * w, wav, hsn, hdn, hcas, rw);
            // opj_v8dwt: LANES columns at a time, so each row is read and written contiguously
            for (int i = 0; i < rw; i += LANES) columns97(a, i, w, lanes, vsn, vdn, vcas, Math.min(LANES, rw - i), rh);
        }
    }

    private static void row97(float[] a, int row, float[] wav, int sn, int dn, int cas, int rw) {
        interleave(a, row, 1, wav, sn, dn, cas);
        decode1(wav, sn, dn, cas);
        System.arraycopy(wav, 0, a, row, rw);
    }

    private static void columns97(float[] a, int i, int w, float[] lanes, int sn, int dn, int cas, int nl, int rh) {
        interleaveV(a, i, w, lanes, sn, dn, cas, nl);
        decodeLanes(lanes, sn, dn, cas, nl);
        for (int k = 0, o = i; k < rh; k++, o += w) System.arraycopy(lanes, k * LANES, a, o, nl);
    }

    /** Columns per vertical pass: opj_v8dwt's 8, doubled (no SIMD lanes to match in Java). */
    static final int LANES = 16;

    /** opj_v8dwt_interleave_v: rows of {@code nl} columns into lanes[k * LANES + lane]. */
    private static void interleaveV(float[] a, int col, int w, float[] lanes, int sn, int dn, int cas, int nl) {
        int ai = col;
        for (int i = 0, bi = cas; i < sn; i++, bi += 2, ai += w) System.arraycopy(a, ai, lanes, bi * LANES, nl);
        for (int i = 0, bi = 1 - cas; i < dn; i++, bi += 2, ai += w) System.arraycopy(a, ai, lanes, bi * LANES, nl);
    }

    /** opj_v8dwt_decode over {@code nl} lanes: the same float operations, in the same order, per lane. */
    private static void decodeLanes(float[] wav, int sn, int dn, int cas, int nl) {
        int a, b;
        if (cas == 0) {
            if (!(dn > 0 || sn > 1)) return;
            a = 0;
            b = 1;
        } else {
            if (!(sn > 0 || dn > 1)) return;
            a = 1;
            b = 0;
        }
        step1Lanes(wav, a, sn, K, nl);
        step1Lanes(wav, b, dn, TWO_INV_K, nl);
        step2Lanes(wav, b, a + 1, sn, Math.min(sn, dn - a), -DELTA, nl);
        step2Lanes(wav, a, b + 1, dn, Math.min(dn, sn - b), -GAMMA, nl);
        step2Lanes(wav, b, a + 1, sn, Math.min(sn, dn - a), -BETA, nl);
        step2Lanes(wav, a, b + 1, dn, Math.min(dn, sn - b), -ALPHA, nl);
    }

    private static void step1Lanes(float[] wav, int base, int n, float c, int nl) {
        for (int i = 0, o = base * LANES; i < n; i++, o += 2 * LANES) {
            for (int l = 0; l < nl; l++) wav[o + l] = wav[o + l] * c;
        }
    }

    private static void step2Lanes(float[] wav, int l0, int base, int end, int m, float c, int nl) {
        int imax = Math.min(end, m);
        int fl = l0 * LANES;
        int fw = base * LANES;
        for (int i = 0; i < imax; i++) {
            int t = fw - LANES;
            for (int l = 0; l < nl; l++) wav[t + l] = wav[t + l] + ((wav[fl + l] + wav[fw + l]) * c);
            fl = fw;
            fw += 2 * LANES;
        }
        if (m < end) {
            c += c;
            int t = fw - LANES;
            for (int l = 0; l < nl; l++) wav[t + l] = wav[t + l] + wav[fl + l] * c;
        }
    }

    private static void interleave(float[] a, int start, int step, float[] wav, int sn, int dn, int cas) {
        int ai = start;
        for (int i = 0, bi = cas; i < sn; i++, bi += 2, ai += step) wav[bi] = a[ai];
        for (int i = 0, bi = 1 - cas; i < dn; i++, bi += 2, ai += step) wav[bi] = a[ai];
    }

    /** opj_v8dwt_decode, one lane. */
    private static void decode1(float[] wav, int sn, int dn, int cas) {
        int a, b;
        if (cas == 0) {
            if (!(dn > 0 || sn > 1)) return;
            a = 0;
            b = 1;
        } else {
            if (!(sn > 0 || dn > 1)) return;
            a = 1;
            b = 0;
        }
        step1(wav, a, sn, K);
        step1(wav, b, dn, TWO_INV_K);
        step2(wav, b, a + 1, sn, Math.min(sn, dn - a), -DELTA);
        step2(wav, a, b + 1, dn, Math.min(dn, sn - b), -GAMMA);
        step2(wav, b, a + 1, sn, Math.min(sn, dn - a), -BETA);
        step2(wav, a, b + 1, dn, Math.min(dn, sn - b), -ALPHA);
    }

    /** opj_v8dwt_decode_step1: every other sample from {@code base}, {@code n} of them, times c. */
    private static void step1(float[] wav, int base, int n, float c) {
        for (int i = 0; i < n; i++) wav[base + 2 * i] = wav[base + 2 * i] * c;
    }

    /**
     * opj_v8dwt_decode_step2 with start = 0: updates wav[base - 1 + 2i] from its neighbours
     * wav[left] (l for i = 0, else base + 2(i - 1)) and wav[base + 2i]; past m, the right
     * neighbour is missing and the left one counts twice (symmetric extension).
     */
    private static void step2(float[] wav, int l, int base, int end, int m, float c) {
        int imax = Math.min(end, m);
        int fl = l;
        int fw = base;
        for (int i = 0; i < imax; i++) {
            wav[fw - 1] = wav[fw - 1] + ((wav[fl] + wav[fw]) * c);
            fl = fw;
            fw += 2;
        }
        if (m < end) {
            c += c;
            wav[fw - 1] = wav[fw - 1] + wav[fl] * c;
        }
    }
}
