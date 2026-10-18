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
 * Provenance: ADAPTED from OpenJPEG src/lib/openjp2/mqc.c, mqc_inl.h @ 6c4a29b0 (BSD-2):
 *   mqc_states, opj_mqc_init_dec, opj_mqc_raw_init_dec, opj_mqc_resetstates, opj_mqc_setstate,
 *   opj_mqc_setcurctx, opj_mqc_decode_macro (+ lps/mps exchange, renormd), opj_mqc_bytein_macro,
 *   opj_mqc_raw_decode. Decoder half only; the encoder isn't ported.
 *   Changed: the C writes an artificial FF FF marker past the end of the segment (and restores
 *   the two bytes afterwards); here reads at or past the end return 0xFF instead, so the input
 *   array is never modified and needs no slack. State pointers became indices into four tables.
 *   Algorithm: ITU-T T.800 Annex C.3.
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

/**
 * MQ arithmetic decoder (T.800 C.3) and the raw "bypass" bit decoder used by selective
 * arithmetic coding bypass (T.800 D.6).
 * <p>
 * The decoding methods keep their hot state in fields; the tier-1 decoder calls them in tight
 * loops, so this class trades encapsulation for speed: fields are package-private.
 */
public final class MqDecoder {

    /** Number of contexts used by tier-1 (T.800 Table D.7). */
    public static final int NUM_CONTEXTS = 19;

    // mqc_states: 47 probability states, each in an MPS=0 and an MPS=1 variant.
    // QE, MPS, and the next state indices after an MPS or an LPS.
    static final int[] QE = new int[94];
    static final int[] MPS = new int[94];
    static final int[] NMPS = new int[94];
    static final int[] NLPS = new int[94];

    static {
        // {qeval, mps, nmps, nlps}, transcribed from OpenJPEG mqc_states[47 * 2]
        int[] t = {
                0x5601, 0, 2, 3, 0x5601, 1, 3, 2,
                0x3401, 0, 4, 12, 0x3401, 1, 5, 13,
                0x1801, 0, 6, 18, 0x1801, 1, 7, 19,
                0x0ac1, 0, 8, 24, 0x0ac1, 1, 9, 25,
                0x0521, 0, 10, 58, 0x0521, 1, 11, 59,
                0x0221, 0, 76, 66, 0x0221, 1, 77, 67,
                0x5601, 0, 14, 13, 0x5601, 1, 15, 12,
                0x5401, 0, 16, 28, 0x5401, 1, 17, 29,
                0x4801, 0, 18, 28, 0x4801, 1, 19, 29,
                0x3801, 0, 20, 28, 0x3801, 1, 21, 29,
                0x3001, 0, 22, 34, 0x3001, 1, 23, 35,
                0x2401, 0, 24, 36, 0x2401, 1, 25, 37,
                0x1c01, 0, 26, 40, 0x1c01, 1, 27, 41,
                0x1601, 0, 58, 42, 0x1601, 1, 59, 43,
                0x5601, 0, 30, 29, 0x5601, 1, 31, 28,
                0x5401, 0, 32, 28, 0x5401, 1, 33, 29,
                0x5101, 0, 34, 30, 0x5101, 1, 35, 31,
                0x4801, 0, 36, 32, 0x4801, 1, 37, 33,
                0x3801, 0, 38, 34, 0x3801, 1, 39, 35,
                0x3401, 0, 40, 36, 0x3401, 1, 41, 37,
                0x3001, 0, 42, 38, 0x3001, 1, 43, 39,
                0x2801, 0, 44, 38, 0x2801, 1, 45, 39,
                0x2401, 0, 46, 40, 0x2401, 1, 47, 41,
                0x2201, 0, 48, 42, 0x2201, 1, 49, 43,
                0x1c01, 0, 50, 44, 0x1c01, 1, 51, 45,
                0x1801, 0, 52, 46, 0x1801, 1, 53, 47,
                0x1601, 0, 54, 48, 0x1601, 1, 55, 49,
                0x1401, 0, 56, 50, 0x1401, 1, 57, 51,
                0x1201, 0, 58, 52, 0x1201, 1, 59, 53,
                0x1101, 0, 60, 54, 0x1101, 1, 61, 55,
                0x0ac1, 0, 62, 56, 0x0ac1, 1, 63, 57,
                0x09c1, 0, 64, 58, 0x09c1, 1, 65, 59,
                0x08a1, 0, 66, 60, 0x08a1, 1, 67, 61,
                0x0521, 0, 68, 62, 0x0521, 1, 69, 63,
                0x0441, 0, 70, 64, 0x0441, 1, 71, 65,
                0x02a1, 0, 72, 66, 0x02a1, 1, 73, 67,
                0x0221, 0, 74, 68, 0x0221, 1, 75, 69,
                0x0141, 0, 76, 70, 0x0141, 1, 77, 71,
                0x0111, 0, 78, 72, 0x0111, 1, 79, 73,
                0x0085, 0, 80, 74, 0x0085, 1, 81, 75,
                0x0049, 0, 82, 76, 0x0049, 1, 83, 77,
                0x0025, 0, 84, 78, 0x0025, 1, 85, 79,
                0x0015, 0, 86, 80, 0x0015, 1, 87, 81,
                0x0009, 0, 88, 82, 0x0009, 1, 89, 83,
                0x0005, 0, 90, 84, 0x0005, 1, 91, 85,
                0x0001, 0, 90, 86, 0x0001, 1, 91, 87,
                0x5601, 0, 92, 92, 0x5601, 1, 93, 93,
        };
        for (int i = 0; i < 94; i++) {
            QE[i] = t[4 * i];
            MPS[i] = t[4 * i + 1];
            NMPS[i] = t[4 * i + 2];
            NLPS[i] = t[4 * i + 3];
        }
    }

