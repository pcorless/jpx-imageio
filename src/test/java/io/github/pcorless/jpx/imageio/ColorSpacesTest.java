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
 * Provenance: ORIGINAL.
 */
package io.github.pcorless.jpx.imageio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The colour spaces the JRE lacks (Jpeg2000ColorSpaces). They exist so CMYK and n-channel images can be
 * BufferedImages, not for colour accuracy, so these pin the textbook formulas and that a read image's
 * getRGB goes through them.
 */
public class ColorSpacesTest {

    private static final float EPS = 1e-6f;

    @DisplayName("CMYK: uncalibrated conversion both ways")
    @Test
    public void cmyk() {
        ColorSpace cs = Jpeg2000ColorSpaces.CMYK;
        assertEquals(ColorSpace.TYPE_CMYK, cs.getType());
        assertEquals(4, cs.getNumComponents());
        assertArrayEquals(new float[]{1, 1, 1}, cs.toRGB(new float[]{0, 0, 0, 0}), EPS, "no ink is white");
        assertArrayEquals(new float[]{0, 0, 0}, cs.toRGB(new float[]{0, 0, 0, 1}), EPS, "K is black");
        assertArrayEquals(new float[]{0, 1, 1}, cs.toRGB(new float[]{1, 0, 0, 0}), EPS, "C is cyan");
        assertArrayEquals(new float[]{0.25f, 0.5f, 0.5f}, cs.toRGB(new float[]{0.5f, 0, 0, 0.5f}), EPS);
        assertArrayEquals(new float[]{0, 0, 0, 1}, cs.fromRGB(new float[]{0, 0, 0}), EPS, "black is all K");
        float[] rgb = {0.2f, 0.6f, 0.9f};
        assertArrayEquals(rgb, cs.toRGB(cs.fromRGB(rgb)), 1e-5f, "round trip");
        assertArrayEquals(ColorSpace.getInstance(ColorSpace.CS_sRGB).toCIEXYZ(rgb),
                cs.toCIEXYZ(cs.fromRGB(rgb)), 1e-4f, "XYZ goes through sRGB");
        assertArrayEquals(rgb, cs.toRGB(cs.fromCIEXYZ(ColorSpace.getInstance(ColorSpace.CS_sRGB).toCIEXYZ(rgb))),
                1e-2f);
    }

    @DisplayName("n channels: the first three read as RGB, or the first as grey")
    @Test
    public void generic() {
        ColorSpace two = Jpeg2000ColorSpaces.generic(2);
        assertEquals(ColorSpace.TYPE_2CLR, two.getType());
        assertArrayEquals(new float[]{0.3f, 0.3f, 0.3f}, two.toRGB(new float[]{0.3f, 0.9f}), EPS);
        assertArrayEquals(new float[]{0.5f, 0}, two.fromRGB(new float[]{0.2f, 0.5f, 0.8f}), EPS);

        ColorSpace five = Jpeg2000ColorSpaces.generic(5);
        assertEquals(ColorSpace.TYPE_5CLR, five.getType());
        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f}, five.toRGB(new float[]{0.1f, 0.2f, 0.3f, 0.4f, 0.5f}), EPS);
        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f, 0, 0}, five.fromRGB(new float[]{0.1f, 0.2f, 0.3f}), EPS);
        float[] rgb = {0.2f, 0.6f, 0.9f};
        assertArrayEquals(rgb, five.toRGB(five.fromCIEXYZ(five.toCIEXYZ(five.fromRGB(rgb)))), 1e-2f);

        assertEquals(ColorSpace.TYPE_FCLR, Jpeg2000ColorSpaces.generic(16).getType(), "past 15 channels");
    }

    @DisplayName("a bare 4-channel codestream reads as CMYK, and its getRGB goes through the CMYK conversion")
    @Test
    public void cmykImage() throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(ImageReaderContractTest.bytes("rev53-cmyk.j2k")));
        assertEquals(ColorSpace.TYPE_CMYK, image.getColorModel().getColorSpace().getType());
        Raster r = image.getRaster();
        int max = (1 << image.getColorModel().getComponentSize(0)) - 1;
        int checked = 0;
        for (int y = 0; y < image.getHeight(); y += 7) {
            for (int x = 0; x < image.getWidth(); x += 7) {
                float[] cmyk = new float[4];
                for (int b = 0; b < 4; b++) cmyk[b] = r.getSample(x, y, b) / (float) max;
                float[] want = Jpeg2000ColorSpaces.CMYK.toRGB(cmyk);
                int rgb = image.getRGB(x, y);
                // ComponentColorModel rounds through its own sRGB lookup: within a couple of levels
                assertEquals(want[0] * 255, (rgb >> 16) & 255, 2.5, "red at " + x + "," + y);
                assertEquals(want[1] * 255, (rgb >> 8) & 255, 2.5, "green at " + x + "," + y);
                assertEquals(want[2] * 255, rgb & 255, 2.5, "blue at " + x + "," + y);
                checked++;
            }
        }
        assertTrue(checked > 10);
    }
}
