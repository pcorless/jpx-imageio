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

import io.github.pcorless.jpx.Jpeg2000Decoder;
import io.github.pcorless.jpx.Jpeg2000Threads;

import javax.imageio.ImageReadParam;

/**
 * ImageReadParam with the decode's threads and size limit. {@link Jpeg2000ImageReader#getDefaultReadParam()}
 * returns one; a plain ImageReadParam (or threads left unset) means
 * {@link Jpeg2000Threads#defaults()}.
 */
public class Jpeg2000ImageReadParam extends ImageReadParam {

    private Jpeg2000Threads threads;
    private long maxSamples;

    /** Null means {@link Jpeg2000Threads#defaults()}. */
    public void setThreads(Jpeg2000Threads threads) {
        this.threads = threads;
    }

    public Jpeg2000Threads getThreads() {
        return threads;
    }

    /**
     * The most samples the read may decode (see {@link Jpeg2000Decoder#MAX_SAMPLES_PROPERTY}); zero or less
     * means {@link Jpeg2000Decoder#defaultMaxSamples()}.
     */
    public void setMaxSamples(long maxSamples) {
        this.maxSamples = maxSamples;
    }

    public long getMaxSamples() {
        return maxSamples;
    }
}
