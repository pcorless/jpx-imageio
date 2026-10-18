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
 * Provenance: PORTED from OpenJPEG src/lib/openjp2/t1.c, t1.h, t1_generate_luts.c @ 6c4a29b0 (BSD-2):
 *   the T1_* flag layout and context numbers; opj_t1_getctxno_zc, opj_t1_getctxtno_sc_or_spb_index,
 *   opj_t1_getctxno_sc, opj_t1_getctxno_mag, opj_t1_getspb, opj_t1_update_flags_macro,
 *   opj_t1_dec_sigpass_{raw,mqc} (+ step macros), opj_t1_dec_refpass_{raw,mqc} (+ step macros),
 *   opj_t1_dec_clnpass (+ step macro, check_segsym), opj_t1_allocate_buffers, opj_t1_decode_cblk,
 *   and the ROI de-shift of opj_t1_clbl_decode_processor; lookup tables built at class load by
 *   t1_init_ctxno_zc, t1_init_ctxno_sc, t1_init_spb (t1_generate_luts.c) instead of the
 *   generated t1_luts.h (CodeBlockDecoderTest checks them against t1_luts.h's CRCs).
 *   Changed: one generic loop per pass instead of the 64x64 / generic x vsc / novsc macro
 *   specialisations; code-block segments come as (offset, length, passes) into one array;
 *   the PTERM checks are not ported.
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

import io.github.pcorless.jpx.JpxException;

/**
 * Tier-1 decoding of one code-block (T.800 Annex D): the significance propagation, magnitude
 * refinement and cleanup passes over the MQ (or raw) coded segments, giving signed coefficients
 * with one extra fractional bit (the caller halves them, or multiplies by half the step size).
 * Reusable: one instance per thread, {@link #decode} per code-block.
 */
public final class CodeBlockDecoder {

    // contexts (t1.h)
    static final int CTXNO_ZC = 0;
    static final int CTXNO_SC = 9;
    static final int CTXNO_MAG = 14;
    static final int CTXNO_AGG = 17;
    static final int CTXNO_UNI = 18;

    // flags for a column of 4 samples (t1.h)
    static final int SIGMA_0 = 1, SIGMA_1 = 1 << 1, SIGMA_2 = 1 << 2, SIGMA_3 = 1 << 3, SIGMA_4 = 1 << 4,
            SIGMA_5 = 1 << 5, SIGMA_6 = 1 << 6, SIGMA_7 = 1 << 7, SIGMA_8 = 1 << 8,
            SIGMA_15 = 1 << 15, SIGMA_16 = 1 << 16, SIGMA_17 = 1 << 17;
    static final int CHI_0_I = 18, CHI_1_I = 19, CHI_2_I = 22, CHI_5_I = 31;
    static final int MU_0 = 1 << 20, PI_0 = 1 << 21, PI_1 = 1 << 24, PI_2 = 1 << 27, PI_3 = 1 << 30;
    static final int PI_ALL = PI_0 | PI_1 | PI_2 | PI_3;
    static final int SIGMA_NEIGHBOURS = SIGMA_0 | SIGMA_1 | SIGMA_2 | SIGMA_3 | SIGMA_5 | SIGMA_6 | SIGMA_7 | SIGMA_8;
    static final int SIGMA_THIS = SIGMA_4;
    static final int MU_THIS = MU_0;
    static final int PI_THIS = PI_0;

    static final int LUT_SGN_W = 1, LUT_SIG_N = 1 << 1, LUT_SGN_E = 1 << 2, LUT_SIG_W = 1 << 3,
            LUT_SGN_N = 1 << 4, LUT_SIG_E = 1 << 5, LUT_SGN_S = 1 << 6, LUT_SIG_S = 1 << 7;

    static final byte[] LUT_CTXNO_ZC = new byte[2048];
    static final byte[] LUT_CTXNO_SC = new byte[256];
    static final byte[] LUT_SPB = new byte[256];

    static {
        for (int j = 0; j < 4; ++j) {
            for (int i = 0; i < 512; ++i) {
                int orient = j == 2 ? 1 : j == 1 ? 2 : j;
                LUT_CTXNO_ZC[(orient << 9) | i] = (byte) initCtxnoZc(i, j);
            }
        }
        for (int i = 0; i < 256; i++) {
            LUT_CTXNO_SC[i] = (byte) initCtxnoSc(i);
            LUT_SPB[i] = (byte) initSpb(i);
        }
    }

    /** t1_init_ctxno_zc. */
    static int initCtxnoZc(int f, int orient) {
        int h = ((f & SIGMA_3) != 0 ? 1 : 0) + ((f & SIGMA_5) != 0 ? 1 : 0);
        int v = ((f & SIGMA_1) != 0 ? 1 : 0) + ((f & SIGMA_7) != 0 ? 1 : 0);
        int d = ((f & SIGMA_0) != 0 ? 1 : 0) + ((f & SIGMA_2) != 0 ? 1 : 0)
                + ((f & SIGMA_8) != 0 ? 1 : 0) + ((f & SIGMA_6) != 0 ? 1 : 0);
        int n = 0;
        if (orient == 3) {
            int hv = h + v;
            if (d == 0) {
                n = hv == 0 ? 0 : hv == 1 ? 1 : 2;
            } else if (d == 1) {
                n = hv == 0 ? 3 : hv == 1 ? 4 : 5;
            } else if (d == 2) {
                n = hv == 0 ? 6 : 7;
            } else {
                n = 8;
            }
        } else {
            if (orient == 2) {
                int t = h;
                h = v;
                v = t;
            }
            if (h == 0) {
                if (v == 0) {
                    n = d == 0 ? 0 : d == 1 ? 1 : 2;
                } else if (v == 1) {
                    n = 3;
                } else {
                    n = 4;
                }
            } else if (h == 1) {
                n = v == 0 ? (d == 0 ? 5 : 6) : 7;
            } else {
                n = 8;
            }
        }
        return CTXNO_ZC + n;
    }

    /** hc and vc of t1_init_ctxno_sc / t1_init_spb. */
    private static int hc(int f) {
        int pos = Math.min((((f & (LUT_SIG_E | LUT_SGN_E)) == LUT_SIG_E) ? 1 : 0)
                + (((f & (LUT_SIG_W | LUT_SGN_W)) == LUT_SIG_W) ? 1 : 0), 1);
        int neg = Math.min((((f & (LUT_SIG_E | LUT_SGN_E)) == (LUT_SIG_E | LUT_SGN_E)) ? 1 : 0)
                + (((f & (LUT_SIG_W | LUT_SGN_W)) == (LUT_SIG_W | LUT_SGN_W)) ? 1 : 0), 1);
        return pos - neg;
    }

    private static int vc(int f) {
        int pos = Math.min((((f & (LUT_SIG_N | LUT_SGN_N)) == LUT_SIG_N) ? 1 : 0)
                + (((f & (LUT_SIG_S | LUT_SGN_S)) == LUT_SIG_S) ? 1 : 0), 1);
        int neg = Math.min((((f & (LUT_SIG_N | LUT_SGN_N)) == (LUT_SIG_N | LUT_SGN_N)) ? 1 : 0)
                + (((f & (LUT_SIG_S | LUT_SGN_S)) == (LUT_SIG_S | LUT_SGN_S)) ? 1 : 0), 1);
        return pos - neg;
    }

    /** t1_init_ctxno_sc. */
    static int initCtxnoSc(int f) {
        int hc = hc(f), vc = vc(f);
        if (hc < 0) {
            hc = -hc;
            vc = -vc;
        }
        int n = 0;
        if (hc == 0) {
            n = vc == -1 ? 1 : vc == 0 ? 0 : 1;
        } else if (hc == 1) {
            n = vc == -1 ? 2 : vc == 0 ? 3 : 4;
        }
        return CTXNO_SC + n;
    }

    /** t1_init_spb. */
    static int initSpb(int f) {
        int hc = hc(f), vc = vc(f);
        if (hc == 0 && vc == 0) return 0;
        return (hc > 0 || (hc == 0 && vc > 0)) ? 0 : 1;
    }

    // ------------------------------------------------------------------------------------------

    private final MqDecoder mqc = new MqDecoder();
    private int[] data = new int[0];
    private int[] flags = new int[0];
    private int w;
    private int h;
    private int stride;
    private int zcOffset;

    /** The decoded coefficients of the last {@link #decode}, row-major, {@link #width()} wide. */
    public int[] data() {
        return data;
    }

    public int width() {
        return w;
    }

    public int height() {
        return h;
    }

    /** opj_t1_allocate_buffers. */
    private void allocate(int width, int height) {
        int size = width * height;
        if (data.length < size) data = new int[size];
        else java.util.Arrays.fill(data, 0, size, 0);
        stride = width + 2;
        int flagsHeight = (height + 3) / 4;
        int flagsSize = (flagsHeight + 2) * stride;
        if (flags.length < flagsSize) flags = new int[flagsSize];
        else java.util.Arrays.fill(flags, 0, flagsSize, 0);
        // magic value to stop any pass being interested in the border rows
        for (int x = 0; x < stride; x++) {
            flags[x] = PI_ALL;
            flags[(flagsHeight + 1) * stride + x] = PI_ALL;
        }
        if (height % 4 != 0) {
            int v = height % 4 == 1 ? PI_1 | PI_2 | PI_3 : height % 4 == 2 ? PI_2 | PI_3 : PI_3;
            for (int x = 0; x < stride; x++) flags[flagsHeight * stride + x] = v;
        }
        w = width;
        h = height;
    }

    private void resetContexts() {
        mqc.resetStates();
        mqc.setState(CTXNO_UNI, 0, 46);
        mqc.setState(CTXNO_AGG, 0, 3);
        mqc.setState(CTXNO_ZC, 0, 4);
    }

    /**
     * opj_t1_decode_cblk.
     *
     * @param buf        the code-block's data: its segments back to back
     * @param segLength  segment lengths in bytes
     * @param segPasses  coding passes in each segment
     * @param numSegs    number of segments
     * @param numBitPlanes Mb - zero bit-planes (cblk->numbps)
     * @param band       0 LL, 1 HL, 2 LH, 3 HH
     */
    public void decode(int width, int height, byte[] buf, int bufOffset, int[] segLength, int[] segPasses,
                       int numSegs, int numBitPlanes, int band, int roiShift, int cblkStyle) throws JpxException {
        if (width < 0 || height < 0 || width > 1024 || height > 1024 || width * height > 4096) {
            throw new JpxException("code-block " + width + "x" + height);
        }
        allocate(width, height);
        zcOffset = band << 9;
        int bpnoPlusOne = roiShift + numBitPlanes;
        if (bpnoPlusOne >= 31) {
            throw JpxException.unsupported("code-block with " + bpnoPlusOne + " bit-planes");
        }
        int passType = 2;
        resetContexts();
        boolean vsc = (cblkStyle & 0x08) != 0;
        int pos = bufOffset;
        for (int seg = 0; seg < numSegs; ++seg) {
            boolean raw = bpnoPlusOne <= numBitPlanes - 4 && passType < 2 && (cblkStyle & 0x01) != 0;
            int len = segLength[seg];
            if (pos + len > buf.length || len < 0) throw new JpxException("code-block segment past its data");
            if (raw) {
                mqc.initRawDecoder(buf, pos, len);
            } else {
                mqc.initDecoder(buf, pos, len);
            }
            pos += len;
            for (int pass = 0; pass < segPasses[seg] && bpnoPlusOne >= 1; ++pass) {
                switch (passType) {
                    case 0:
                        sigpass(bpnoPlusOne, vsc, raw);
                        break;
                    case 1:
                        refpass(bpnoPlusOne, raw);
                        break;
                    default:
                        clnpass(bpnoPlusOne, vsc, (cblkStyle & 0x20) != 0);
                        break;
                }
                if ((cblkStyle & 0x02) != 0 && !raw) {
                    resetContexts();
                }
                if (++passType == 3) {
                    passType = 0;
                    bpnoPlusOne--;
                }
            }
        }
        if (roiShift != 0) deShiftRoi(roiShift);
    }

    /** The ROI de-shift of opj_t1_clbl_decode_processor (T.800 H.1). */
    private void deShiftRoi(int roiShift) {
        int n = w * h;
        if (roiShift >= 31) {
            java.util.Arrays.fill(data, 0, n, 0);
            return;
        }
        int thresh = 1 << roiShift;
        for (int i = 0; i < n; i++) {
            int val = data[i];
            int mag = Math.abs(val);
            if (mag >= thresh) {
                mag >>= roiShift;
                data[i] = val < 0 ? -mag : mag;
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // context helpers

    private int zc(int f) {
        return LUT_CTXNO_ZC[zcOffset + (f & SIGMA_NEIGHBOURS)];
    }

    /** opj_t1_getctxtno_sc_or_spb_index. */
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

    private int decode(int ctx) {
        mqc.curctx = ctx;
        return mqc.decode();
    }

    /**
     * opj_t1_update_flags_macro: marks sample ci of the column at p significant with sign s,
     * updating the neighbours in the array; returns the column's own (local) flags.
     */
    private int updateFlags(int f, int p, int ci, int s, boolean vsc) {
        int[] fl = flags;
        fl[p - 1] |= SIGMA_5 << (3 * ci);
        f |= ((s << CHI_1_I) | SIGMA_4) << (3 * ci);
        fl[p + 1] |= SIGMA_3 << (3 * ci);
        if (ci == 0 && !vsc) {
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

    // ------------------------------------------------------------------------------------------
    // significance propagation pass

    /** One sample of opj_t1_dec_sigpass_step_{raw,mqc}; f is the column's flags, returned updated. */
    private int sigStep(int f, int p, int d, int ci, int oneplushalf, boolean vsc, boolean raw) {
        if ((f & ((SIGMA_THIS | PI_THIS) << (ci * 3))) == 0 && (f & (SIGMA_NEIGHBOURS << (ci * 3))) != 0) {
            if (raw) {
                if (mqc.rawDecode() != 0) {
                    int v = mqc.rawDecode();
                    data[d + ci * w] = v != 0 ? -oneplushalf : oneplushalf;
                    // the raw step updates *flagsp in place: same as updating the local copy
                    f = updateFlags(f, p, ci, v, vsc);
                }
            } else {
                if (decode(zc(f >>> (ci * 3))) != 0) {
                    int lu = scIndex(f, flags[p - 1], flags[p + 1], ci);
                    int v = decode(LUT_CTXNO_SC[lu]) ^ LUT_SPB[lu];
                    data[d + ci * w] = v != 0 ? -oneplushalf : oneplushalf;
                    f = updateFlags(f, p, ci, v, vsc);
                }
            }
            f |= PI_THIS << (ci * 3);
        }
        return f;
    }

    private void sigpass(int bpno, boolean vsc, boolean raw) {
        int one = 1 << bpno;
        int oneplushalf = one | (one >> 1);
        int p = stride + 1;
        int d = 0;
        int k;
        for (k = 0; k < (h & ~3); k += 4, d += 3 * w, p += 2) {
            for (int i = 0; i < w; ++i, ++d, ++p) {
                int f = flags[p];
                if (f != 0) {
                    f = sigStep(f, p, d, 0, oneplushalf, vsc, raw);
                    f = sigStep(f, p, d, 1, oneplushalf, false, raw);
                    f = sigStep(f, p, d, 2, oneplushalf, false, raw);
                    f = sigStep(f, p, d, 3, oneplushalf, false, raw);
                    flags[p] = f;
                }
            }
        }
        if (k < h) {
            for (int i = 0; i < w; ++i, ++d, ++p) {
                for (int j = 0; j < h - k; ++j) {
                    flags[p] = sigStep(flags[p], p, d, j, oneplushalf, vsc, raw);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // magnitude refinement pass

    private int refStep(int f, int d, int ci, int poshalf, boolean raw) {
        if ((f & ((SIGMA_THIS | PI_THIS) << (ci * 3))) == (SIGMA_THIS << (ci * 3))) {
            int v = raw ? mqc.rawDecode() : decode(mag(f >>> (ci * 3)));
            int idx = d + ci * w;
            data[idx] += (v ^ (data[idx] < 0 ? 1 : 0)) != 0 ? poshalf : -poshalf;
            f |= MU_THIS << (ci * 3);
        }
        return f;
    }

    private void refpass(int bpno, boolean raw) {
        int one = 1 << bpno;
        int poshalf = one >> 1;
        int p = stride + 1;
        int d = 0;
        int k;
        for (k = 0; k < (h & ~3); k += 4, d += 3 * w, p += 2) {
            for (int i = 0; i < w; ++i, ++d, ++p) {
                int f = flags[p];
                if (f != 0) {
                    f = refStep(f, d, 0, poshalf, raw);
                    f = refStep(f, d, 1, poshalf, raw);
                    f = refStep(f, d, 2, poshalf, raw);
                    f = refStep(f, d, 3, poshalf, raw);
                    flags[p] = f;
                }
            }
        }
        if (k < h) {
            for (int i = 0; i < w; ++i, ++d, ++p) {
                for (int j = 0; j < h - k; ++j) {
                    flags[p] = refStep(flags[p], d, j, poshalf, raw);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // cleanup pass

    /** opj_t1_dec_clnpass_step_macro. */
    private int clnStep(boolean checkFlags, boolean partial, int f, int p, int d, int ci, int oneplushalf, boolean vsc) {
        if (!checkFlags || (f & ((SIGMA_THIS | PI_THIS) << (ci * 3))) == 0) {
            if (!partial) {
                if (decode(zc(f >>> (ci * 3))) == 0) return f;
            }
            int lu = scIndex(f, flags[p - 1], flags[p + 1], ci);
            int v = decode(LUT_CTXNO_SC[lu]) ^ LUT_SPB[lu];
            data[d + ci * w] = v != 0 ? -oneplushalf : oneplushalf;
            f = updateFlags(f, p, ci, v, vsc);
        }
        return f;
    }

    @SuppressWarnings("fallthrough") // the run-length cases fall through, as in the C
    private void clnpass(int bpno, boolean vsc, boolean segsym) {
        int one = 1 << bpno;
        int oneplushalf = one | (one >> 1);
        int p = stride + 1;
        int d = 0;
        int k;
        for (k = 0; k < (h & ~3); k += 4, d += 3 * w, p += 2) {
            for (int i = 0; i < w; ++i, ++d, ++p) {
                int f = flags[p];
                if (f == 0) {
                    if (decode(CTXNO_AGG) == 0) {
                        continue;
                    }
                    mqc.curctx = CTXNO_UNI;
                    int runlen = mqc.decode();
                    runlen = (runlen << 1) | mqc.decode();
                    boolean partial = true;
                    switch (runlen) {
                        case 0:
                            f = clnStep(false, true, f, p, d, 0, oneplushalf, vsc);
                            partial = false;
                            // fall through
                        case 1:
                            f = clnStep(false, partial, f, p, d, 1, oneplushalf, false);
                            partial = false;
                            // fall through
                        case 2:
                            f = clnStep(false, partial, f, p, d, 2, oneplushalf, false);
                            partial = false;
                            // fall through
                        default:
                            f = clnStep(false, partial, f, p, d, 3, oneplushalf, false);
                            break;
                    }
                } else {
                    f = clnStep(true, false, f, p, d, 0, oneplushalf, vsc);
                    f = clnStep(true, false, f, p, d, 1, oneplushalf, false);
                    f = clnStep(true, false, f, p, d, 2, oneplushalf, false);
                    f = clnStep(true, false, f, p, d, 3, oneplushalf, false);
                }
                flags[p] = f & ~PI_ALL;
            }
        }
        if (k < h) {
            for (int i = 0; i < w; ++i, ++p, ++d) {
                for (int j = 0; j < h - k; ++j) {
                    flags[p] = clnStep(true, false, flags[p], p, d, j, oneplushalf, vsc);
                }
                flags[p] &= ~PI_ALL;
            }
        }
        if (segsym) {
            // opj_t1_dec_clnpass_check_segsym: four UNI symbols (0xA when intact), not checked
            mqc.curctx = CTXNO_UNI;
            mqc.decode();
            mqc.decode();
            mqc.decode();
            mqc.decode();
        }
    }
}
