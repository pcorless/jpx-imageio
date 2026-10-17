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
 * Provenance: ORIGINAL. Vectors: OpenJPEG's tag-tree and bit encoders via tools/vectors/tgtvec.c.
 */
package org.icepdf.jpx.tier2;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TagTreeTest {

    static final class Vector {
        int w, h, layers;
        int[] first, zero, raw;
        byte[] bytes;

        @Override
        public String toString() {
            return w + "x" + h + " layers=" + layers;
        }
    }

    static int[] ints(String line) {
        String s = line.substring(1).trim();
        if (s.isEmpty()) return new int[0];
        String[] f = s.split(" ");
        int[] out = new int[f.length];
        for (int i = 0; i < f.length; i++) out[i] = Integer.parseInt(f[i]);
        return out;
    }

    static List<Vector> vectors() throws IOException {
        String text;
        try (InputStream in = TagTreeTest.class.getResourceAsStream("/vectors/tgt.txt")) {
            text = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        }
        List<Vector> out = new ArrayList<>();
        Vector v = null;
        for (String line : text.split("\n")) {
            if (line.startsWith("T ")) {
                String[] f = line.split(" ");
                v = new Vector();
                v.w = Integer.parseInt(f[1]);
                v.h = Integer.parseInt(f[2]);
                v.layers = Integer.parseInt(f[3]);
                out.add(v);
            } else if (v != null && line.startsWith("I")) {
                v.first = ints(line);
            } else if (v != null && line.startsWith("Z")) {
                v.zero = ints(line);
            } else if (v != null && line.startsWith("R")) {
                v.raw = ints(line);
            } else if (v != null && line.startsWith("B ")) {
                String s = line.substring(2).trim();
                v.bytes = new byte[s.length() / 2];
                for (int i = 0; i < v.bytes.length; i++) {
                    v.bytes[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
                }
            }
        }
        return out;
    }

    @DisplayName("replays OpenJPEG's inclusion / zero bit-plane coding")
    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    public void decodesOpenJpegVector(Vector v) {
        TagTree incl = new TagTree(v.w, v.h);
        TagTree imsb = new TagTree(v.w, v.h);
        PacketBitReader bio = new PacketBitReader();
        bio.init(v.bytes, 0, v.bytes.length);
        int n = v.w * v.h;
        int[] included = new int[n];
        java.util.Arrays.fill(included, 999);
        int r = 0;
        for (int l = 0; l < v.layers; l++) {
            for (int i = 0; i < n; i++) {
                if (included[i] < l) continue;
                // packet header: inclusion is coded by the tag tree until a block is first included (B.10.4)
                boolean in = incl.decode(bio, i, l + 1);
                assertEquals(v.first[i] <= l, in, "leaf " + i + " layer " + l);
                if (in) {
                    included[i] = l;
                    // zero bit-planes (B.10.5): raise the threshold until the value is known
                    int t = 1;
                    while (!imsb.decode(bio, i, t)) t++;
                    assertEquals(v.zero[i], t - 1, "zero bit-planes of leaf " + i);
                    assertEquals(v.raw[r++], bio.read(3), "raw bits after leaf " + i);
                }
            }
        }
        assertEquals(v.raw.length, r);
        assertFalse(bio.overrun(), "read past the header");
    }

    @DisplayName("reading past the end yields zeros and reports overrun")
    @Test
    public void overrun() {
        PacketBitReader bio = new PacketBitReader();
        bio.init(new byte[]{(byte) 0xA5}, 0, 1);
        assertEquals(0xA5, bio.read(8));
        assertFalse(bio.overrun());
        assertEquals(0, bio.read(8));
        assertTrue(bio.overrun());
    }

    @DisplayName("after 0xFF the next byte carries 7 bits (bit stuffing)")
    @Test
    public void bitStuffing() {
        PacketBitReader bio = new PacketBitReader();
        bio.init(new byte[]{(byte) 0xFF, 0x7F, (byte) 0x80}, 0, 3);
        assertEquals(0xFF, bio.read(8));
        assertEquals(0x7F, bio.read(7)); // the stuffed MSB of 0x7F is skipped
        assertEquals(1, bio.readBit());
        assertEquals(3, bio.bytesRead());
    }

    @DisplayName("degenerate tree sizes are rejected")
    @Test
    public void rejectsEmptyTree() {
        assertThrows(IllegalArgumentException.class, () -> new TagTree(0, 3));
    }
}
