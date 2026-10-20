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
 * Provenance: ADAPTED from OpenJPEG src/lib/openjp2/j2k.c @ 6c4a29b0 (BSD-2):
 *   the component geometry of opj_j2k_update_image_dimensions and opj_j2k_set_decode_area
 *   (ceildiv by dx/dy, then ceildivpow2 by the reduce factor) and the tile copy of opj_j2k_update_image_data.
 *   Changed: tiles are decoded one after another into the output arrays; only the tiles that meet a decode area are decoded,
 *   each one whole, then cropped (OpenJPEG decodes just the code-blocks the area needs);
 *   a per-tile Progress hook can stop the decode. decodeImage is ORIGINAL glue around
 *   Jpeg2000Headers and opj_jp2_decode's post-processing (Jp2Channels).
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
 * Copyright (c) 2008, Jerome Fimes, Communications & Systemes <jerome.fimes@c-s.fr>
 * Copyright (c) 2006-2007, Parvatha Elangovan
 * Copyright (c) 2010-2011, Kaori Hagihara
 * Copyright (c) 2011-2012, Centre National d'Etudes Spatiales (CNES), France
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
package io.github.pcorless.jpx;

import io.github.pcorless.jpx.codestream.Codestream;
import io.github.pcorless.jpx.codestream.CodestreamReader;
import io.github.pcorless.jpx.internal.Parallel;
import io.github.pcorless.jpx.jp2.Jp2Channels;
import io.github.pcorless.jpx.tile.TileDecoder;
import io.github.pcorless.jpx.tile.TileDecoder.DecodedComponent;

import java.util.ArrayList;
import java.util.List;

/** Decodes a JPEG 2000 codestream (J2K) to its components' samples. */
public final class Jpeg2000Decoder {

    /** A decoded component: samples row-major, no colour conversion, no upsampling. */
    public static final class Component {
        public final int width;
        public final int height;
        public final int precision;
        public final boolean signed;
        public final int[] samples;
        /**
         * The JP2 cdef channel type (0 colour, 1 opacity, 2 premultiplied opacity, 65535
         * unspecified); 0 when there is no cdef box, as OpenJPEG's opj_image_comp_t.alpha.
         */
        public final int channelType;

        public Component(int width, int height, int precision, boolean signed, int[] samples) {
            this(width, height, precision, signed, samples, 0);
        }

        public Component(int width, int height, int precision, boolean signed, int[] samples, int channelType) {
            this.width = width;
            this.height = height;
            this.precision = precision;
            this.signed = signed;
            this.samples = samples;
            this.channelType = channelType;
        }

        public Component withChannelType(int type) {
            return type == channelType ? this : new Component(width, height, precision, signed, samples, type);
        }
    }

    /**
     * System property: the most samples one decode may hold, summed over components, in its output or in
     * one tile's working buffers. {@value #DEFAULT_MAX_SAMPLES} by default.
     */
    public static final String MAX_SAMPLES_PROPERTY = "io.github.pcorless.jpx.maxSamples";

    /**
     * 2^28 samples, about 1 GB as ints: 16384 x 16384 grey or 9459 x 9459 RGB. The largest of 3,744
     * JPEG 2000 streams from real PDFs holds 60 million (DECISIONS.md D-22).
     */
    public static final long DEFAULT_MAX_SAMPLES = 1L << 28;

    private Jpeg2000Decoder() {
    }

    /** {@value #MAX_SAMPLES_PROPERTY} when set to a positive number, otherwise {@value #DEFAULT_MAX_SAMPLES}. */
    public static long defaultMaxSamples() {
        String v = System.getProperty(MAX_SAMPLES_PROPERTY);
        if (v != null) {
            try {
                long max = Long.parseLong(v.trim());
                if (max > 0) return max;
            } catch (NumberFormatException e) {
                // keep the default
            }
        }
        return DEFAULT_MAX_SAMPLES;
    }

