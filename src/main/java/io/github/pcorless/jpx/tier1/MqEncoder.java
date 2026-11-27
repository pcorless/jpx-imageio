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
 * Provenance: PORTED from OpenJPEG src/lib/openjp2/mqc.c, mqc_inl.h @ 6c4a29b0 (BSD-2):
 *   opj_mqc_init_enc, opj_mqc_setbits, opj_mqc_flush, opj_mqc_byteout, opj_mqc_numbytes,
 *   opj_mqc_encode_macro, opj_mqc_codemps_macro, opj_mqc_codelps_macro, opj_mqc_renorme_macro.
 *   Encoder half, MQ only (no bypass, ERTERM, restart or SEGMARK: the writer uses none of them).
 *   Changed: a growable array with the C's fake byte at index 0 instead of a pointer one before
 *   the buffer; C's unsigned 32-bit register as an int, compared unsigned in SETBITS. The state
 *   tables are MqDecoder's (the same mqc_states).
 *   Algorithm: ITU-T T.800 Annex C.2.
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
 * Copyright (c) 2008, Jerome Fimes, Communications & Systemes <jerome.fimes@c-s.fr>
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

import java.util.Arrays;

/** MQ arithmetic encoder (T.800 C.2), the counterpart of {@link MqDecoder}. Reusable: {@link #init} per segment. */
public final class MqEncoder {

    final int[] ctxs = new int[MqDecoder.NUM_CONTEXTS];
    int curctx;

    /** C register, A register, CT (bits until the next byte out). */
    private int c;
    private int a;
    private int ct;

    /** Output: {@code buf[0]} is the C's fake byte before the start, the segment starts at 1. */
    private byte[] buf = new byte[4096];
    private int bp;

    /** opj_mqc_init_enc: INITENC, T.800 C.2.8. */
    public void init() {
        curctx = 0;
        a = 0x8000;
        c = 0;
        buf[0] = 0;
        bp = 0;
        ct = 12;
    }

    /** opj_mqc_resetstates. */
    public void resetStates() {
        Arrays.fill(ctxs, 0);
    }

    /** opj_mqc_setstate. */
    public void setState(int ctxno, int msb, int prob) {
        ctxs[ctxno] = msb + (prob << 1);
    }

    /** opj_mqc_encode_macro: ENCODE, T.800 C.2.2, symbol {@code d} in context {@code cx}. */
    public void encode(int cx, int d) {
        int state = ctxs[cx];
        int qe = MqDecoder.QE[state];
        int sub = a - qe;
        int mps = (MqDecoder.MPS[state] ^ d) - 1;   // -1 when d is the MPS
        int exch = (sub - qe) >> 31;                // -1 when A - Qe < Qe
        int upper = mps ^ exch;                     // -1: code the upper sub-interval (C += Qe, A - Qe)
        a = (sub & upper) | (qe & ~upper);
        c += qe & upper;
        if (mps == 0 || (sub & 0x8000) == 0) {
            ctxs[cx] = mps != 0 ? MqDecoder.NMPS[state] : MqDecoder.NLPS[state];
            renormalize();
        }
    }

    /** opj_mqc_renorme_macro: RENORME, T.800 C.2.6. */
    private void renormalize() {
        do {
            a <<= 1;
            c <<= 1;
            ct--;
            if (ct == 0) {
                byteOut();
            }
        } while ((a & 0x8000) == 0);
    }

    /** opj_mqc_byteout: BYTEOUT with bit stuffing, T.800 C.2.7. */
    private void byteOut() {
        if (bp + 2 >= buf.length) {
            buf = Arrays.copyOf(buf, buf.length * 2);
        }
        if ((buf[bp] & 0xff) == 0xff) {
            bp++;
            buf[bp] = (byte) (c >>> 20);
            c &= 0xfffff;
            ct = 7;
        } else if ((c & 0x8000000) == 0) {
            bp++;
            buf[bp] = (byte) (c >>> 19);
            c &= 0x7ffff;
            ct = 8;
        } else {
            buf[bp]++;
            if ((buf[bp] & 0xff) == 0xff) {
                c &= 0x7ffffff;
                bp++;
                buf[bp] = (byte) (c >>> 20);
                c &= 0xfffff;
                ct = 7;
            } else {
                bp++;
                buf[bp] = (byte) (c >>> 19);
                c &= 0x7ffff;
                ct = 8;
            }
        }
    }

    /** opj_mqc_setbits. */
    private void setBits() {
        int tempc = c + a;
        c |= 0xffff;
        if (Integer.compareUnsigned(c, tempc) >= 0) {
            c -= 0x8000;
        }
    }

    /** opj_mqc_flush: FLUSH, T.800 C.2.9. The segment never ends with 0xFF. */
    public void flush() {
        setBits();
        c <<= ct;
        byteOut();
        c <<= ct;
        byteOut();
        if ((buf[bp] & 0xff) != 0xff) {
            bp++;
        }
    }

    /** opj_mqc_numbytes: bytes of the segment so far (after {@link #flush}, its length). */
    public int numBytes() {
        return bp - 1;
    }

    /** The output array; the segment is {@code [1, 1 + numBytes())}. */
    public byte[] buffer() {
        return buf;
    }
}
