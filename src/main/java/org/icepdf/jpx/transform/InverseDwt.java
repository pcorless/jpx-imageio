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
 *   V pass on every column, per level).
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
package org.icepdf.jpx.transform;

import org.icepdf.jpx.tile.TileLayout.Resolution;

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
        if (numRes <= 1 || w == 0) return;
        int[] mem = new int[maxResolutionSize(res, numRes)];
        int rw = res[0].width(), rh = res[0].height();
        for (int r = 1; r < numRes; r++) {
            int hsn = rw, vsn = rh;
            rw = res[r].width();
            rh = res[r].height();
            int hdn = rw - hsn;
            int hcas = res[r].x0 % 2;
            for (int j = 0; j < rh; j++) {
                int row = j * w;
                interleave(a, row, 1, mem, hsn, hdn, hcas);
                decode1(mem, hdn, hsn, hcas);
                System.arraycopy(mem, 0, a, row, rw);
            }
            int vdn = rh - vsn;
            int vcas = res[r].y0 % 2;
            for (int i = 0; i < rw; i++) {
                interleave(a, i, w, mem, vsn, vdn, vcas);
                decode1(mem, vdn, vsn, vcas);
                for (int k = 0; k < rh; k++) a[i + k * w] = mem[k];
            }
        }
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
        if (numRes <= 1 || w == 0) return;
        float[] wav = new float[maxResolutionSize(res, numRes) + 1];
        int rw = res[0].width(), rh = res[0].height();
        for (int r = 1; r < numRes; r++) {
            int hsn = rw, vsn = rh;
            rw = res[r].width();
            rh = res[r].height();
            int hdn = rw - hsn;
            int hcas = res[r].x0 % 2;
            for (int j = 0; j < rh; j++) {
                int row = j * w;
                interleave(a, row, 1, wav, hsn, hdn, hcas);
                decode1(wav, hsn, hdn, hcas);
                System.arraycopy(wav, 0, a, row, rw);
            }
            int vdn = rh - vsn;
            int vcas = res[r].y0 % 2;
            for (int i = 0; i < rw; i++) {
                interleave(a, i, w, wav, vsn, vdn, vcas);
                decode1(wav, vsn, vdn, vcas);
                for (int k = 0; k < rh; k++) a[i + k * w] = wav[k];
            }
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
