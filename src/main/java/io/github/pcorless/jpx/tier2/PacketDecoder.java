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
 * Provenance: PORTED from OpenJPEG src/lib/openjp2/t2.c @ 6c4a29b0 (BSD-2):
 *   opj_t2_decode_packets, opj_t2_decode_packet, opj_t2_skip_packet, opj_t2_read_packet_header,
 *   opj_t2_read_packet_data, opj_t2_skip_packet_data, opj_t2_init_seg, opj_t2_getcommacode,
 *   opj_t2_getnumpasses. Non-strict mode (truncated data marks code-blocks corrupted, as
 *   OpenJPEG does by default); no HT code-block branch (rejected in CodestreamReader).
 *   Changed: data chunks are (array, offset, length) references; tile-part data is read
 *   across tile-part boundaries through one concatenated view; the packet sequence comes
 *   from PacketIterator.
 *   Syntax: ITU-T T.800 B.9-B.10.
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

import io.github.pcorless.jpx.JpxException;
import io.github.pcorless.jpx.codestream.Codestream;
import io.github.pcorless.jpx.codestream.Codestream.TileCoding;
import io.github.pcorless.jpx.codestream.Markers;
import io.github.pcorless.jpx.tile.TileLayout;
import io.github.pcorless.jpx.tile.TileLayout.Band;
import io.github.pcorless.jpx.tile.TileLayout.CodeBlock;
import io.github.pcorless.jpx.tile.TileLayout.Precinct;
import io.github.pcorless.jpx.tile.TileLayout.Resolution;
import io.github.pcorless.jpx.tile.TileLayout.Segment;

/** Reads a tile's packets into its code-blocks' segment lists (tier-2 decoding). */
public final class PacketDecoder {

    private final TileLayout tile;
    private final TileCoding tcp;
    private final PacketBitReader bio = new PacketBitReader();
    /** Packet data: the tile's tile-parts back to back. */
    private final byte[] data;
    private int pos;
    private final int end;
    /** Packed headers (PPT/PPM) or null. */
    private final byte[] headers;
    private int headerPos;

    private PacketDecoder(Codestream cs, TileLayout tile) {
        this.tile = tile;
        this.tcp = tile.coding;
        Codestream.Tile t = cs.tile(tile.tileIndex);
        if (t == null) {
            data = new byte[0];
        } else if (t.parts.size() == 1) {
            data = cs.data();
        } else {
            // concatenate tile-parts so packets that span them read straight through
            byte[] all = new byte[t.dataLength()];
            int o = 0;
            for (Codestream.TilePart p : t.parts) {
                System.arraycopy(cs.data(), p.dataOffset, all, o, p.dataLength);
                o += p.dataLength;
            }
            data = all;
        }
        if (t != null && t.parts.size() == 1) {
            pos = t.parts.get(0).dataOffset;
            end = pos + t.parts.get(0).dataLength;
        } else {
            pos = 0;
            end = data.length;
        }
        headers = t == null ? null : t.packedHeaders();
    }

    /**
     * opj_t2_decode_packets: reads every packet of the tile. Packets of resolutions at or above
     * {@code tile.components[c].minimumNumResolutions} are parsed and skipped.
     */
    public static void decode(Codestream cs, TileLayout tile, PacketIterator pi) throws JpxException {
        PacketDecoder t2 = new PacketDecoder(cs, tile);
        for (int i = 0; i < pi.count(); i++) {
            if (t2.pos >= t2.end && t2.headers == null) {
                // nothing left: a truncated stream; remaining code-blocks keep what they have
                cs.warningsSink("tile " + tile.tileIndex + ": data ends after " + i + " of " + pi.count() + " packets");
                return;
            }
            int c = pi.component(i), r = pi.resolution(i);
            boolean skip = r >= tile.components[c].minimumNumResolutions;
            t2.packet(pi.layer(i), r, c, pi.precinct(i), skip);
        }
    }

    // ------------------------------------------------------------------------------------------

    /** opj_t2_decode_packet / opj_t2_skip_packet. */
    private void packet(int layer, int resno, int compno, int precno, boolean skip) throws JpxException {
        Resolution res = tile.components[compno].resolutions[resno];
        boolean present = readHeader(layer, res, compno, precno);
        if (!present) return;
        if (skip) {
            skipData(res, precno);
        } else {
            readData(res, precno);
        }
    }

    /** opj_t2_getcommacode. */
    private int commaCode() {
        int n = 0;
        while (bio.read(1) != 0) {
            ++n;
            if (n > 64) break; // corrupt header; the overrun check catches it
        }
        return n;
    }

