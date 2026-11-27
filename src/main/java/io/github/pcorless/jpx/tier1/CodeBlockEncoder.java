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
 * Provenance: ADAPTED from OpenJPEG src/lib/openjp2/t1.c @ 6c4a29b0 (BSD-2):
 *   opj_t1_encode_cblk (bit-plane count, pass loop, final termination),
 *   opj_t1_enc_sigpass / _step_macro, opj_t1_enc_refpass / _step_macro,
 *   opj_t1_enc_clnpass / _step_macro, opj_to_smr / opj_smr_abs / opj_smr_sign.
 *   Changed: only code-block style 0 (MQ, one segment terminated after the last pass, no
 *   distortion bookkeeping: there is no rate control); integer magnitudes with no
 *   T1_NMSEDEC_FRACBITS (the caller quantises); natural (row-major) sample order, and the loops
 *   and flag updates laid out as CodeBlockDecoder's, whose context and sign tables it shares.
 *   Algorithm: ITU-T T.800 Annex D.
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
 * Copyright (c) 2007, Callum Lerwick <seg@haxxed.com>
 * Copyright (c) 2012, Carl Hetherington
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
package io.github.pcorless.jpx.tier1;

import static io.github.pcorless.jpx.tier1.CodeBlockDecoder.*;

/**
 * Tier-1 encoding of one code-block (T.800 Annex D), code-block style 0: significance
 * propagation, magnitude refinement and cleanup passes from the most significant bit-plane down,
 * all in one MQ segment. Reusable: one instance per thread; fill {@link #begin}'s array with the
 * quantised coefficients, then {@link #encode}.
 */
public final class CodeBlockEncoder {

    private final MqEncoder mqc = new MqEncoder();
    /** Sign-magnitude coefficients (sign in bit 31), row-major, {@code w} wide. */
    private int[] data = new int[0];
    private int[] flags = new int[0];
    private int w;
    private int h;
    private int stride;
    private int zcOffset;
    private int numBitPlanes;
    private int numPasses;

    /**
     * Starts a {@code width x height} code-block: returns the array to fill with its signed
     * coefficients, row-major and {@code width} wide (at least {@code width * height} long).
     */
    public int[] begin(int width, int height) {
        if (width <= 0 || height <= 0 || width > 1024 || height > 1024 || width * height > 4096) {
            throw new IllegalArgumentException("code-block " + width + "x" + height);
        }
        int size = width * height;
        if (data.length < size) data = new int[size];
        w = width;
        h = height;
        return data;
    }

    /** opj_t1_allocate_buffers: the flags with their border, as CodeBlockDecoder's. */
    private void allocateFlags() {
        stride = w + 2;
        int flagsHeight = (h + 3) / 4;
        int flagsSize = (flagsHeight + 2) * stride;
        if (flags.length < flagsSize) flags = new int[flagsSize];
        else java.util.Arrays.fill(flags, 0, flagsSize, 0);
        for (int x = 0; x < stride; x++) {
            flags[x] = PI_ALL;
            flags[(flagsHeight + 1) * stride + x] = PI_ALL;
        }
        if (h % 4 != 0) {
            int v = h % 4 == 1 ? PI_1 | PI_2 | PI_3 : h % 4 == 2 ? PI_2 | PI_3 : PI_3;
            for (int x = 0; x < stride; x++) flags[flagsHeight * stride + x] = v;
        }
    }

    /**
     * opj_t1_encode_cblk: codes the coefficients given to {@link #begin}. {@code band} is 0 LL,
     * 1 HL, 2 LH, 3 HH. Returns the segment length in bytes ({@link #bytes()} from offset 1);
     * 0 when every coefficient is 0 (no passes).
     */
    public int encode(int band) {
        int n = w * h;
        int max = 0;
        int[] d = data;
        for (int i = 0; i < n; i++) {
            int v = d[i];
            if (v < 0) {
                int m = v == Integer.MIN_VALUE ? Integer.MAX_VALUE : -v;
                max = Math.max(max, m);
                d[i] = m | Integer.MIN_VALUE; // opj_to_smr
            } else {
                max = Math.max(max, v);
            }
        }
        numBitPlanes = max == 0 ? 0 : 32 - Integer.numberOfLeadingZeros(max);
        if (numBitPlanes == 0) {
            numPasses = 0;
            return 0;
        }
        allocateFlags();
        zcOffset = band << 9;
        mqc.resetStates();
        mqc.setState(CTXNO_UNI, 0, 46);
        mqc.setState(CTXNO_AGG, 0, 3);
        mqc.setState(CTXNO_ZC, 0, 4);
        mqc.init();
        int passes = 0;
        int passType = 2;
        for (int bpno = numBitPlanes - 1; bpno >= 0; passes++) {
            switch (passType) {
                case 0:
                    sigpass(bpno);
                    break;
                case 1:
                    refpass(bpno);
                    break;
                default:
                    clnpass(bpno);
                    break;
            }
            if (++passType == 3) {
                passType = 0;
                bpno--;
            }
        }
        mqc.flush();
        numPasses = passes;
        return mqc.numBytes();
    }

