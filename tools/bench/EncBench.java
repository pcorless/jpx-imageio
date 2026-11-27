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
 */
// Provenance: ORIGINAL. Encoder benchmark: decodes the given JPEG 2000 files (or makes the
// synthetic 8000 x 6000 RGB image when given "synth") to 8-bit samples in memory, then encodes
// each N rounds after warm-up and prints the best round per file and the total MP/s. Optionally
// writes each source as a binary PPM/PGM for opj_compress comparisons (-Dppm=<dir>).
//   java -Xmx8g -cp <classes> tools/bench/EncBench.java <threads> <quality> <rounds> files... | synth
import io.github.pcorless.jpx.Jpeg2000Decoder;
import io.github.pcorless.jpx.Jpeg2000Encoder;
import io.github.pcorless.jpx.Jpeg2000Headers;
import io.github.pcorless.jpx.Jpeg2000Threads;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;

public class EncBench {

    static final class Img {
        final String name;
        final int w, h, c;
        final byte[] px;

        Img(String name, int w, int h, int c, byte[] px) {
            this.name = name;
            this.w = w;
            this.h = h;
            this.c = c;
            this.px = px;
        }
    }

    public static void main(String[] args) throws Exception {
        int threads = Integer.parseInt(args[0]);
        float quality = Float.parseFloat(args[1]);
        int rounds = Integer.parseInt(args[2]);
        List<Img> imgs = new ArrayList<>();
        for (int i = 3; i < args.length; i++) {
            if (args[i].equals("synth")) imgs.add(synth(8000, 6000));
            else imgs.add(load(Paths.get(args[i])));
        }
        String ppm = System.getProperty("ppm");
        if (ppm != null) for (Img m : imgs) writePnm(Paths.get(ppm, m.name + (m.c == 1 ? ".pgm" : ".ppm")), m);
        Jpeg2000Threads t = threads <= 1 ? Jpeg2000Threads.NONE
                : Jpeg2000Threads.of(Executors.newFixedThreadPool(threads - 1, r -> {
                    Thread th = new Thread(r);
                    th.setDaemon(true);
                    return th;
                }), threads);
        // warm-up
        for (int k = 0; k < 2; k++) for (Img m : imgs) encode(m, quality, t, null);
        double totalMs = 0, totalMp = 0;
        long totalBytes = 0;
        for (Img m : imgs) {
            double best = Double.MAX_VALUE;
            long bytes = 0;
            for (int r = 0; r < rounds; r++) {
                CountingStream out = new CountingStream();
                long t0 = System.nanoTime();
                encode(m, quality, t, out);
                best = Math.min(best, (System.nanoTime() - t0) / 1e6);
                bytes = out.n;
            }
            double mp = m.w * (double) m.h / 1e6;
            System.out.printf("%s\t%dx%dx%d\t%.1f ms\t%.1f MP/s\t%d bytes%n", m.name, m.w, m.h, m.c, best, mp / best * 1000, bytes);
            totalMs += best;
            totalMp += mp;
            totalBytes += bytes;
        }
        System.out.printf("total\t%.1f MP\t%.1f ms\t%.1f MP/s\t%d bytes%n", totalMp, totalMs, totalMp / totalMs * 1000, totalBytes);
        System.exit(0);
    }

    static void encode(Img m, float q, Jpeg2000Threads t, OutputStream out) throws Exception {
        int row = m.w * m.c;
        Jpeg2000Encoder.encode(() -> (y, dest) -> {
            for (int i = 0, s = y * row; i < row; i++, s++) dest[i] = m.px[s] & 0xff;
        }, m.w, m.h, m.c, new Jpeg2000Encoder.Options().quality(q).threads(t), out != null ? out : new CountingStream());
    }

    static final class CountingStream extends OutputStream {
        long n;

        public void write(int b) {
            n++;
        }

        public void write(byte[] b, int off, int len) {
            n += len;
        }
    }

    static Img load(Path p) throws Exception {
        byte[] data = Files.readAllBytes(p);
        Jpeg2000Decoder.Component[] cs = Jpeg2000Decoder.decodeImage(Jpeg2000Headers.read(data, 0, data.length),
                new Jpeg2000Decoder.Options()).components;
        int c = cs.length >= 3 ? 3 : 1;
        int w = cs[0].width, h = cs[0].height;
        byte[] px = new byte[w * h * c];
        for (int i = 0; i < c; i++) for (int k = 0; k < w * h; k++) px[k * c + i] = (byte) Math.max(0, Math.min(255, cs[i].samples[k]));
        String n = p.getFileName().toString();
        return new Img(n.substring(0, Math.min(12, n.length())), w, h, c, px);
    }

    /** The synthetic image of PERFORMANCE.md's lossless runs: smooth, with mild noise. */
    static Img synth(int w, int h) {
        Random rnd = new Random(1);
        byte[] px = new byte[w * h * 3];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                for (int c = 0; c < 3; c++) {
                    double v = 128 + 100 * Math.sin((x + 300 * c) / 400.0) * Math.cos(y / 300.0) + rnd.nextInt(5) - 2;
                    px[(y * w + x) * 3 + c] = (byte) Math.max(0, Math.min(255, (int) v));
                }
            }
        }
        return new Img("synth-8000x6000", w, h, 3, px);
    }

    static void writePnm(Path p, Img m) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(((m.c == 1 ? "P5\n" : "P6\n") + m.w + " " + m.h + "\n255\n").getBytes("US-ASCII"));
        b.write(m.px);
        Files.write(p, b.toByteArray());
    }
}
