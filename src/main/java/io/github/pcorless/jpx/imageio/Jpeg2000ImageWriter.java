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
 * Provenance: ORIGINAL (javax.imageio ImageWriter contract).
 */
package io.github.pcorless.jpx.imageio;

import io.github.pcorless.jpx.Jpeg2000Encoder;
import io.github.pcorless.jpx.Jpeg2000Threads;

import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Rectangle;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes an 8-bit grey or RGB image as a JP2 file or a raw JPEG 2000 codestream (J2K), through
 * {@link Jpeg2000Encoder}: lossless by default, lossy with an explicit quality below 1. A writer
 * from {@link Jp2ImageWriterSpi} writes JP2, one from {@link Jpeg2000ImageWriterSpi} J2K;
 * {@link Jpeg2000ImageWriteParam#setFormat} overrides it. See {@code docs/IMAGEIO-WRITER-CONTRACT.md}.
 * <ul>
 *   <li>Writes one image; no metadata, no thumbnails (they are left out with a warning).</li>
 *   <li>JP2: the colour space is sRGB, greyscale, or the image's own ICC profile when it is a
 *   grey TRC or RGB matrix/TRC one; any other colour space is refused (J2K takes it).</li>
 *   <li>{@link ImageWriteParam} source region, subsampling (with offsets) and source bands are
 *   honoured; destination offset and type don't apply to a codestream and are ignored.</li>
 *   <li>An alpha band is left out, with a warning; premultiplied alpha is refused. An indexed
 *   image is written as grey when its palette is grey, else as RGB.</li>
 *   <li>The progress listener and {@link #abort()} are served on the calling thread, between
 *   code-blocks; an aborted write writes nothing.</li>
 * </ul>
 */
public class Jpeg2000ImageWriter extends ImageWriter {

    public Jpeg2000ImageWriter(ImageWriterSpi spi) {
        super(spi);
    }

    @Override
    public ImageWriteParam getDefaultWriteParam() {
        return new Jpeg2000ImageWriteParam(getLocale());
    }

    @Override
    public IIOMetadata getDefaultStreamMetadata(ImageWriteParam param) {
        return null;
    }

    @Override
    public IIOMetadata getDefaultImageMetadata(ImageTypeSpecifier imageType, ImageWriteParam param) {
        return null;
    }

    @Override
    public IIOMetadata convertStreamMetadata(IIOMetadata inData, ImageWriteParam param) {
        return null;
    }

    @Override
    public IIOMetadata convertImageMetadata(IIOMetadata inData, ImageTypeSpecifier imageType, ImageWriteParam param) {
        return null;
    }

    @Override
    public boolean canWriteRasters() {
        return true;
    }

    @Override
    public void write(IIOMetadata streamMetadata, IIOImage image, ImageWriteParam param) throws IOException {
        Object out = getOutput();
        if (out == null) throw new IllegalStateException("output not set");
        if (image == null) throw new IllegalArgumentException("image is null");
        ImageOutputStream stream = (ImageOutputStream) out;
        clearAbortRequest();
        RenderedImage ri = image.hasRaster() ? null : image.getRenderedImage();
        Raster raster = image.hasRaster() ? image.getRaster() : null;
        Rectangle bounds = ri != null
                ? new Rectangle(ri.getMinX(), ri.getMinY(), ri.getWidth(), ri.getHeight())
                : raster.getBounds();

        Rectangle region = bounds;
        int xStep = 1, yStep = 1, xOff = 0, yOff = 0;
        int[] sourceBands = null;
        if (param != null) {
            if (param.getSourceRegion() != null) region = region.intersection(param.getSourceRegion());
            xStep = param.getSourceXSubsampling();
            yStep = param.getSourceYSubsampling();
            xOff = param.getSubsamplingXOffset();
            yOff = param.getSubsamplingYOffset();
            sourceBands = param.getSourceBands();
        }
        if (region.isEmpty()) throw new IllegalArgumentException("source region " + region + " misses the image " + bounds);
        String[] why = new String[1];
        Jpeg2000WriterSource src = Jpeg2000WriterSource.of(ri, raster, region, xStep, yStep, xOff, yOff, sourceBands, why);
        if (src == null) throw new IIOException(why[0]);

        if (image.getNumThumbnails() > 0) processWarningOccurred(0, "thumbnails are not written");
        if (image.getMetadata() != null || streamMetadata != null) processWarningOccurred(0, "metadata is not written");
        if (src.alphaDropped) processWarningOccurred(0, "the alpha band is not written (no alpha in a JPEG 2000 codestream here)");

        Jpeg2000Encoder.Options o = new Jpeg2000Encoder.Options();
        Jpeg2000Encoder.Format format = defaultFormat();
        Jpeg2000Threads threads = null;
        if (param instanceof Jpeg2000ImageWriteParam) {
            Jpeg2000ImageWriteParam jp = (Jpeg2000ImageWriteParam) param;
            o.quality(jp.effectiveQuality());
            if (jp.getLevels() >= 0) o.levels(jp.getLevels());
            threads = jp.getThreads();
            o.maxSamples(jp.getMaxSamples());
            if (jp.getFormat() != null) format = jp.getFormat();
        } else if (param != null && param.canWriteCompressed() && param.getCompressionMode() == ImageWriteParam.MODE_EXPLICIT) {
            o.quality(param.getCompressionQuality());
        }
        o.threads(threads != null ? threads : Jpeg2000Threads.defaults());
        o.format(format);
        if (format == Jpeg2000Encoder.Format.JP2) {
            if (src.jp2Refusal != null) throw new IIOException(src.jp2Refusal);
            o.iccProfile(src.jp2Icc);
        }
        o.progress((done, total) -> {
            processImageProgress(100f * done / total);
            return !abortRequested();
        });

        processImageStarted(0);
        boolean written = Jpeg2000Encoder.encode(src.encoderSource(), src.width, src.height, src.numComponents, o,
                new StreamAdapter(stream));
        if (!written) {
            processWriteAborted();
            return;
        }
        stream.flush();
        processImageComplete();
    }

    /** JP2 for a writer from {@link Jp2ImageWriterSpi}, else J2K. */
    private Jpeg2000Encoder.Format defaultFormat() {
        return originatingProvider instanceof Jp2ImageWriterSpi ? Jpeg2000Encoder.Format.JP2 : Jpeg2000Encoder.Format.J2K;
    }

    /** An OutputStream over the ImageOutputStream (which isn't one). */
    private static final class StreamAdapter extends OutputStream {
        private final ImageOutputStream out;

        StreamAdapter(ImageOutputStream out) {
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }
    }
}
