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
 * Provenance: ORIGINAL. The CMYK conversion is the textbook uncalibrated one (no ICC profile
 * ships with the JRE); it exists so a BufferedImage can be built, not for colour accuracy.
 */
package io.github.pcorless.jpx.imageio;

import java.awt.color.ColorSpace;

/** Colour spaces the JRE lacks, for building BufferedImages of CMYK and n-channel data. */
final class Jpeg2000ColorSpaces {

    private Jpeg2000ColorSpaces() {
    }

    private static final ColorSpace SRGB = ColorSpace.getInstance(ColorSpace.CS_sRGB);

    static final ColorSpace CMYK = new Cmyk();

    /** An n-channel space: the first three channels read as RGB, or the first as grey. */
    static ColorSpace generic(int n) {
        return new Generic(n);
    }

    private static final class Cmyk extends ColorSpace {
        private static final long serialVersionUID = 1L;

        Cmyk() {
            super(TYPE_CMYK, 4);
        }

        @Override
        public float[] toRGB(float[] v) {
            float k = 1 - v[3];
            return new float[]{(1 - v[0]) * k, (1 - v[1]) * k, (1 - v[2]) * k};
        }

        @Override
        public float[] fromRGB(float[] rgb) {
            float k = 1 - Math.max(rgb[0], Math.max(rgb[1], rgb[2]));
            if (k >= 1) return new float[]{0, 0, 0, 1};
            float d = 1 - k;
            return new float[]{(1 - rgb[0] - k) / d, (1 - rgb[1] - k) / d, (1 - rgb[2] - k) / d, k};
        }

        @Override
        public float[] toCIEXYZ(float[] v) {
            return SRGB.toCIEXYZ(toRGB(v));
        }

        @Override
        public float[] fromCIEXYZ(float[] xyz) {
            return fromRGB(SRGB.fromCIEXYZ(xyz));
        }
    }

    private static final class Generic extends ColorSpace {
        private static final long serialVersionUID = 1L;

        Generic(int n) {
            super(n >= 2 && n <= 15 ? TYPE_2CLR + n - 2 : TYPE_FCLR, n);
        }

        @Override
        public float[] toRGB(float[] v) {
            return getNumComponents() >= 3 ? new float[]{v[0], v[1], v[2]} : new float[]{v[0], v[0], v[0]};
        }

        @Override
        public float[] fromRGB(float[] rgb) {
            float[] v = new float[getNumComponents()];
            if (v.length >= 3) {
                System.arraycopy(rgb, 0, v, 0, 3);
            } else {
                v[0] = (rgb[0] + rgb[1] + rgb[2]) / 3;
            }
            return v;
        }

        @Override
        public float[] toCIEXYZ(float[] v) {
            return SRGB.toCIEXYZ(toRGB(v));
        }

        @Override
        public float[] fromCIEXYZ(float[] xyz) {
            return fromRGB(SRGB.fromCIEXYZ(xyz));
        }
    }
}
