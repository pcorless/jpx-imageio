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
 * Provenance: ADAPTED + ORIGINAL.
 *   ADAPTED from OpenJPEG src/lib/openjp2/dwt.c @ 6c4a29b0 (BSD-2): opj_dwt_norms_real,
 *   opj_dwt_getnorm_real, opj_dwt_calc_explicit_stepsizes, opj_dwt_encode_stepsize (the 9/7
 *   step sizes, here scaled by the quality's base step); and tcd.c @ 6c4a29b0: the encoder's
 *   band step size (Equation E-3 with the Table E-1 gains) and band / code-block geometry of
 *   opj_tcd_init_tile, and the quantisation of opj_t1_cblk_encode_processor (without its
 *   fractional bits). Changed: one tile at the origin, so every partition starts at 0.
 *   ORIGINAL: the pipeline (rows read, level-shifted and colour-transformed in one pass over
 *   threads, forward DWT, code-blocks over threads, packets, markers), the quality mapping and
 *   the guard bits chosen from the coded bit-planes.
 */
/*
 * The copyright in this software is being made available under the 2-clauses
 * BSD License, included below. This software may be subject to other third
 * party and contributor rights, including patent rights, and no such rights
 * are granted under this license.
 *
 * Copyright (c) 2002-2014, Universite catholique de Louvain (UCL), Belgium
 * Copyright (c) 2002-2014, Professor Benoit Macq
 * Copyright (c) 2001-2003, David Janssens
 * Copyright (c) 2002-2003, Yannick Verschueren
 * Copyright (c) 2003-2007, Francois-Olivier Devaux
 * Copyright (c) 2003-2014, Antonin Descampe
 * Copyright (c) 2005, Herve Drolon, FreeImage Team
 * Copyright (c) 2007, Jonathan Ballard <dzonatas@dzonux.net>
 * Copyright (c) 2007, Callum Lerwick <seg@haxxed.com>
 * Copyright (c) 2017, IntoPIX SA <support@intopix.com>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS `AS IS'
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
/*
 * The copyright in this software is being made available under the 2-clauses
 * BSD License, included below. This software may be subject to other third
 * party and contributor rights, including patent rights, and no such rights
 * are granted under this license.
 *
 * Copyright (c) 2002-2014, Universite catholique de Louvain (UCL), Belgium
 * Copyright (c) 2002-2014, Professor Benoit Macq
 * Copyright (c) 2001-2003, David Janssens
 * Copyright (c) 2002-2003, Yannick Verschueren
 * Copyright (c) 2003-2007, Francois-Olivier Devaux
 * Copyright (c) 2003-2014, Antonin Descampe
 * Copyright (c) 2005, Herve Drolon, FreeImage Team
 * Copyright (c) 2006-2007, Parvatha Elangovan
 * Copyright (c) 2008, 2011-2012, Centre National d'Etudes Spatiales (CNES), FR
 * Copyright (c) 2012, CS Systemes d'Information, France
 * Copyright (c) 2017, IntoPIX SA <support@intopix.com>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS `AS IS'
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
/*
 * The copyright in this software is being made available under the 2-clauses
 * BSD License, included below. This software may be subject to other third
 * party and contributor rights, including patent rights, and no such rights
 * are granted under this license.
 *
 * Copyright (c) 2002-2014, Universite catholique de Louvain (UCL), Belgium
 * Copyright (c) 2002-2014, Professor Benoit Macq
 * Copyright (c) 2001-2003, David Janssens
 * Copyright (c) 2002-2003, Yannick Verschueren
 * Copyright (c) 2003-2007, Francois-Olivier Devaux
 * Copyright (c) 2003-2014, Antonin Descampe
 * Copyright (c) 2005, Herve Drolon, FreeImage Team
 * Copyright (c) 2007, Callum Lerwick <seg@haxxed.com>
 * Copyright (c) 2012, Carl Hetherington
 * Copyright (c) 2017, IntoPIX SA <support@intopix.com>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS `AS IS'
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package io.github.pcorless.jpx;

import io.github.pcorless.jpx.codestream.CodestreamWriter;
import io.github.pcorless.jpx.internal.Parallel;
import io.github.pcorless.jpx.tier1.CodeBlockEncoder;
import io.github.pcorless.jpx.tier2.PacketEncoder;
import io.github.pcorless.jpx.transform.ForwardDwt;
import io.github.pcorless.jpx.transform.ForwardMct;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A basic JPEG 2000 encoder: 8-bit grey or RGB into a raw codestream (J2K, as PDF's JPXDecode
 * takes it). One tile, one quality layer, LRCP, 64 x 64 code-blocks, no precincts; lossless 5/3
 * with the RCT, or lossy 9/7 with the ICT and step sizes set by a quality (no rate control: you
 * choose a quality, not a size).
 */
public final class Jpeg2000Encoder {

    /** Bits per sample: the encoder takes 8-bit unsigned samples. */
    public static final int PRECISION = 8;
    /** Decomposition levels when not set (OpenJPEG's default), fewer for a small image. */
    public static final int DEFAULT_LEVELS = 5;

    private static final int CBLK_EXP = 6;
    private static final int CBLK = 1 << CBLK_EXP;
    /** Default precinct size, 2^15 (no precincts signalled): 2^14 in a high-pass band. */
    private static final int PRECINCT_EXP = 15;
    private static final int MIN_GUARD_BITS = 2;

    private Jpeg2000Encoder() {
    }

    /** Fills {@code dest[0, width * numComponents)} with row {@code y}'s samples, interleaved, 0-255. */
    public interface RowReader {
        void read(int y, int[] dest);
    }

    /**
     * Gives each encoding thread its own {@link RowReader}; rows are read once each, in no set
     * order, and possibly at the same time on different threads.
     */
    public interface Source {
        RowReader newRowReader();
    }

    /** Called on the calling thread as code-blocks finish; return false to stop the encode. */
    public interface Progress {
        boolean codeBlocksDone(int done, int total);
    }

    /** How to encode: lossless or a quality, decomposition levels, threads, progress. Not thread-safe. */
    public static final class Options {
        float quality = 1f;
        int levels = -1;
        Jpeg2000Threads threads;
        Progress progress;

        /**
         * 1 (the default) is lossless: 5/3 wavelet and RCT, decoded bit-exactly. Below 1 is lossy:
         * 9/7 wavelet and ICT, each step size {@link #baseStep(float)} over its band's norm, so
         * lower means coarser and smaller.
         */
        public Options quality(float quality) {
            if (!(quality >= 0f && quality <= 1f)) throw new IllegalArgumentException("quality " + quality);
            this.quality = quality;
            return this;
        }

        public Options lossless() {
            return quality(1f);
        }

        /** Wavelet decomposition levels, 0-32; capped so the smallest resolution is at least 1 x 1. */
        public Options levels(int levels) {
            if (levels < 0 || levels > 32) throw new IllegalArgumentException("levels " + levels);
            this.levels = levels;
            return this;
        }

        /** Threads to use; {@link Jpeg2000Threads#defaults()} when not set. */
        public Options threads(Jpeg2000Threads threads) {
            this.threads = threads;
            return this;
        }

        public Options progress(Progress progress) {
            this.progress = progress;
            return this;
        }

        boolean reversible() {
            return quality >= 1f;
        }
    }

    /**
     * The lossy quantiser's base step, in sample units, for a quality below 1: 2^(6 (1 - q)),
     * from just over 1 (near lossless) down to 64 at quality 0. A band's step is this over its
     * synthesis norm, so each band adds about the same error.
     */
    public static double baseStep(float quality) {
        return Math.pow(2.0, 6.0 * (1.0 - quality));
    }

    /** Encodes {@code samples} (interleaved, {@code numComponents} per pixel, row-major) to a codestream. */
    public static byte[] encode(byte[] samples, int width, int height, int numComponents, Options options)
            throws IOException {
        if ((long) width * height * numComponents > samples.length) {
            throw new IllegalArgumentException(samples.length + " samples for " + width + "x" + height + "x" + numComponents);
        }
        int rowLength = width * numComponents;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        encode(() -> (y, dest) -> {
            for (int i = 0, s = y * rowLength; i < rowLength; i++, s++) dest[i] = samples[s] & 0xff;
        }, width, height, numComponents, options, out);
        return out.toByteArray();
    }

    /**
     * Encodes the image {@code source} gives to {@code out} as a J2K codestream. Returns false,
     * having written nothing, when the progress hook stopped it.
     */
    public static boolean encode(Source source, int width, int height, int numComponents, Options options,
                                 OutputStream out) throws IOException {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("size " + width + "x" + height);
        if (numComponents != 1 && numComponents != 3) {
            throw new IllegalArgumentException(numComponents + " components (1 or 3)");
        }
        if ((long) width * height > Integer.MAX_VALUE - 16) {
            throw JpxException.unsupported("encoding " + width + "x" + height + " (one tile, over 2^31 samples)");
        }
        Options o = options != null ? options : new Options();
        Jpeg2000Threads threads = o.threads != null ? o.threads : Jpeg2000Threads.defaults();
        boolean reversible = o.reversible();
        int levels = Math.min(o.levels >= 0 ? o.levels : DEFAULT_LEVELS, maxLevels(width, height));
        boolean mct = numComponents == 3;

        // rows: level shift and colour transform, in one pass, over threads
        int[][] ints = reversible ? new int[numComponents][width * height] : null;
        float[][] floats = reversible ? null : new float[numComponents][width * height];
        readRows(source, width, height, numComponents, mct, ints, floats, threads);

        // forward DWT per component
        for (int c = 0; c < numComponents; c++) {
            if (reversible) ForwardDwt.encode53(ints[c], width, height, levels, threads);
            else ForwardDwt.encode97(floats[c], width, height, levels, threads);
        }

        // bands, their exponents (and mantissas) and step sizes, in codestream order
        Band[] bands = bands(width, height, levels);
        int[] exponent = new int[bands.length];
        int[] mantissa = reversible ? null : new int[bands.length];
        float[] step = new float[bands.length];
        stepSizes(bands, levels, reversible, baseStep(o.quality), exponent, mantissa, step);

        // tier-1: every code-block of every component, over threads
        Coded[][] coded = codeBlocks(bands, ints, floats, width, step, numComponents, threads, o.progress);
        if (coded == null) return false;

        int guardBits = guardBits(bands, coded, exponent);

        // tier-2: the packets, LRCP with one layer
        List<byte[]> headers = new ArrayList<>();
        List<PacketEncoder.Blocks[]> bodies = new ArrayList<>();
        long packetBytes = packets(bands, coded, exponent, guardBits, levels, numComponents, headers, bodies);

        DataOutputStream d = new DataOutputStream(new BufferedOutputStream(out, 1 << 16));
        CodestreamWriter.soc(d);
        CodestreamWriter.siz(d, width, height, numComponents, PRECISION);
        CodestreamWriter.cod(d, levels, mct, reversible);
        CodestreamWriter.qcd(d, guardBits, exponent, mantissa);
        CodestreamWriter.sot(d, CodestreamWriter.TILE_PART_HEADER_LENGTH + packetBytes);
        CodestreamWriter.sod(d);
        for (int i = 0; i < headers.size(); i++) {
            d.write(headers.get(i));
            PacketEncoder.writeBody(bodies.get(i), d);
        }
        CodestreamWriter.eoc(d);
        d.flush();
        return true;
    }

    /** Levels such that the smallest resolution is at least 1 x 1 and no band is empty. */
    static int maxLevels(int width, int height) {
        return 31 - Integer.numberOfLeadingZeros(Math.min(width, height));
    }

    // ------------------------------------------------------------------------------------------
    // rows

    private static final int ROWS_PER_ITEM = 16;

    private static void readRows(Source source, int width, int height, int numComps, boolean mct,
                                 int[][] ints, float[][] floats, Jpeg2000Threads threads) throws JpxException {
        int shift = 1 << (PRECISION - 1);
        int items = (height + ROWS_PER_ITEM - 1) / ROWS_PER_ITEM;
        boolean parallel = Parallel.worthIt(threads, (long) width * height >= 256 * 256);
        Parallel.forEach(parallel ? threads : Jpeg2000Threads.NONE, items, () -> {
            RowReader reader = source.newRowReader();
            int[] px = new int[width * numComps];
            return item -> {
                for (int y = item * ROWS_PER_ITEM, e = Math.min(height, y + ROWS_PER_ITEM); y < e; y++) {
                    reader.read(y, px);
                    int off = y * width;
                    if (mct && ints != null) {
                        ForwardMct.reversible(px, width, shift, ints[0], ints[1], ints[2], off);
                    } else if (mct) {
                        ForwardMct.irreversible(px, width, shift, floats[0], floats[1], floats[2], off);
                    } else {
                        for (int c = 0; c < numComps; c++) {
                            if (ints != null) ForwardMct.shift(px, width, numComps, c, shift, ints[c], off);
                            else ForwardMct.shift(px, width, numComps, c, shift, floats[c], off);
                        }
                    }
                }
            };
        });
    }

    // ------------------------------------------------------------------------------------------
    // bands and step sizes

    /** A sub-band of the tile-component: where it sits in the transformed plane, and its code-blocks. */
    static final class Band {
        /** Resolution (0 = the lowest), orientation 0 LL, 1 HL, 2 LH, 3 HH. */
        final int res, orient;
        /** Size, and offset of its top-left coefficient in the plane. */
        final int width, height, offset;
        /** Code-blocks across and down. */
        final int cbw, cbh;

        Band(int res, int orient, int width, int height, int offset) {
            this.res = res;
            this.orient = orient;
            this.width = width;
            this.height = height;
            this.offset = offset;
            this.cbw = (width + CBLK - 1) >> CBLK_EXP;
            this.cbh = (height + CBLK - 1) >> CBLK_EXP;
        }

        int numBlocks() {
            return cbw * cbh;
        }
    }

    private static int ceilDivPow2(int v, int e) {
        return (int) (((long) v + (1L << e) - 1) >> e);
    }

    /** The 3 levels + 1 bands in codestream order: LL, then HL, LH, HH from the lowest resolution up. */
    static Band[] bands(int width, int height, int levels) {
        Band[] bands = new Band[3 * levels + 1];
        bands[0] = new Band(0, 0, ceilDivPow2(width, levels), ceilDivPow2(height, levels), 0);
        int b = 1;
        for (int r = 1; r <= levels; r++) {
            int lev = levels - r + 1;
            int lw = ceilDivPow2(width, lev), lh = ceilDivPow2(height, lev);       // the low half
            int rw = ceilDivPow2(width, lev - 1), rh = ceilDivPow2(height, lev - 1); // this resolution
            bands[b++] = new Band(r, 1, rw - lw, lh, lw);
            bands[b++] = new Band(r, 2, lw, rh - lh, lh * width);
            bands[b++] = new Band(r, 3, rw - lw, rh - lh, lh * width + lw);
        }
        return bands;
    }

    /** opj_dwt_norms_real: norms of the 9/7 synthesis basis per orientation and level. */
    private static final double[][] NORMS_97 = {
            {1.000, 1.965, 4.177, 8.403, 16.90, 33.84, 67.69, 135.3, 270.6, 540.9},
            {2.022, 3.989, 8.355, 17.04, 34.27, 68.63, 137.3, 274.6, 549.0},
            {2.022, 3.989, 8.355, 17.04, 34.27, 68.63, 137.3, 274.6, 549.0},
            {2.080, 3.865, 8.307, 17.18, 34.71, 69.59, 139.3, 278.6, 557.2},
    };

    /** opj_dwt_getnorm_real; the level counts from 0 for the finest high-pass bands. */
    private static double norm97(int level, int orient) {
        double[] row = NORMS_97[orient];
        return row[Math.min(level, row.length - 1)];
    }

    /** Table E-1 log2 gains. */
    private static int log2Gain(int orient) {
        return orient == 0 ? 0 : orient == 3 ? 2 : 1;
    }

    /**
     * opj_dwt_calc_explicit_stepsizes and opj_dwt_encode_stepsize, then each band's step size
     * as the encoder of tcd.c computes it from the coded exponent and mantissa (E-3).
     */
    static void stepSizes(Band[] bands, int levels, boolean reversible, double baseStep,
                          int[] exponent, int[] mantissa, float[] step) {
        for (int b = 0; b < bands.length; b++) {
            Band band = bands[b];
            int gain = log2Gain(band.orient);
            if (reversible) {
                // no quantisation: the exponent is the band's nominal dynamic range
                exponent[b] = PRECISION + gain;
                step[b] = 1f;
                continue;
            }
            // level as opj_dwt_calc_explicit_stepsizes counts it: numresolutions - 1 - resno
            int level = levels - band.res;
            int s = (int) Math.floor(baseStep / norm97(level, band.orient) * 8192.0);
            s = Math.max(1, s);
            int p = (31 - Integer.numberOfLeadingZeros(s)) - 13;
            int n = 11 - (31 - Integer.numberOfLeadingZeros(s));
            int mant = (n < 0 ? s >> -n : s << n) & 0x7ff;
            // numbps of opj_dwt_calc_explicit_stepsizes: prec + 0 for the 9/7 (its gain is 0)
            int expn = Math.max(0, Math.min(31, PRECISION - p));
            exponent[b] = expn;
            mantissa[b] = mant;
            step[b] = (float) ((1.0 + mant / 2048.0) * Math.pow(2.0, PRECISION + gain - expn));
        }
    }

    // ------------------------------------------------------------------------------------------
    // tier-1

    /** A code-block's coded data. */
    static final class Coded {
        final int numBitPlanes;
        final int numPasses;
        final byte[] data;

        Coded(int numBitPlanes, int numPasses, byte[] data) {
            this.numBitPlanes = numBitPlanes;
            this.numPasses = numPasses;
            this.data = data;
        }
    }

    private static final byte[] NO_BYTES = new byte[0];
    private static final Coded EMPTY = new Coded(0, 0, NO_BYTES);

    /** coded[component * bands + band][block], or null when stopped. */
    private static Coded[][] codeBlocks(Band[] bands, int[][] ints, float[][] floats, int planeWidth, float[] step,
                                        int numComps, Jpeg2000Threads threads, Progress progress) throws JpxException {
        Coded[][] coded = new Coded[numComps * bands.length][];
        // one job per (component, band, block)
        int total = 0;
        for (Band b : bands) total += b.numBlocks();
        total *= numComps;
        int[] jobBand = new int[total];
        int[] jobBlock = new int[total];
        int j = 0;
        for (int c = 0; c < numComps; c++) {
            for (int b = 0; b < bands.length; b++) {
                coded[c * bands.length + b] = new Coded[bands[b].numBlocks()];
                for (int k = 0; k < bands[b].numBlocks(); k++, j++) {
                    jobBand[j] = c * bands.length + b;
                    jobBlock[j] = k;
                }
            }
        }
        int n = total;
        boolean[] stopped = {false};
        Parallel.Progress p = progress == null ? null : done -> {
            if (!progress.codeBlocksDone(done, n)) {
                stopped[0] = true;
                return false;
            }
            return true;
        };
        Parallel.forEach(threads, n, () -> {
            CodeBlockEncoder t1 = new CodeBlockEncoder();
            return job -> {
                int cb = jobBand[job];
                int comp = cb / bands.length;
                Band band = bands[cb % bands.length];
                int k = jobBlock[job];
                int bx = (k % band.cbw) << CBLK_EXP, by = (k / band.cbw) << CBLK_EXP;
                int cw = Math.min(CBLK, band.width - bx), ch = Math.min(CBLK, band.height - by);
                int[] d = t1.begin(cw, ch);
                int o = band.offset + by * planeWidth + bx;
                if (ints != null) {
                    int[] a = ints[comp];
                    for (int y = 0; y < ch; y++, o += planeWidth) System.arraycopy(a, o, d, y * cw, cw);
                } else {
                    // quantise: q = sign(c) floor(|c| / step) (T.800 E.1 inverse)
                    float[] a = floats[comp];
                    float inv = 1f / step[cb % bands.length];
                    for (int y = 0; y < ch; y++, o += planeWidth) {
                        for (int x = 0, dy = y * cw; x < cw; x++) {
                            float v = a[o + x];
                            int q = (int) (Math.abs(v) * inv);
                            d[dy + x] = v < 0 ? -q : q;
                        }
                    }
                }
                int len = t1.encode(band.orient);
                coded[cb][k] = len == 0 && t1.numPasses() == 0 ? EMPTY
                        : new Coded(t1.numBitPlanes(), t1.numPasses(), Arrays.copyOfRange(t1.bytes(), 1, 1 + len));
            };
        }, p);
        return stopped[0] ? null : coded;
    }

    /**
     * Guard bits so that every code-block's bit-planes fit under its band's Mb = exponent + G - 1
     * (E-2); OpenJPEG's 2 unless the data needs more.
     */
    static int guardBits(Band[] bands, Coded[][] coded, int[] exponent) throws JpxException {
        int g = MIN_GUARD_BITS;
        for (int i = 0; i < coded.length; i++) {
            int b = i % bands.length;
            for (Coded c : coded[i]) g = Math.max(g, c.numBitPlanes - exponent[b] + 1);
        }
        if (g > 7) throw new JpxException("coefficients need " + g + " guard bits (at most 7)");
        return g;
    }

    // ------------------------------------------------------------------------------------------
    // tier-2

    /** The packets in LRCP order (one layer): headers and the blocks whose data follows each. */
    private static long packets(Band[] bands, Coded[][] coded, int[] exponent, int guardBits, int levels,
                                int numComps, List<byte[]> headers, List<PacketEncoder.Blocks[]> bodies) {
        PacketEncoder t2 = new PacketEncoder();
        long total = 0;
        for (int r = 0; r <= levels; r++) {
            // this resolution's bands, and the precinct grid (2^15 in the resolution, 2^14 in a band)
            int first = r == 0 ? 0 : 3 * r - 2;
            int count = r == 0 ? 1 : 3;
            int pexp = r == 0 ? PRECINCT_EXP : PRECINCT_EXP - 1;
            // HL's width plus LH's (the low half) is the resolution's width; heights likewise
            int resW = r == 0 ? bands[0].width : bands[first].width + bands[first + 1].width;
            int resH = r == 0 ? bands[0].height : bands[first].height + bands[first + 1].height;
            int pw = ceilDivPow2(resW, PRECINCT_EXP), ph = ceilDivPow2(resH, PRECINCT_EXP);
            for (int c = 0; c < numComps; c++) {
                for (int prc = 0; prc < pw * ph; prc++) {
                    int px = prc % pw, py = prc / pw;
                    List<PacketEncoder.Blocks> in = new ArrayList<>(count);
                    for (int b = first; b < first + count; b++) {
                        Band band = bands[b];
                        if (band.width == 0 || band.height == 0) continue;
                        PacketEncoder.Blocks blocks = precinctBlocks(band, coded[c * bands.length + b], px, py, pexp,
                                exponent[b] + guardBits - 1);
                        if (blocks != null) in.add(blocks);
                    }
                    PacketEncoder.Blocks[] arr = in.toArray(new PacketEncoder.Blocks[0]);
                    int len = t2.header(arr);
                    headers.add(Arrays.copyOf(t2.headerBytes(), len));
                    bodies.add(arr);
                    total += len + PacketEncoder.bodyLength(arr);
                }
            }
        }
        return total;
    }

    /** The band's code-blocks inside precinct (px, py) of size 2^pexp in band coordinates; null if none. */
    private static PacketEncoder.Blocks precinctBlocks(Band band, Coded[] coded, int px, int py, int pexp, int mb) {
        int x0 = px << pexp, y0 = py << pexp;
        if (x0 >= band.width || y0 >= band.height) return null;
        int x1 = (int) Math.min(band.width, (long) (px + 1) << pexp);
        int y1 = (int) Math.min(band.height, (long) (py + 1) << pexp);
        int cx0 = x0 >> CBLK_EXP, cy0 = y0 >> CBLK_EXP;
        int cw = ((x1 + CBLK - 1) >> CBLK_EXP) - cx0, ch = ((y1 + CBLK - 1) >> CBLK_EXP) - cy0;
        int n = cw * ch;
        int[] bps = new int[n], passes = new int[n];
        byte[][] data = new byte[n][];
        for (int i = 0; i < n; i++) {
            Coded cb = coded[(cy0 + i / cw) * band.cbw + cx0 + i % cw];
            bps[i] = cb.numBitPlanes;
            passes[i] = cb.numPasses;
            data[i] = cb.data;
        }
        return new PacketEncoder.Blocks(cw, ch, mb, bps, passes, data);
    }
}