    /** opj_t2_getnumpasses (Table B.4). */
    private int numPasses() {
        if (bio.read(1) == 0) return 1;
        if (bio.read(1) == 0) return 2;
        int n = bio.read(2);
        if (n != 3) return 3 + n;
        n = bio.read(5);
        if (n != 31) return 6 + n;
        return 37 + bio.read(7);
    }

    /** opj_t2_init_seg. */
    private static void initSeg(CodeBlock cblk, int index, int cblkStyle, boolean first) {
        Segment seg = cblk.segment(index);
        seg.length = 0;
        seg.numPasses = 0;
        seg.realNumPasses = 0;
        seg.numNewPasses = 0;
        seg.newLength = 0;
        if ((cblkStyle & Markers.CBLK_TERMALL) != 0) {
            seg.maxPasses = 1;
        } else if ((cblkStyle & Markers.CBLK_BYPASS) != 0) {
            if (first) {
                seg.maxPasses = 10;
            } else {
                int prev = cblk.segs[index - 1].maxPasses;
                seg.maxPasses = (prev == 1 || prev == 10) ? 2 : 1;
            }
        } else {
            // B.10.6: (Mb - 1) * 3 + 1 with Mb = 37
            seg.maxPasses = 109;
        }
    }

    /** opj_t2_read_packet_header; returns whether the packet has data. */
    private boolean readHeader(int layer, Resolution res, int compno, int precno) throws JpxException {
        if (layer == 0) {
            // reset the tag trees
            for (Band band : res.bands) {
                if (band.isEmpty()) continue;
                if (precno >= band.precincts.length) throw new JpxException("invalid precinct " + precno);
                Precinct prc = band.precincts[precno];
                if (prc.inclusion != null) {
                    prc.inclusion.reset();
                    prc.zeroBitPlanes.reset();
                }
                for (CodeBlock cb : prc.codeBlocks) {
                    cb.numSegs = 0;
                    cb.realNumSegs = 0;
                }
            }
        }
        // SOP markers are optional: just skip one if present
        if ((tcp.csty & Markers.CSTY_SOP) != 0) {
            if (end - pos >= 6 && (data[pos] & 0xff) == 0xff && (data[pos + 1] & 0xff) == 0x91) {
                pos += 6;
            }
        }
        byte[] hdr;
        int hdrStart, hdrEnd;
        if (headers != null) {
            hdr = headers;
            hdrStart = headerPos;
            hdrEnd = headers.length;
        } else {
            hdr = data;
            hdrStart = pos;
            hdrEnd = end;
        }
        bio.init(hdr, hdrStart, hdrEnd - hdrStart);
        int present = bio.read(1);
        if (present == 0) {
            bio.alignToByte();
            int p = hdrStart + bio.bytesRead();
            p = readEph(hdr, p, hdrEnd);
            advanceHeader(p);
            return false;
        }
        int cblkStyle = tcp.components[compno].cblkStyle;
        for (Band band : res.bands) {
            if (band.isEmpty()) continue;
            Precinct prc = band.precincts[precno];
            CodeBlock[] blocks = prc.codeBlocks;
            for (int cblkno = 0; cblkno < blocks.length; cblkno++) {
                CodeBlock cblk = blocks[cblkno];
                boolean included;
                if (cblk.numSegs == 0) {
                    included = prc.inclusion.decode(bio, cblkno, layer + 1);
                } else {
                    included = bio.read(1) != 0;
                }
                if (!included) {
                    cblk.numNewPasses = 0;
                    continue;
                }
                if (cblk.numSegs == 0) {
                    int i = 0;
                    while (!prc.zeroBitPlanes.decode(bio, cblkno, i)) {
                        ++i;
                        if (bio.overrun() || i > 74) throw new JpxException("corrupt zero bit-plane tag tree");
                    }
                    // OpenJPEG lets this wrap to a huge unsigned value, which its tier-1 then rejects
                    if (band.numBitPlanes + 1 < i) throw new JpxException("more zero bit-planes than the band has");
                    cblk.numBitPlanes = band.numBitPlanes + 1 - i;
                    cblk.numLenBits = 3;
                }
                cblk.numNewPasses = numPasses();
                cblk.numLenBits += commaCode();
                int segno = 0;
                if (cblk.numSegs == 0) {
                    initSeg(cblk, 0, cblkStyle, true);
                } else {
                    segno = cblk.numSegs - 1;
                    if (cblk.segs[segno].numPasses == cblk.segs[segno].maxPasses) {
                        ++segno;
                        initSeg(cblk, segno, cblkStyle, false);
                    }
                }
                int n = cblk.numNewPasses;
                do {
                    Segment seg = cblk.segs[segno];
                    seg.numNewPasses = Math.min(seg.maxPasses - seg.numPasses, n);
                    int bits = cblk.numLenBits + floorLog2(seg.numNewPasses);
                    if (bits > 32) throw new JpxException("invalid code-block length field of " + bits + " bits");
                    seg.newLength = bio.read(bits);
                    n -= seg.numNewPasses;
                    if (n > 0) {
                        ++segno;
                        initSeg(cblk, segno, cblkStyle, false);
                    }
                } while (n > 0);
            }
        }
        if (!bio.alignToByte()) throw new JpxException("packet header runs past its data");
        if (bio.overrun()) throw new JpxException("packet header runs past its data");
        int p = hdrStart + bio.bytesRead();
        p = readEph(hdr, p, hdrEnd);
        if (p == hdrStart) throw new JpxException("empty packet header");
        advanceHeader(p);
        return true;
    }

