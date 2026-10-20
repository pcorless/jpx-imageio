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
package io.github.pcorless.jpx;

import io.github.pcorless.jpx.imageio.Jpeg2000ImageReadParam;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sample limit (DECISIONS.md D-22): a header can declare billions of samples in a few hundred bytes,
 * so a decode over the limit is refused before anything is allocated, with a JpxException rather than an
 * OutOfMemoryError.
 */
public class SampleLimitTest {

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = SampleLimitTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertNotNull(in, name);
            return in.readAllBytes();
        }
    }

    /** rev53-gray.j2k with SIZ rewritten to declare one {@code size} x {@code size} tile. */
    private static byte[] bomb(int size) throws IOException {
        byte[] d = fixture("rev53-gray.j2k");
        int siz = 2; // SOC, then SIZ
        assertEquals((byte) 0x51, d[siz + 1]);
        ByteBuffer b = ByteBuffer.wrap(d);
        b.putInt(siz + 6, size).putInt(siz + 10, size).putInt(siz + 14, 0).putInt(siz + 18, 0);
        b.putInt(siz + 22, size).putInt(siz + 26, size);
        return d;
    }

    private static void assertTooLarge(JpxException e) {
        assertTrue(e.isUnsupported(), e.getMessage());
        assertTrue(e.getMessage().contains(Jpeg2000Decoder.MAX_SAMPLES_PROPERTY), e.getMessage());
    }

    @DisplayName("a 200 byte header declaring 1.6 billion samples is refused, not allocated")
    @Test
    public void bombIsRefused() throws IOException {
        byte[] d = bomb(40000);
        assertTrue(d.length < 300, "the bomb is " + d.length + " bytes");
        assertTooLarge(assertThrows(JpxException.class, () -> Jpeg2000Decoder.decodeImage(d, 0, d.length, 0)));
    }

    @DisplayName("the limit counts the tile decoded whole, not only the area asked for")
    @Test
    public void tileIsCounted() throws IOException {
        byte[] d = bomb(40000);
        Jpeg2000Headers h = Jpeg2000Headers.read(d, 0, d.length);
        JpxException e = assertThrows(JpxException.class, () -> Jpeg2000Decoder.decodeImage(h,
                new Jpeg2000Decoder.Options().area(0, 0, 16, 16)));
        assertTooLarge(e);
        assertTrue(e.getMessage().startsWith("tile"), e.getMessage());
    }

    @DisplayName("Options.maxSamples lowers the limit; the default decodes an ordinary image")
    @Test
    public void optionsLimit() throws IOException {
        byte[] d = fixture("rev53-gray.j2k");
        Jpeg2000Headers h = Jpeg2000Headers.read(d, 0, d.length);
        assertTooLarge(assertThrows(JpxException.class, () -> Jpeg2000Decoder.decodeImage(h,
                new Jpeg2000Decoder.Options().maxSamples(64 * 64 - 1))));
        assertEquals(64, Jpeg2000Decoder.decodeImage(h, new Jpeg2000Decoder.Options().maxSamples(64 * 64))
                .components[0].width);
        assertEquals(64, Jpeg2000Decoder.decodeImage(d, 0, d.length, 0).components[0].width);
    }

    @DisplayName("the ImageIO reader refuses before ImageIO makes the destination")
    @Test
    public void readerLimit() throws IOException {
        for (byte[] d : new byte[][]{bomb(40000), bomb(60000)}) {
            try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(d))) {
                ImageReader r = ImageIO.getImageReaders(in).next();
                r.setInput(in);
                // 60000^2 is past 2^31 pixels, where getDestination itself would throw unchecked
                assertTooLarge(assertThrows(JpxException.class, () -> r.read(0)));
                assertTooLarge(assertThrows(JpxException.class, () -> r.readRaster(0, null)));
                r.dispose();
            }
        }
        byte[] d = fixture("rev53-gray.j2k");
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(d))) {
            ImageReader r = ImageIO.getImageReaders(in).next();
            r.setInput(in);
            Jpeg2000ImageReadParam p = (Jpeg2000ImageReadParam) r.getDefaultReadParam();
            p.setMaxSamples(100);
            assertTooLarge(assertThrows(JpxException.class, () -> r.read(0, p)));
            p.setSourceSubsampling(8, 8, 0, 0);
            assertEquals(8, r.read(0, p).getWidth(), "8x8 is under 100 samples");
            r.dispose();
        }
    }
}
