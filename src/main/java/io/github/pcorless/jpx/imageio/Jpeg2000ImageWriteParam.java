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
package io.github.pcorless.jpx.imageio;

import io.github.pcorless.jpx.Jpeg2000Encoder;
import io.github.pcorless.jpx.Jpeg2000Threads;

import javax.imageio.ImageWriteParam;
import java.util.Locale;

/**
 * ImageWriteParam for {@link Jpeg2000ImageWriter}. Compression is always on: lossless unless the
 * mode is {@link #MODE_EXPLICIT} with a quality below 1, which writes lossy (9/7) with step
 * sizes from {@link Jpeg2000Encoder#baseStep(float)}. Also the decomposition levels and threads.
 */
public class Jpeg2000ImageWriteParam extends ImageWriteParam {

    private Jpeg2000Threads threads;
    private int levels = -1;

    public Jpeg2000ImageWriteParam() {
        this(Locale.getDefault());
    }

    public Jpeg2000ImageWriteParam(Locale locale) {
        super(locale);
        canWriteCompressed = true;
        compressionMode = MODE_DEFAULT;
        compressionQuality = 1f;
    }

    /** True unless the mode is explicit with a quality below 1. */
    @Override
    public boolean isCompressionLossless() {
        if (getCompressionMode() != MODE_EXPLICIT) throw new IllegalStateException("compression mode is not MODE_EXPLICIT");
        return getCompressionQuality() >= 1f;
    }

    @Override
    public String[] getCompressionQualityDescriptions() {
        super.getCompressionQualityDescriptions(); // the mode checks
        return new String[]{"lossy (9/7 wavelet): lower is smaller and coarser", "lossless (5/3 wavelet)"};
    }

    @Override
    public float[] getCompressionQualityValues() {
        super.getCompressionQualityValues();
        return new float[]{0f, 1f, 1f};
    }

    /** The quality the write uses: 1 (lossless) unless the mode is explicit. */
    float effectiveQuality() {
        return getCompressionMode() == MODE_EXPLICIT ? getCompressionQuality() : 1f;
    }

    /** Null means {@link Jpeg2000Threads#defaults()}. */
    public void setThreads(Jpeg2000Threads threads) {
        this.threads = threads;
    }

    public Jpeg2000Threads getThreads() {
        return threads;
    }

    /** Wavelet decomposition levels, 0-32; negative (the default) means {@link Jpeg2000Encoder#DEFAULT_LEVELS}. */
    public void setLevels(int levels) {
        if (levels > 32) throw new IllegalArgumentException("levels " + levels);
        this.levels = levels;
    }

    public int getLevels() {
        return levels;
    }
}