    static int ceilDiv(long a, long b) {
        return (int) ((a + b - 1) / b);
    }

    static int ceilDivPow2(long a, int b) {
        return (int) ((a + (1L << b) - 1) >> b);
    }

    /**
     * Decodes a JP2 file or a bare codestream, chosen as OpenJPEG's jpxref sniffs it: a codestream
     * starts FF 4F, anything else is read as JP2. JP2 palettes and channel definitions are applied
     * as opj_decode applies them.
     */
    public static Jpeg2000Image decodeImage(byte[] data, int offset, int length, int reduce) throws JpxException {
        return decodeImage(Jpeg2000Headers.read(data, offset, length), new Options().reduce(reduce));
    }

    /** Decodes an area of the reference grid on the calling thread only (see {@link Options}). */
    public static Jpeg2000Image decodeImage(Jpeg2000Headers h, int reduce, long ax0, long ay0, long ax1, long ay1,
                                            Progress progress) throws JpxException {
        return decodeImage(h, new Options().reduce(reduce).area(ax0, ay0, ax1, ay1).progress(progress)
                .threads(Jpeg2000Threads.NONE));
    }

    /** How to decode: resolution levels discarded, area, progress hook, threads, size limit. Not thread-safe. */
    public static final class Options {
        int reduce;
        long[] area;
        Progress progress;
        Jpeg2000Threads threads;
        long maxSamples;

        /** The most samples the decode may hold; {@link #defaultMaxSamples()} when not set or not positive. */
        public Options maxSamples(long maxSamples) {
            this.maxSamples = maxSamples;
            return this;
        }

        /** Discard {@code reduce} resolution levels (each halves the size). */
        public Options reduce(int reduce) {
            if (reduce < 0) throw new IllegalArgumentException("reduce " + reduce);
            this.reduce = reduce;
            return this;
        }

        /** Decode only {@code [x0, x1) x [y0, y1)} of the reference grid; the whole image by default. */
        public Options area(long x0, long y0, long x1, long y1) {
            this.area = new long[]{x0, y0, x1, y1};
            return this;
        }

        public Options progress(Progress progress) {
            this.progress = progress;
            return this;
        }

        /** Threads to use; {@link Jpeg2000Threads#defaults()} when not set. */
        public Options threads(Jpeg2000Threads threads) {
            this.threads = threads;
            return this;
        }
    }

    public static Jpeg2000Image decodeImage(Jpeg2000Headers h, Options o) throws JpxException {
        Codestream cs = h.codestream;
        long[] a = o.area != null ? o.area : new long[]{cs.x0, cs.y0, cs.x1, cs.y1};
        Jpeg2000Threads threads = o.threads != null ? o.threads : Jpeg2000Threads.defaults();
        List<String> warnings = new ArrayList<>(h.warnings());
        long max = o.maxSamples > 0 ? o.maxSamples : defaultMaxSamples();
        Component[] comps = decode(cs, o.reduce, a[0], a[1], a[2], a[3], o.progress, threads, max);
        warnings.addAll(cs.warnings());
        if (h.jp2 != null) comps = Jp2Channels.apply(h.jp2, comps, warnings);
        return new Jpeg2000Image(comps, h.jp2, warnings);
    }

    /** Decodes the codestream in {@code data[offset, offset + length)} with {@code reduce} levels discarded. */
    public static Component[] decode(byte[] data, int offset, int length, int reduce) throws JpxException {
        return decode(CodestreamReader.read(data, offset, length), reduce);
    }

    /**
     * Called on the decoding (calling) thread as tiles finish, with the count done so far;
     * return false to stop (the components keep what was decoded).
     */
    public interface Progress {
        boolean tileDone(int done, int total);
    }

    public static Component[] decode(Codestream cs, int reduce) throws JpxException {
        return decode(cs, reduce, cs.x0, cs.y0, cs.x1, cs.y1, null, Jpeg2000Threads.NONE);
    }

