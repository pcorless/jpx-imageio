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
 * Provenance: PORTED from OpenJPEG src/lib/openjp2/bio.c @ 6c4a29b0 (BSD-2):
 *   opj_bio_init_dec, opj_bio_bytein, opj_bio_getbit, opj_bio_read, opj_bio_inalign,
 *   opj_bio_numbytes. Decoder half only.
 *   Changed: reading past the end yields zero bits as in the C, but also sets {@link #overrun()}
 *   so tier-2 can tell a truncated header from a valid one (the C ignores it, see its "MSD" note).
 *   Algorithm: ITU-T T.800 B.10.1 (bit-stuffing after 0xFF).
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
package io.github.pcorless.jpx.tier2;

/**
 * Bit reader for packet headers: MSB first, and after a 0xFF byte the next byte carries only 7
 * bits (T.800 B.10.1).
 */
public final class PacketBitReader {

    private byte[] data;
    private int start;
    private int end;
    private int bp;
    private int buf;
    private int ct;
    private boolean overrun;

    /** opj_bio_init_dec. */
    public void init(byte[] data, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IllegalArgumentException("header " + offset + "+" + length + " outside " + data.length);
        }
        this.data = data;
        this.start = offset;
        this.end = offset + length;
        this.bp = offset;
        this.buf = 0;
        this.ct = 0;
        this.overrun = false;
    }

    /** opj_bio_bytein. */
    private boolean byteIn() {
        buf = (buf << 8) & 0xffff;
        ct = buf == 0xff00 ? 7 : 8;
        if (bp >= end) {
            overrun = true;
            return false;
        }
        buf |= data[bp++] & 0xff;
        return true;
    }

    /** opj_bio_getbit. */
    public int readBit() {
        if (ct == 0) {
            byteIn();
        }
        ct--;
        return (buf >>> ct) & 1;
    }

    /** opj_bio_read: {@code n} bits, MSB first, 1 <= n <= 32. */
    public int read(int n) {
        if (n <= 0 || n > 32) {
            throw new IllegalArgumentException("bit count " + n);
        }
        int v = 0;
        for (int i = n - 1; i >= 0; i--) {
            v |= readBit() << i;
        }
        return v;
    }

    /** opj_bio_inalign: skip to the next byte boundary (and over a stuffed byte after 0xFF). */
    public boolean alignToByte() {
        if ((buf & 0xff) == 0xff) {
            if (!byteIn()) {
                return false;
            }
        }
        ct = 0;
        return true;
    }

    /** opj_bio_numbytes: bytes consumed since {@link #init}. */
    public int bytesRead() {
        return bp - start;
    }

    /** True once a read went past the end of the header data. */
    public boolean overrun() {
        return overrun;
    }
}
