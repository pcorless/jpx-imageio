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
 *   opj_bio_init_enc, opj_bio_byteout, opj_bio_putbit, opj_bio_write, opj_bio_flush,
 *   opj_bio_numbytes. Encoder half.
 *   Changed: writes into a growable array, so a byte out never fails.
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

import java.util.Arrays;

/** Bit writer for packet headers: MSB first, and after a 0xFF byte the next carries only 7 bits. */
public final class PacketBitWriter {

    private byte[] out = new byte[256];
    private int bp;
    private int buf;
    private int ct;

    /** opj_bio_init_enc. */
    public void init() {
        bp = 0;
        buf = 0;
        ct = 8;
    }

    /** opj_bio_byteout. */
    private void byteOut() {
        buf = (buf << 8) & 0xffff;
        ct = buf == 0xff00 ? 7 : 8;
        if (bp == out.length) out = Arrays.copyOf(out, out.length * 2);
        out[bp++] = (byte) (buf >>> 8);
    }

    /** opj_bio_putbit. */
    public void putBit(int b) {
        if (ct == 0) {
            byteOut();
        }
        ct--;
        buf |= b << ct;
    }

    /** opj_bio_write: the low {@code n} bits of {@code v}, most significant first. */
    public void write(int v, int n) {
        for (int i = n - 1; i >= 0; i--) {
            putBit((v >>> i) & 1);
        }
    }

    /** opj_bio_flush: writes the last partial byte, and a 0 byte after it if it was 0xFF. */
    public void flush() {
        byteOut();
        if (ct == 7) {
            byteOut();
        }
    }

    /** opj_bio_numbytes. */
    public int numBytes() {
        return bp;
    }

    /** The bytes written: {@code [0, numBytes())}. */
    public byte[] bytes() {
        return out;
    }
}
