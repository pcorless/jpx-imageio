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
 * Provenance: SPEC. Marker codes from ITU-T T.800 (06/2019) Table A.2; CAP and CPF from
 * T.814 (HTJ2K); MCT/MCC/MCO/CBD from T.801 (Part 2).
 */
package io.github.pcorless.jpx.codestream;

/** Codestream marker codes. */
public final class Markers {

    public static final int SOC = 0xFF4F;
    public static final int CAP = 0xFF50;
    public static final int SIZ = 0xFF51;
    public static final int COD = 0xFF52;
    public static final int COC = 0xFF53;
    public static final int TLM = 0xFF55;
    public static final int PLM = 0xFF57;
    public static final int PLT = 0xFF58;
    public static final int CPF = 0xFF59;
    public static final int QCD = 0xFF5C;
    public static final int QCC = 0xFF5D;
    public static final int RGN = 0xFF5E;
    public static final int POC = 0xFF5F;
    public static final int PPM = 0xFF60;
    public static final int PPT = 0xFF61;
    public static final int CRG = 0xFF63;
    public static final int COM = 0xFF64;
    public static final int MCT = 0xFF74;
    public static final int MCC = 0xFF75;
    public static final int MCO = 0xFF77;
    public static final int CBD = 0xFF78;
    public static final int SOT = 0xFF90;
    public static final int SOP = 0xFF91;
    public static final int EPH = 0xFF92;
    public static final int SOD = 0xFF93;
    public static final int EOC = 0xFFD9;

    /** Scod / Scoc flags (Table A.13). */
    public static final int CSTY_PRECINCTS = 0x01;
    public static final int CSTY_SOP = 0x02;
    public static final int CSTY_EPH = 0x04;

    /** Code-block style flags (Table A.19). */
    public static final int CBLK_BYPASS = 0x01;
    public static final int CBLK_RESET = 0x02;
    public static final int CBLK_TERMALL = 0x04;
    public static final int CBLK_VSC = 0x08;
    public static final int CBLK_PTERM = 0x10;
    public static final int CBLK_SEGSYM = 0x20;
    public static final int CBLK_HT = 0x40;
    public static final int CBLK_HT_MIXED = 0x80;

    /** Quantization styles (Table A.28). */
    public static final int QNT_NONE = 0;
    public static final int QNT_SCALAR_DERIVED = 1;
    public static final int QNT_SCALAR_EXPOUNDED = 2;

    /** Progression orders (Table A.16). */
    public static final int LRCP = 0;
    public static final int RLCP = 1;
    public static final int RPCL = 2;
    public static final int PCRL = 3;
    public static final int CPRL = 4;

    /** Markers 0xFF30-0xFF3F carry no segment (A.1.4). */
    public static boolean hasNoSegment(int marker) {
        return marker >= 0xFF30 && marker <= 0xFF3F;
    }

    private Markers() {
    }
}
