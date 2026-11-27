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
package io.github.pcorless.jpx.tier1;

import io.github.pcorless.jpx.JpxException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CodeBlockEncoderTest {

    private static final int[][] SIZES = {
            {1, 1}, {1, 7}, {7, 1}, {2, 3}, {4, 4}, {5, 9}, {64, 64}, {64, 1}, {1, 64}, {17, 64}, {64, 13},
            {32, 32}, {63, 63}, {33, 6}, {128, 32}, {16, 256},
    };

    @DisplayName("code-blocks of every shape, orientation and density decode to the coefficients")
    @Test
    public void roundTrip() throws JpxException {
        Random rnd = new Random(2);
        CodeBlockEncoder enc = new CodeBlockEncoder();
        CodeBlockDecoder dec = new CodeBlockDecoder();
        int cases = 0;
        for (int[] s : SIZES) {
            for (int band = 0; band < 4; band++) {
                for (int kind = 0; kind < 6; kind++) {
                    int w = s[0], h = s[1];
                    int[] want = new int[w * h];
                    for (int i = 0; i < want.length; i++) want[i] = sample(rnd, kind);
                    int[] d = enc.begin(w, h);
                    System.arraycopy(want, 0, d, 0, want.length);
                    int len = enc.encode(band);
                    byte[] buf = new byte[len + 1];
                    System.arraycopy(enc.bytes(), 1, buf, 1, len);
                    dec.decode(w, h, buf, 1, new int[]{len}, new int[]{enc.numPasses()}, enc.numPasses() > 0 ? 1 : 0,
                            enc.numBitPlanes(), band, 0, 0);
                    int[] got = dec.data();
                    for (int i = 0; i < want.length; i++) {
                        if (got[i] / 2 != want[i]) {
                            assertEquals(want[i], got[i] / 2, w + "x" + h + " band " + band + " kind " + kind + " at " + i);
                        }
                    }
                    cases++;
                }
            }
        }
        assertEquals(SIZES.length * 24, cases);
    }

    private static int sample(Random rnd, int kind) {
        switch (kind) {
            case 0:
                return 0;
            case 1: // sparse
                return rnd.nextInt(50) == 0 ? rnd.nextInt(200) - 100 : 0;
            case 2: // small, dense
                return rnd.nextInt(7) - 3;
            case 3: // wide range
                return rnd.nextInt(1 << 16) - (1 << 15);
            case 4: // one sign
                return rnd.nextInt(1000);
            default: // a few large among small
                return rnd.nextInt(20) == 0 ? rnd.nextInt(1 << 20) - (1 << 19) : rnd.nextInt(3) - 1;
        }
    }
}
