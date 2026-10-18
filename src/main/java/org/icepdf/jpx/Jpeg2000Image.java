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
 * Provenance: ORIGINAL. The colour-space mapping follows opj_jp2_read_header (OpenJPEG jp2.c
 * @ 6c4a29b0), which is a plain table of T.800 Table I.10 EnumCS values.
 */
package org.icepdf.jpx;

import org.icepdf.jpx.jp2.Jp2Header;

import java.util.Collections;
import java.util.List;

/** A decoded JPEG 2000 image: its channels, and the JP2 header when it came from a JP2 file. */
public final class Jpeg2000Image {

    /** As OpenJPEG's OPJ_COLOR_SPACE. */
    public enum ColourSpace {
        /** A bare codestream: nothing says. */
        UNSPECIFIED,
        /** A JP2 colr box that isn't one of the enumerations below (or an ICC profile). */
        UNKNOWN,
        SRGB, GRAY, SYCC, EYCC, CMYK
    }

    public final Jpeg2000Decoder.Component[] components;
    /** Null for a bare codestream. */
    public final Jp2Header header;
    private final List<String> warnings;

    Jpeg2000Image(Jpeg2000Decoder.Component[] components, Jp2Header header, List<String> warnings) {
        this.components = components;
        this.header = header;
        this.warnings = Collections.unmodifiableList(warnings);
    }

    public ColourSpace colourSpace() {
        if (header == null) return ColourSpace.UNSPECIFIED;
        switch (header.enumeratedColourSpace()) {
            case 16: return ColourSpace.SRGB;
            case 17: return ColourSpace.GRAY;
            case 18: return ColourSpace.SYCC;
            case 24: return ColourSpace.EYCC;
            case 12: return ColourSpace.CMYK;
            default: return ColourSpace.UNKNOWN;
        }
    }

    /** The restricted ICC profile of the chosen colr box, or null. */
    public byte[] iccProfile() {
        return header == null ? null : header.iccProfile();
    }

    public List<String> warnings() {
        return warnings;
    }
}
