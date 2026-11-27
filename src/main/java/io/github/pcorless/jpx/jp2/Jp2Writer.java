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
 * Provenance: SPEC, ITU-T T.800 (06/2019) I.4 (box structure, Table I.1, XLBox), I.5.1 (signature),
 *   I.5.2 (ftyp), I.5.3 (jp2h), I.5.3.1 (ihdr, Table I.2), I.5.3.3 (colr, Tables I.9-I.10),
 *   I.5.4 (jp2c).
 */
package io.github.pcorless.jpx.jp2;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Writes the boxes of a minimal JP2 file before its codestream: signature, file type, a header
 * with the image header and one colour specification, and the contiguous codestream box's header.
 * The codestream itself follows, written by the caller.
 */
public final class Jp2Writer {

    /** Enumerated colour spaces (T.800 Table I.10). */
    public static final int ENUM_SRGB = 16;
    public static final int ENUM_GREYSCALE = 17;

    private static final int BRAND_JP2 = 0x6A703220; // "jp2 "

    private Jp2Writer() {
    }

    /**
     * Everything up to the codestream: signature, ftyp, jp2h (ihdr, colr) and the jp2c box header
     * for a codestream of {@code codestreamLength} bytes. The colour specification is the ICC
     * profile when {@code icc} is not null (method 2, restricted ICC), else {@code enumCs}
     * (method 1). Components are unsigned, {@code precision} bits, and the codestream is
     * JPEG 2000 (C = 7).
     */
    public static void writeHeader(DataOutputStream out, int width, int height, int numComps, int precision,
                                   int enumCs, byte[] icc, long codestreamLength) throws IOException {
        // I.5.1 signature box
        out.writeInt(12);
        out.writeInt(Jp2Header.JP);
        out.writeInt(0x0D0A870A);
        // I.5.2 file type box: brand, minor version, compatibility list
        out.writeInt(20);
        out.writeInt(Jp2Header.FTYP);
        out.writeInt(BRAND_JP2);
        out.writeInt(0);
        out.writeInt(BRAND_JP2);
        // I.5.3 JP2 header superbox
        int ihdr = 22;
        int colr = icc != null ? 11 + icc.length : 15;
        out.writeInt(8 + ihdr + colr);
        out.writeInt(Jp2Header.JP2H);
        // I.5.3.1 image header
        out.writeInt(ihdr);
        out.writeInt(Jp2Header.IHDR);
        out.writeInt(height);
        out.writeInt(width);
        out.writeShort(numComps);
        out.writeByte(precision - 1);     // BPC: unsigned, the same for every component
        out.writeByte(7);                 // C: JPEG 2000 (ITU-T T.800)
        out.writeByte(0);                 // UnkC: the colour space is known
        out.writeByte(0);                 // IPR: no intellectual property box
        // I.5.3.3 colour specification
        out.writeInt(colr);
        out.writeInt(Jp2Header.COLR);
        out.writeByte(icc != null ? 2 : 1); // METH
        out.writeByte(0);                 // PREC
        out.writeByte(0);                 // APPROX
        if (icc != null) {
            out.write(icc);
        } else {
            out.writeInt(enumCs);
        }
        // I.5.4 contiguous codestream box header; XLBox when the length doesn't fit in LBox
        if (codestreamLength + 8 <= 0xFFFFFFFFL) {
            out.writeInt((int) (codestreamLength + 8));
            out.writeInt(Jp2Header.JP2C);
        } else {
            out.writeInt(1);
            out.writeInt(Jp2Header.JP2C);
            out.writeLong(codestreamLength + 16);
        }
    }
}
