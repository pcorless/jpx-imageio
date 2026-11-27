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
 * Provenance: ORIGINAL (javax.imageio / java.awt.image raster access).
 */
package io.github.pcorless.jpx.imageio;

import io.github.pcorless.jpx.Jpeg2000Encoder;

import java.awt.Rectangle;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBuffer;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.SampleModel;
import java.awt.image.SinglePixelPackedSampleModel;

/**
 * What the writer encodes from an image or raster: which bands, how rows are read, and the
 * reason when it can't. Rows are read through {@link Raster#getDataElements} (one call per row)
 * for byte-interleaved and int-packed rasters, the ones Java2D makes; other rasters go through
 * {@link Raster#getSamples}. The backing array is never taken, so a host's image keeps its
 * Java2D acceleration.
 */
final class Jpeg2000WriterSource {

    /** How rows are read. */
    private enum Path { BYTES, PACKED, INDEXED, SAMPLES }

    final int width;
    final int height;
    /** 1 (grey) or 3 (RGB, or whatever three components the raster holds). */
    final int numComponents;
    /** True when an alpha band is left out. */
    final boolean alphaDropped;
    /** For JP2: the ICC profile to write, or null for the enumerated sRGB / greyscale space. */
    final byte[] jp2Icc;
    /** For JP2: why the colour space can't be described, or null when it can. */
    final String jp2Refusal;

    private final Raster raster;
    private final int x0, y0, xStep, yStep;
    /** Raster bands written, one per component. */
    private final int[] bands;
    private final Path path;
    private final int[] bitOffsets;
    private final byte[][] palette;

    private Jpeg2000WriterSource(Raster raster, int x0, int y0, int width, int height, int xStep, int yStep,
                                 int[] bands, Path path, byte[][] palette, boolean alphaDropped,
                                 byte[] jp2Icc, String jp2Refusal) {
        this.raster = raster;
        this.x0 = x0;
        this.y0 = y0;
        this.width = width;
        this.height = height;
        this.xStep = xStep;
        this.yStep = yStep;
        this.bands = bands;
        this.numComponents = palette != null ? palette.length : bands.length;
        this.path = path;
        this.palette = palette;
        this.alphaDropped = alphaDropped;
        this.jp2Icc = jp2Icc;
        this.jp2Refusal = jp2Refusal;
        SampleModel sm = raster.getSampleModel();
        this.bitOffsets = sm instanceof SinglePixelPackedSampleModel ? ((SinglePixelPackedSampleModel) sm).getBitOffsets() : null;
    }

    /**
     * The bands to write for {@code cm}/{@code sm} (cm may be null for a raster), or a reason
     * they can't be written in {@code why[0]}. {@code sourceBands} overrides the choice.
     */
    static int[] bands(ColorModel cm, SampleModel sm, int[] sourceBands, String[] why) {
        int[] bands;
        if (sourceBands != null) {
            bands = sourceBands.clone();
        } else if (cm instanceof IndexColorModel) {
            if (sm.getNumBands() != 1) return fail(why, "an indexed image with " + sm.getNumBands() + " bands");
            return new int[]{0};
        } else if (cm != null) {
            if (cm.hasAlpha() && cm.isAlphaPremultiplied()) return fail(why, "premultiplied alpha");
            bands = new int[cm.getNumColorComponents()];
            for (int i = 0; i < bands.length; i++) bands[i] = i; // Java colour models put alpha last
        } else {
            bands = new int[sm.getNumBands()];
            for (int i = 0; i < bands.length; i++) bands[i] = i;
        }
        if (bands.length != 1 && bands.length != 3) {
            return fail(why, bands.length + " colour components (only grey or three components)");
        }
        for (int b : bands) {
            if (b < 0 || b >= sm.getNumBands()) return fail(why, "source band " + b + " of " + sm.getNumBands());
            if (sm.getSampleSize(b) != 8) return fail(why, sm.getSampleSize(b) + "-bit samples (only 8-bit)");
        }
        return bands;
    }

    private static int[] fail(String[] why, String reason) {
        why[0] = "can't write " + reason;
        return null;
    }

    /**
     * The source for {@code image} or {@code raster} (one is null), cropped to {@code region}
     * (already within bounds) and subsampled; {@code why[0]} gets the reason when it's null.
     */
    static Jpeg2000WriterSource of(RenderedImage image, Raster raster, Rectangle region, int xStep, int yStep,
                                   int xOffset, int yOffset, int[] sourceBands, String[] why) {
        ColorModel cm = image != null ? image.getColorModel() : null;
        Raster r = raster;
        if (image instanceof BufferedImage) {
            r = ((BufferedImage) image).getRaster();
        } else if (image != null) {
            r = image.getNumXTiles() == 1 && image.getNumYTiles() == 1
                    ? image.getTile(image.getMinTileX(), image.getMinTileY()) : image.getData(region);
        }
        int[] bands = bands(cm, r.getSampleModel(), sourceBands, why);
        if (bands == null) return null;
        int x0 = region.x + xOffset, y0 = region.y + yOffset;
        int w = (region.width - xOffset + xStep - 1) / xStep;
        int h = (region.height - yOffset + yStep - 1) / yStep;
        if (w <= 0 || h <= 0) {
            why[0] = "nothing to write: the subsampled region is empty";
            return null;
        }
        byte[][] palette = null;
        boolean alphaDropped = false;
        Path path;
        SampleModel sm = r.getSampleModel();
        if (sourceBands == null && cm instanceof IndexColorModel) {
            palette = palette((IndexColorModel) cm);
            alphaDropped = cm.hasAlpha() && !opaque((IndexColorModel) cm);
            path = Path.INDEXED;
        } else {
            alphaDropped = sourceBands == null && cm != null && cm.hasAlpha();
            if (sm instanceof ComponentSampleModel && sm.getTransferType() == DataBuffer.TYPE_BYTE) {
                path = Path.BYTES;
            } else if (sm instanceof SinglePixelPackedSampleModel && sm.getTransferType() == DataBuffer.TYPE_INT) {
                path = Path.PACKED;
            } else {
                path = Path.SAMPLES;
            }
        }
        int nc = palette != null ? palette.length : bands.length;
        byte[][] icc = new byte[1][];
        String refusal = jp2Colour(cm, sourceBands, nc, icc);
        return new Jpeg2000WriterSource(r, x0, y0, w, h, xStep, yStep, bands, path, palette, alphaDropped, icc[0], refusal);
    }

