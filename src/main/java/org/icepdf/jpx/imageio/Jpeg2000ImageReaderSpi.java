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
 * Provenance: ORIGINAL. Names, suffixes and MIME types per docs/IMAGEIO-CONTRACT.md; the
 * signatures are T.800 A.4.1 (SOC, SIZ) and I.5.1 (JP2 signature box).
 */
package org.icepdf.jpx.imageio;

import org.icepdf.jpx.Jpeg2000Headers;

import javax.imageio.ImageReader;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.spi.ServiceRegistry;
import javax.imageio.stream.ImageInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;

/** Registers {@link Jpeg2000ImageReader} with ImageIO. */
public final class Jpeg2000ImageReaderSpi extends ImageReaderSpi {

    static final String VERSION = "0.1.0";

    /** Other JPEG 2000 readers this one is ordered ahead of. */
    private static final String[] OUTRANKS = {
            "com.github.jaiimageio.jpeg2000.impl.J2KImageReaderSpi",
            "com.sun.media.imageioimpl.plugins.jpeg2000.J2KImageReaderSpi",
            "com.sun.media.imageioimpl.plugins.jpeg2000.J2KImageReaderCodecLibSpi",
    };

    public Jpeg2000ImageReaderSpi() {
        super("ICEpdf", VERSION,
                new String[]{"jpeg2000", "JPEG2000", "jpeg 2000", "JPEG 2000", "jp2", "JP2", "j2k", "J2K", "jpx", "JPX"},
                new String[]{"jp2", "j2k", "j2c", "jpx", "jpf"},
                new String[]{"image/jp2", "image/jpx", "image/jpeg2000"},
                "org.icepdf.jpx.imageio.Jpeg2000ImageReader",
                new Class<?>[]{ImageInputStream.class},
                null,
                false, null, null, null, null,
                true, Jpeg2000Metadata.NATIVE, Jpeg2000Metadata.Format.class.getName(), null, null);
    }

    @Override
    public boolean canDecodeInput(Object source) throws IOException {
        if (!(source instanceof ImageInputStream)) return false;
        ImageInputStream in = (ImageInputStream) source;
        byte[] b = new byte[12];
        in.mark();
        try {
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) break;
                n += r;
            }
            return Jpeg2000Headers.sniff(b, 0, n);
        } catch (EOFException e) {
            return false;
        } finally {
            in.reset();
        }
    }

    @Override
    public ImageReader createReaderInstance(Object extension) {
        return new Jpeg2000ImageReader(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "JPEG 2000 (JP2, J2K) reader, pure Java (ICEpdf)";
    }

    /**
     * Orders this reader ahead of the JPEG 2000 readers already registered. ImageIO tells a
     * provider only about its own registration, so a reader registered later (a jar further down
     * the class path) is not covered: see {@link #preferOverOtherReaders()} (D-20).
     */
    @Override
    public void onRegistration(ServiceRegistry registry, Class<?> category) {
        outrank(registry, category, this);
    }

    private static void outrank(ServiceRegistry registry, Class<?> category, Object self) {
        Iterator<?> it = registry.getServiceProviders(category, false);
        while (it.hasNext()) {
            Object other = it.next();
            for (String name : OUTRANKS) {
                if (other != self && other.getClass().getName().equals(name)) {
                    setOrdering(registry, category, self, other);
                }
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setOrdering(ServiceRegistry registry, Class category, Object first, Object second) {
        registry.setOrdering(category, first, second);
    }

    /**
     * Puts this reader ahead of every other registered JPEG 2000 reader (JJ2000 / JAI) in the
     * default registry, whichever was registered first. Call it after any ImageIO.scanForPlugins().
     */
    public static void preferOverOtherReaders() {
        IIORegistry registry = IIORegistry.getDefaultInstance();
        Jpeg2000ImageReaderSpi self = registry.getServiceProviderByClass(Jpeg2000ImageReaderSpi.class);
        if (self == null) {
            self = new Jpeg2000ImageReaderSpi();
            registry.registerServiceProvider(self, ImageReaderSpi.class);
        }
        outrank(registry, ImageReaderSpi.class, self);
    }

    /** Registers the reader by hand, for class loaders ImageIO's service scan doesn't see. */
    public static void register() {
        IIORegistry.getDefaultInstance().registerServiceProvider(new Jpeg2000ImageReaderSpi(), ImageReaderSpi.class);
    }
}
