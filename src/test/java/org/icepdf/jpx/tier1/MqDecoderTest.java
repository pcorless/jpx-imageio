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
 * Provenance: ORIGINAL. Vectors: OpenJPEG's encoder via tools/vectors/mqvec.c, and the MQ coder
 * test sequence of ITU-T T.88 Annex H.2 (the same MQ coder as T.800 Annex C).
 */
package org.icepdf.jpx.tier1;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MqDecoderTest {

    static final class Vector {
        String name;
        boolean raw;
        int[] ctx;
        int[] bit;
        byte[] bytes;

        @Override
        public String toString() {
            return name;
        }
    }

    static List<Vector> vectors() throws IOException {
        String text;
        try (InputStream in = MqDecoderTest.class.getResourceAsStream("/vectors/mq.txt")) {
            text = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        }
        List<Vector> out = new ArrayList<>();
        Vector v = null;
        for (String line : text.split("\n")) {
            if (line.startsWith("V ")) {
                String[] f = line.split(" ");
                v = new Vector();
                v.name = f[1];
                v.raw = f[2].equals("raw");
                out.add(v);
            } else if (line.startsWith("S") && v != null) {
                String[] f = line.substring(1).trim().split(" ");
                v.ctx = new int[f.length];
                v.bit = new int[f.length];
                for (int i = 0; i < f.length; i++) {
                    int colon = f[i].indexOf(':');
                    v.ctx[i] = Integer.parseInt(f[i].substring(0, colon));
                    v.bit[i] = Integer.parseInt(f[i].substring(colon + 1));
                }
            } else if (line.startsWith("B ") && v != null) {
                v.bytes = hex(line.substring(2).trim());
            }
        }
        return out;
    }

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    /** The tier-1 initial context states (T.800 Table D.7), as the vector generator sets them. */
    static void t1Init(MqDecoder mq) {
        mq.resetStates();
        mq.setState(18, 0, 46);
        mq.setState(17, 0, 3);
        mq.setState(0, 0, 4);
    }

    @DisplayName("decodes what OpenJPEG's encoder wrote")
    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    public void decodesOpenJpegVector(Vector v) {
        // embed the segment in a larger array to prove offsets are honoured and nothing past the end is read as data
        byte[] buf = new byte[v.bytes.length + 7];
        java.util.Arrays.fill(buf, (byte) 0x5a);
        System.arraycopy(v.bytes, 0, buf, 3, v.bytes.length);
        byte[] before = buf.clone();
        MqDecoder mq = new MqDecoder();
        if (v.raw) {
            mq.initRawDecoder(buf, 3, v.bytes.length);
        } else {
            mq.initDecoder(buf, 3, v.bytes.length);
            t1Init(mq);
        }
        for (int i = 0; i < v.bit.length; i++) {
            int d;
            if (v.raw) {
                d = mq.rawDecode();
            } else {
                mq.setCurrentContext(v.ctx[i]);
                d = mq.decode();
            }
            if (d != v.bit[i]) {
                assertEquals(v.bit[i], d, v.name + ": symbol " + i);
            }
        }
        assertTrue(mq.position() <= v.bytes.length, "read past the segment: " + mq.position());
        assertTrue(java.util.Arrays.equals(before, buf), "the decoder modified its input");
    }

    @DisplayName("T.88 Annex H.2 MQ test sequence decodes (single context, initial state 0)")
    @Test
    public void t88AnnexH2() {
        byte[] data = hex("00020051000000C00352872AAAAAAAAA82C02000FCD79EF6BF7FED904F46A3BF");
        byte[] coded = hex("84C73BFCE1A143040220000041 0DBB86F4317FFF88FF37471ADB6ADFFFAC".replace(" ", ""));
        MqDecoder mq = new MqDecoder();
        mq.initDecoder(coded, 0, coded.length);
        mq.resetStates();
        for (int i = 0; i < data.length * 8; i++) {
            int want = (data[i >> 3] >> (7 - (i & 7))) & 1;
            assertEquals(want, mq.decode(), "bit " + i);
        }
    }

    @DisplayName("an empty segment decodes (as the virtual FF FF marker) without reading outside it")
    @Test
    public void emptySegment() {
        MqDecoder mq = new MqDecoder();
        mq.initDecoder(new byte[]{1, 2, 3}, 3, 0);
        t1Init(mq);
        for (int i = 0; i < 100; i++) mq.decode();
        assertEquals(0, mq.position());
        assertTrue(mq.endOfByteStreamCount() > 0);
    }

    @DisplayName("a segment outside the array is rejected")
    @Test
    public void rejectsBadSegment() {
        MqDecoder mq = new MqDecoder();
        assertThrows(IllegalArgumentException.class, () -> mq.initDecoder(new byte[4], 2, 3));
        assertThrows(IllegalArgumentException.class, () -> mq.initRawDecoder(new byte[4], -1, 1));
    }
}
