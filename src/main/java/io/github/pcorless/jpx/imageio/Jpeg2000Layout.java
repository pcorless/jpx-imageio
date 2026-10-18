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
 * Provenance: ORIGINAL. Which colour space a JP2's channels are read in follows T.800 I.5.3.3
 * (colr), I.5.3.6 (cdef); the guesses for bare codestreams are documented in DECISIONS.md D-19.
 */
package io.github.pcorless.jpx.imageio;

import io.github.pcorless.jpx.Jpeg2000Decoder.Component;
import io.github.pcorless.jpx.Jpeg2000Headers;
import io.github.pcorless.jpx.Jpeg2000Image;
import io.github.pcorless.jpx.JpxException;
import io.github.pcorless.jpx.codestream.Codestream;
import io.github.pcorless.jpx.jp2.Jp2Channels;
import io.github.pcorless.jpx.jp2.Jp2Header;

import javax.imageio.ImageTypeSpecifier;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.SampleModel;
import java.util.ArrayList;
import java.util.List;

/**
 * The channels a decode produces (after pclr/cmap/cdef) and how they become raster bands: their
 * depth, the colour model, and the band order of {@code read} (colour channels, then extra
 * channels, then opacity last, as ComponentColorModel needs).
 */
final class Jpeg2000Layout {

    final int channels;
    final int[] precision;
    final boolean[] signed;
    final int[] channelType;
    /** Raster data type for all bands. */
    final int dataType;
    /** Channel shown in each band of {@code read}. */
    final int[] displayOrder;
    final ColorModel colorModel;
    final String colourSpaceName;
    final List<String> warnings = new ArrayList<>();

    Jpeg2000Layout(Jpeg2000Headers h) throws JpxException {
        Component[] probe = probe(h);
        channels = probe.length;
        precision = new int[channels];
        signed = new boolean[channels];
        channelType = new int[channels];
        int maxPrec = 1;
        for (int c = 0; c < channels; c++) {
            precision[c] = Math.min(probe[c].precision, 32);
            signed[c] = probe[c].signed;
            channelType[c] = probe[c].channelType;
            maxPrec = Math.max(maxPrec, precision[c]);
        }
        dataType = maxPrec <= 8 ? DataBuffer.TYPE_BYTE : maxPrec <= 16 ? DataBuffer.TYPE_USHORT : DataBuffer.TYPE_INT;

        List<Integer> colour = new ArrayList<>(), extra = new ArrayList<>();
        int alpha = -1;
        boolean premultiplied = false;
        for (int c = 0; c < channels; c++) {
            int t = channelType[c];
            if (t == Jp2Header.CHANNEL_COLOUR) {
                colour.add(c);
            } else if ((t == Jp2Header.CHANNEL_OPACITY || t == Jp2Header.CHANNEL_PREMULTIPLIED_OPACITY) && alpha < 0) {
                alpha = c;
                premultiplied = t == Jp2Header.CHANNEL_PREMULTIPLIED_OPACITY;
            } else {
                extra.add(c);
            }
        }
        boolean hasCdef = h.jp2 != null && h.jp2.channelDefinitions != null;
        ColorSpace cs = null;
        String name = null;
        // the colour space the file declares
        if (h.jp2 != null) {
            Jp2Header.Colour colr = h.jp2.colour();
            int need;
            if (colr != null && colr.iccProfile != null) {
                try {
                    ICC_Profile p = ICC_Profile.getInstance(colr.iccProfile);
                    ColorSpace icc = new ICC_ColorSpace(p);
                    need = icc.getNumComponents();
                    if (fit(colour, extra, need, hasCdef)) {
                        cs = icc;
                        name = "ICC";
                    }
                } catch (RuntimeException e) {
                    warnings.add("ICC profile in colr box is unusable (" + e.getMessage() + "); colour space guessed");
                }
            } else if (colr != null && colr.method == 1) {
                Jpeg2000Image.ColourSpace e = Jpeg2000Image.colourSpaceOf(colr.enumeratedColourSpace);
                switch (e) {
                    case SRGB:
                        need = 3;
                        cs = ColorSpace.getInstance(ColorSpace.CS_sRGB);
                        name = "RGB";
                        break;
                    case GRAY:
                        need = 1;
                        cs = ColorSpace.getInstance(ColorSpace.CS_GRAY);
                        name = "GRAY";
                        break;
                    case CMYK:
                        need = 4;
                        cs = Jpeg2000ColorSpaces.CMYK;
                        name = "CMYK";
                        break;
                    case SYCC:
                    case EYCC:
                        need = 3;
                        cs = ColorSpace.getInstance(ColorSpace.CS_sRGB);
                        name = "YCbCr";
                        warnings.add("EnumCS " + colr.enumeratedColourSpace + " (YCC) samples are not converted to RGB");
                        break;
                    default:
                        need = -1;
                        warnings.add("EnumCS " + colr.enumeratedColourSpace + " is not supported; colour space guessed");
                }
                if (cs != null && !fit(colour, extra, need, hasCdef)) {
                    warnings.add("colr says " + name + " but there are " + colour.size() + " colour channels; colour space guessed");
                    cs = null;
                    name = null;
                }
            }
        }
        if (cs == null) {
            // D-19: guess by channel count. Without a cdef box, a 2-channel image is grey + alpha
            // and 4 channels are CMYK (as pdf.js and Acrobat treat a bare 4-component codestream).
            if (!hasCdef && colour.size() == 2) fit(colour, extra, 1, false);
            int n = colour.size();
            if (n == 1) {
                cs = ColorSpace.getInstance(ColorSpace.CS_GRAY);
                name = "GRAY";
            } else if (n == 3) {
                cs = ColorSpace.getInstance(ColorSpace.CS_sRGB);
                name = "RGB";
            } else if (n == 4) {
                cs = Jpeg2000ColorSpaces.CMYK;
                name = "CMYK";
            }
        }
        // the alpha a no-cdef image gets from fit(): the first extra channel
        if (alpha < 0 && !hasCdef && !extra.isEmpty() && cs != null) {
            alpha = extra.remove(0);
        }
        if (cs == null || !extra.isEmpty()) {
            // channels the colour space doesn't account for: keep every band, in a generic space
            int n = colour.size() + extra.size();
            if (cs != null) warnings.add(extra.size() + " channel(s) beyond the " + name + " colour channels; generic colour model");
            colour.addAll(extra);
            extra.clear();
            cs = Jpeg2000ColorSpaces.generic(n);
            name = clrName(n);
        }
        displayOrder = new int[channels];
        int k = 0;
        for (int c : colour) displayOrder[k++] = c;
        if (alpha >= 0) displayOrder[k++] = alpha;
        colourSpaceName = name;
        int[] bits = new int[channels];
        for (int b = 0; b < channels; b++) bits[b] = precision[displayOrder[b]];
        colorModel = new ComponentColorModel(cs, bits, alpha >= 0, premultiplied,
                alpha >= 0 ? Transparency.TRANSLUCENT : Transparency.OPAQUE, dataType);
    }