    /**
     * How a JP2 colr box describes the components (T.800 I.5.3.3): null with {@code icc[0]} null
     * for the enumerated sRGB or greyscale space, null with {@code icc[0]} set for a restricted ICC
     * profile, or the reason neither fits. A raster, picked source bands or a palette expanded to
     * grey/RGB are taken as sRGB / greyscale.
     */
    static String jp2Colour(ColorModel cm, int[] sourceBands, int numComponents, byte[][] icc) {
        if (cm == null || sourceBands != null || cm instanceof IndexColorModel) return null;
        ColorSpace cs = cm.getColorSpace();
        if (numComponents == 3 && cs.isCS_sRGB()) return null;
        if (numComponents == 1 && cs == ColorSpace.getInstance(ColorSpace.CS_GRAY)) return null;
        int want = numComponents == 1 ? ColorSpace.TYPE_GRAY : ColorSpace.TYPE_RGB;
        if (cs.getType() == want && cs instanceof ICC_ColorSpace) {
            ICC_Profile p = ((ICC_ColorSpace) cs).getProfile();
            if (restricted(p, numComponents)) {
                icc[0] = p.getData();
                return null;
            }
            return "can't write this colour space in a JP2 file: its ICC profile isn't a grey TRC or RGB "
                    + "matrix/TRC one (restricted ICC, T.800 I.5.3.3); write J2K, or convert the image to sRGB";
        }
        return "can't write a colour space of type " + cs.getType() + " with " + numComponents
                + " component(s) in a JP2 file (grey or RGB only); write J2K, or convert the image to sRGB";
    }

    /** A monochrome (grey TRC) or three-component matrix-based (colorants and TRCs) profile. */
    private static boolean restricted(ICC_Profile p, int numComponents) {
        if (numComponents == 1) return p.getData(ICC_Profile.icSigGrayTRCTag) != null;
        int[] tags = {ICC_Profile.icSigRedColorantTag, ICC_Profile.icSigGreenColorantTag, ICC_Profile.icSigBlueColorantTag,
                ICC_Profile.icSigRedTRCTag, ICC_Profile.icSigGreenTRCTag, ICC_Profile.icSigBlueTRCTag};
        for (int t : tags) if (p.getData(t) == null) return false;
        return true;
    }

    /** One array per component (grey if every entry is grey, else R, G, B). */
    private static byte[][] palette(IndexColorModel icm) {
        int n = icm.getMapSize();
        byte[] r = new byte[n], g = new byte[n], b = new byte[n];
        icm.getReds(r);
        icm.getGreens(g);
        icm.getBlues(b);
        boolean grey = true;
        for (int i = 0; i < n && grey; i++) grey = r[i] == g[i] && g[i] == b[i];
        return grey ? new byte[][]{r} : new byte[][]{r, g, b};
    }

    private static boolean opaque(IndexColorModel icm) {
        byte[] a = new byte[icm.getMapSize()];
        icm.getAlphas(a);
        for (byte v : a) if (v != (byte) 0xff) return false;
        return true;
    }

    /** Readers for the encoder, one per thread. */
    Jpeg2000Encoder.Source encoderSource() {
        return RowReader::new;
    }

    private final class RowReader implements Jpeg2000Encoder.RowReader {
        /** The source row: every pixel of the span the subsampled row covers. */
        private final int span = (width - 1) * xStep + 1;
        private byte[] bytes;
        private int[] ints;

        @Override
        public void read(int y, int[] dest) {
            int sy = y0 + y * yStep;
            int nc = numComponents;
            switch (path) {
                case BYTES: {
                    int nb = raster.getNumDataElements();
                    bytes = (byte[]) raster.getDataElements(x0, sy, span, 1, bytes);
                    for (int i = 0, s = 0; i < width; i++, s += xStep * nb) {
                        for (int c = 0; c < nc; c++) dest[i * nc + c] = bytes[s + bands[c]] & 0xff;
                    }
                    break;
                }
                case PACKED: {
                    ints = (int[]) raster.getDataElements(x0, sy, span, 1, ints);
                    for (int i = 0, s = 0; i < width; i++, s += xStep) {
                        int p = ints[s];
                        for (int c = 0; c < nc; c++) dest[i * nc + c] = (p >>> bitOffsets[bands[c]]) & 0xff;
                    }
                    break;
                }
                case INDEXED: {
                    ints = raster.getSamples(x0, sy, span, 1, 0, ints);
                    for (int i = 0, s = 0; i < width; i++, s += xStep) {
                        int idx = ints[s];
                        for (int c = 0; c < nc; c++) {
                            byte[] p = palette[c];
                            dest[i * nc + c] = idx < p.length ? p[idx] & 0xff : 0;
                        }
                    }
                    break;
                }
                default: {
                    for (int c = 0; c < nc; c++) {
                        ints = raster.getSamples(x0, sy, span, 1, bands[c], ints);
                        for (int i = 0, s = 0; i < width; i++, s += xStep) dest[i * nc + c] = ints[s] & 0xff;
                    }
                    break;
                }
            }
        }
    }
}
