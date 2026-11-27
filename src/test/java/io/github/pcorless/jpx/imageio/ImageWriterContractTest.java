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
 * Provenance: ORIGINAL. The checks of docs/IMAGEIO-WRITER-CONTRACT.md.
 */
package io.github.pcorless.jpx.imageio;

import io.github.pcorless.jpx.Jpeg2000Threads;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.event.IIOWriteProgressListener;
import javax.imageio.event.IIOWriteWarningListener;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ImageWriterContractTest {

    static ImageWriter writer() {
        Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg2000");
        while (it.hasNext()) {
            ImageWriter w = it.next();
            if (w instanceof Jpeg2000ImageWriter) return w;
        }
        throw new AssertionError("no Jpeg2000ImageWriter registered");
    }

    /** A picture with gradients, shapes and noise, drawn into an image of {@code type}. */
    static BufferedImage picture(int type, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, type);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(250, 20, 40), w, h, new Color(10, 200, 240)));
        g.fillRect(0, 0, w, h);
        g.setColor(Color.YELLOW);
        g.fillOval(w / 4, h / 4, w / 2, h / 2);
        g.dispose();
        Random rnd = new Random(w * 31L + h + type);
        for (int i = 0; i < w * h / 20; i++) {
            img.setRGB(rnd.nextInt(w), rnd.nextInt(h), rnd.nextInt() | 0xff000000);
        }
        return img;
    }

    static byte[] write(BufferedImage img, ImageWriteParam param) throws IOException {
        ImageWriter w = writer();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            w.setOutput(out);
            w.write(null, new IIOImage(img, null, null), param);
        } finally {
            w.dispose();
        }
        return bytes.toByteArray();
    }

    static BufferedImage read(byte[] j2k) throws IOException {
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(j2k))) {
            ImageReader r = new Jpeg2000ImageReaderSpi().createReaderInstance(null);
            r.setInput(in);
            BufferedImage out = r.read(0);
            r.dispose();
            return out;
        }
    }

    static int[] rgb(BufferedImage img) {
        return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
    }

    /** Our writer for a lookup, and the SPI it came from. */
    static Class<?> spiOf(Iterator<ImageWriter> it) {
        while (it.hasNext()) {
            ImageWriter w = it.next();
            if (w instanceof Jpeg2000ImageWriter) return w.getOriginatingProvider().getClass();
        }
        return null;
    }

    @DisplayName("found by format name, suffix and MIME type: jp2/jpeg2000 write JP2, j2k/j2c codestreams; linked to the reader")
    @Test
    public void lookups() {
        for (String name : new String[]{"jp2", "JP2", "jpeg2000", "JPEG2000", "jpeg 2000"}) {
            assertSame(Jp2ImageWriterSpi.class, spiOf(ImageIO.getImageWritersByFormatName(name)), name);
        }
        for (String name : new String[]{"j2k", "J2K"}) {
            assertSame(Jpeg2000ImageWriterSpi.class, spiOf(ImageIO.getImageWritersByFormatName(name)), name);
        }
        assertSame(Jp2ImageWriterSpi.class, spiOf(ImageIO.getImageWritersBySuffix("jp2")));
        assertSame(Jp2ImageWriterSpi.class, spiOf(ImageIO.getImageWritersByMIMEType("image/jp2")));
        assertSame(Jpeg2000ImageWriterSpi.class, spiOf(ImageIO.getImageWritersBySuffix("j2k")));
        assertSame(Jpeg2000ImageWriterSpi.class, spiOf(ImageIO.getImageWritersBySuffix("j2c")));
        ImageWriter w = writer();
        ImageReader r = ImageIO.getImageReader(w);
        assertNotNull(r);
        assertSame(Jpeg2000ImageReader.class, r.getClass());
        assertSame(Jpeg2000ImageWriter.class, ImageIO.getImageWriter(r).getClass());
    }

    @DisplayName("canEncodeImage: 8-bit grey/RGB, indexed, straight alpha yes; premultiplied, 16-bit, 565, CMYK no")
    @Test
    public void canEncode() {
        Jpeg2000ImageWriterSpi spi = new Jpeg2000ImageWriterSpi();
        int[] yes = {BufferedImage.TYPE_BYTE_GRAY, BufferedImage.TYPE_3BYTE_BGR, BufferedImage.TYPE_INT_RGB,
                BufferedImage.TYPE_INT_BGR, BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_4BYTE_ABGR,
                BufferedImage.TYPE_BYTE_BINARY, BufferedImage.TYPE_BYTE_INDEXED};
        int[] no = {BufferedImage.TYPE_INT_ARGB_PRE, BufferedImage.TYPE_4BYTE_ABGR_PRE, BufferedImage.TYPE_USHORT_GRAY,
                BufferedImage.TYPE_USHORT_565_RGB, BufferedImage.TYPE_USHORT_555_RGB};
        for (int t : yes) assertTrue(spi.canEncodeImage(ImageTypeSpecifier.createFromBufferedImageType(t)), "type " + t);
        for (int t : no) assertFalse(spi.canEncodeImage(ImageTypeSpecifier.createFromBufferedImageType(t)), "type " + t);
        ComponentColorModel cmyk = new ComponentColorModel(new java.awt.color.ColorSpace(ColorSpace.TYPE_CMYK, 4) {
            private static final long serialVersionUID = 1L;
            public float[] toRGB(float[] v) { return new float[3]; }
            public float[] fromRGB(float[] v) { return new float[4]; }
            public float[] toCIEXYZ(float[] v) { return new float[3]; }
            public float[] fromCIEXYZ(float[] v) { return new float[4]; }
        }, false, false, java.awt.Transparency.OPAQUE, DataBuffer.TYPE_BYTE);
        assertFalse(spi.canEncodeImage(new ImageTypeSpecifier(cmyk, cmyk.createCompatibleSampleModel(1, 1))));
    }

    @DisplayName("lossless by default: every supported BufferedImage type reads back pixel for pixel")
    @Test
    public void losslessEveryType() throws IOException {
        int[] types = {BufferedImage.TYPE_BYTE_GRAY, BufferedImage.TYPE_3BYTE_BGR, BufferedImage.TYPE_INT_RGB,
                BufferedImage.TYPE_INT_BGR, BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_4BYTE_ABGR,
                BufferedImage.TYPE_BYTE_BINARY, BufferedImage.TYPE_BYTE_INDEXED};
        for (int t : types) {
            BufferedImage src = picture(t, 131, 77);
            BufferedImage back = read(write(src, null));
            assertEquals(src.getWidth(), back.getWidth());
            assertEquals(src.getHeight(), back.getHeight());
            int[] want = rgb(src);
            if (src.getColorModel().hasAlpha()) for (int i = 0; i < want.length; i++) want[i] |= 0xff000000;
            if (t == BufferedImage.TYPE_BYTE_GRAY) {
                // grey reads back as grey: compare the samples, not sRGB conversions of them
                assertArrayEquals(src.getRaster().getSamples(0, 0, 131, 77, 0, (int[]) null),
                        back.getRaster().getSamples(0, 0, 131, 77, 0, (int[]) null), "type " + t);
                continue;
            }
            if (t == BufferedImage.TYPE_BYTE_BINARY) {
                assertEquals(1, back.getRaster().getNumBands(), "a black and white palette is written as grey");
                int[] got = back.getRaster().getSamples(0, 0, 131, 77, 0, (int[]) null);
                for (int i = 0; i < got.length; i++) assertEquals(want[i] & 0xff, got[i], "binary at " + i);
                continue;
            }
            int[] got = rgb(back);
            assertArrayEquals(want, got, "type " + t);
        }
    }

    static boolean isJp2(byte[] b) {
        return b.length > 12 && b[3] == 12 && b[4] == 'j' && b[5] == 'P';
    }

    static boolean isJ2k(byte[] b) {
        return b.length > 2 && b[0] == (byte) 0xff && b[1] == (byte) 0x4f;
    }

    @DisplayName("ImageIO.write: jpeg2000 and jp2 give a JP2 file, j2k a codestream")
    @Test
    public void imageIoWrite() throws IOException {
        BufferedImage src = picture(BufferedImage.TYPE_INT_RGB, 64, 40);
        for (String name : new String[]{"jpeg2000", "jp2", "j2k"}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            assertTrue(ImageIO.write(src, name, bytes), name);
            byte[] b = bytes.toByteArray();
            assertTrue(name.equals("j2k") ? isJ2k(b) : isJp2(b), name);
            assertArrayEquals(rgb(src), rgb(ImageIO.read(new ByteArrayInputStream(b))), name);
        }
    }

    static byte[] writeWith(ImageWriter w, BufferedImage img, ImageWriteParam param) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            w.setOutput(out);
            w.write(null, new IIOImage(img, null, null), param);
        }
        return bytes.toByteArray();
    }

    static ImageWriter j2kWriter() {
        return new Jpeg2000ImageWriterSpi().createWriterInstance(null);
    }

    @DisplayName("JP2 colour: sRGB and grey enumerated, a matrix ICC profile written and read back, others refused")
    @Test
    public void jp2Colour() throws IOException {
        // sRGB and Java's grey: enumerated (method 1), and the reader's colour model says so
        BufferedImage rgbImg = picture(BufferedImage.TYPE_3BYTE_BGR, 50, 30);
        BufferedImage back = read(write(rgbImg, null));
        assertTrue(back.getColorModel().getColorSpace().isCS_sRGB());
        assertArrayEquals(rgb(rgbImg), rgb(back));
        BufferedImage grey = picture(BufferedImage.TYPE_BYTE_GRAY, 50, 30);
        BufferedImage greyBack = read(write(grey, null));
        assertEquals(ColorSpace.TYPE_GRAY, greyBack.getColorModel().getColorSpace().getType());
        assertArrayEquals(grey.getRaster().getSamples(0, 0, 50, 30, 0, (int[]) null),
                greyBack.getRaster().getSamples(0, 0, 50, 30, 0, (int[]) null));

        // linear RGB: a matrix/TRC ICC profile, written as method 2; the reader gives the same profile
        ColorSpace linear = ColorSpace.getInstance(ColorSpace.CS_LINEAR_RGB);
        ComponentColorModel cm = new ComponentColorModel(linear, false, false, java.awt.Transparency.OPAQUE, DataBuffer.TYPE_BYTE);
        WritableRaster r = cm.createCompatibleWritableRaster(40, 20);
        Random rnd = new Random(4);
        for (int y = 0; y < 20; y++) for (int x = 0; x < 40; x++) for (int b = 0; b < 3; b++) r.setSample(x, y, b, rnd.nextInt(256));
        BufferedImage lin = new BufferedImage(cm, r, false, null);
        assertTrue(new Jp2ImageWriterSpi().canEncodeImage(ImageTypeSpecifier.createFromRenderedImage(lin)));
        byte[] jp2 = write(lin, null);
        assertTrue(isJp2(jp2));
        // the colr box (method 2) holds the profile's bytes as they are
        byte[] profile = ((java.awt.color.ICC_ColorSpace) linear).getProfile().getData();
        int colr = 12 + 20 + 8 + 22;
        assertEquals(2, jp2[colr + 8], "colr method");
        assertArrayEquals(profile, java.util.Arrays.copyOfRange(jp2, colr + 11, colr + 11 + profile.length));
        // read back: an ICC colour space that converts as the original (Java's CMM re-serialises a
        // profile once linked, e.g. a 14-byte gamma curve unpadded, so the bytes are not compared)
        BufferedImage linBack = read(jp2);
        ColorSpace cs = linBack.getColorModel().getColorSpace();
        assertTrue(cs instanceof java.awt.color.ICC_ColorSpace, cs.toString());
        for (float[] v : new float[][]{{0, 0, 0}, {1, 1, 1}, {0.2f, 0.5f, 0.9f}, {0.7f, 0.1f, 0.3f}}) {
            float[] want = linear.toRGB(v), got = cs.toRGB(v);
            for (int i = 0; i < 3; i++) assertEquals(want[i], got[i], 0.01f, "toRGB " + java.util.Arrays.toString(v));
        }
        for (int b = 0; b < 3; b++) {
            assertArrayEquals(r.getSamples(0, 0, 40, 20, b, (int[]) null), linBack.getRaster().getSamples(0, 0, 40, 20, b, (int[]) null));
        }

        // PhotoYCC (a LUT profile): no JP2 colr box fits; refused for JP2, written as J2K
        ColorSpace pycc = ColorSpace.getInstance(ColorSpace.CS_PYCC);
        ComponentColorModel ycm = new ComponentColorModel(pycc, false, false, java.awt.Transparency.OPAQUE, DataBuffer.TYPE_BYTE);
        BufferedImage ycc = new BufferedImage(ycm, ycm.createCompatibleWritableRaster(8, 8), false, null);
        assertFalse(new Jp2ImageWriterSpi().canEncodeImage(ImageTypeSpecifier.createFromRenderedImage(ycc)));
        assertTrue(new Jpeg2000ImageWriterSpi().canEncodeImage(ImageTypeSpecifier.createFromRenderedImage(ycc)));
        IIOException e = assertThrows(IIOException.class, () -> write(ycc, null));
        assertTrue(e.getMessage().contains("J2K"), e.getMessage());
        assertTrue(isJ2k(writeWith(j2kWriter(), ycc, null)));
    }

    @DisplayName("setFormat overrides the writer's own format, both ways")
    @Test
    public void formatParam() throws IOException {
        BufferedImage src = picture(BufferedImage.TYPE_INT_RGB, 33, 17);
        Jpeg2000ImageWriteParam toJ2k = new Jpeg2000ImageWriteParam();
        toJ2k.setFormat(io.github.pcorless.jpx.Jpeg2000Encoder.Format.J2K);
        byte[] a = write(src, toJ2k);
        assertTrue(isJ2k(a));
        Jpeg2000ImageWriteParam toJp2 = new Jpeg2000ImageWriteParam();
        toJp2.setFormat(io.github.pcorless.jpx.Jpeg2000Encoder.Format.JP2);
        byte[] b = writeWith(j2kWriter(), src, toJp2);
        assertTrue(isJp2(b));
        assertTrue(isJ2k(writeWith(j2kWriter(), src, null)));
        assertArrayEquals(rgb(read(a)), rgb(read(b)));
        // the JP2 file is the codestream plus 85 bytes of boxes (signature, ftyp, jp2h, jp2c header)
        assertEquals(a.length + 12 + 20 + 45 + 8, b.length);
    }

    @DisplayName("params: explicit quality, source region, subsampling with offsets, source bands, levels")
    @Test
    public void params() throws IOException {
        BufferedImage src = picture(BufferedImage.TYPE_3BYTE_BGR, 200, 120);
        Jpeg2000ImageWriteParam p = (Jpeg2000ImageWriteParam) writer().getDefaultWriteParam();
        assertTrue(p.canWriteCompressed());
        assertThrows(IllegalStateException.class, p::isCompressionLossless);
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        assertTrue(p.isCompressionLossless());
        assertEquals(2, p.getCompressionQualityDescriptions().length);
        p.setCompressionQuality(0.6f);
        assertFalse(p.isCompressionLossless());
        byte[] lossy = write(src, p);
        byte[] lossless = write(src, null);
        assertTrue(lossy.length < lossless.length * 0.55, lossy.length + " vs " + lossless.length);
        BufferedImage back = read(lossy);
        double se = 0;
        int[] a = rgb(src), b = rgb(back);
        for (int i = 0; i < a.length; i++) {
            for (int s = 0; s < 24; s += 8) {
                int d = ((a[i] >> s) & 0xff) - ((b[i] >> s) & 0xff);
                se += d * d;
            }
        }
        double psnr = 10 * Math.log10(255.0 * 255 * a.length * 3 / se);
        assertTrue(psnr > 30, "PSNR " + psnr);

        // region, subsampling and offsets: the written image is exactly the picked pixels
        ImageWriteParam q = writer().getDefaultWriteParam();
        q.setSourceRegion(new Rectangle(13, 7, 150, 100));
        q.setSourceSubsampling(3, 2, 1, 1);
        BufferedImage sub = read(write(src, q));
        int w = (150 - 1 + 2) / 3, h = (100 - 1 + 1) / 2;
        assertEquals(w, sub.getWidth());
        assertEquals(h, sub.getHeight());
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                assertEquals(src.getRGB(13 + 1 + 3 * x, 7 + 1 + 2 * y), sub.getRGB(x, y), x + "," + y);
            }
        }

        // one source band: grey
        ImageWriteParam one = writer().getDefaultWriteParam();
        one.setSourceBands(new int[]{1});
        BufferedImage g = read(write(src, one));
        assertEquals(1, g.getRaster().getNumBands());
        assertArrayEquals(src.getRaster().getSamples(0, 0, 200, 120, 1, (int[]) null),
                g.getRaster().getSamples(0, 0, 200, 120, 0, (int[]) null));

        // levels and threads
        Jpeg2000ImageWriteParam lv = new Jpeg2000ImageWriteParam();
        lv.setLevels(0);
        lv.setThreads(Jpeg2000Threads.NONE);
        assertArrayEquals(rgb(src), rgb(read(write(src, lv))));
    }

    @DisplayName("a Raster writes its bands as they are")
    @Test
    public void raster() throws IOException {
        WritableRaster r = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, 50, 30, 3, null);
        Random rnd = new Random(1);
        for (int y = 0; y < 30; y++) for (int x = 0; x < 50; x++) for (int b = 0; b < 3; b++) r.setSample(x, y, b, rnd.nextInt(256));
        ImageWriter w = writer();
        assertTrue(w.canWriteRasters());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            w.setOutput(out);
            w.write(null, new IIOImage(r, null, null), null);
        }
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            ImageReader rd = new Jpeg2000ImageReaderSpi().createReaderInstance(null);
            rd.setInput(in);
            Raster back = rd.readRaster(0, null);
            for (int b = 0; b < 3; b++) {
                assertArrayEquals(r.getSamples(0, 0, 50, 30, b, (int[]) null), back.getSamples(0, 0, 50, 30, b, (int[]) null));
            }
        }
    }

    @DisplayName("listeners: progress, a warning for the dropped alpha, abort writes nothing")
    @Test
    public void listenersAndAbort() throws IOException {
        BufferedImage src = picture(BufferedImage.TYPE_INT_ARGB, 300, 300);
        ImageWriter w = writer();
        List<String> events = new ArrayList<>();
        w.addIIOWriteWarningListener((IIOWriteWarningListener) (source, imageIndex, warning) -> events.add("warning " + warning));
        Progress progress = new Progress(events, w, false);
        w.addIIOWriteProgressListener(progress);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            w.setOutput(out);
            w.write(src);
        }
        assertTrue(events.contains("started"), events.toString());
        assertTrue(events.contains("complete"), events.toString());
        assertTrue(events.stream().anyMatch(e -> e.startsWith("warning") && e.contains("alpha")), events.toString());
        assertTrue(progress.last > 99f, "progress " + progress.last);

        // abort at the first progress report: aborted, nothing written; the writer is reusable after
        events.clear();
        w.removeAllIIOWriteProgressListeners();
        w.addIIOWriteProgressListener(new Progress(events, w, true));
        ByteArrayOutputStream none = new ByteArrayOutputStream();
        try (ImageOutputStream out = new MemoryCacheImageOutputStream(none)) {
            w.setOutput(out);
            w.write(src);
        }
        assertTrue(events.contains("aborted"), events.toString());
        assertFalse(events.contains("complete"), events.toString());
        assertEquals(0, none.size());

        w.removeAllIIOWriteProgressListeners();
        ByteArrayOutputStream again = new ByteArrayOutputStream();
        try (ImageOutputStream out = new MemoryCacheImageOutputStream(again)) {
            w.setOutput(out);
            w.write(src);
        }
        assertArrayEquals(bytes.toByteArray(), again.toByteArray());
        w.dispose();
    }

    @DisplayName("errors: no output, no image, unsupported images")
    @Test
    public void errors() {
        ImageWriter w = writer();
        BufferedImage ok = picture(BufferedImage.TYPE_INT_RGB, 8, 8);
        assertThrows(IllegalStateException.class, () -> w.write(ok));
        w.setOutput(new MemoryCacheImageOutputStream(new ByteArrayOutputStream()));
        assertThrows(IllegalArgumentException.class, () -> w.write((IIOImage) null));
        assertThrows(IIOException.class, () -> w.write(new BufferedImage(8, 8, BufferedImage.TYPE_USHORT_GRAY)));
        assertThrows(IIOException.class, () -> w.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB_PRE)));
        ImageWriteParam two = w.getDefaultWriteParam();
        two.setSourceBands(new int[]{0, 1});
        assertThrows(IIOException.class, () -> w.write(null, new IIOImage(ok, null, null), two));
        Jpeg2000ImageWriteParam small = new Jpeg2000ImageWriteParam();
        small.setMaxSamples(8 * 8 * 3 - 1);
        ByteArrayOutputStream none = new ByteArrayOutputStream();
        w.setOutput(new MemoryCacheImageOutputStream(none));
        assertThrows(io.github.pcorless.jpx.JpxException.class, () -> w.write(null, new IIOImage(ok, null, null), small));
        assertEquals(0, none.size());
        assertNull(w.getDefaultStreamMetadata(null));
        assertNull(w.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(ok), null));
        IndexColorModel icm = new IndexColorModel(8, 2, new byte[]{0, (byte) 255}, new byte[]{0, 0}, new byte[]{0, 0});
        assertTrue(new Jpeg2000ImageWriterSpi().canEncodeImage(ImageTypeSpecifier.createFromRenderedImage(
                new BufferedImage(4, 4, BufferedImage.TYPE_BYTE_INDEXED, icm))));
    }

    static final class Progress implements IIOWriteProgressListener {
        final List<String> events;
        final ImageWriter writer;
        final boolean abort;
        float last;

        Progress(List<String> events, ImageWriter writer, boolean abort) {
            this.events = events;
            this.writer = writer;
            this.abort = abort;
        }

        public void imageStarted(ImageWriter source, int imageIndex) {
            events.add("started");
        }

        public void imageProgress(ImageWriter source, float percentageDone) {
            last = percentageDone;
            if (abort) writer.abort();
        }

        public void imageComplete(ImageWriter source) {
            events.add("complete");
        }

        public void thumbnailStarted(ImageWriter source, int imageIndex, int thumbnailIndex) {
        }

        public void thumbnailProgress(ImageWriter source, float percentageDone) {
        }

        public void thumbnailComplete(ImageWriter source) {
        }

        public void writeAborted(ImageWriter source) {
            events.add("aborted");
        }
    }
}
