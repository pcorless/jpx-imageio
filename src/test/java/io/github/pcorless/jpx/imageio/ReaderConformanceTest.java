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
 * jpxref.c), read through ImageIO.
 */
package io.github.pcorless.jpx.imageio;

import io.github.pcorless.jpx.Jpeg2000DecoderTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Rectangle;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class ReaderConformanceTest {

    static List<String[]> references() {
        return Jpeg2000DecoderTest.references();
    }

    static ImageReader reader(String file, ImageInputStream in) throws java.io.IOException {
        ImageReader r = new Jpeg2000ImageReaderSpi().createReaderInstance();
        r.setInput(in, true, true);
        return r;
    }

    static ImageReadParam param(ImageReader r, String reduce, String region) {
        ImageReadParam p = r.getDefaultReadParam();
        int s = 1 << Integer.parseInt(reduce);
        if (s > 1) p.setSourceSubsampling(s, s, 0, 0);
        if (!region.isEmpty()) {
            String[] f = region.split(",");
            int x0 = Integer.parseInt(f[0]), y0 = Integer.parseInt(f[1]);
            p.setSourceRegion(new Rectangle(x0, y0, Integer.parseInt(f[2]) - x0, Integer.parseInt(f[3]) - y0));
        }
        return p;
    }

    @ParameterizedTest(name = "{0}.{2}")
    @MethodSource("references")
    public void readRasterMatchesOpenJpeg(String name, String file, String tag, String comps, String reduce,
                                          String region, String kind) throws Exception {
        byte[] data = Jpeg2000DecoderTest.fixture(file);
        try (ImageInputStream in = ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(data))) {
            ImageReader r = reader(file, in);
            ImageReadParam p = param(r, reduce, region);
            Raster raster = r.readRaster(0, p);
            BufferedImage image = r.read(0, p);
            int n = Integer.parseInt(comps);
            assertEquals(n, raster.getNumBands(), "bands");
            Jpeg2000Layout layout = new Jpeg2000Layout(io.github.pcorless.jpx.Jpeg2000Headers.read(data, 0, data.length));
            for (int c = 0; c < n; c++) {
                int[] d = new int[4];
                int[] want = Jpeg2000DecoderTest.pgx(Jpeg2000DecoderTest.fixture("ref/" + name + "." + tag + "." + c + ".pgx"), d);
                int w = raster.getWidth(), h = raster.getHeight();
                if (c == 0) assertEquals(d[0] + "x" + d[1], w + "x" + h, "image size (component 0)");
                int shift = d[3] == 1 ? 1 << (d[2] - 1) : 0;
                int band = -1;
                for (int b = 0; b < n; b++) if (layout.displayOrder[b] == c) band = b;
                int bad = 0;
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        // a subsampled component is upsampled by nearest neighbour
                        int cx = Math.min(d[0] - 1, (int) ((long) x * d[0] / w));
                        int cy = Math.min(d[1] - 1, (int) ((long) y * d[1] / h));
                        int expect = want[cy * d[0] + cx] + shift;
                        if (raster.getSample(x, y, c) != expect) bad++;
                        if (image.getRaster().getSample(x, y, band) != expect) bad++;
                    }
                }
                if (bad > 0) fail("component " + c + ": " + bad + " samples differ");
            }
        }
    }

    static BufferedImage read(String file) throws Exception {
        byte[] data = Jpeg2000DecoderTest.fixture(file);
        try (ImageInputStream in = ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(data))) {
            return reader(file, in).read(0);
        }
    }

    @Test
    public void colourModels() throws Exception {
        assertEquals(ColorSpace.TYPE_RGB, read("jp2-rgb.jp2").getColorModel().getColorSpace().getType());
        assertEquals(ColorSpace.TYPE_GRAY, read("jp2-gray.jp2").getColorModel().getColorSpace().getType());
        // despite its name, opj_compress wrote EnumCS 16 (sRGB) and no cdef: RGB plus an alpha (D-19)
        BufferedImage jp2Cmyk = read("jp2-cmyk.jp2");
        assertEquals(ColorSpace.TYPE_RGB, jp2Cmyk.getColorModel().getColorSpace().getType());
        assertTrue(jp2Cmyk.getColorModel().hasAlpha());
        assertEquals(ColorSpace.TYPE_CMYK, read("rev53-cmyk.j2k").getColorModel().getColorSpace().getType());
        assertEquals(ColorSpace.TYPE_RGB, read("jp2-pclr-rgb.jp2").getColorModel().getColorSpace().getType());
        BufferedImage icc = read("jp2-icc-gray.jp2");
        assertTrue(icc.getColorModel().getColorSpace() instanceof ICC_ColorSpace, "ICC profile used");
        BufferedImage rgba = read("jp2-cdef-rgba.jp2");
        assertTrue(rgba.getColorModel().hasAlpha(), "cdef opacity");
        assertFalse(rgba.getColorModel().isAlphaPremultiplied());
        assertEquals(12, read("prec12-gray.j2k").getColorModel().getComponentSize(0));
        assertEquals(java.awt.image.DataBuffer.TYPE_USHORT, read("prec16-gray.j2k").getRaster().getTransferType());
    }
}
