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
 * Provenance: ORIGINAL. Oracle: the fixtures' reference components (libopenjp2 v2.5.4 via
 * jpxref.c, see fixtures/README.md).
 */
package org.icepdf.jpx;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class Jpeg2000DecoderTest {

    static byte[] fixture(String path) {
        try (InputStream in = Jpeg2000DecoderTest.class.getResourceAsStream("/fixtures/" + path)) {
            if (in == null) throw new IllegalArgumentException("no fixture " + path);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** references.tsv rows for .j2k fixtures: name, file, tag, components, reduce, region. */
    static List<String[]> references() {
        List<String[]> out = new ArrayList<>();
        for (String line : new String(fixture("references.tsv"), StandardCharsets.US_ASCII).split("\n")) {
            if (line.startsWith("#") || line.isEmpty()) continue;
            String[] f = line.split("\t", -1);
            if (f[1].endsWith(".j2k") && f[5].isEmpty()) out.add(f); // regions: not implemented yet
        }
        return out;
    }

    static int[] pgx(byte[] b, int[] dims) {
        int nl = 0;
        while (b[nl] != '\n') nl++;
        String[] h = new String(b, 0, nl, StandardCharsets.US_ASCII).trim().split("\\s+");
        boolean signed = "-".equals(h[2]);
        int prec = Integer.parseInt(h[3]);
        dims[0] = Integer.parseInt(h[4]);
        dims[1] = Integer.parseInt(h[5]);
        int n = prec <= 8 ? 1 : prec <= 16 ? 2 : 4;
        int[] s = new int[dims[0] * dims[1]];
        int p = nl + 1;
        for (int i = 0; i < s.length; i++) {
            int v = 0;
            for (int k = 0; k < n; k++) v = (v << 8) | (b[p++] & 0xff);
            if (signed && n < 4) v = (v << (32 - 8 * n)) >> (32 - 8 * n);
            s[i] = v;
        }
        return s;
    }

    @DisplayName("decodes exactly like OpenJPEG")
    @ParameterizedTest(name = "{0}.{2}")
    @MethodSource("references")
    public void matchesOpenJpeg(String name, String file, String tag, String comps, String reduce, String region, String kind)
            throws JpxException {
        byte[] data = fixture(file);
        Jpeg2000Decoder.Component[] out = Jpeg2000Decoder.decode(data, 0, data.length, Integer.parseInt(reduce));
        int n = Integer.parseInt(comps);
        assertEquals(n, out.length, "components");
        for (int c = 0; c < n; c++) {
            int[] dims = new int[2];
            int[] want = pgx(fixture("ref/" + name + "." + tag + "." + c + ".pgx"), dims);
            assertEquals(dims[0] + "x" + dims[1], out[c].width + "x" + out[c].height, "component " + c + " size");
            int bad = 0, first = -1;
            long max = 0;
            for (int i = 0; i < want.length; i++) {
                if (want[i] != out[c].samples[i]) {
                    if (first < 0) first = i;
                    bad++;
                    max = Math.max(max, Math.abs((long) want[i] - out[c].samples[i]));
                }
            }
            if (bad > 0) {
                fail(String.format("component %d: %d/%d samples differ (max %d); first at (%d,%d): want %d got %d",
                        c, bad, want.length, max, first % dims[0], first / dims[0], want[first], out[c].samples[first]));
            }
        }
    }
}