    /**
     * Decodes the area {@code [ax0, ax1) x [ay0, ay1)} of the reference grid, clipped to the image,
     * as opj_set_decode_area defines the output: each component covers
     * ceildivpow2(ceildiv(a, dx), reduce) of the area's edges. Only tiles that meet the area are
     * decoded, each one whole (OpenJPEG decodes only the code-blocks the area needs; the samples
     * inside the area are the same).
     */
    public static Component[] decode(Codestream cs, int reduce, long ax0, long ay0, long ax1, long ay1,
                                     Progress progress) throws JpxException {
        return decode(cs, reduce, ax0, ay0, ax1, ay1, progress, Jpeg2000Threads.NONE);
    }

    /** As {@link #decode(Codestream, int, long, long, long, long, Progress)}, using {@code threads}. */
    public static Component[] decode(Codestream cs, int reduce, long ax0, long ay0, long ax1, long ay1,
                                     Progress progress, Jpeg2000Threads threads) throws JpxException {
        return decode(cs, reduce, ax0, ay0, ax1, ay1, progress, threads, defaultMaxSamples());
    }

    private static Component[] decode(Codestream cs, int reduce, long ax0, long ay0, long ax1, long ay1,
                                      Progress progress, Jpeg2000Threads threads, long maxSamples)
            throws JpxException {
        // opj_j2k_set_decode_area: clip to the image
        int x0 = (int) Math.max(ax0, cs.x0), y0 = (int) Math.max(ay0, cs.y0);
        int x1 = (int) Math.min(ax1, cs.x1), y1 = (int) Math.min(ay1, cs.y1);
        if (x0 >= x1 || y0 >= y1) throw new JpxException("decode area " + ax0 + "," + ay0 + "," + ax1 + "," + ay1
                + " is outside the image");
        int nc = cs.components.length;
        int[] cx0 = new int[nc], cy0 = new int[nc], cw = new int[nc], ch = new int[nc];
        int[][] samples = new int[nc][];
        for (int c = 0; c < nc; c++) {
            Codestream.Component ic = cs.components[c];
            int cx = ceilDivPow2(ceilDiv(x0, ic.dx), reduce);
            int cy = ceilDivPow2(ceilDiv(y0, ic.dy), reduce);
            int cx1 = ceilDivPow2(ceilDiv(x1, ic.dx), reduce);
            int cy1 = ceilDivPow2(ceilDiv(y1, ic.dy), reduce);
            cx0[c] = cx;
            cy0[c] = cy;
            cw[c] = cx1 - cx;
            ch[c] = cy1 - cy;
            long size = (long) cw[c] * ch[c];
            if (size > Integer.MAX_VALUE - 8) throw JpxException.unsupported("component of " + size + " samples");
        }
        // refuse before allocating anything: the output, and the working buffers of the largest tile
        // (decoded whole, whatever the area), each summed over the components
        long output = 0, tileSamples = 0;
        int[] widestX = new int[256], widestY = new int[256]; // by subsampling (1-255); 0 = not yet
        for (int c = 0; c < nc; c++) {
            Codestream.Component ic = cs.components[c];
            output += (long) cw[c] * ch[c];
            if (widestX[ic.dx] == 0) widestX[ic.dx] = 1 + widestTile(cs.tileX0, cs.tileWidth, cs.tilesX, cs.x0, cs.x1, ic.dx, reduce);
            if (widestY[ic.dy] == 0) widestY[ic.dy] = 1 + widestTile(cs.tileY0, cs.tileHeight, cs.tilesY, cs.y0, cs.y1, ic.dy, reduce);
            tileSamples += (long) (widestX[ic.dx] - 1) * (widestY[ic.dy] - 1);
        }
        if (output > maxSamples) throw JpxException.tooLarge("image", output, maxSamples);
        if (tileSamples > maxSamples) throw JpxException.tooLarge("tile", tileSamples, maxSamples);
        // tiles that meet the area (B.3)
        int p0 = (int) Math.max(0, ((long) x0 - cs.tileX0) / cs.tileWidth);
        int q0 = (int) Math.max(0, ((long) y0 - cs.tileY0) / cs.tileHeight);
        int p1 = Math.min(cs.tilesX, ceilDiv((long) x1 - cs.tileX0, cs.tileWidth));
        int q1 = Math.min(cs.tilesY, ceilDiv((long) y1 - cs.tileY0, cs.tileHeight));
        int tw = p1 - p0, total = tw * (q1 - q0);
        // several tiles: one tile per work item, each decoded serially; one tile: the threads go
        // to its code-blocks and transforms instead
        boolean tiles = total > 1;
        if (tiles) {
            for (int c = 0; c < nc; c++) samples[c] = new int[cw[c] * ch[c]];
        }
        Jpeg2000Threads inner = tiles ? Jpeg2000Threads.NONE : threads;
        Parallel.forEach(tiles ? threads : Jpeg2000Threads.NONE, total, () -> {
            TileDecoder decoder = new TileDecoder();
            return item -> {
                int t = (q0 + item / tw) * cs.tilesX + p0 + item % tw;
                if (cs.tile(t) == null) {
                    cs.warningsSink("tile " + t + " has no tile-parts; left empty");
                    return;
                }
                DecodedComponent[] tile = decoder.decode(cs, t, reduce, inner);
                for (int c = 0; c < nc; c++) {
                    DecodedComponent dc = tile[c];
                    if (!tiles && dc.x0 == cx0[c] && dc.y0 == cy0[c] && dc.width() == cw[c] && dc.height() == ch[c]) {
                        samples[c] = dc.samples; // one tile that is exactly the output: no copy
                    } else {
                        if (samples[c] == null) samples[c] = new int[cw[c] * ch[c]];
                        copy(dc, samples[c], cx0[c], cy0[c], cw[c], ch[c]);
                    }
                }
            };
        }, progress == null ? null : done -> progress.tileDone(done, total));
        for (int c = 0; c < nc; c++) {
            if (samples[c] == null) samples[c] = new int[cw[c] * ch[c]]; // a tile with no data
        }
        return result(cs, cw, ch, samples);
    }