    /** Bit-planes coded by the last {@link #encode} (cblk->numbps). */
    public int numBitPlanes() {
        return numBitPlanes;
    }

    /** Coding passes of the last {@link #encode}: 3 per bit-plane less 2, or 0. */
    public int numPasses() {
        return numPasses;
    }

    /** The coded segment is {@code bytes()[1 .. 1 + length)}. */
    public byte[] bytes() {
        return mqc.buffer();
    }

    // ------------------------------------------------------------------------------------------

    private int zc(int f) {
        return LUT_CTXNO_ZC[zcOffset + (f & SIGMA_NEIGHBOURS)];
    }

    /** opj_t1_getctxtno_sc_or_spb_index (as CodeBlockDecoder's). */
    private static int scIndex(int fX, int pfX, int nfX, int ci) {
        int lu = (fX >>> (ci * 3)) & (SIGMA_1 | SIGMA_3 | SIGMA_5 | SIGMA_7);
        lu |= (pfX >>> (CHI_1_I + ci * 3)) & 1;
        lu |= (nfX >>> (CHI_1_I - 2 + ci * 3)) & (1 << 2);
        if (ci == 0) {
            lu |= (fX >>> (CHI_0_I - 4)) & (1 << 4);
        } else {
            lu |= (fX >>> (CHI_1_I - 4 + (ci - 1) * 3)) & (1 << 4);
        }
        lu |= (fX >>> (CHI_2_I - 6 + ci * 3)) & (1 << 6);
        return lu;
    }

    /** opj_t1_getctxno_mag. */
    private static int mag(int f) {
        int tmp = (f & SIGMA_NEIGHBOURS) != 0 ? CTXNO_MAG + 1 : CTXNO_MAG;
        return (f & MU_0) != 0 ? CTXNO_MAG + 2 : tmp;
    }

    /** opj_t1_update_flags_macro without VSC; returns the column's own flags. */
    private int updateFlags(int f, int p, int ci, int s) {
        int[] fl = flags;
        fl[p - 1] |= SIGMA_5 << (3 * ci);
        f |= ((s << CHI_1_I) | SIGMA_4) << (3 * ci);
        fl[p + 1] |= SIGMA_3 << (3 * ci);
        if (ci == 0) {
            int north = p - stride;
            fl[north] |= (s << CHI_5_I) | SIGMA_16;
            fl[north - 1] |= SIGMA_17;
            fl[north + 1] |= SIGMA_15;
        }
        if (ci == 3) {
            int south = p + stride;
            fl[south] |= (s << CHI_0_I) | SIGMA_1;
            fl[south - 1] |= SIGMA_2;
            fl[south + 1] |= SIGMA_0;
        }
        return f;
    }

    /** Codes the sign of sample ci (it just became significant) and marks it. */
    private int codeSign(int f, int p, int v, int ci) {
        int lu = scIndex(f, flags[p - 1], flags[p + 1], ci);
        int s = v >>> 31;
        mqc.encode(LUT_CTXNO_SC[lu], s ^ LUT_SPB[lu]);
        return updateFlags(f, p, ci, s);
    }

    // ------------------------------------------------------------------------------------------
    // significance propagation pass

    /** opj_t1_enc_sigpass_step_macro for one sample; f is the column's flags, returned updated. */
    private int sigStep(int f, int p, int d, int ci, int one) {
        if ((f & ((SIGMA_THIS | PI_THIS) << (ci * 3))) == 0 && (f & (SIGMA_NEIGHBOURS << (ci * 3))) != 0) {
            int v = data[d + ci * w];
            int bit = (v & one) != 0 ? 1 : 0;
            mqc.encode(zc(f >>> (ci * 3)), bit);
            if (bit != 0) {
                f = codeSign(f, p, v, ci);
            }
            f |= PI_THIS << (ci * 3);
        }
        return f;
    }

