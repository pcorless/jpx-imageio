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
 * Provenance: ORIGINAL. Oracle: src/test/resources/jp2mut/expected.tsv, libopenjp2 v2.5.4 via
 * jpxref (tools/vectors/jp2mut.py).
 */
package org.icepdf.jpx.jp2;

import org.icepdf.jpx.Jpeg2000Decoder;
import org.icepdf.jpx.Jpeg2000Image;
import org.icepdf.jpx.JpxException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

public class Jp2MutantTest {

    /** Mutants where the outcome deliberately differs from OpenJPEG: name -> decision. */
    static final Map<String, String> DIVERGENCES;

    static {
        Map<String, String> m = new HashMap<>();
        // D-15: nothing after the codestream is read; OpenJPEG's opj_end_decompress parses
        // trailing boxes and fails a fully decoded image on a malformed one.
        m.put("trailing-bad-colr", "D-15");
        // D-17 is invisible here: the PGX oracle keeps only the low 16 bits of the unextended
        // palette value, which equal our sign-extended one (pclr-wide-signed).
        DIVERGENCES = Collections.unmodifiableMap(m);
    }

    static byte[] resource(String path) {
        try (InputStream in = Jp2MutantTest.class.getResourceAsStream("/jp2mut/" + path)) {
            if (in == null) throw new IllegalArgumentException("no resource " + path);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<String[]> mutants() {
        List<String[]> out = new ArrayList<>();
        for (String line : new String(resource("expected.tsv"), StandardCharsets.UTF_8).split("\n")) {
            if (line.startsWith("#") || line.isEmpty()) continue;
            out.add(line.split("\t", -1));
        }
        return out;
    }

    static Jpeg2000Image decode(String name) throws JpxException {
        byte[] data = resource(name + ".jp2");
        return Jpeg2000Decoder.decodeImage(data, 0, data.length, 0);
    }

    /** As jp2mut.py digest(): per component w, h, prec (u32), sgnd (u8), then s32 samples. */
    static String digest(Jpeg2000Decoder.Component[] comps) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            for (Jpeg2000Decoder.Component c : comps) {
                ByteBuffer b = ByteBuffer.allocate(13 + 4 * c.samples.length);
                b.putInt(c.width).putInt(c.height).putInt(c.precision).put((byte) (c.signed ? 1 : 0));
                for (int v : c.samples) b.putInt(v);
                md.update(b.array());
            }
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @DisplayName("box-level mutants end as they do in OpenJPEG")
    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("mutants")
    public void matchesOpenJpeg(String name, String outcome, String comps, String sha1, String what) {
        String divergence = DIVERGENCES.get(name);
        Jpeg2000Image image;
        try {
            image = decode(name);
        } catch (JpxException e) {
            if (!"fail".equals(outcome)) fail(name + " (" + what + "): OpenJPEG decodes it, we threw: " + e.getMessage());
            if (divergence != null) fail(name + ": listed as divergence " + divergence + " but fails like OpenJPEG");
            return;
        }
        if ("fail".equals(outcome)) {
            if (divergence == null) fail(name + " (" + what + "): OpenJPEG fails it, we decoded it");
            return; // a recorded divergence: we decode it
        }
        assertEquals(Integer.parseInt(comps), image.components.length, "components");
        assertEquals(sha1, digest(image.components), "decoded components");
    }

    @Test
    public void channelTypesFollowCdef() throws JpxException {
        int[][] want = {
                {0, 0, 0, 1}, // cdef-ok: RGB + opacity
                {0, 0, 0, 2}, // cdef-premultiplied
                {0, 0, 0, 65535}, // cdef-unspecified
                {0, 0, 0, 1}, // cdef-opacity-first: OpenJPEG moves the opacity last, D-16 keeps its type
        };
        String[] names = {"cdef-ok", "cdef-premultiplied", "cdef-unspecified", "cdef-opacity-first"};
        for (int i = 0; i < names.length; i++) {
            Jpeg2000Decoder.Component[] c = decode(names[i]).components;
            int[] got = new int[c.length];
            for (int k = 0; k < c.length; k++) got[k] = c[k].channelType;
            assertArrayEquals(want[i], got, names[i]);
        }
    }

    @Test
    public void signedPaletteColumnsAreSignExtended() throws JpxException {
        // D-17: column 0 is 12-bit signed, entry j = 100 j - 800, stored as 16-bit two's complement
        Jpeg2000Decoder.Component c = decode("pclr-wide-signed").components[0];
        assertEquals(12, c.precision);
        boolean negative = false;
        for (int v : c.samples) {
            if (v < -2048 || v > 2047 || (v + 800) % 100 != 0) fail("12-bit signed sample " + v);
            negative |= v < 0;
        }
        assertEquals(true, negative, "some negative entries");
    }

    @Test
    public void colourSpecifications() throws JpxException {
        assertEquals(Jpeg2000Image.ColourSpace.SRGB, decode("ok-rgb").colourSpace());
        assertEquals(Jpeg2000Image.ColourSpace.UNKNOWN, decode("colr-missing").colourSpace());
        // METH 3 is ignored; the following enumerated GRAY box is the one used
        Jpeg2000Image meth3 = decode("colr-meth3-then-gray");
        assertEquals(Jpeg2000Image.ColourSpace.GRAY, meth3.colourSpace());
        assertEquals(2, meth3.header.colours().size());
        // a second colr is kept but not used
        Jpeg2000Image two = decode("colr-two");
        assertEquals(Jpeg2000Image.ColourSpace.SRGB, two.colourSpace());
        assertEquals(17, two.header.colours().get(1).enumeratedColourSpace);
        Jp2Header.Colour lab = decode("colr-cielab-35").header.colour();
        assertEquals(14, lab.enumeratedColourSpace);
        assertArrayEquals(new long[]{14, 0, 100, 0, 255, 128, 255, 128, 0x00443530L}, lab.cielab);
        assertNull(decode("ok-rgb").iccProfile());
    }

    @Test
    public void resolutionBox() throws JpxException {
        Jp2Header h = decode("res-ok").header;
        assertNotNull(h.captureResolution);
        assertEquals(30000.0, h.captureResolution.verticalPerMetre(), 1e-9);
        assertEquals(72.0, h.displayResolution.horizontalPerMetre(), 1e-9);
        assertNull(decode("ok-rgb").header.captureResolution);
    }
}
