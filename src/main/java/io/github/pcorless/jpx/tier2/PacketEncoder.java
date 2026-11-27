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
 * Provenance: ADAPTED from OpenJPEG src/lib/openjp2/t2.c @ 6c4a29b0 (BSD-2):
 *   opj_t2_encode_packet (header and body), opj_t2_putcommacode, opj_t2_putnumpasses.
 *   Changed: one quality layer only, so every code-block is included in layer 0 with all its
 *   passes in one terminated segment, or not at all; no SOP/EPH; the empty-packet test is
 *   kept (a packet with no code-block included is a single 0 bit, as the C writes it).
 *   Algorithm: ITU-T T.800 B.9, B.10.
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
package io.github.pcorless.jpx.tier2;

import java.io.IOException;
import java.io.OutputStream;

/** Packet encoding (T.800 B.9-B.10) for a single quality layer. */
public final class PacketEncoder {

    /** One band's code-blocks inside one precinct, raster order. */
    public static final class Blocks {
        final int cw;
        final int ch;
        /** Mb of the band (zero bit-planes are counted from it). */
        final int bandBitPlanes;
        final int[] numBitPlanes;
        final int[] numPasses;
        final byte[][] data;
        final TagTree incl;
        final TagTree imsb;

        public Blocks(int cw, int ch, int bandBitPlanes, int[] numBitPlanes, int[] numPasses, byte[][] data) {
            int n = cw * ch;
            if (numBitPlanes.length < n || numPasses.length < n || data.length < n) {
                throw new IllegalArgumentException("code-block arrays shorter than " + cw + "x" + ch);
            }
            this.cw = cw;
            this.ch = ch;
            this.bandBitPlanes = bandBitPlanes;
            this.numBitPlanes = numBitPlanes;
            this.numPasses = numPasses;
            this.data = data;
            this.incl = new TagTree(cw, ch);
            this.imsb = new TagTree(cw, ch);
        }
    }

    private final PacketBitWriter bio = new PacketBitWriter();

    /**
     * opj_t2_encode_packet, layer 0 of 1: the packet header for {@code bands} (the precinct's
     * non-empty bands, in band order) into {@link #headerBytes()}; returns its length. The body
     * that follows it is {@link #writeBody}.
     */
    public int header(Blocks[] bands) {
        boolean empty = true;
        for (Blocks b : bands) {
            b.incl.reset();
            b.imsb.reset();
            int n = b.cw * b.ch;
            for (int i = 0; i < n; i++) {
                b.imsb.setValue(i, b.bandBitPlanes - b.numBitPlanes[i]);
                if (b.numPasses[i] > 0) {
                    b.incl.setValue(i, 0);
                    empty = false;
                }
            }
        }
        bio.init();
        bio.putBit(empty ? 0 : 1);
        if (!empty) {
            for (Blocks b : bands) {
                int n = b.cw * b.ch;
                for (int i = 0; i < n; i++) {
                    int passes = b.numPasses[i];
                    // first (and only) layer: inclusion from the tag tree
                    b.incl.encode(bio, i, 1);
                    if (passes == 0) continue;
                    b.imsb.encode(bio, i, 999);
                    putNumPasses(passes);
                    int len = b.data[i].length;
                    int numLenBits = 3;
                    int passBits = floorLog2(passes);
                    int increment = Math.max(0, floorLog2(Math.max(len, 1)) + 1 - (numLenBits + passBits));
                    putCommaCode(increment);
                    bio.write(len, numLenBits + increment + passBits);
                }
            }
        }
        bio.flush();
        return bio.numBytes();
    }

    public byte[] headerBytes() {
        return bio.bytes();
    }

    /** The packet body: the included code-blocks' bytes, in header order. */
    public static long bodyLength(Blocks[] bands) {
        long n = 0;
        for (Blocks b : bands) {
            for (int i = 0; i < b.cw * b.ch; i++) {
                if (b.numPasses[i] > 0) n += b.data[i].length;
            }
        }
        return n;
    }

    public static void writeBody(Blocks[] bands, OutputStream out) throws IOException {
        for (Blocks b : bands) {
            for (int i = 0; i < b.cw * b.ch; i++) {
                if (b.numPasses[i] > 0) out.write(b.data[i]);
            }
        }
    }

    private static int floorLog2(int v) {
        return 31 - Integer.numberOfLeadingZeros(v);
    }

    /** opj_t2_putcommacode. */
    private void putCommaCode(int n) {
        while (--n >= 0) {
            bio.putBit(1);
        }
        bio.putBit(0);
    }

    /** opj_t2_putnumpasses (T.800 Table B.4). */
    private void putNumPasses(int n) {
        if (n == 1) {
            bio.putBit(0);
        } else if (n == 2) {
            bio.write(2, 2);
        } else if (n <= 5) {
            bio.write(0xc | (n - 3), 4);
        } else if (n <= 36) {
            bio.write(0x1e0 | (n - 6), 9);
        } else if (n <= 164) {
            bio.write(0xff80 | (n - 37), 16);
        } else {
            throw new IllegalArgumentException(n + " coding passes");
        }
    }
}