    private int readEph(byte[] hdr, int p, int hdrEnd) throws JpxException {
        if ((tcp.csty & Markers.CSTY_EPH) != 0) {
            if (hdrEnd - p < 2) throw new JpxException("not enough space for the required EPH marker");
            if ((hdr[p] & 0xff) != 0xff || (hdr[p + 1] & 0xff) != 0x92) throw new JpxException("expected an EPH marker");
            p += 2;
        }
        return p;
    }

    private void advanceHeader(int p) {
        if (headers != null) {
            headerPos = p;
        } else {
            pos = p;
        }
    }

    private static int floorLog2(int a) {
        return 31 - Integer.numberOfLeadingZeros(Math.max(a, 1));
    }

    /** opj_t2_read_packet_data. */
    private void readData(Resolution res, int precno) {
        boolean partial = false;
        for (Band band : res.bands) {
            if (band.isEmpty()) continue;
            for (CodeBlock cblk : band.precincts[precno].codeBlocks) {
                if (cblk.numNewPasses == 0) continue;
                if (partial || cblk.corrupted) {
                    cblk.numChunks = 0;
                    cblk.corrupted = true;
                    continue;
                }
                int segno;
                if (cblk.numSegs == 0) {
                    segno = 0;
                    ++cblk.numSegs;
                } else {
                    segno = cblk.numSegs - 1;
                    if (cblk.segs[segno].numPasses == cblk.segs[segno].maxPasses) {
                        ++segno;
                        ++cblk.numSegs;
                    }
                }
                do {
                    Segment seg = cblk.segs[segno];
                    if ((long) pos + seg.newLength > end) {
                        // non-strict: this code-block and the rest of the packet are lost
                        partial = true;
                        cblk.corrupted = true;
                        cblk.numChunks = 0;
                        break;
                    }
                    cblk.addChunk(data, pos, seg.newLength);
                    pos += seg.newLength;
                    seg.length += seg.newLength;
                    seg.numPasses += seg.numNewPasses;
                    cblk.numNewPasses -= seg.numNewPasses;
                    seg.realNumPasses = seg.numPasses;
                    if (cblk.numNewPasses > 0) {
                        ++segno;
                        ++cblk.numSegs;
                    }
                } while (cblk.numNewPasses > 0);
                cblk.realNumSegs = cblk.numSegs;
            }
        }
        if (partial) pos = end;
    }

    /** opj_t2_skip_packet_data. */
    private void skipData(Resolution res, int precno) {
        for (Band band : res.bands) {
            if (band.isEmpty()) continue;
            for (CodeBlock cblk : band.precincts[precno].codeBlocks) {
                if (cblk.numNewPasses == 0) continue;
                int segno;
                if (cblk.numSegs == 0) {
                    segno = 0;
                    ++cblk.numSegs;
                } else {
                    segno = cblk.numSegs - 1;
                    if (cblk.segs[segno].numPasses == cblk.segs[segno].maxPasses) {
                        ++segno;
                        ++cblk.numSegs;
                    }
                }
                do {
                    Segment seg = cblk.segs[segno];
                    if ((long) pos + seg.newLength > end) {
                        pos = end;
                        return;
                    }
                    pos += seg.newLength;
                    seg.numPasses += seg.numNewPasses;
                    cblk.numNewPasses -= seg.numNewPasses;
                    if (cblk.numNewPasses > 0) {
                        ++segno;
                        ++cblk.numSegs;
                    }
                } while (cblk.numNewPasses > 0);
            }
        }
    }
}
