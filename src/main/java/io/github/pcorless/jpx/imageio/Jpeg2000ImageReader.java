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
 * Provenance: ORIGINAL. Written against the javax.imageio ImageReader contract (JDK API
 * documentation) and docs/IMAGEIO-CONTRACT.md.
 */
package io.github.pcorless.jpx.imageio;

import io.github.pcorless.jpx.Jpeg2000Decoder;
import io.github.pcorless.jpx.Jpeg2000Headers;
import io.github.pcorless.jpx.Jpeg2000Image;
import io.github.pcorless.jpx.Jpeg2000Threads;
import io.github.pcorless.jpx.JpxException;
import io.github.pcorless.jpx.codestream.Codestream;
import io.github.pcorless.jpx.internal.Parallel;

import javax.imageio.IIOException;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.PixelInterleavedSampleModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;

/**
 * Reads JPEG 2000 (JP2 files and bare J2K codestreams) through ImageIO.
 * <ul>
 * <li>{@code read} returns every channel after the JP2 palette and channel definitions, in a
 * colour model built from the colr box (enumerated or ICC), with opacity as the last band.</li>
 * <li>{@code readRaster} returns the same samples in channel order, with no colour model.</li>
 * <li>Samples keep their depth (1-8 bits in bytes, 9-16 in shorts, more in ints); signed channels
 * are level-shifted by 2^(precision-1) into unsigned bands. Subsampled components are upsampled
 * (nearest neighbour) to the image grid.</li>
 * <li>Decodes use {@link Jpeg2000Threads#defaults()} unless a {@link Jpeg2000ImageReadParam}
 * says otherwise; the progress listener and abort are served on the calling thread.</li>
 * <li>Source subsampling by a power of two is met by discarding resolution levels, so it costs
 * less than a full decode; the rest is point-sampled. A source region decodes only the tiles it
 * meets.</li>
 * </ul>
 */
public final class Jpeg2000ImageReader extends ImageReader {

    private byte[] data;
    private Jpeg2000Headers headers;
    private Jpeg2000Layout layout;

    Jpeg2000ImageReader(ImageReaderSpi spi) {
        super(spi);
    }