    /**
     * The widest tile-component along one axis, as opj_tcd_init_tile sizes it: the tile clipped to the
     * image, divided by the subsampling and the discarded levels, both rounding up.
     */
    private static int widestTile(int t0, int size, int count, int i0, int i1, int d, int reduce) {
        int widest = 0;
        for (int i = 0; i < count; i++) {
            long a = Math.max((long) t0 + (long) i * size, i0), b = Math.min((long) t0 + (long) (i + 1) * size, i1);
            widest = Math.max(widest, ceilDivPow2(ceilDiv(b, d), reduce) - ceilDivPow2(ceilDiv(a, d), reduce));
        }
        return widest;
    }

    /** opj_j2k_update_image_data: copy the part of a tile-component inside the output window. */
    private static void copy(DecodedComponent dc, int[] dst, int wx0, int wy0, int ww, int wh) {
        int sx0 = Math.max(dc.x0, wx0), sx1 = Math.min(dc.x1, wx0 + ww);
        int sy0 = Math.max(dc.y0, wy0), sy1 = Math.min(dc.y1, wy0 + wh);
        if (sx0 >= sx1) return;
        for (int y = sy0; y < sy1; y++) {
            System.arraycopy(dc.samples, (y - dc.y0) * dc.width() + (sx0 - dc.x0), dst, (y - wy0) * ww + (sx0 - wx0),
                    sx1 - sx0);
        }
    }

    private static Component[] result(Codestream cs, int[] cw, int[] ch, int[][] samples) {
        int nc = cs.components.length;
        Component[] out = new Component[nc];
        for (int c = 0; c < nc; c++) {
            out[c] = new Component(cw[c], ch[c], cs.components[c].precision, cs.components[c].signed, samples[c]);
        }
        return out;
    }
}