    /**
     * Makes the colour channels number {@code need} by moving surplus ones to the extras, when
     * there is no cdef to say what they are. With a cdef the count must already be right.
     */
    private static boolean fit(List<Integer> colour, List<Integer> extra, int need, boolean hasCdef) {
        if (need <= 0) return false;
        if (colour.size() == need) return true;
        if (hasCdef || colour.size() < need) return false;
        while (colour.size() > need) extra.add(0, colour.remove(colour.size() - 1));
        return true;
    }

    /** The decoded channels' shape, by running the JP2 channel step on 1x1 stand-ins. */
    private static Component[] probe(Jpeg2000Headers h) throws JpxException {
        Codestream cs = h.codestream;
        Component[] comps = new Component[cs.components.length];
        for (int c = 0; c < comps.length; c++) {
            comps[c] = new Component(1, 1, cs.components[c].precision, cs.components[c].signed, new int[1]);
        }
        return h.jp2 == null ? comps : Jp2Channels.apply(h.jp2, comps, new ArrayList<>());
    }

    /** The javax_imageio_1.0 ColorSpaceType for n generic channels: 2CLR .. FCLR. */
    static String clrName(int n) {
        return Integer.toHexString(Math.max(2, Math.min(n, 15))).toUpperCase(java.util.Locale.ROOT) + "CLR";
    }

    boolean hasAlpha() {
        return colorModel.hasAlpha();
    }

    /** Sample value stored in a band: signed channels are level-shifted to unsigned. */
    int stored(int channel, int value) {
        if (signed[channel]) value += 1 << (precision[channel] - 1);
        return value;
    }

    ImageTypeSpecifier displayType() {
        SampleModel sm = colorModel.createCompatibleSampleModel(1, 1);
        return new ImageTypeSpecifier(colorModel, sm);
    }
}