    @Override
    public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
        super.setInput(input, seekForwardOnly, ignoreMetadata);
        release();
    }

    @Override
    public void reset() {
        super.reset();
        release();
    }

    @Override
    public void dispose() {
        release();
    }

    private void release() {
        data = null;
        headers = null;
        layout = null;
    }

    private void load() throws IOException {
        if (headers != null) return;
        if (!(input instanceof ImageInputStream)) throw new IllegalStateException("no input set");
        ImageInputStream in = (ImageInputStream) input;
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length() > 0 ? (int) Math.min(in.length(), 1 << 30) : 1 << 16);
        byte[] buf = new byte[1 << 16];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        data = out.toByteArray();
        headers = Jpeg2000Headers.read(data, 0, data.length);
        layout = new Jpeg2000Layout(headers);
        for (String w : headers.warnings()) processWarningOccurred(w);
        for (String w : layout.warnings) processWarningOccurred(w);
    }

    private void check(int imageIndex) throws IOException {
        if (imageIndex != 0) throw new IndexOutOfBoundsException("JPEG 2000 has one image, not " + imageIndex);
        load();
    }

    @Override
    public int getNumImages(boolean allowSearch) {
        if (input == null) throw new IllegalStateException("no input set");
        return 1;
    }

    @Override
    public int getWidth(int imageIndex) throws IOException {
        check(imageIndex);
        return headers.codestream.width();
    }

    @Override
    public int getHeight(int imageIndex) throws IOException {
        check(imageIndex);
        return headers.codestream.height();
    }

    @Override
    public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IOException {
        check(imageIndex);
        List<ImageTypeSpecifier> types = new ArrayList<>();
        types.add(layout.displayType());
        types.add(ImageTypeSpecifier.createFromBufferedImageType(
                layout.hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB));
        return types.iterator();
    }

    @Override
    public ImageTypeSpecifier getRawImageType(int imageIndex) throws IOException {
        check(imageIndex);
        return layout.displayType();
    }

    @Override
    public IIOMetadata getStreamMetadata() {
        return null;
    }

    @Override
    public IIOMetadata getImageMetadata(int imageIndex) throws IOException {
        check(imageIndex);
        return new Jpeg2000Metadata(headers, layout);
    }

    /** A {@link Jpeg2000ImageReadParam}, so callers can choose the decode's threads. */
    @Override
    public ImageReadParam getDefaultReadParam() {
        return new Jpeg2000ImageReadParam();
    }

    // tiles -------------------------------------------------------------------------------------

    @Override
    public boolean isImageTiled(int imageIndex) throws IOException {
        check(imageIndex);
        return headers.codestream.numTiles() > 1;
    }

    @Override
    public int getTileWidth(int imageIndex) throws IOException {
        return isImageTiled(imageIndex) ? headers.codestream.tileWidth : getWidth(imageIndex);
    }

    @Override
    public int getTileHeight(int imageIndex) throws IOException {
        return isImageTiled(imageIndex) ? headers.codestream.tileHeight : getHeight(imageIndex);
    }

    @Override
    public int getTileGridXOffset(int imageIndex) throws IOException {
        return isImageTiled(imageIndex) ? headers.codestream.tileX0 - headers.codestream.x0 : 0;
    }

    @Override
    public int getTileGridYOffset(int imageIndex) throws IOException {
        return isImageTiled(imageIndex) ? headers.codestream.tileY0 - headers.codestream.y0 : 0;
    }

    @Override
    public BufferedImage readTile(int imageIndex, int tileX, int tileY) throws IOException {
        return read(imageIndex, tileParam(imageIndex, tileX, tileY));
    }

    @Override
    public Raster readTileRaster(int imageIndex, int tileX, int tileY) throws IOException {
        return readRaster(imageIndex, tileParam(imageIndex, tileX, tileY));
    }

    private ImageReadParam tileParam(int imageIndex, int tileX, int tileY) throws IOException {
        check(imageIndex);
        Codestream cs = headers.codestream;
        if (tileX < 0 || tileY < 0 || tileX >= cs.tilesX || tileY >= cs.tilesY) {
            throw new IllegalArgumentException("no tile " + tileX + "," + tileY + " (" + cs.tilesX + "x" + cs.tilesY + ")");
        }
        long x0 = Math.max(cs.x0, (long) cs.tileX0 + (long) tileX * cs.tileWidth);
        long y0 = Math.max(cs.y0, (long) cs.tileY0 + (long) tileY * cs.tileHeight);
        long x1 = Math.min(cs.x1, (long) cs.tileX0 + (long) (tileX + 1) * cs.tileWidth);
        long y1 = Math.min(cs.y1, (long) cs.tileY0 + (long) (tileY + 1) * cs.tileHeight);
        ImageReadParam p = getDefaultReadParam();
        p.setSourceRegion(new Rectangle((int) (x0 - cs.x0), (int) (y0 - cs.y0), (int) (x1 - x0), (int) (y1 - y0)));
        return p;
    }

    // reading -----------------------------------------------------------------------------------

    @Override
    public boolean canReadRaster() {
        return true;
    }

    @Override
    public Raster readRaster(int imageIndex, ImageReadParam param) throws IOException {
        check(imageIndex);
        if (param == null) param = getDefaultReadParam();
        int w = headers.codestream.width(), h = headers.codestream.height();
        checkSize(param, w, h);
        Rectangle src = new Rectangle(), dst = new Rectangle();
        computeRegions(param, w, h, null, src, dst);
        int[] sourceBands = param.getSourceBands();
        int bands = sourceBands == null ? layout.channels : sourceBands.length;
        if (sourceBands != null) {
            for (int b : sourceBands) {
                if (b < 0 || b >= layout.channels) throw new IllegalArgumentException("no source band " + b);
            }
        }
        clearAbortRequest();
        processImageStarted(imageIndex);
        Decoded decoded = decode(param, src, dst.width, dst.height);
        int[] offsets = new int[bands];
        for (int b = 0; b < bands; b++) offsets[b] = b;
        WritableRaster raster = Raster.createWritableRaster(new PixelInterleavedSampleModel(layout.dataType,
                dst.width, dst.height, bands, dst.width * bands, offsets), null);
        int[] channels = new int[bands];
        for (int b = 0; b < bands; b++) channels[b] = sourceBands == null ? b : sourceBands[b];
        decoded.copy(channels, raster, 0, 0, offsets);
        finish();
        return raster;
    }

    @Override
    public BufferedImage read(int imageIndex, ImageReadParam param) throws IOException {
        check(imageIndex);
        if (param == null) param = getDefaultReadParam();
        int w = headers.codestream.width(), h = headers.codestream.height();
        checkSize(param, w, h);
        BufferedImage dest = getDestination(param, getImageTypes(imageIndex), w, h);
        Rectangle src = new Rectangle(), dst = new Rectangle();
        computeRegions(param, w, h, dest, src, dst);
        WritableRaster out = dest.getRaster();
        int[] sourceBands = param.getSourceBands(), destBands = param.getDestinationBands();
        boolean bandCopy = sourceBands != null || destBands != null || layout.colorModel.equals(dest.getColorModel());
        if (bandCopy) checkReadParamBandSettings(param, layout.channels, out.getNumBands());
        clearAbortRequest();
        processImageStarted(imageIndex);
        Decoded decoded = decode(param, src, dst.width, dst.height);
        if (bandCopy) {
            int n = sourceBands == null ? Math.min(layout.channels, out.getNumBands()) : sourceBands.length;
            int[] channels = new int[n], bands = new int[n];
            for (int i = 0; i < n; i++) {
                channels[i] = layout.displayOrder[sourceBands == null ? i : sourceBands[i]];
                bands[i] = destBands == null ? i : destBands[i];
            }
            decoded.copy(channels, out, dst.x, dst.y, bands);
        } else {
            // a destination of another type: convert through the image's own colour model
            BufferedImage tmp = image(decoded, dst.width, dst.height);
            int[] row = new int[dst.width];
            for (int y = 0; y < dst.height; y++) {
                tmp.getRGB(0, y, dst.width, 1, row, 0, dst.width);
                dest.setRGB(dst.x, dst.y + y, dst.width, 1, row, 0, dst.width);
            }
        }
        finish();
        return dest;
    }

    private static long maxSamples(ImageReadParam param) {
        long max = param instanceof Jpeg2000ImageReadParam ? ((Jpeg2000ImageReadParam) param).getMaxSamples() : 0;
        return max > 0 ? max : Jpeg2000Decoder.defaultMaxSamples();
    }

    /**
     * Refuses a read whose output would be over the sample limit before the destination is made: the
     * decoder checks its own buffers, but ImageIO's getDestination allocates first (and throws an
     * unchecked exception past 2^31 pixels).
     */
    private void checkSize(ImageReadParam param, int w, int h) throws IOException {
        Rectangle region = param.getSourceRegion();
        long rw = w, rh = h;
        if (region != null) {
            rw = Math.max(0, Math.min((long) w, (long) region.x + region.width) - Math.max(0, region.x));
            rh = Math.max(0, Math.min((long) h, (long) region.y + region.height) - Math.max(0, region.y));
        }
        long outW = (rw + param.getSourceXSubsampling() - 1) / param.getSourceXSubsampling();
        long outH = (rh + param.getSourceYSubsampling() - 1) / param.getSourceYSubsampling();
        long samples = outW * outH * layout.channels, max = maxSamples(param);
        if (samples > max) throw JpxException.tooLarge("image", samples, max);
    }

    private void finish() {
        if (abortRequested()) {
            processReadAborted();
        } else {
            processImageComplete();
        }
    }

    private BufferedImage image(Decoded decoded, int w, int h) throws JpxException {
        ColorModel cm = layout.colorModel;
        WritableRaster r = cm.createCompatibleWritableRaster(w, h);
        int[] bands = new int[layout.channels];
        for (int b = 0; b < bands.length; b++) bands[b] = b;
        decoded.copy(layout.displayOrder, r, 0, 0, bands);
        return new BufferedImage(cm, r, cm.isAlphaPremultiplied(), new Hashtable<>());
    }

    /**
     * Decodes the source region; {@link Decoded#copy} then writes its channels into bands as
     * {@code outW x outH} stored sample values, already subsampled.
     */
    private Decoded decode(ImageReadParam param, Rectangle src, int outW, int outH) throws IOException {
        int sx = param.getSourceXSubsampling(), sy = param.getSourceYSubsampling();
        // discard resolution levels for the power-of-two part of the subsampling, when the
        // region starts on that grid
        int reduce = 0, maxReduce = headers.maxReduce();
        while (reduce < maxReduce) {
            int f = 2 << reduce;
            if (sx % f != 0 || sy % f != 0 || src.x % f != 0 || src.y % f != 0) break;
            reduce++;
        }
        int rsx = sx >> reduce, rsy = sy >> reduce;
        Codestream cs = headers.codestream;
        long ax0 = (long) cs.x0 + src.x, ay0 = (long) cs.y0 + src.y;
        long ax1 = ax0 + src.width, ay1 = ay0 + src.height;
        Jpeg2000Threads threads = param instanceof Jpeg2000ImageReadParam
                ? ((Jpeg2000ImageReadParam) param).getThreads() : null;
        Jpeg2000Image image = Jpeg2000Decoder.decodeImage(headers, new Jpeg2000Decoder.Options()
                .reduce(reduce).area(ax0, ay0, ax1, ay1).threads(threads).maxSamples(maxSamples(param))
                .progress((done, total) -> {
                    processImageProgress(100f * done / total);
                    return !abortRequested();
                }));
        List<String> warnings = image.warnings();
        for (int i = headers.warnings().size(); i < warnings.size(); i++) processWarningOccurred(warnings.get(i));
        // the area on the reduced grid of an unsubsampled component
        int rw = ceilDivPow2(ax1, reduce) - ceilDivPow2(ax0, reduce);
        int rh = ceilDivPow2(ay1, reduce) - ceilDivPow2(ay0, reduce);
        return new Decoded(image.components.clone(), outW, outH, rsx, rsy, rw, rh,
                threads != null ? threads : Jpeg2000Threads.defaults());
    }

    /**
     * The decoded components, written into raster bands row by row. There used to be a full
     * {@code int[]} plane per channel between the two: 4 more bytes a sample, alive alongside the
     * decoder's own output and the destination, about 9 bytes a sample at the peak of an 8 bit read.
     * <p>
     * A destination with one data element per band (the byte and ushort interleaved rasters
     * ImageIO makes) gets whole pixel-interleaved rows through {@code setDataElements}, Java2D's
     * own fast path; anything else goes band by band through {@code setSamples}. The backing array
     * is never taken, which would make the image untrackable and lose Java2D's acceleration of it.
     * Rows are spread over the decode's threads.
     */
    private final class Decoded {
        /** Output rows per work item. */
        private static final int ROWS_PER_ITEM = 32;
        /** Smallest output (samples) worth spreading over threads. */
        private static final long PARALLEL_MIN_SAMPLES = 128 * 128 * 3;

        private final Jpeg2000Decoder.Component[] components;
        private final int outW, outH, rsx, rsy, rw, rh;
        private final Jpeg2000Threads threads;

        Decoded(Jpeg2000Decoder.Component[] components, int outW, int outH, int rsx, int rsy, int rw, int rh,
                Jpeg2000Threads threads) {
            this.components = components;
            this.outW = outW;
            this.outH = outH;
            this.rsx = rsx;
            this.rsy = rsy;
            this.rw = rw;
            this.rh = rh;
            this.threads = threads;
        }

        /** Writes {@code channels[i]} into band {@code bands[i]} of {@code r}, at {@code (x0, y0)}. */
        void copy(int[] channels, WritableRaster r, int x0, int y0, int[] bands) throws JpxException {
            int n = channels.length;
            // per channel: the component, the level shift of a signed one, and the column of each
            // output x (null when the component lines up with the output)
            Jpeg2000Decoder.Component[] k = new Jpeg2000Decoder.Component[n];
            int[] shift = new int[n];
            int[][] xs = new int[n][];
            for (int i = 0; i < n; i++) {
                int c = channels[i];
                k[i] = components[c];
                shift[i] = layout.stored(c, 0);
                if (!(rsx == 1 && k[i].width == rw && k[i].width == outW)) {
                    xs[i] = new int[outW];
                    for (int x = 0; x < outW; x++) xs[i][x] = index((long) x * rsx, k[i].width, rw);
                }
            }
            int transfer = r.getTransferType();
            boolean interleaved = r.getNumDataElements() == n && r.getNumBands() == n
                    && (transfer == DataBuffer.TYPE_BYTE || transfer == DataBuffer.TYPE_USHORT);
            for (int i = 0; interleaved && i < n; i++) interleaved = bands[i] == i;
            final boolean packed = interleaved;
            Jpeg2000Threads t = packed && Parallel.worthIt(threads, (long) outW * outH * n >= PARALLEL_MIN_SAMPLES)
                    ? threads : Jpeg2000Threads.NONE;
            Parallel.forEach(t, (outH + ROWS_PER_ITEM - 1) / ROWS_PER_ITEM, () -> {
                byte[] bytes = packed && transfer == DataBuffer.TYPE_BYTE ? new byte[outW * n] : null;
                short[] shorts = packed && transfer == DataBuffer.TYPE_USHORT ? new short[outW * n] : null;
                int[] row = packed ? null : new int[outW];
                return item -> {
                    for (int y = item * ROWS_PER_ITEM, e = Math.min(outH, y + ROWS_PER_ITEM); y < e; y++) {
                        for (int i = 0; i < n; i++) {
                            int[] samples = k[i].samples;
                            int from = index((long) y * rsy, k[i].height, rh) * k[i].width;
                            int add = shift[i];
                            int[] col = xs[i];
                            if (bytes != null) {
                                for (int x = 0, o = i; x < outW; x++, o += n) {
                                    bytes[o] = (byte) (samples[col == null ? from + x : from + col[x]] + add);
                                }
                            } else if (shorts != null) {
                                for (int x = 0, o = i; x < outW; x++, o += n) {
                                    shorts[o] = (short) (samples[col == null ? from + x : from + col[x]] + add);
                                }
                            } else {
                                for (int x = 0; x < outW; x++) {
                                    row[x] = samples[col == null ? from + x : from + col[x]] + add;
                                }
                                r.setSamples(x0, y0 + y, outW, 1, bands[i], row);
                            }
                        }
                        if (bytes != null) r.setDataElements(x0, y0 + y, outW, 1, bytes);
                        else if (shorts != null) r.setDataElements(x0, y0 + y, outW, 1, shorts);
                    }
                };
            });
            for (int c : channels) components[c] = null;
        }
    }

    /** Position {@code p} on a grid of {@code full} points, in a component of {@code size}. */
    private static int index(long p, int size, int full) {
        long i = size == full ? p : p * size / Math.max(1, full);
        return (int) Math.max(0, Math.min(size - 1, i));
    }

    private static int ceilDivPow2(long a, int b) {
        return (int) ((a + (1L << b) - 1) >> b);
    }
}
