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
 * Provenance: SPEC, ITU-T T.800 (06/2019) A.4.1 (SOC), A.4.2 (SOT), A.4.3 (SOD), A.4.4 (EOC),
 *   A.5.1 (SIZ, Table A.9), A.6.1 (COD, Tables A.12-A.16), A.6.4 (QCD, Tables A.27-A.30).
 */
package io.github.pcorless.jpx.codestream;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Writes the marker segments of a single-tile codestream: SOC, SIZ, COD, QCD in the main
 * header, one SOT/SOD tile-part, EOC. Image, tile and component origins are all 0 and the
 * components are not subsampled.
 */
public final class CodestreamWriter {

    private CodestreamWriter() {
    }

    public static void soc(DataOutputStream out) throws IOException {
        out.writeShort(Markers.SOC);
    }

    /** SIZ for one tile covering a {@code width x height} image of unsigned {@code precision}-bit components. */
    public static void siz(DataOutputStream out, int width, int height, int numComps, int precision) throws IOException {
        out.writeShort(Markers.SIZ);
        out.writeShort(38 + 3 * numComps);   // Lsiz
        out.writeShort(0);                    // Rsiz: no restrictions (Part 1)
        out.writeInt(width);                  // Xsiz
        out.writeInt(height);                 // Ysiz
        out.writeInt(0);                      // XOsiz
        out.writeInt(0);                      // YOsiz
        out.writeInt(width);                  // XTsiz
        out.writeInt(height);                 // YTsiz
        out.writeInt(0);                      // XTOsiz
        out.writeInt(0);                      // YTOsiz
        out.writeShort(numComps);             // Csiz
        for (int c = 0; c < numComps; c++) {
            out.writeByte(precision - 1);     // Ssiz: unsigned
            out.writeByte(1);                 // XRsiz
            out.writeByte(1);                 // YRsiz
        }
    }

    /**
     * COD: LRCP, one layer, the default precincts (2^15), 64 x 64 code-blocks, code-block
     * style 0, the given decomposition levels, MCT and wavelet (5/3 when {@code reversible}).
     */
    public static void cod(DataOutputStream out, int levels, boolean mct, boolean reversible) throws IOException {
        out.writeShort(Markers.COD);
        out.writeShort(12);                   // Lcod
        out.writeByte(0);                     // Scod: no precincts, SOP or EPH
        out.writeByte(0);                     // progression order: LRCP
        out.writeShort(1);                    // layers
        out.writeByte(mct ? 1 : 0);           // multiple component transform
        out.writeByte(levels);                // decomposition levels
        out.writeByte(6 - 2);                 // code-block width exponent - 2
        out.writeByte(6 - 2);                 // code-block height exponent - 2
        out.writeByte(0);                     // code-block style
        out.writeByte(reversible ? 1 : 0);    // 1: 5/3 reversible, 0: 9/7 irreversible
    }

    /**
     * QCD: no quantisation (exponents only) when {@code mantissa} is null, else scalar
     * expounded; one entry per sub-band in codestream order (LL, then HL, LH, HH per level).
     */
    public static void qcd(DataOutputStream out, int guardBits, int[] exponent, int[] mantissa) throws IOException {
        int n = exponent.length;
        out.writeShort(Markers.QCD);
        out.writeShort(mantissa == null ? 3 + n : 3 + 2 * n);
        out.writeByte((guardBits << 5) | (mantissa == null ? Markers.QNT_NONE : Markers.QNT_SCALAR_EXPOUNDED));
        for (int b = 0; b < n; b++) {
            if (mantissa == null) {
                out.writeByte(exponent[b] << 3);
            } else {
                out.writeShort((exponent[b] << 11) | mantissa[b]);
            }
        }
    }

    /**
     * SOT for tile 0, tile-part 0 of 1; {@code tilePartLength} counts from the SOT marker to the
     * end of the tile-part's data (0: up to EOC, for one too long for Psot).
     */
    public static void sot(DataOutputStream out, long tilePartLength) throws IOException {
        out.writeShort(Markers.SOT);
        out.writeShort(10);                   // Lsot
        out.writeShort(0);                    // Isot
        out.writeInt(tilePartLength > 0xFFFFFFFFL ? 0 : (int) tilePartLength);
        out.writeByte(0);                     // TPsot
        out.writeByte(1);                     // TNsot
    }

    public static void sod(DataOutputStream out) throws IOException {
        out.writeShort(Markers.SOD);
    }

    public static void eoc(DataOutputStream out) throws IOException {
        out.writeShort(Markers.EOC);
    }

    /** Bytes of SOT and SOD: the part of the tile-part length before the packets. */
    public static final int TILE_PART_HEADER_LENGTH = 12 + 2;
}
