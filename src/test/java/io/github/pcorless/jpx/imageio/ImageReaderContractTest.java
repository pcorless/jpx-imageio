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
 * Provenance: ORIGINAL. The checks of docs/IMAGEIO-CONTRACT.md; every one must pass.
 */
package io.github.pcorless.jpx.imageio;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.event.IIOReadProgressListener;
import javax.imageio.event.IIOReadWarningListener;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ImageReaderContractTest {

    static final String RGB = "rev53-rgb.j2k";
    static final String JP2 = "jp2-rgb.jp2";
    static final String TILED = "tiles.j2k"; // 100x70, 32x32 tiles
    static final String PALETTE = "jp2-pclr-rgb.jp2";

    static byte[] bytes(String name) {
        return resource("/fixtures/" + name);
    }

    static byte[] resource(String name) {
        try (InputStream in = ImageReaderContractTest.class.getResourceAsStream(name)) {
            if (in == null) throw new IllegalArgumentException("no resource " + name);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static ImageInputStream stream(byte[] b) throws IOException {
        return ImageIO.createImageInputStream(new ByteArrayInputStream(b));
    }

    interface Check {
        void run(ImageReader r) throws Exception;
    }

    static void withReader(String fixture, Check check) throws Exception {
        try (ImageInputStream in = stream(bytes(fixture))) {
            Iterator<ImageReader> it = ImageIO.getImageReaders(in);
            assertTrue(it.hasNext(), "no reader claims " + fixture);
            ImageReader r = it.next();
            assertSame(Jpeg2000ImageReader.class, r.getClass(), "first reader for " + fixture);
            try {
                r.setInput(in, false, false);
                check.run(r);
            } finally {
                r.dispose();
            }
        }
    }

    static ImageReaderSpi spi() {
        return IIORegistry.getDefaultInstance().getServiceProviderByClass(Jpeg2000ImageReaderSpi.class);
    }

    static void hasAll(String[] have, String... want) {
        List<String> lower = Arrays.asList(have);
        for (String w : want) assertTrue(lower.contains(w), w + " missing from " + lower);
    }

    @Test
    public void spiNames() {
        assertNotNull(spi(), "registered through META-INF/services");
        hasAll(spi().getFormatNames(), "jpeg2000", "jpeg 2000", "jp2", "j2k", "jpx");
        hasAll(spi().getFileSuffixes(), "jp2", "j2k", "j2c", "jpx", "jpf");
        hasAll(spi().getMIMETypes(), "image/jp2", "image/jpx");
    }

    @Test
    public void lookups() {
        for (Iterator<ImageReader> it : Arrays.asList(ImageIO.getImageReadersByFormatName("jpeg2000"),
                ImageIO.getImageReadersBySuffix("jp2"), ImageIO.getImageReadersByMIMEType("image/jp2"))) {
            assertTrue(it.hasNext());
            assertSame(Jpeg2000ImageReader.class, it.next().getClass());
        }
    }

    static boolean sniff(byte[] data) throws IOException {
        try (ImageInputStream in = stream(data)) {
            long before = in.getStreamPosition();
            boolean got = spi().canDecodeInput(in);
            assertEquals(before, in.getStreamPosition(), "canDecodeInput moved the stream");
            return got;
        }
    }

    @Test
    public void sniffing() throws IOException {
        assertTrue(sniff(bytes(RGB)));
        assertTrue(sniff(bytes(JP2)));
        assertFalse(sniff(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13}));
        assertFalse(sniff(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1}));
        assertFalse(sniff(new byte[]{(byte) 0xFF, 0x4F}));
        assertFalse(sniff(new byte[0]));
    }

    @Test
    public void headersAndTypes() throws Exception {
        withReader(RGB, r -> {
            assertEquals(1, r.getNumImages(true));
            Iterator<ImageTypeSpecifier> it = r.getImageTypes(0);
            assertTrue(it.hasNext());
            assertNotNull(r.getRawImageType(0));
            assertTrue(r.canReadRaster());
            assertThrows(IndexOutOfBoundsException.class, () -> r.getWidth(1));
        });
        withReader(TILED, r -> {
            assertEquals("100x70", r.getWidth(0) + "x" + r.getHeight(0));
            assertEquals("true 32x32", r.isImageTiled(0) + " " + r.getTileWidth(0) + "x" + r.getTileHeight(0));
        });
    }

    @Test
    public void readRasterAndTiles() throws Exception {
        withReader(RGB, r -> {
            Raster raster = r.readRaster(0, null);
            assertEquals("64x64x3", raster.getWidth() + "x" + raster.getHeight() + "x" + raster.getNumBands());
        });
        withReader(TILED, r -> {
            BufferedImage t = r.readTile(0, 1, 1);
            assertEquals("32x32", t.getWidth() + "x" + t.getHeight());
            BufferedImage edge = r.readTile(0, 3, 2); // 100 = 3*32 + 4, 70 = 2*32 + 6
            assertEquals("4x6", edge.getWidth() + "x" + edge.getHeight());
            assertThrows(IllegalArgumentException.class, () -> r.readTile(0, 4, 0));
            // a tile holds the same pixels as the whole image there
            BufferedImage all = r.read(0);
            assertEquals(all.getRGB(32 + 5, 32 + 7), t.getRGB(5, 7));
        });
    }

    @Test
    public void reusableAfterRead() throws Exception {
        withReader(RGB, r -> {
            r.read(0);
            BufferedImage second = r.read(0);
            assertEquals("64x64", second.getWidth() + "x" + second.getHeight());
        });
    }

    @Test
    public void params() throws Exception {
        withReader(TILED, r -> {
            BufferedImage all = r.read(0);
            ImageReadParam p = r.getDefaultReadParam();
            p.setSourceRegion(new Rectangle(10, 20, 50, 30));
            BufferedImage i = r.read(0, p);
            assertEquals("50x30", i.getWidth() + "x" + i.getHeight());
            assertEquals(all.getRGB(10 + 49, 20 + 29), i.getRGB(49, 29));

            p = r.getDefaultReadParam();
            p.setSourceSubsampling(4, 4, 0, 0);
            i = r.read(0, p);
            assertEquals("25x18", i.getWidth() + "x" + i.getHeight());

            p = r.getDefaultReadParam();
            p.setSourceSubsampling(3, 3, 1, 2); // not a power of two: point-sampled
            i = r.read(0, p);
            assertEquals("33x23", i.getWidth() + "x" + i.getHeight());
            assertEquals(all.getRGB(1 + 3 * 10, 2 + 3 * 7), i.getRGB(10, 7));
        });
        withReader(RGB, r -> {
            ImageReadParam p = r.getDefaultReadParam();
            p.setSourceBands(new int[]{1});
            p.setDestinationBands(new int[]{0});
            Raster raster = r.readRaster(0, p);
            assertEquals(1, raster.getNumBands());
            assertEquals(r.readRaster(0, null).getSample(5, 6, 1), raster.getSample(5, 6, 0));
        });
        withReader(RGB, r -> {
            ImageReadParam p = r.getDefaultReadParam();
            BufferedImage dest = new BufferedImage(80, 80, BufferedImage.TYPE_INT_RGB);
            p.setDestination(dest);
            p.setDestinationOffset(new Point(8, 8));
            BufferedImage i = r.read(0, p);
            assertSame(dest, i);
            assertEquals(0, dest.getRGB(0, 0) & 0xFFFFFF); // getRGB of TYPE_INT_RGB always has alpha FF
            assertNotEquals(0, dest.getRGB(8, 8) & 0xFFFFFF);
            assertEquals(r.read(0).getRGB(3, 4), dest.getRGB(8 + 3, 8 + 4));
        });
    }

    static class Progress implements IIOReadProgressListener {
        public void sequenceStarted(ImageReader source, int minIndex) {
        }

        public void sequenceComplete(ImageReader source) {
        }

        public void imageStarted(ImageReader source, int imageIndex) {
        }

        public void imageProgress(ImageReader source, float percentageDone) {
        }

        public void imageComplete(ImageReader source) {
        }

        public void thumbnailStarted(ImageReader source, int imageIndex, int thumbnailIndex) {
        }

        public void thumbnailProgress(ImageReader source, float percentageDone) {
        }

        public void thumbnailComplete(ImageReader source) {
        }

        public void readAborted(ImageReader source) {
        }
    }

    @Test
    public void listeners() throws Exception {
        withReader(TILED, r -> {
            AtomicInteger started = new AtomicInteger(), completed = new AtomicInteger(), progress = new AtomicInteger();
            float[] last = {-1};
            boolean[] monotonic = {true};
            r.addIIOReadProgressListener(new Progress() {
                @Override
                public void imageStarted(ImageReader source, int imageIndex) {
                    started.incrementAndGet();
                }

                @Override
                public void imageProgress(ImageReader source, float percentageDone) {
                    progress.incrementAndGet();
                    monotonic[0] &= percentageDone >= last[0];
                    last[0] = percentageDone;
                }

                @Override
                public void imageComplete(ImageReader source) {
                    completed.incrementAndGet();
                }
            });
            r.read(0);
            assertEquals("1/1", started.get() + "/" + completed.get());
            // reported on the calling thread as tiles finish: with helper threads, fewer reports
            assertTrue(progress.get() >= 1, "progress reported");
            assertTrue(monotonic[0], "progress never goes back");
            assertEquals(100f, last[0], "progress ends at 100%");
        });
        withReader(TILED, r -> {
            AtomicInteger aborted = new AtomicInteger(), completed = new AtomicInteger();
            r.addIIOReadProgressListener(new Progress() {
                @Override
                public void imageStarted(ImageReader source, int imageIndex) {
                    source.abort();
                }

                @Override
                public void imageComplete(ImageReader source) {
                    completed.incrementAndGet();
                }

                @Override
                public void readAborted(ImageReader source) {
                    aborted.incrementAndGet();
                }
            });
            r.read(0);
            assertEquals("1/0", aborted.get() + "/" + completed.get());
        });
        // a recoverable oddity is a warning, not an exception
        try (ImageInputStream in = stream(resource("/jp2mut/ihdr-c-not-7.jp2"))) {
            ImageReader r = spi().createReaderInstance();
            AtomicInteger warnings = new AtomicInteger();
            IIOReadWarningListener l = (source, warning) -> warnings.incrementAndGet();
            r.addIIOReadWarningListener(l);
            r.setInput(in);
            assertEquals(64, r.read(0).getWidth());
            assertEquals(1, warnings.get(), "compression type warning");
        }
        // so is a broken cmap box (D-23): the palette is skipped and the indices come back as one band
        try (ImageInputStream in = stream(resource("/jp2mut/cmap-mtyp-2.jp2"))) {
            ImageReader r = spi().createReaderInstance();
            List<String> warnings = new ArrayList<>();
            r.addIIOReadWarningListener((source, warning) -> warnings.add(warning));
            r.setInput(in);
            assertEquals(1, r.read(0).getRaster().getNumBands());
            assertTrue(warnings.stream().anyMatch(w -> w.contains("cmap")), "cmap warning in " + warnings);
        }
    }

    /**
     * regress/icc-unlinkable.jp2 is jp2-icc-gray.jp2 with one byte of its ICC profile changed (found by
     * mutation fuzzing): the profile parses, but the CMM can't link it, which used to escape as an
     * unchecked CMMException from the colour model.
     */
    @Test
    public void unlinkableIccProfileIsGuessedAround() throws Exception {
        try (ImageInputStream in = stream(resource("/regress/icc-unlinkable.jp2"))) {
            ImageReader r = spi().createReaderInstance();
            AtomicInteger warnings = new AtomicInteger();
            r.addIIOReadWarningListener((source, warning) -> warnings.incrementAndGet());
            r.setInput(in);
            assertEquals(64, r.getWidth(0));
            assertEquals(64, r.read(0).getWidth());
            assertTrue(warnings.get() >= 1, "unusable ICC profile warning");
        }
    }

    @Test
    public void metadata() throws Exception {
        withReader(JP2, r -> {
            IIOMetadata m = r.getImageMetadata(0);
            assertNotNull(m);
            assertTrue(m.isStandardMetadataFormatSupported());
            assertNotNull(m.getAsTree("javax_imageio_1.0"));
            assertNotNull(m.getAsTree(m.getNativeMetadataFormatName()));
            assertNotNull(m.getMetadataFormat(m.getNativeMetadataFormatName()));
            assertEquals(null, r.getStreamMetadata());
        });
    }

    @Test
    public void plainImageIoRead() throws Exception {
        BufferedImage i = ImageIO.read(new ByteArrayInputStream(bytes(JP2)));
        assertNotNull(i);
        assertEquals("64x64", i.getWidth() + "x" + i.getHeight());
        BufferedImage p = ImageIO.read(new ByteArrayInputStream(bytes(PALETTE)));
        assertNotNull(p);
        assertEquals(3, p.getRaster().getNumBands(), "palette applied");
    }

    @Test
    public void notJpeg2000() {
        assertThrows(IOException.class, () -> {
            try (ImageInputStream in = stream(new byte[]{0, 0, 0, 12, 'j', 'P', ' ', ' ', 13, 10, (byte) 0x87, 10, 0})) {
                ImageReader r = spi().createReaderInstance();
                r.setInput(in);
                r.read(0);
            }
        });
    }
}
