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
 * Provenance: ORIGINAL (javax.imageio SPI contract).
 */
package io.github.pcorless.jpx.imageio;

import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;
import java.util.Locale;

/**
 * Registers {@link Jpeg2000ImageWriter} with ImageIO for JP2 files: format names {@code jp2} and
 * {@code jpeg2000}, suffix {@code jp2}, MIME type {@code image/jp2}. Its writers write JP2 unless a
 * {@link Jpeg2000ImageWriteParam} asks for a raw codestream ({@link Jpeg2000ImageWriterSpi}'s default).
 */
public final class Jp2ImageWriterSpi extends ImageWriterSpi {

    public Jp2ImageWriterSpi() {
        super("jpx-imageio", Jpeg2000ImageReaderSpi.VERSION,
                new String[]{"jp2", "JP2", "jpeg2000", "JPEG2000", "jpeg 2000", "JPEG 2000"},
                new String[]{"jp2"},
                new String[]{"image/jp2"},
                "io.github.pcorless.jpx.imageio.Jpeg2000ImageWriter",
                new Class<?>[]{ImageOutputStream.class},
                new String[]{Jpeg2000ImageReaderSpi.class.getName()},
                false, null, null, null, null,
                false, null, null, null, null);
    }

    /** As {@link Jpeg2000ImageWriterSpi}, and the colour space must fit a JP2 colr box (sRGB, grey or a restricted ICC profile). */
    @Override
    public boolean canEncodeImage(ImageTypeSpecifier type) {
        int[] bands = Jpeg2000WriterSource.bands(type.getColorModel(), type.getSampleModel(), null, new String[1]);
        return bands != null && Jpeg2000WriterSource.jp2Colour(type.getColorModel(), null, bands.length, new byte[1][]) == null;
    }

    @Override
    public ImageWriter createWriterInstance(Object extension) {
        return new Jpeg2000ImageWriter(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "JPEG 2000 JP2 file writer, 8-bit grey or RGB, pure Java (jpx-imageio)";
    }

    /** Registers the writer by hand, for class loaders ImageIO's service scan doesn't see. */
    public static void register() {
        IIORegistry.getDefaultInstance().registerServiceProvider(new Jp2ImageWriterSpi(), ImageWriterSpi.class);
    }
}
