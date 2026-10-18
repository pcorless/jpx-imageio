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
 * Provenance: ADAPTED from OpenJPEG src/lib/openjp2/jp2.c @ 6c4a29b0 (BSD-2):
 *   opj_jp2_read_header_procedure, opj_jp2_read_boxhdr, opj_jp2_read_boxhdr_char,
 *   opj_jp2_read_jp, opj_jp2_read_ftyp, opj_jp2_read_jp2h, opj_jp2_read_ihdr,
 *   opj_jp2_read_bpcc, opj_jp2_read_colr, opj_jp2_read_pclr, opj_jp2_read_cmap,
 *   opj_jp2_read_cdef, the has_jp2h/has_ihdr checks of opj_jp2_read_header.
 *   Changed: reads from a byte range instead of a stream; colr boxes after the chosen one are
 *   parsed (leniently) and kept instead of skipped; the res box is read per T.800 I.5.3.7
 *   (SPEC; OpenJPEG skips it) and a malformed one is only a warning. Nothing after the jp2c
 *   box header is read, as OpenJPEG hands the rest of the stream to the codestream reader.
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
 * Copyright (c) 2010-2011, Kaori Hagihara
 * Copyright (c) 2008, 2011-2012, Centre National d'Etudes Spatiales (CNES), FR
 * Copyright (c) 2012, CS Systemes d'Information, France
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
package io.github.pcorless.jpx.jp2;

import io.github.pcorless.jpx.JpxException;

/** Reads the boxes of a JP2 file up to its codestream. */
public final class Jp2Reader {

    // opj_jp2_t jp2_state
    private static final int STATE_SIGNATURE = 0x1, STATE_FILE_TYPE = 0x2, STATE_HEADER = 0x4;

    private final byte[] data;
    private final int end;
    private final Jp2Header h = new Jp2Header();
    private int state;

    private Jp2Reader(byte[] data, int end) {
        this.data = data;
        this.end = end;
    }

    /** True when the bytes start with a JP2 signature box. */
    public static boolean isJp2(byte[] data, int offset, int length) {
        return length >= 12 && u32(data, offset) == 12 && u32(data, offset + 4) == Jp2Header.JP
                && u32(data, offset + 8) == 0x0d0a870aL;
    }

