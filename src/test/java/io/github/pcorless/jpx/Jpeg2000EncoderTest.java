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

import io.github.pcorless.jpx.codestream.Codestream;
import io.github.pcorless.jpx.codestream.CodestreamReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class Jpeg2000EncoderTest {

    static final int[][] SIZES = {
            {1, 1}, {1, 9}, {9, 1}, {2, 2}, {3, 5}, {17, 33}, {64, 64}, {65, 129}, {200, 150}, {300, 257}, {1000, 3},
    };

    /** A test picture: smooth gradients, an edge, and noise, so every band and pass is exercised. */
    static byte[] picture(int w, int h, int comps, long seed) {
        Random rnd = new Random(seed);
        byte[] b = new byte[w * h * comps];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                for (int c = 0; c < comps; c++) {
                    double v = 128 + 90 * Math.sin((x + 7 * c) / 11.0) * Math.cos(y / 17.0);
                    if (x > w / 2) v = 255 - v;
                    v += rnd.nextGaussian() * 6;
                    b[(y * w + x) * comps + c] = (byte) Math.max(0, Math.min(255, (int) Math.round(v)));
                }
            }
        }
        return b;
    }

    static int[] decodeInterleaved(byte[] j2k, int comps) throws JpxException {
        Jpeg2000Decoder.Component[] cs = Jpeg2000Decoder.decode(j2k, 0, j2k.length, 0);
        assertEquals(comps, cs.length);
        int n = cs[0].width * cs[0].height;
        int[] out = new int[n * comps];
        for (int c = 0; c < comps; c++) {
            assertEquals(8, cs[c].precision);
            assertFalse(cs[c].signed);
            for (int i = 0; i < n; i++) out[i * comps + c] = cs[c].samples[i];
        }
        return out;
    }

    static double psnr(byte[] want, int[] got) {
        double se = 0;
        for (int i = 0; i < want.length; i++) {
            double d = (want[i] & 0xff) - got[i];
            se += d * d;
        }
        if (se == 0) return Double.POSITIVE_INFINITY;
        return 10 * Math.log10(255.0 * 255.0 * want.length / se);
    }

    @DisplayName("lossless grey and RGB decode bit-exactly, every size")
    @Test
    public void losslessRoundTrip() throws Exception {
        for (int comps : new int[]{1, 3}) {
            for (int[] s : SIZES) {
                byte[] px = picture(s[0], s[1], comps, s[0] * 31L + s[1]);
                byte[] j2k = Jpeg2000Encoder.encode(px, s[0], s[1], comps, new Jpeg2000Encoder.Options()
                        .threads(Jpeg2000Threads.NONE));
                int[] got = decodeInterleaved(j2k, comps);
                int[] want = new int[px.length];
                for (int i = 0; i < px.length; i++) want[i] = px[i] & 0xff;
                assertArrayEquals(want, got, s[0] + "x" + s[1] + "x" + comps);
            }
        }
    }

    /**
     * The opj_compress fixtures made with its lossless defaults (5/3, LRCP, one layer, 64 x 64,
     * no precincts; some with -n): the same pixels (their reference decode) must give the same
     * bytes, less the COM marker opj_compress adds. Fixture provenance: tools/fixtures/README.md.
     */
    @DisplayName("lossless output is byte-identical to opj_compress 2.5.4 (less its COM marker)")
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"rev53-gray", "rev53-rgb", "rev53-dwt0", "rev53-dwt6", "size-1x1", "size-odd", "size-tall",
            "reduce", "noise-rev"})
    public void matchesOpenJpeg(String name) throws Exception {
        byte[] theirs = withoutCom(Jpeg2000DecoderTest.fixture(name + ".j2k"));
        Codestream cs = CodestreamReader.read(theirs, 0, theirs.length);
        int comps = cs.components.length;
        int[] dims = new int[2];
        int[][] planes = new int[comps][];
        for (int c = 0; c < comps; c++) {
            planes[c] = Jpeg2000DecoderTest.pgx(Jpeg2000DecoderTest.fixture("ref/" + name + ".full." + c + ".pgx"), dims);
        }
        byte[] px = new byte[dims[0] * dims[1] * comps];
        for (int c = 0; c < comps; c++) for (int i = 0; i < planes[c].length; i++) px[i * comps + c] = (byte) planes[c][i];
        int levels = cs.coding(0).components[0].numResolutions - 1;
        byte[] ours = Jpeg2000Encoder.encode(px, dims[0], dims[1], comps, new Jpeg2000Encoder.Options().levels(levels));
        assertArrayEquals(theirs, ours, name);
    }

    /** The codestream without its main-header COM segments. */
    static byte[] withoutCom(byte[] j2k) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(j2k, 0, 2);
        int k = 2;
        while (true) {
            int m = ((j2k[k] & 0xff) << 8) | (j2k[k + 1] & 0xff);
            if (m == 0xFF90) break;
            int len = ((j2k[k + 2] & 0xff) << 8) | (j2k[k + 3] & 0xff);
            if (m != 0xFF64) out.write(j2k, k, 2 + len);
            k += 2 + len;
        }
        out.write(j2k, k, j2k.length - k);
        return out.toByteArray();
    }

    @DisplayName("extremes: all 0, all 255, alternating 0/255, every level count")
    @Test
    public void losslessExtremes() throws Exception {
        int w = 70, h = 45;
        for (int kind = 0; kind < 3; kind++) {
            byte[] px = new byte[w * h * 3];
            for (int i = 0; i < px.length; i++) {
                px[i] = (byte) (kind == 0 ? 0 : kind == 1 ? 255 : ((i / 3 + i / (3 * w)) & 1) * 255);
            }
            for (int levels = 0; levels <= 6; levels++) {
                byte[] j2k = Jpeg2000Encoder.encode(px, w, h, 3, new Jpeg2000Encoder.Options().levels(levels));
                int[] got = decodeInterleaved(j2k, 3);
                for (int i = 0; i < px.length; i++) {
                    if ((px[i] & 0xff) != got[i]) assertEquals(px[i] & 0xff, got[i], "kind " + kind + " levels " + levels + " at " + i);
                }
                Codestream cs = CodestreamReader.read(j2k, 0, j2k.length);
                // 45 high: at most 5 levels leave every band non-empty
                assertEquals(Math.min(levels, 5) + 1, cs.coding(0).components[0].numResolutions);
            }
        }
    }

    @DisplayName("lossy: PSNR falls and size shrinks as quality drops")
    @Test
    public void lossyQuality() throws Exception {
        int w = 300, h = 257;
        for (int comps : new int[]{1, 3}) {
            byte[] px = picture(w, h, comps, 5);
            double lastPsnr = Double.POSITIVE_INFINITY;
            int lastSize = Integer.MAX_VALUE;
            for (float q : new float[]{0.95f, 0.8f, 0.6f, 0.4f, 0.2f}) {
                byte[] j2k = Jpeg2000Encoder.encode(px, w, h, comps, new Jpeg2000Encoder.Options().quality(q));
                double p = psnr(px, decodeInterleaved(j2k, comps));
                assertTrue(p < lastPsnr && j2k.length < lastSize, "q " + q + ": " + p + " dB, " + j2k.length + " bytes");
                assertTrue(p > 25, "q " + q + ": " + p + " dB");
                lastPsnr = p;
                lastSize = j2k.length;
            }
        }
        // near lossless stays close; odd sizes decode
        for (int[] s : SIZES) {
            byte[] px = picture(s[0], s[1], 3, 9);
            byte[] j2k = Jpeg2000Encoder.encode(px, s[0], s[1], 3, new Jpeg2000Encoder.Options().quality(0.99f));
            double p = psnr(px, decodeInterleaved(j2k, 3));
            assertTrue(p > 45, s[0] + "x" + s[1] + ": " + p + " dB");
        }
    }

    @DisplayName("threads give the same bytes as one thread")
    @Test
    public void threadsSameBytes() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Jpeg2000Threads four = Jpeg2000Threads.of(pool, 4);
            int w = 700, h = 520;
            byte[] px = picture(w, h, 3, 11);
            for (float q : new float[]{1f, 0.7f}) {
                byte[] one = Jpeg2000Encoder.encode(px, w, h, 3, new Jpeg2000Encoder.Options().quality(q)
                        .threads(Jpeg2000Threads.NONE));
                byte[] many = Jpeg2000Encoder.encode(px, w, h, 3, new Jpeg2000Encoder.Options().quality(q).threads(four));
                assertArrayEquals(one, many, "quality " + q);
            }
        } finally {
            pool.shutdown();
        }
    }

    @DisplayName("progress can stop the encode, which then writes nothing")
    @Test
    public void progressStops() throws Exception {
        int w = 300, h = 300;
        byte[] px = picture(w, h, 1, 3);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int rowLen = w;
        boolean done = Jpeg2000Encoder.encode(() -> (y, dest) -> {
            for (int i = 0; i < rowLen; i++) dest[i] = px[y * rowLen + i] & 0xff;
        }, w, h, 1, new Jpeg2000Encoder.Options().progress((d, total) -> d < 3).threads(Jpeg2000Threads.NONE), out);
        assertFalse(done);
        assertEquals(0, out.size());
    }
}