    private void sigpass(int bpno) {
        int one = 1 << bpno;
        int p = stride + 1;
        int d = 0;
        int k;
        for (k = 0; k < (h & ~3); k += 4, d += 3 * w, p += 2) {
            for (int i = 0; i < w; ++i, ++d, ++p) {
                int f = flags[p];
                if (f != 0) {
                    f = sigStep(f, p, d, 0, one);
                    f = sigStep(f, p, d, 1, one);
                    f = sigStep(f, p, d, 2, one);
                    f = sigStep(f, p, d, 3, one);
                    flags[p] = f;
                }
            }
        }
        if (k < h) {
            for (int i = 0; i < w; ++i, ++d, ++p) {
                for (int j = 0; j < h - k; ++j) {
                    flags[p] = sigStep(flags[p], p, d, j, one);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // magnitude refinement pass

    /** opj_t1_enc_refpass_step_macro. */
    private int refStep(int f, int d, int ci, int one) {
        if ((f & ((SIGMA_THIS | PI_THIS) << (ci * 3))) == (SIGMA_THIS << (ci * 3))) {
            mqc.encode(mag(f >>> (ci * 3)), (data[d + ci * w] & one) != 0 ? 1 : 0);
            f |= MU_THIS << (ci * 3);
        }
        return f;
    }

    private void refpass(int bpno) {
        int one = 1 << bpno;
        int p = stride + 1;
        int d = 0;
        int k;
        for (k = 0; k < (h & ~3); k += 4, d += 3 * w, p += 2) {
            for (int i = 0; i < w; ++i, ++d, ++p) {
                int f = flags[p];
                if (f != 0) {
                    f = refStep(f, d, 0, one);
                    f = refStep(f, d, 1, one);
                    f = refStep(f, d, 2, one);
                    f = refStep(f, d, 3, one);
                    flags[p] = f;
                }
            }
        }
        if (k < h) {
            for (int i = 0; i < w; ++i, ++d, ++p) {
                for (int j = 0; j < h - k; ++j) {
                    flags[p] = refStep(flags[p], d, j, one);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // cleanup pass

    /** opj_t1_enc_clnpass_step_macro for one sample not yet significant nor visited. */
    private int clnStep(boolean checkFlags, int f, int p, int d, int ci, int one) {
        if (!checkFlags || (f & ((SIGMA_THIS | PI_THIS) << (ci * 3))) == 0) {
            int v = data[d + ci * w];
            int bit = (v & one) != 0 ? 1 : 0;
            mqc.encode(zc(f >>> (ci * 3)), bit);
            if (bit != 0) {
                f = codeSign(f, p, v, ci);
            }
        }
        return f;
    }

    private void clnpass(int bpno) {
        int one = 1 << bpno;
        int p = stride + 1;
        int d = 0;
        int k;
        for (k = 0; k < (h & ~3); k += 4, d += 3 * w, p += 2) {
            for (int i = 0; i < w; ++i, ++d, ++p) {
                int f = flags[p];
                if (f == 0) {
                    // run-length mode: the column's first sample significant in this plane, or 4
                    int runlen = 0;
                    while (runlen < 4 && (data[d + runlen * w] & one) == 0) runlen++;
                    mqc.encode(CTXNO_AGG, runlen != 4 ? 1 : 0);
                    if (runlen == 4) {
                        continue;
                    }
                    mqc.encode(CTXNO_UNI, runlen >> 1);
                    mqc.encode(CTXNO_UNI, runlen & 1);
                    f = codeSign(f, p, data[d + runlen * w], runlen);
                    for (int ci = runlen + 1; ci < 4; ci++) {
                        f = clnStep(false, f, p, d, ci, one);
                    }
                } else {
                    f = clnStep(true, f, p, d, 0, one);
                    f = clnStep(true, f, p, d, 1, one);
                    f = clnStep(true, f, p, d, 2, one);
                    f = clnStep(true, f, p, d, 3, one);
                }
                flags[p] = f & ~PI_ALL;
            }
        }
        if (k < h) {
            for (int i = 0; i < w; ++i, ++p, ++d) {
                for (int j = 0; j < h - k; ++j) {
                    flags[p] = clnStep(true, flags[p], p, d, j, one);
                }
                flags[p] &= ~PI_ALL;
            }
        }
    }
}