    /** Context states: index into the state tables, per context. */
    final int[] ctxs = new int[NUM_CONTEXTS];
    /** Active context number. */
    int curctx;

    /** C register (code register), A register (interval), CT (bits left in the byte buffer). */
    int c;
    int a;
    int ct;
    /** Number of times a terminating 0xFF followed by a byte > 0x8F was read (end of segment). */
    int endOfByteStreamCounter;

    byte[] data;
    /** Current read position. */
    int bp;
    /** Start and end (exclusive) of the segment in {@link #data}. */
    int start;
    int end;

    /** The byte at {@code i}, or 0xFF at or past the end of the segment (the virtual FF FF marker). */
    private int byteAt(int i) {
        return i < end ? data[i] & 0xff : 0xff;
    }

    /** opj_mqc_init_dec: INITDEC, T.800 C.3.5. Also resets the current context to 0. */
    public void initDecoder(byte[] data, int offset, int length) {
        initCommon(data, offset, length);
        curctx = 0;
        endOfByteStreamCounter = 0;
        if (length == 0) {
            c = 0xff << 16;
        } else {
            c = (data[bp] & 0xff) << 16;
        }
        byteIn();
        c <<= 7;
        ct -= 7;
        a = 0x8000;
    }

    /** opj_mqc_raw_init_dec: start reading raw (bypass) bits. */
    public void initRawDecoder(byte[] data, int offset, int length) {
        initCommon(data, offset, length);
        c = 0;
        ct = 0;
    }

    private void initCommon(byte[] data, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IllegalArgumentException("segment " + offset + "+" + length + " outside " + data.length);
        }
        this.data = data;
        this.start = offset;
        this.end = offset + length;
        this.bp = offset;
    }

    /** opj_mqc_resetstates: every context to state 0 (T.800 Table D.7 initial states come from setState). */
    public void resetStates() {
        java.util.Arrays.fill(ctxs, 0);
    }

    /** opj_mqc_setstate. */
    public void setState(int ctxno, int msb, int prob) {
        ctxs[ctxno] = msb + (prob << 1);
    }

    /** opj_mqc_setcurctx. */
    public void setCurrentContext(int ctxno) {
        curctx = ctxno;
    }

    /** Bytes consumed since initialisation (for termination checks and diagnostics). */
    public int position() {
        return bp - start;
    }

    public int endOfByteStreamCount() {
        return endOfByteStreamCounter;
    }

    /** opj_mqc_bytein_macro: BYTEIN, T.800 C.3.4. */
    void byteIn() {
        int next = byteAt(bp + 1);
        if (byteAt(bp) == 0xff) {
            if (next > 0x8f) {
                c += 0xff00;
                ct = 8;
                endOfByteStreamCounter++;
            } else {
                bp++;
                c += next << 9;
                ct = 7;
            }
        } else {
            bp++;
            c += next << 8;
            ct = 8;
        }
    }

    /** opj_mqc_renormd_macro: RENORMD, T.800 C.3.3. */
    private void renormalize() {
        do {
            if (ct == 0) {
                byteIn();
            }
            a <<= 1;
            c <<= 1;
            ct--;
        } while (a < 0x8000);
    }

    /** opj_mqc_decode_macro: DECODE, T.800 C.3.2, in the active context. Returns 0 or 1. */
    public int decode() {
        int[] ctx = ctxs;
        int cx = curctx;
        int state = ctx[cx];
        int qe = QE[state];
        int d;
        a -= qe;
        // C is a 32-bit unsigned register in the C code; compare its top half unsigned.
        if ((c >>> 16) < qe) {
            // LPS exchange
            if (a < qe) {
                a = qe;
                d = MPS[state];
                ctx[cx] = NMPS[state];
            } else {
                a = qe;
                d = 1 - MPS[state];
                ctx[cx] = NLPS[state];
            }
            renormalize();
        } else {
            c -= qe << 16;
            if ((a & 0x8000) == 0) {
                // MPS exchange
                if (a < qe) {
                    d = 1 - MPS[state];
                    ctx[cx] = NLPS[state];
                } else {
                    d = MPS[state];
                    ctx[cx] = NMPS[state];
                }
                renormalize();
            } else {
                d = MPS[state];
            }
        }
        return d;
    }

    /** opj_mqc_raw_decode: one raw bit (selective arithmetic coding bypass), T.800 D.6. */
    public int rawDecode() {
        if (ct == 0) {
            // the virtual FF FF at the end guarantees this stops
            if (c == 0xff) {
                if (byteAt(bp) > 0x8f) {
                    c = 0xff;
                    ct = 8;
                } else {
                    c = byteAt(bp);
                    bp++;
                    ct = 7;
                }
            } else {
                c = byteAt(bp);
                bp++;
                ct = 8;
            }
        }
        ct--;
        return (c >>> ct) & 1;
    }
}
