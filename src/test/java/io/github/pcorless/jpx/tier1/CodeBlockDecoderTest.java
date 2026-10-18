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
 * Provenance: ORIGINAL. The CRCs are of the tables in OpenJPEG v2.5.4 src/lib/openjp2/t1_luts.h
 * (computed with zlib.crc32 over the byte values, 2026-10-17).
 */
package io.github.pcorless.jpx.tier1;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CodeBlockDecoderTest {

    static long crc(byte[] b) {
        CRC32 c = new CRC32();
        c.update(b);
        return c.getValue();
    }

    @DisplayName("generated context tables equal OpenJPEG's t1_luts.h")
    @Test
    public void lookupTables() {
        assertEquals(0x63e5919eL, crc(CodeBlockDecoder.LUT_CTXNO_ZC));
        assertEquals(0x892f9290L, crc(CodeBlockDecoder.LUT_CTXNO_SC));
        assertEquals(0x14867ebfL, crc(CodeBlockDecoder.LUT_SPB));
    }
}
