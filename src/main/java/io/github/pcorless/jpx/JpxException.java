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

import java.io.IOException;

/**
 * A JPEG 2000 stream that can't be decoded: malformed, truncated past recovery, using a feature
 * this decoder doesn't implement, or larger than the sample limit ({@link #isUnsupported()} for
 * the last two). The only exception the decoder throws for bad input; the ImageIO reader lets it
 * through as the IOException its methods declare.
 */
public class JpxException extends IOException {

    private static final long serialVersionUID = 1L;

    private final boolean unsupported;

    public JpxException(String message) {
        this(message, false);
    }

    public JpxException(String message, boolean unsupported) {
        super(message);
        this.unsupported = unsupported;
    }

    public static JpxException unsupported(String feature) {
        return new JpxException("unsupported JPEG 2000 feature: " + feature, true);
    }

    /**
     * A decode refused because it would hold more samples than {@code limit}: the stream may be valid,
     * but a few hundred bytes can declare an image of billions of samples (a decompression bomb).
     */
    public static JpxException tooLarge(String what, long samples, long limit) {
        return new JpxException(what + " of " + samples + " samples is over the limit of " + limit
                + " (" + Jpeg2000Decoder.MAX_SAMPLES_PROPERTY + ")", true);
    }

    /** True when the stream may be valid but uses something this decoder doesn't implement, or is too large. */
    public boolean isUnsupported() {
        return unsupported;
    }
}
