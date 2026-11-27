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
 * Provenance: ORIGINAL. Vectors: OpenJPEG v2.5.4's MQ encoder via tools/vectors/mqvec.c
 * (the same file MqDecoderTest decodes).
 */
package io.github.pcorless.jpx.tier1;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public class MqEncoderTest {

    static List<MqDecoderTest.Vector> mqVectors() throws IOException {
        return MqDecoderTest.vectors().stream().filter(v -> !v.raw).collect(Collectors.toList());
    }

    static void t1Init(MqEncoder mq) {
        mq.resetStates();
        mq.setState(18, 0, 46);
        mq.setState(17, 0, 3);
        mq.setState(0, 0, 4);
    }

    @DisplayName("writes the same bytes as OpenJPEG's encoder")
    @ParameterizedTest(name = "{0}")
    @MethodSource("mqVectors")
    public void matchesOpenJpeg(MqDecoderTest.Vector v) {
        MqEncoder mq = new MqEncoder();
        t1Init(mq);
        mq.init();
        for (int i = 0; i < v.bit.length; i++) mq.encode(v.ctx[i], v.bit[i]);
        mq.flush();
        byte[] got = Arrays.copyOfRange(mq.buffer(), 1, 1 + mq.numBytes());
        assertArrayEquals(v.bytes, got, v.name);
    }

    @DisplayName("random symbols over all contexts round-trip through MqDecoder; reusable")
    @Test
    public void roundTrip() {
        Random rnd = new Random(697);
        MqEncoder enc = new MqEncoder();
        MqDecoder dec = new MqDecoder();
        for (int round = 0; round < 200; round++) {
            int n = rnd.nextInt(5000);
            int[] ctx = new int[n], bit = new int[n];
            int skew = rnd.nextInt(100);
            for (int i = 0; i < n; i++) {
                ctx[i] = rnd.nextInt(MqDecoder.NUM_CONTEXTS);
                bit[i] = rnd.nextInt(100) < skew ? 1 : 0;
            }
            t1Init(enc);
            enc.init();
            for (int i = 0; i < n; i++) enc.encode(ctx[i], bit[i]);
            enc.flush();
            int len = enc.numBytes();
            if (len > 0) assertNotEquals((byte) 0xff, enc.buffer()[len], "segment ends with 0xFF");
            byte[] seg = Arrays.copyOfRange(enc.buffer(), 1, 1 + len);
            dec.initDecoder(seg, 0, seg.length);
            MqDecoderTest.t1Init(dec);
            for (int i = 0; i < n; i++) {
                dec.setCurrentContext(ctx[i]);
                int d = dec.decode();
                if (d != bit[i]) assertEquals(bit[i], d, "round " + round + " symbol " + i);
            }
        }
    }
}
