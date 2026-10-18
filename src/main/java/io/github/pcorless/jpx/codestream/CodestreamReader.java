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
 * Provenance: ADAPTED from OpenJPEG src/lib/openjp2/j2k.c @ 6c4a29b0 (BSD-2):
 *   opj_j2k_read_header_procedure, opj_j2k_read_unk, opj_j2k_read_siz, opj_j2k_read_cod,
 *   opj_j2k_read_coc, opj_j2k_read_SPCod_SPCoc, opj_j2k_read_qcd, opj_j2k_read_qcc,
 *   opj_j2k_read_SQcd_SQcc, opj_j2k_read_poc, opj_j2k_read_rgn, opj_j2k_read_com,
 *   opj_j2k_read_ppm, opj_j2k_merge_ppm, opj_j2k_read_ppt, opj_j2k_merge_ppt,
 *   opj_j2k_get_sot_values, opj_j2k_read_sot, opj_j2k_read_sod, opj_j2k_read_tile_header,
 *   opj_j2k_copy_default_tcp_and_create_tcd (the validations and limits are OpenJPEG's).
 *   Changed (see docs/DECISIONS.md):
 *   - D-9: COC/QCC win over COD/QCD in the same header whatever their order (T.800 A.6);
 *     OpenJPEG lets a later COD/QCD overwrite an earlier COC/QCC.
 *   - D-10: TNsot is informational. Tile-parts are found by walking SOT/Psot to EOC, so the
 *     common one-short TNsot needs no repair (OpenJPEG errors, or corrects by look-ahead).
 *   - D-11: PPM data is split per tile-part (Nppm boundaries kept) instead of one shared
 *     buffer, so tiles can be decoded in any order.
 *   - Unknown markers are skipped by their length when that lands on a marker, else by
 *     OpenJPEG's scan for the next known marker.
 *   - Part 2 (MCT/MCC/MCO/CBD) and HTJ2K (CAP Part 15, HT code-blocks) are rejected as
 *     unsupported rather than silently mis-decoded.
 *   Syntax: ITU-T T.800 (06/2019) Annex A.
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
 * Copyright (c) 2006-2007, Parvatha Elangovan
 * Copyright (c) 2010-2011, Kaori Hagihara
 * Copyright (c) 2011-2012, Centre National d'Etudes Spatiales (CNES), France
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
package io.github.pcorless.jpx.codestream;

import io.github.pcorless.jpx.JpxException;
import io.github.pcorless.jpx.codestream.Codestream.ComponentCoding;
import io.github.pcorless.jpx.codestream.Codestream.Poc;
import io.github.pcorless.jpx.codestream.Codestream.Tile;
import io.github.pcorless.jpx.codestream.Codestream.TileCoding;
import io.github.pcorless.jpx.codestream.Codestream.TilePart;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Parses a codestream's main header and all tile-part headers; packet data is located, not read. */
public final class CodestreamReader {

    private final byte[] data;
    private final int start;
    private final int end;
    private Codestream cs;
    /** Per-component "set by COC / QCC in the header being read" (D-9). */
    private boolean[] cocSet;
    private boolean[] qccSet;
    private final TreeMap<Integer, byte[]> ppm = new TreeMap<>();
    private final List<TilePart> partsInOrder = new ArrayList<>();

    private CodestreamReader(byte[] data, int offset, int length) {
        this.data = data;
        this.start = offset;
        this.end = offset + length;
    }

    /** Parses the codestream in {@code data[offset, offset + length)}. */
    public static Codestream read(byte[] data, int offset, int length) throws JpxException {
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IllegalArgumentException("codestream " + offset + "+" + length + " outside " + data.length);
        }
        return new CodestreamReader(data, offset, length).parse();
    }

    // ------------------------------------------------------------------------------------------
    // bytes

    private int u8(int p, int limit) throws JpxException {
        if (p >= limit) throw new JpxException("marker segment too short at " + (p - start));
        return data[p] & 0xff;
    }

    private int u16(int p, int limit) throws JpxException {
        if (p + 2 > limit) throw new JpxException("marker segment too short at " + (p - start));
        return ((data[p] & 0xff) << 8) | (data[p + 1] & 0xff);
    }

    private long u32(int p, int limit) throws JpxException {
        if (p + 4 > limit) throw new JpxException("marker segment too short at " + (p - start));
        return ((long) (data[p] & 0xff) << 24) | ((data[p + 1] & 0xff) << 16)
                | ((data[p + 2] & 0xff) << 8) | (data[p + 3] & 0xff);
    }

    private int marker(int p) {
        if (p + 2 > end) return -1;
        return ((data[p] & 0xff) << 8) | (data[p + 1] & 0xff);
    }

    /** Segment length Lxxx at p (after the marker); returns the end of the segment. */
    private int segmentEnd(int p, int limit) throws JpxException {
        int len = u16(p, limit);
        if (len < 2) throw new JpxException(String.format("invalid marker segment length %d at %d", len, p - start));
        if (p + len > limit) throw new JpxException(String.format("marker segment at %d runs past the end", p - start));
        return p + len;
    }

    private void warn(String w) {
        cs.warnings.add(w);
    }

    // ------------------------------------------------------------------------------------------
    // main header (opj_j2k_read_header_procedure)

    private Codestream parse() throws JpxException {
        if (marker(start) != Markers.SOC) throw new JpxException("no SOC marker: not a JPEG 2000 codestream");
        int p = start + 2;
        if (marker(p) != Markers.SIZ) throw new JpxException("SIZ must follow SOC");
        int segEnd = segmentEnd(p + 2, end);
        readSiz(p + 4, segEnd);
        p = segEnd;
        boolean hasCod = false, hasQcd = false;
        cocSet = new boolean[cs.components.length];
        qccSet = new boolean[cs.components.length];
        for (;;) {
            int m = marker(p);
            if (m < 0) throw new JpxException("codestream ends in the main header");
            if (m == Markers.SOT) break;
            if (m < 0xFF00) throw new JpxException(String.format("expected a marker at %d, found %04x", p - start, m));
            if (m == Markers.SIZ || m == Markers.SOD || m == Markers.EOC || m == Markers.PLT || m == Markers.PPT) {
                throw new JpxException(String.format("marker %04x not allowed in the main header", m));
            }
            if (Markers.hasNoSegment(m)) {
                p += 2;
                continue;
            }
            int body = p + 4;
            segEnd = segmentEnd(p + 2, end);
            switch (m) {
                case Markers.COD:
                    readCod(cs.defaults, body, segEnd);
                    hasCod = true;
                    break;
                case Markers.COC:
                    readCoc(cs.defaults, body, segEnd);
                    break;
                case Markers.QCD:
                    readQcd(cs.defaults, body, segEnd);
                    hasQcd = true;
                    break;
                case Markers.QCC:
                    readQcc(cs.defaults, body, segEnd);
                    break;
                case Markers.POC:
                    readPoc(cs.defaults, body, segEnd);
                    break;
                case Markers.RGN:
                    readRgn(cs.defaults, body, segEnd);
                    break;
                case Markers.PPM:
                    readPpm(body, segEnd);
                    break;
                case Markers.COM:
                    readCom(body, segEnd);
                    break;
                case Markers.CAP:
                    readCap(body, segEnd);
                    break;
                case Markers.TLM:
                case Markers.PLM:
                case Markers.CRG:
                case Markers.CPF:
                    break; // not needed: tile-parts are located by Psot, CRG doesn't affect samples
                case Markers.MCT:
                case Markers.MCC:
                case Markers.MCO:
                case Markers.CBD:
                    throw JpxException.unsupported(String.format("Part 2 multiple component transform (marker %04x)", m));
                default:
                    p = skipUnknown(p, end);
                    continue;
            }
            p = segEnd;
        }
        if (!hasCod) throw new JpxException("main header has no COD marker");
        if (!hasQcd) throw new JpxException("main header has no QCD marker");
        List<byte[]> ppmChunks = splitPpm();
        readTileParts(p);
        if (ppmChunks != null) {
            if (ppmChunks.size() < partsInOrder.size()) {
                warn("PPM holds headers for " + ppmChunks.size() + " of " + partsInOrder.size() + " tile-parts");
            }
            for (int i = 0; i < partsInOrder.size() && i < ppmChunks.size(); i++) {
                partsInOrder.get(i).ppmHeaders = ppmChunks.get(i);
            }
        }
        return cs;
    }

    /** opj_j2k_read_unk: returns the position of the next marker to process. */
    private int skipUnknown(int p, int limit) throws JpxException {
        int m = marker(p);
        warn(String.format("unknown marker %04x at %d skipped", m, p - start));
        if (p + 4 <= limit) {
            int len = u16(p + 2, limit);
            int next = p + 2 + len;
            if (len >= 2 && next + 2 <= limit && marker(next) >= 0xFF00) return next;
        }
        // OpenJPEG: scan two bytes at a time for a known marker
        for (int q = p + 2; q + 2 <= limit; q += 2) {
            int c = marker(q);
            if (c >= 0xFF00 && isKnown(c)) return q;
        }
        throw new JpxException(String.format("no marker found after unknown marker %04x", m));
    }

    private static boolean isKnown(int m) {
        switch (m) {
            case Markers.SOT: case Markers.COD: case Markers.COC: case Markers.RGN: case Markers.QCD:
            case Markers.QCC: case Markers.POC: case Markers.SIZ: case Markers.TLM: case Markers.PLM:
            case Markers.PLT: case Markers.PPM: case Markers.PPT: case Markers.SOP: case Markers.CRG:
            case Markers.COM: case Markers.MCT: case Markers.CBD: case Markers.CAP: case Markers.CPF:
            case Markers.MCC: case Markers.MCO: case Markers.SOD: case Markers.EOC:
                return true;
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------------------------------
    // SIZ (opj_j2k_read_siz)

    private void readSiz(int p, int segEnd) throws JpxException {
        int size = segEnd - p;
        if (size < 36 || (size - 36) % 3 != 0) throw new JpxException("bad SIZ marker size");
        int rsiz = u16(p, segEnd);
        long x1 = u32(p + 2, segEnd), y1 = u32(p + 6, segEnd);
        long x0 = u32(p + 10, segEnd), y0 = u32(p + 14, segEnd);
        long tdx = u32(p + 18, segEnd), tdy = u32(p + 22, segEnd);
        long tx0 = u32(p + 26, segEnd), ty0 = u32(p + 30, segEnd);
        int csiz = u16(p + 34, segEnd);
        if (csiz == 0 || csiz > 16384) throw new JpxException("SIZ: illegal number of components " + csiz);
        if (csiz != (size - 36) / 3) throw new JpxException("SIZ: component count doesn't match the marker size");
        if (x0 >= x1 || y0 >= y1) throw new JpxException("SIZ: zero or negative image size");
        if (tdx == 0 || tdy == 0) throw new JpxException("SIZ: zero tile size");
        if (tx0 > x0 || ty0 > y0 || tx0 + tdx <= x0 || ty0 + tdy <= y0) {
            throw new JpxException("SIZ: illegal tile offset");
        }
        if (x1 > Integer.MAX_VALUE || y1 > Integer.MAX_VALUE || tdx > Integer.MAX_VALUE || tdy > Integer.MAX_VALUE) {
            throw JpxException.unsupported("reference grid beyond 2^31");
        }
        long tw = Codestream.ceilDiv(x1 - tx0, tdx), th = Codestream.ceilDiv(y1 - ty0, tdy);
        if (tw == 0 || th == 0 || tw * th > 65535) {
            throw new JpxException("SIZ: " + tw + " x " + th + " tiles (65535 at most)");
        }
        Codestream.Component[] comps = new Codestream.Component[csiz];
        for (int i = 0; i < csiz; i++) {
            int q = p + 36 + 3 * i;
            int ssiz = u8(q, segEnd);
            int prec = (ssiz & 0x7f) + 1;
            int dx = u8(q + 1, segEnd), dy = u8(q + 2, segEnd);
            if (dx < 1 || dy < 1) throw new JpxException("SIZ: component " + i + " subsampling " + dx + "x" + dy);
            if (prec > 31) throw JpxException.unsupported("component precision " + prec + " (31 at most)");
            comps[i] = new Codestream.Component(prec, (ssiz & 0x80) != 0, dx, dy);
        }
        cs = new Codestream(data, rsiz, (int) x0, (int) y0, (int) x1, (int) y1, (int) tdx, (int) tdy,
                (int) tx0, (int) ty0, comps);
    }

    // ------------------------------------------------------------------------------------------
    // COD / COC (opj_j2k_read_cod, opj_j2k_read_coc, opj_j2k_read_SPCod_SPCoc)

    private void readCod(TileCoding tcp, int p, int segEnd) throws JpxException {
        if (segEnd - p < 5) throw new JpxException("COD marker too short");
        int csty = u8(p, segEnd);
        if ((csty & ~(Markers.CSTY_PRECINCTS | Markers.CSTY_SOP | Markers.CSTY_EPH)) != 0) {
            throw new JpxException("COD: unknown Scod value " + csty);
        }
        int prog = u8(p + 1, segEnd);
        if (prog > Markers.CPRL) throw new JpxException("COD: unknown progression order " + prog);
        int layers = u16(p + 2, segEnd);
        if (layers < 1) throw new JpxException("COD: zero layers");
        int mct = u8(p + 4, segEnd);
        if (mct > 1) throw new JpxException("COD: invalid multiple component transform " + mct);
        ComponentCoding spcod = new ComponentCoding();
        spcod.csty = csty & Markers.CSTY_PRECINCTS;
        int q = readSPCod(spcod, p + 5, segEnd);
        if (q != segEnd) throw new JpxException("COD marker has trailing bytes");
        tcp.csty = csty;
        tcp.progression = prog;
        tcp.numLayers = layers;
        tcp.mct = mct;
        for (int i = 0; i < tcp.components.length; i++) {
            if (cocSet[i]) continue; // D-9
            tcp.components[i].csty = spcod.csty;
            tcp.components[i].copyCodingFrom(spcod);
        }
    }

    private int componentIndex(int p, int segEnd) throws JpxException {
        int c = cs.components.length <= 256 ? u8(p, segEnd) : u16(p, segEnd);
        if (c >= cs.components.length) throw new JpxException("component " + c + " out of range");
        return c;
    }

    private int componentRoom() {
        return cs.components.length <= 256 ? 1 : 2;
    }

    private void readCoc(TileCoding tcp, int p, int segEnd) throws JpxException {
        int room = componentRoom();
        if (segEnd - p < room + 1) throw new JpxException("COC marker too short");
        int c = componentIndex(p, segEnd);
        ComponentCoding tccp = tcp.components[c];
        tccp.csty = u8(p + room, segEnd);
        int q = readSPCod(tccp, p + room + 1, segEnd);
        if (q != segEnd) throw new JpxException("COC marker has trailing bytes");
        cocSet[c] = true;
    }

    /** SPcod / SPcoc into tccp (csty already set); returns the position after it. */
    private int readSPCod(ComponentCoding tccp, int p, int segEnd) throws JpxException {
        if (segEnd - p < 5) throw new JpxException("SPcod/SPcoc too short");
        int numRes = u8(p, segEnd) + 1;
        if (numRes > Codestream.MAX_RESOLUTIONS) throw new JpxException("too many decomposition levels: " + (numRes - 1));
        int cbw = u8(p + 1, segEnd) + 2, cbh = u8(p + 2, segEnd) + 2;
        if (cbw > 10 || cbh > 10 || cbw + cbh > 12) throw new JpxException("invalid code-block size 2^" + cbw + " x 2^" + cbh);
        int style = u8(p + 3, segEnd);
        if ((style & (Markers.CBLK_HT | Markers.CBLK_HT_MIXED)) != 0) {
            throw JpxException.unsupported("HTJ2K code-blocks (Part 15)");
        }
        int transform = u8(p + 4, segEnd);
        if (transform > 1) throw new JpxException("invalid wavelet transform " + transform);
        tccp.numResolutions = numRes;
        tccp.cblkWidthExp = cbw;
        tccp.cblkHeightExp = cbh;
        tccp.cblkStyle = style;
        tccp.transform = transform;
        int q = p + 5;
        if ((tccp.csty & Markers.CSTY_PRECINCTS) != 0) {
            if (segEnd - q < numRes) throw new JpxException("precinct sizes missing");
            for (int i = 0; i < numRes; i++) {
                int v = u8(q++, segEnd);
                // exponent 0 is only allowed for the lowest resolution (Table A.21)
                if (i != 0 && ((v & 0xf) == 0 || (v >> 4) == 0)) throw new JpxException("invalid precinct size");
                tccp.precinctWidthExp[i] = v & 0xf;
                tccp.precinctHeightExp[i] = v >> 4;
            }
        } else {
            for (int i = 0; i < numRes; i++) {
                tccp.precinctWidthExp[i] = 15;
                tccp.precinctHeightExp[i] = 15;
            }
        }
        return q;
    }

    // ------------------------------------------------------------------------------------------
    // QCD / QCC (opj_j2k_read_qcd, opj_j2k_read_qcc, opj_j2k_read_SQcd_SQcc)

    private void readQcd(TileCoding tcp, int p, int segEnd) throws JpxException {
        ComponentCoding q = new ComponentCoding();
        readSQcd(q, p, segEnd);
        for (int i = 0; i < tcp.components.length; i++) {
            if (!qccSet[i]) tcp.components[i].copyQuantizationFrom(q); // D-9
        }
    }

    private void readQcc(TileCoding tcp, int p, int segEnd) throws JpxException {
        int room = componentRoom();
        if (segEnd - p < room) throw new JpxException("QCC marker too short");
        int c = componentIndex(p, segEnd);
        readSQcd(tcp.components[c], p + room, segEnd);
        qccSet[c] = true;
    }

    private void readSQcd(ComponentCoding tccp, int p, int segEnd) throws JpxException {
        if (segEnd - p < 1) throw new JpxException("SQcd/SQcc too short");
        int sq = u8(p, segEnd);
        int style = sq & 0x1f;
        if (style > Markers.QNT_SCALAR_EXPOUNDED) throw new JpxException("unknown quantization style " + style);
        tccp.quantStyle = style;
        tccp.guardBits = sq >> 5;
        int remaining = segEnd - p - 1;
        int q = p + 1;
        int bands;
        if (style == Markers.QNT_SCALAR_DERIVED) {
            bands = 1;
        } else {
            bands = style == Markers.QNT_NONE ? remaining : remaining / 2;
        }
        if (style == Markers.QNT_NONE) {
            if (remaining != bands) throw new JpxException("SQcd size mismatch");
            for (int b = 0; b < bands; b++) {
                int v = u8(q++, segEnd);
                if (b < Codestream.MAX_BANDS) {
                    tccp.stepExponent[b] = v >> 3;
                    tccp.stepMantissa[b] = 0;
                }
            }
        } else {
            if (remaining != 2 * bands) throw new JpxException("SQcd size mismatch");
            for (int b = 0; b < bands; b++) {
                int v = u16(q, segEnd);
                q += 2;
                if (b < Codestream.MAX_BANDS) {
                    tccp.stepExponent[b] = v >> 11;
                    tccp.stepMantissa[b] = v & 0x7ff;
                }
            }
        }
        if (bands > Codestream.MAX_BANDS) warn("quantization lists " + bands + " sub-bands; the rest are ignored");
        if (style == Markers.QNT_SCALAR_DERIVED) {
            // E.1.1.2: derived exponents for the other sub-bands
            for (int b = 1; b < Codestream.MAX_BANDS; b++) {
                tccp.stepExponent[b] = Math.max(tccp.stepExponent[0] - (b - 1) / 3, 0);
                tccp.stepMantissa[b] = tccp.stepMantissa[0];
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // POC, RGN, COM, CAP, PPM, PPT

    private void readPoc(TileCoding tcp, int p, int segEnd) throws JpxException {
        int room = componentRoom();
        int chunk = 5 + 2 * room;
        int size = segEnd - p;
        if (size == 0 || size % chunk != 0) throw new JpxException("bad POC marker size");
        int n = size / chunk;
        if (tcp.pocs.size() + n >= Codestream.MAX_POCS) throw new JpxException("too many POC entries");
        for (int i = 0; i < n; i++) {
            int q = p + i * chunk;
            int rs = u8(q, segEnd);
            int csx = room == 1 ? u8(q + 1, segEnd) : u16(q + 1, segEnd);
            int le = u16(q + 1 + room, segEnd);
            int re = u8(q + 3 + room, segEnd);
            int ce = room == 1 ? u8(q + 4 + room, segEnd) : u16(q + 4 + room, segEnd);
            int pr = u8(q + 4 + 2 * room, segEnd);
            if (pr > Markers.CPRL) throw new JpxException("POC: unknown progression order " + pr);
            // CEpoc = 0 means 256 (or 16384 with 2-byte indices), Table A.32
            if (ce == 0) ce = room == 1 ? 256 : 16384;
            tcp.pocs.add(new Poc(rs, csx, Math.min(le, tcp.numLayers), re, Math.min(ce, cs.components.length), pr));
        }
    }

    private void readRgn(TileCoding tcp, int p, int segEnd) throws JpxException {
        int room = componentRoom();
        if (segEnd - p != 2 + room) throw new JpxException("bad RGN marker size");
        int c = componentIndex(p, segEnd);
        int style = u8(p + room, segEnd);
        if (style != 0) throw new JpxException("RGN: unknown ROI style " + style);
        tcp.components[c].roiShift = u8(p + room + 1, segEnd);
    }

    private void readCom(int p, int segEnd) throws JpxException {
        if (segEnd - p < 2) return;
        int rcom = u16(p, segEnd);
        if (rcom == 1) cs.comments.add(new String(data, p + 2, segEnd - p - 2, StandardCharsets.ISO_8859_1));
    }

    private void readCap(int p, int segEnd) throws JpxException {
        long pcap = u32(p, segEnd);
        cs.capabilities = (int) pcap;
        // Pcap^i set => Part i capabilities follow; Part 15 (HTJ2K) is bit 15 counted from the MSB
        if ((pcap & (1L << (32 - 15))) != 0) throw JpxException.unsupported("HTJ2K (Part 15) codestream");
    }

    private void readPpm(int p, int segEnd) throws JpxException {
        if (segEnd - p < 2) throw new JpxException("PPM marker too short");
        int z = u8(p, segEnd);
        if (ppm.containsKey(z)) throw new JpxException("PPM Zppm " + z + " repeated");
        byte[] b = new byte[segEnd - p - 1];
        System.arraycopy(data, p + 1, b, 0, b.length);
        ppm.put(z, b);
    }

    /** opj_j2k_merge_ppm, but keeping the Nppm boundaries: one chunk per tile-part (D-11). */
    private List<byte[]> splitPpm() throws JpxException {
        if (ppm.isEmpty()) return null;
        int total = 0;
        for (byte[] b : ppm.values()) total += b.length;
        byte[] all = new byte[total];
        int o = 0;
        for (byte[] b : ppm.values()) {
            System.arraycopy(b, 0, all, o, b.length);
            o += b.length;
        }
        List<byte[]> chunks = new ArrayList<>();
        int q = 0;
        while (q < all.length) {
            if (all.length - q < 4) throw new JpxException("PPM: not enough bytes to read Nppm");
            long n = ((long) (all[q] & 0xff) << 24) | ((all[q + 1] & 0xff) << 16) | ((all[q + 2] & 0xff) << 8) | (all[q + 3] & 0xff);
            q += 4;
            if (n > all.length - q) throw new JpxException("PPM: corrupted Nppm");
            byte[] c = new byte[(int) n];
            System.arraycopy(all, q, c, 0, c.length);
            chunks.add(c);
            q += c.length;
        }
        return chunks;
    }

    private void readPpt(Tile tile, int p, int segEnd) throws JpxException {
        if (!ppm.isEmpty()) throw new JpxException("PPT and PPM in the same codestream");
        if (segEnd - p < 2) throw new JpxException("PPT marker too short");
        int z = u8(p, segEnd);
        while (tile.ppt.size() <= z) tile.ppt.add(null);
        if (tile.ppt.get(z) != null) throw new JpxException("PPT Zppt " + z + " repeated in tile " + tile.index);
        byte[] b = new byte[segEnd - p - 1];
        System.arraycopy(data, p + 1, b, 0, b.length);
        tile.ppt.set(z, b);
    }

    // ------------------------------------------------------------------------------------------
    // tile-parts (opj_j2k_read_sot, opj_j2k_read_tile_header, opj_j2k_read_sod)

    private void readTileParts(int p) throws JpxException {
        while (p < end) {
            int m = marker(p);
            if (m == Markers.EOC) return;
            if (m != Markers.SOT) {
                if (m < 0) {
                    warn("codestream ends without EOC");
                } else {
                    warn(String.format("expected SOT or EOC at %d, found %04x; the rest is ignored", p - start, m));
                }
                return;
            }
            p = readTilePart(p);
        }
        warn("codestream ends without EOC");
    }

    /** Reads the tile-part at p (an SOT marker); returns where the next one starts. */
    private int readTilePart(int sot) throws JpxException {
        int segEnd = segmentEnd(sot + 2, end);
        if (segEnd - (sot + 4) != 8) throw new JpxException("bad SOT marker size");
        int isot = u16(sot + 4, segEnd);
        long psot = u32(sot + 6, segEnd);
        int tpsot = u8(sot + 10, segEnd);
        int tnsot = u8(sot + 11, segEnd);
        if (isot >= cs.tiles.length) throw new JpxException("SOT: tile " + isot + " out of range");
        Tile tile = cs.tiles[isot];
        if (tile == null) {
            tile = new Tile(isot, cs.defaults.clone());
            cs.tiles[isot] = tile;
        }
        // A.4.2: tile-parts of a tile appear in order
        if (tpsot != tile.parts.size()) {
            throw new JpxException("tile " + isot + ": tile-part " + tpsot + " where " + tile.parts.size() + " was expected");
        }
        if (tnsot != 0 && tpsot >= tnsot) {
            warn("tile " + isot + ": tile-part " + tpsot + " beyond TNsot " + tnsot + " (encoder under-count; ignored)");
        }
        int partEnd;
        if (psot == 0) {
            // the last tile-part runs to EOC (or the end of the data)
            partEnd = marker(end - 2) == Markers.EOC ? end - 2 : end;
        } else if (psot < 14 && psot != 12) {
            throw new JpxException("SOT: invalid Psot " + psot);
        } else if (psot > end - sot) {
            warn("tile " + isot + " part " + tpsot + ": Psot runs past the end of the data; truncated");
            partEnd = end;
        } else {
            partEnd = (int) (sot + psot);
        }
        if (psot == 12) {
            // an SOT with no header and no data (Kakadu "PHR" padding)
            tile.parts.add(new TilePart(tpsot, tnsot, partEnd, 0));
            partsInOrder.add(tile.parts.get(tile.parts.size() - 1));
            return partEnd;
        }
        // tile-part header markers: precedence flags apply per header (D-9); the first tile-part
        // of a tile starts from the main-header values, later ones refine the tile's own
        boolean first = tpsot == 0;
        if (first) {
            cocSet = new boolean[cs.components.length];
            qccSet = new boolean[cs.components.length];
        }
        int p = segEnd;
        for (;;) {
            int m = marker(p);
            if (m < 0 || p + 2 > partEnd) throw new JpxException("tile " + isot + ": no SOD in tile-part " + tpsot);
            if (m == Markers.SOD) {
                p += 2;
                break;
            }
            if (m < 0xFF00) throw new JpxException(String.format("tile %d: expected a marker at %d, found %04x", isot, p - start, m));
            if (Markers.hasNoSegment(m)) {
                p += 2;
                continue;
            }
            int body = p + 4;
            int se = segmentEnd(p + 2, partEnd);
            TileCoding tcp = tile.coding;
            switch (m) {
                case Markers.COD:
                    readCod(tcp, body, se);
                    break;
                case Markers.COC:
                    readCoc(tcp, body, se);
                    break;
                case Markers.QCD:
                    readQcd(tcp, body, se);
                    break;
                case Markers.QCC:
                    readQcc(tcp, body, se);
                    break;
                case Markers.POC:
                    if (!tile.tilePocRead) tcp.pocs.clear(); // a tile's POC replaces the main header's
                    readPoc(tcp, body, se);
                    break;
                case Markers.RGN:
                    readRgn(tcp, body, se);
                    break;
                case Markers.PPT:
                    readPpt(tile, body, se);
                    break;
                case Markers.COM:
                    readCom(body, se);
                    break;
                case Markers.PLT:
                    break;
                case Markers.MCT:
                case Markers.MCC:
                case Markers.MCO:
                    throw JpxException.unsupported(String.format("Part 2 multiple component transform (marker %04x)", m));
                case Markers.SIZ:
                case Markers.SOT:
                case Markers.EOC:
                case Markers.PPM:
                case Markers.TLM:
                case Markers.PLM:
                case Markers.CRG:
                case Markers.CAP:
                case Markers.CPF:
                case Markers.CBD:
                    throw new JpxException(String.format("tile %d: marker %04x not allowed in a tile-part header", isot, m));
                default:
                    p = skipUnknown(p, partEnd);
                    continue;
            }
            if (m == Markers.POC) tile.tilePocRead = true;
            p = se;
        }
        TilePart part = new TilePart(tpsot, tnsot, p, partEnd - p);
        tile.parts.add(part);
        partsInOrder.add(part);
        return partEnd;
    }
}
