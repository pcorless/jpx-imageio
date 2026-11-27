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
// Provenance: ORIGINAL. Dev tool, never shipped: checks the encoder against OpenJPEG's decoder.
//
// For each source (synthetic pictures, and corpus streams decoded to 8-bit grey/RGB), writes a
// J2K with Jpeg2000Encoder at each quality, decodes it with opj_decompress (-o x.pgm / x.ppm) and
// with this decoder, and prints: name, size, components, quality, bytes, bits per pixel, PSNR
// against the source through OpenJPEG and through this decoder, and whether the two decodes
// agree. Lossless rows must show "inf inf". Usage:
//   java -cp build/classes/java/main tools/vectors/WriterCheck.java <opj_decompress> <workdir> [corpus files...]
import io.github.pcorless.jpx.Jpeg2000Decoder;
import io.github.pcorless.jpx.Jpeg2000Encoder;
import io.github.pcorless.jpx.Jpeg2000Image;
import io.github.pcorless.jpx.Jpeg2000Headers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class WriterCheck {

    static final float[] QUALITIES = {1f, 0.95f, 0.9f, 0.8f, 0.7f, 0.6f, 0.5f, 0.4f, 0.3f};

    static final class Src {
        final String name;
        final int w, h, c;
        final byte[] px;

        Src(String name, int w, int h, int c, byte[] px) {
            this.name = name;
            this.w = w;
            this.h = h;
            this.c = c;
            this.px = px;
        }
    }

    public static void main(String[] args) throws Exception {
        String opj = args[0];
        Path work = Paths.get(args[1]);
        Files.createDirectories(work);
        List<Src> srcs = new ArrayList<>();
        int[][] sizes = {{1, 1}, {3, 5}, {17, 33}, {65, 129}, {300, 257}, {1000, 3}, {1023, 769}};
        for (int[] s : sizes) {
            srcs.add(new Src("synth-grey-" + s[0] + "x" + s[1], s[0], s[1], 1, picture(s[0], s[1], 1, s[0])));
            srcs.add(new Src("synth-rgb-" + s[0] + "x" + s[1], s[0], s[1], 3, picture(s[0], s[1], 3, s[1])));
        }
        for (int i = 2; i < args.length; i++) {
            Src s = fromCorpus(Paths.get(args[i]));
            if (s != null) srcs.add(s);
        }
        System.out.println("name\tw\th\tc\tquality\tbytes\tbpp\tpsnr_opj\tpsnr_ours\tdecodes_agree");
        for (Src s : srcs) {
            for (float q : QUALITIES) {
                byte[] j2k = Jpeg2000Encoder.encode(s.px, s.w, s.h, s.c, new Jpeg2000Encoder.Options().quality(q));
                Path in = work.resolve("w.j2k");
                Path out = work.resolve(s.c == 1 ? "w.pgm" : "w.ppm");
                Files.write(in, j2k);
                Files.deleteIfExists(out);
                Process p = new ProcessBuilder(opj, "-i", in.toString(), "-o", out.toString())
                        .redirectErrorStream(true).redirectOutput(work.resolve("opj.log").toFile()).start();
                int rc = p.waitFor();
                int[] theirs = rc == 0 && Files.exists(out) ? readPnm(Files.readAllBytes(out), s) : null;
                int[] ours = decode(j2k, s);
                boolean agree = theirs != null && java.util.Arrays.equals(theirs, ours);
                System.out.printf("%s\t%d\t%d\t%d\t%.2f\t%d\t%.3f\t%s\t%s\t%s%n", s.name, s.w, s.h, s.c, q, j2k.length,
                        8.0 * j2k.length / ((double) s.w * s.h), theirs == null ? "FAIL(rc=" + rc + ")" : fmt(psnr(s.px, theirs)),
                        fmt(psnr(s.px, ours)), agree ? "yes" : "no");
            }
        }
    }

    static String fmt(double p) {
        return Double.isInfinite(p) ? "inf" : String.format("%.2f", p);
    }

    static int[] decode(byte[] j2k, Src s) throws IOException {
        Jpeg2000Decoder.Component[] cs = Jpeg2000Decoder.decode(j2k, 0, j2k.length, 0);
        int n = s.w * s.h;
        int[] out = new int[n * s.c];
        for (int c = 0; c < s.c; c++) for (int i = 0; i < n; i++) out[i * s.c + c] = cs[c].samples[i];
        return out;
    }

    /** Binary PGM/PPM with maxval 255. */
    static int[] readPnm(byte[] b, Src s) {
        int pos = 0, fields = 0;
        int[] f = new int[4];
        while (fields < 4) {
            while (Character.isWhitespace(b[pos])) pos++;
            if (b[pos] == '#') { while (b[pos] != '\n') pos++; continue; }
            int start = pos;
            while (!Character.isWhitespace(b[pos])) pos++;
            String t = new String(b, start, pos - start);
            f[fields++] = fields == 1 ? 0 : Integer.parseInt(t);
        }
        pos++;
        int n = s.w * s.h * s.c;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = b[pos + i] & 0xff;
        return out;
    }

    static double psnr(byte[] want, int[] got) {
        double se = 0;
        for (int i = 0; i < want.length; i++) {
            double d = (want[i] & 0xff) - got[i];
            se += d * d;
        }
        return se == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(255.0 * 255.0 * want.length / se);
    }

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

    /** A corpus stream decoded to 8-bit grey or RGB (the first 1 or 3 components), or null. */
    static Src fromCorpus(Path p) throws IOException {
        byte[] data = Files.readAllBytes(p);
        Jpeg2000Image img = Jpeg2000Decoder.decodeImage(Jpeg2000Headers.read(data, 0, data.length), new Jpeg2000Decoder.Options());
        Jpeg2000Decoder.Component[] cs = img.components;
        int c = cs.length >= 3 ? 3 : 1;
        int w = cs[0].width, h = cs[0].height;
        for (int i = 0; i < c; i++) {
            if (cs[i].width != w || cs[i].height != h || cs[i].precision != 8 || cs[i].signed) return null;
        }
        byte[] px = new byte[w * h * c];
        for (int i = 0; i < c; i++) for (int k = 0; k < w * h; k++) px[k * c + i] = (byte) cs[i].samples[k];
        String name = p.getFileName().toString();
        return new Src("corpus-" + name.substring(0, Math.min(12, name.length())), w, h, c, px);
    }
}
