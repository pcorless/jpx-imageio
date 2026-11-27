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
 * Registers {@link Jpeg2000ImageWriter} with ImageIO for raw codestreams (J2K): format names
 * {@code j2k}, suffixes {@code j2k} and {@code j2c}. JP2 files: {@link Jp2ImageWriterSpi}.
 */
public final class Jpeg2000ImageWriterSpi extends ImageWriterSpi {

    public Jpeg2000ImageWriterSpi() {
        super("jpx-imageio", Jpeg2000ImageReaderSpi.VERSION,
                new String[]{"j2k", "J2K"},
                new String[]{"j2k", "j2c"},
                null,
                "io.github.pcorless.jpx.imageio.Jpeg2000ImageWriter",
                new Class<?>[]{ImageOutputStream.class},
                new String[]{Jpeg2000ImageReaderSpi.class.getName()},
                false, null, null, null, null,
                false, null, null, null, null);
    }

    /** 8-bit grey or three-component images, indexed images, with or without (straight) alpha. */
    @Override
    public boolean canEncodeImage(ImageTypeSpecifier type) {
        return Jpeg2000WriterSource.bands(type.getColorModel(), type.getSampleModel(), null, new String[1]) != null;
    }

    @Override
    public ImageWriter createWriterInstance(Object extension) {
        return new Jpeg2000ImageWriter(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "JPEG 2000 codestream (J2K) writer, 8-bit grey or RGB, pure Java (jpx-imageio)";
    }

    /** Registers the writer by hand, for class loaders ImageIO's service scan doesn't see. */
    public static void register() {
        IIORegistry.getDefaultInstance().registerServiceProvider(new Jpeg2000ImageWriterSpi(), ImageWriterSpi.class);
    }
}
