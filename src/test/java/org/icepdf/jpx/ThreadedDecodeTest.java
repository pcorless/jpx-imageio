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
 * Provenance: ORIGINAL. Threads must not change a single sample: every fixture reference
 * decoded with helpers (and the size thresholds ignored, so the small fixtures take the
 * parallel paths) equals the single-threaded decode.
 */
package org.icepdf.jpx;

import org.icepdf.jpx.internal.Parallel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ThreadedDecodeTest {

    static ExecutorService pool;

    @BeforeAll
    static void start() {
        pool = Executors.newFixedThreadPool(4);
    }

    @AfterAll
    static void stop() {
        pool.shutdownNow();
        Parallel.ignoreThresholds = false;
    }

    static List<String[]> references() {
        return Jpeg2000DecoderTest.references();
    }

    @ParameterizedTest(name = "{0}.{2}")
    @MethodSource("references")
    public void sameSamplesWithThreads(String name, String file, String tag, String comps, String reduce,
                                       String region, String kind) throws JpxException {
        byte[] data = Jpeg2000DecoderTest.fixture(file);
        Jpeg2000Headers h = Jpeg2000Headers.read(data, 0, data.length);
        Jpeg2000Decoder.Options o = new Jpeg2000Decoder.Options().reduce(Integer.parseInt(reduce));
        if (!region.isEmpty()) {
            String[] r = region.split(",");
            o.area(Long.parseLong(r[0]), Long.parseLong(r[1]), Long.parseLong(r[2]), Long.parseLong(r[3]));
        }
        Jpeg2000Image serial = Jpeg2000Decoder.decodeImage(h, o.threads(Jpeg2000Threads.NONE));
        Parallel.ignoreThresholds = true;
        Jpeg2000Image threaded;
        try {
            threaded = Jpeg2000Decoder.decodeImage(Jpeg2000Headers.read(data, 0, data.length),
                    o.threads(Jpeg2000Threads.of(pool, 4)));
        } finally {
            Parallel.ignoreThresholds = false;
        }
        assertEquals(serial.components.length, threaded.components.length);
        for (int c = 0; c < serial.components.length; c++) {
            assertArrayEquals(serial.components[c].samples, threaded.components[c].samples, "component " + c);
        }
    }
}