    public static Jp2Header read(byte[] data, int offset, int length) throws JpxException {
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IllegalArgumentException("file " + offset + "+" + length + " outside " + data.length);
        }
        Jp2Reader r = new Jp2Reader(data, offset + length);
        r.readHeader(offset);
        // opj_jp2_read_header
        if (!r.h.hasJp2h) throw new JpxException("JP2H box missing. Required.");
        if (!r.h.hasIhdr) throw new JpxException("IHDR box missing. Required.");
        return r.h;
    }

    static long u32(byte[] b, int p) {
        return ((b[p] & 0xffL) << 24) | ((b[p + 1] & 0xff) << 16) | ((b[p + 2] & 0xff) << 8) | (b[p + 3] & 0xff);
    }

    static int u16(byte[] b, int p) {
        return ((b[p] & 0xff) << 8) | (b[p + 1] & 0xff);
    }

    static String name(long type) {
        StringBuilder sb = new StringBuilder(4);
        for (int s = 24; s >= 0; s -= 8) sb.append((char) ((type >> s) & 0xff));
        return sb.toString();
    }

    private void warn(String w) {
        h.warnings.add(w);
    }

    /** opj_jp2_read_header_procedure: top-level boxes until jp2c. */
    private void readHeader(int p) throws JpxException {
        while (end - p >= 8) {
            // opj_jp2_read_boxhdr
            long length = u32(data, p);
            int type = (int) u32(data, p + 4);
            int headerSize = 8;
            if (length == 0) { // last box
                length = (long) (end - p - 8) + 8;
            } else if (length == 1) {
                if (end - p < 16) break;
                headerSize = 16;
                if (u32(data, p + 8) != 0) break; // "Cannot handle box sizes higher than 2^32"
                length = u32(data, p + 12);
            }
            if (type == Jp2Header.JP2C) {
                if ((state & STATE_HEADER) == 0) throw new JpxException("bad placed jpeg codestream");
                h.codestreamOffset = p + headerSize;
                h.codestreamLength = end - h.codestreamOffset;
                h.codestreamBoxLength = u32(data, p) == 0 ? -1 : length - headerSize;
                return;
            } else if (length == 0) {
                throw new JpxException("Cannot handle box of undefined sizes");
            } else if (length < headerSize) {
                throw new JpxException("invalid box size " + length + " (" + name(type) + ")");
            }
            long size = length - headerSize;
            boolean top = type == Jp2Header.JP || type == Jp2Header.FTYP || type == Jp2Header.JP2H;
            boolean image = isImageBox(type);
            p += headerSize;
            if (top || image) {
                if (!top) {
                    warn("Found a misplaced '" + name(type) + "' box outside jp2h box");
                    if ((state & STATE_HEADER) == 0) {
                        warn("JPEG2000 Header box not read yet, '" + name(type) + "' box will be ignored");
                        if (size > end - p) throw new JpxException("Problem with skipping JPEG2000 box, stream error");
                        p += (int) size;
                        continue;
                    }
                }
                if (size > end - p) {
                    throw new JpxException("Invalid box size " + length + " for box '" + name(type) + "'. Need "
                            + size + " bytes, " + (end - p) + " bytes remaining");
                }
                int n = (int) size;
                if (type == Jp2Header.JP) readJp(p, n);
                else if (type == Jp2Header.FTYP) readFtyp(p, n);
                else if (type == Jp2Header.JP2H) readJp2h(p, n);
                else readImageBox(type, p, n);
                p += n;
            } else {
                if ((state & STATE_SIGNATURE) == 0) {
                    throw new JpxException("Malformed JP2 file format: first box must be JPEG 2000 signature box");
                }
                if ((state & STATE_FILE_TYPE) == 0) {
                    throw new JpxException("Malformed JP2 file format: second box must be file type box");
                }
                if (size > end - p) throw new JpxException("Problem with skipping JPEG2000 box, stream error");
                p += (int) size;
            }
        }
    }

    private static boolean isImageBox(int type) {
        return type == Jp2Header.IHDR || type == Jp2Header.COLR || type == Jp2Header.BPCC
                || type == Jp2Header.PCLR || type == Jp2Header.CMAP || type == Jp2Header.CDEF;
    }

    private void readImageBox(int type, int p, int n) throws JpxException {
        switch (type) {
            case Jp2Header.IHDR: readIhdr(p, n); break;
            case Jp2Header.COLR: readColr(p, n); break;
            case Jp2Header.BPCC: readBpcc(p, n); break;
            case Jp2Header.PCLR: readPclr(p, n); break;
            case Jp2Header.CMAP: readCmap(p, n); break;
            case Jp2Header.CDEF: readCdef(p, n); break;
            default: throw new IllegalStateException();
        }
    }

    private void readJp(int p, int n) throws JpxException {
        if (state != 0) throw new JpxException("The signature box must be the first box in the file.");
        if (n != 4) throw new JpxException("Error with JP signature Box size");
        if (u32(data, p) != 0x0d0a870aL) throw new JpxException("Error with JP Signature : bad magic number");
        state |= STATE_SIGNATURE;
    }

    private void readFtyp(int p, int n) throws JpxException {
        if (state != STATE_SIGNATURE) throw new JpxException("The ftyp box must be the second box in the file.");
        if (n < 8) throw new JpxException("Error with FTYP signature Box size");
        h.brand = u32(data, p);
        h.minorVersion = u32(data, p + 4);
        int remaining = n - 8;
        if ((remaining & 3) != 0) throw new JpxException("Error with FTYP signature Box size");
        h.compatibility = new long[remaining >> 2];
        for (int i = 0; i < h.compatibility.length; i++) h.compatibility[i] = u32(data, p + 8 + 4 * i);
        state |= STATE_FILE_TYPE;
    }

    /** opj_jp2_read_jp2h, plus the res super-box. */
    private void readJp2h(int p, int n) throws JpxException {
        if ((state & STATE_FILE_TYPE) != STATE_FILE_TYPE) {
            throw new JpxException("The  box must be the first box in the file.");
        }
        boolean hasIhdr = false;
        while (n > 0) {
            // opj_jp2_read_boxhdr_char
            if (n < 8) throw new JpxException("Stream error while reading JP2 Header box");
            long length = u32(data, p);
            int type = (int) u32(data, p + 4);
            int headerSize = 8;
            if (length == 1) {
                if (n < 16) throw new JpxException("Stream error while reading JP2 Header box");
                if (u32(data, p + 8) != 0) throw new JpxException("Stream error while reading JP2 Header box");
                headerSize = 16;
                length = u32(data, p + 12);
                if (length == 0) throw new JpxException("Stream error while reading JP2 Header box");
            } else if (length == 0) {
                throw new JpxException("Stream error while reading JP2 Header box");
            }
            if (length < headerSize) throw new JpxException("Stream error while reading JP2 Header box");
            if (length > n) {
                throw new JpxException("Stream error while reading JP2 Header box: box length is inconsistent.");
            }
            int size = (int) length - headerSize;
            if (isImageBox(type)) {
                readImageBox(type, p + headerSize, size);
            } else if (type == Jp2Header.RES) {
                readRes(p + headerSize, size);
            }
            if (type == Jp2Header.IHDR) hasIhdr = true;
            p += (int) length;
            n -= (int) length;
        }
        if (!hasIhdr) throw new JpxException("Stream error while reading JP2 Header box: no 'ihdr' box.");
        state |= STATE_HEADER;
        h.hasJp2h = true;
    }

    private void readIhdr(int p, int n) throws JpxException {
        if (h.hasIhdr) {
            warn("Ignoring ihdr box. First ihdr box already read");
            return;
        }
        if (n != 14) throw new JpxException("Bad image header box (bad size)");
        h.height = u32(data, p);
        h.width = u32(data, p + 4);
        h.components = u16(data, p + 8);
        if (h.height < 1 || h.width < 1 || h.components < 1) {
            throw new JpxException("Wrong values for: w(" + h.width + ") h(" + h.height + ") numcomps("
                    + h.components + ") (ihdr)");
        }
        if (h.components - 1 >= 16384) throw new JpxException("Invalid number of components (ihdr)");
        h.bitsPerComponent = data[p + 10] & 0xff;
        h.compressionType = data[p + 11] & 0xff;
        if (h.compressionType != 7) {
            warn("JP2 IHDR box: compression type indicate that the file is not a conforming JP2 file ("
                    + h.compressionType + ")");
        }
        h.unknownColourspace = data[p + 12] & 0xff;
        h.intellectualProperty = data[p + 13] & 0xff;
        h.hasIhdr = true;
    }

    private void readBpcc(int p, int n) throws JpxException {
        if (h.bitsPerComponent != 255) {
            warn("A BPCC header box is available although BPC given by the IHDR box (" + h.bitsPerComponent
                    + ") indicate components bit depth is constant");
        }
        if (n != h.components) throw new JpxException("Bad BPCC header box (bad size)");
        h.bitsPerComponents = new int[n];
        for (int i = 0; i < n; i++) h.bitsPerComponents[i] = data[p + i] & 0xff;
    }

    private void readColr(int p, int n) throws JpxException {
        if (n < 3) throw new JpxException("Bad COLR header box (bad size)");
        // I.5.3.3: 'A conforming JP2 reader shall ignore all Colour Specification boxes after the
        // first.' OpenJPEG skips them; they are kept here (unchosen) and never fail the read.
        boolean chosen = h.chosenColour < 0;
        int meth = data[p] & 0xff, precedence = data[p + 1] & 0xff, approx = data[p + 2] & 0xff;
        if (meth == 1) {
            if (n < 7) {
                if (chosen) throw new JpxException("Bad COLR header box (bad size: " + n + ")");
                warn("ignored colr box: bad size " + n);
                return;
            }
            int enumcs = (int) u32(data, p + 3);
            if (n > 7 && enumcs != 14 && chosen) warn("Bad COLR header box (bad size: " + n + ")");
            long[] cielab = null;
            if (enumcs == 14) { // CIELab
                long rl = 0, ol = 0, ra = 0, oa = 0, rb = 0, ob = 0, il = 0x00443530L; // D50
                cielab = new long[9];
                cielab[0] = 14;
                cielab[1] = 0x44454600L; // DEF
                if (n == 35) {
                    rl = u32(data, p + 7);
                    ol = u32(data, p + 11);
                    ra = u32(data, p + 15);
                    oa = u32(data, p + 19);
                    rb = u32(data, p + 23);
                    ob = u32(data, p + 27);
                    il = u32(data, p + 31);
                    cielab[1] = 0;
                } else if (n != 7) {
                    warn("Bad COLR header box (CIELab, bad size: " + n + ")");
                }
                cielab[2] = rl;
                cielab[4] = ra;
                cielab[6] = rb;
                cielab[3] = ol;
                cielab[5] = oa;
                cielab[7] = ob;
                cielab[8] = il;
            }
            add(new Jp2Header.Colour(meth, precedence, approx, enumcs, null, cielab), chosen);
        } else if (meth == 2) {
            if (n == 3) { // opj_calloc(1, 0) returns NULL, so OpenJPEG fails on an empty profile
                if (chosen) throw new JpxException("Empty ICC profile in COLR box");
                warn("ignored colr box: empty ICC profile");
                return;
            }
            byte[] icc = new byte[n - 3];
            System.arraycopy(data, p + 3, icc, 0, icc.length);
            add(new Jp2Header.Colour(meth, precedence, approx, -1, icc, null), chosen);
        } else {
            if (meth > 2 && chosen) {
                warn("COLR BOX meth value is not a regular value (" + meth
                        + "), so we will ignore the entire Colour Specification box.");
            }
            h.colours.add(new Jp2Header.Colour(meth, precedence, approx, -1, null, null));
        }
    }

    private void add(Jp2Header.Colour c, boolean chosen) {
        if (chosen) h.chosenColour = h.colours.size();
        h.colours.add(c);
    }

    private void readPclr(int p, int n) throws JpxException {
        if (h.palette != null) throw new JpxException("Only one PCLR box is allowed.");
        if (n < 3) throw new JpxException("Bad PCLR box (bad size)");
        int start = p;
        int entries = u16(data, p);
        p += 2;
        if (entries == 0 || entries > 1024) {
            throw new JpxException("Invalid PCLR box. Reports " + entries + " entries");
        }
        int columns = data[p++] & 0xff;
        if (columns == 0) throw new JpxException("Invalid PCLR box. Reports 0 palette columns");
        if (n < 3 + columns) throw new JpxException("Bad PCLR box (bad size)");
        int[] sizes = new int[columns];
        boolean[] signed = new boolean[columns];
        for (int i = 0; i < columns; i++) {
            int b = data[p++] & 0xff;
            sizes[i] = (b & 0x7f) + 1;
            signed[i] = (b & 0x80) != 0;
        }
        int[] values = new int[entries * columns];
        for (int j = 0, k = 0; j < entries; j++) {
            for (int i = 0; i < columns; i++) {
                int bytes = Math.min((sizes[i] + 7) >> 3, 4);
                if (n < (p - start) + bytes) throw new JpxException("Bad PCLR box (bad size)");
                int v = 0;
                for (int b = 0; b < bytes; b++) v = (v << 8) | (data[p++] & 0xff);
                values[k++] = v;
            }
        }
        h.palette = new Jp2Header.Palette(entries, columns, sizes, signed, values);
    }

    private void readCmap(int p, int n) throws JpxException {
        if (h.palette == null) throw new JpxException("Need to read a PCLR box before the CMAP box.");
        if (h.componentMapping != null) throw new JpxException("Only one CMAP box is allowed.");
        int channels = h.palette.columns;
        if (n < channels * 4) throw new JpxException("Insufficient data for CMAP box.");
        Jp2Header.ComponentMapping[] cmap = new Jp2Header.ComponentMapping[channels];
        for (int i = 0; i < channels; i++, p += 4) {
            cmap[i] = new Jp2Header.ComponentMapping(u16(data, p), data[p + 2] & 0xff, data[p + 3] & 0xff);
        }
        h.componentMapping = cmap;
    }

    private void readCdef(int p, int n) throws JpxException {
        if (h.channelDefinitions != null) throw new JpxException("Only one CDEF box is allowed.");
        if (n < 2) throw new JpxException("Insufficient data for CDEF box.");
        int count = u16(data, p);
        p += 2;
        if (count == 0) throw new JpxException("Number of channel description is equal to zero in CDEF box.");
        if (n < 2 + count * 6) throw new JpxException("Insufficient data for CDEF box.");
        Jp2Header.ChannelDefinition[] cdef = new Jp2Header.ChannelDefinition[count];
        for (int i = 0; i < count; i++, p += 6) {
            cdef[i] = new Jp2Header.ChannelDefinition(u16(data, p), u16(data, p + 2), u16(data, p + 4));
        }
        h.channelDefinitions = cdef;
    }

    /** T.800 I.5.3.7: a super-box of resc and resd, 10 bytes each. Never fails the read. */
    private void readRes(int p, int n) {
        int end = p + n;
        while (end - p >= 8) {
            long length = u32(data, p);
            int type = (int) u32(data, p + 4);
            if (length < 8 || length > end - p) {
                warn("malformed res box");
                return;
            }
            if ((type == Jp2Header.RESC || type == Jp2Header.RESD) && length == 18) {
                int q = p + 8;
                Jp2Header.Resolution r = new Jp2Header.Resolution(u16(data, q), u16(data, q + 2), u16(data, q + 4),
                        u16(data, q + 6), data[q + 8], data[q + 9]);
                if (type == Jp2Header.RESC) h.captureResolution = r;
                else h.displayResolution = r;
            } else if (type == Jp2Header.RESC || type == Jp2Header.RESD) {
                warn("malformed " + name(type) + " box");
            }
            p += (int) length;
        }
    }
}
