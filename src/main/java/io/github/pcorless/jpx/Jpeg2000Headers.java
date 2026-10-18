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
 * Provenance: ADAPTED + ORIGINAL. ADAPTED from OpenJPEG src/lib/openjp2/j2k.c @ 6c4a29b0 (BSD-2):
 *   the IHDR/SIZ size check and the BPC != 255 warning of opj_j2k_read_siz, and the
 *   reduce limit of opj_j2k_read_SPCod_SPCoc (reduce must be below every COD/COC's number
 *   of resolutions). ORIGINAL: the format sniff (as jpxref.c) and the holder.
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
import io.github.pcorless.jpx.jp2.Jp2Header;
import io.github.pcorless.jpx.jp2.Jp2Reader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The parsed headers of a JP2 file or bare codestream: everything known before decoding. */
public final class Jpeg2000Headers {

    /** Null for a bare codestream. */
    public final Jp2Header jp2;
    public final Codestream codestream;
    private final List<String> warnings;

    private Jpeg2000Headers(Jp2Header jp2, Codestream codestream, List<String> warnings) {
        this.jp2 = jp2;
        this.codestream = codestream;
        this.warnings = Collections.unmodifiableList(warnings);
    }

    /** True when the bytes start like a codestream (SOC then SIZ) or a JP2 signature box. */
    public static boolean sniff(byte[] b, int offset, int length) {
        if (length >= 4 && (b[offset] & 0xff) == 0xff && (b[offset + 1] & 0xff) == 0x4f
                && (b[offset + 2] & 0xff) == 0xff && (b[offset + 3] & 0xff) == 0x51) {
            return true;
        }
        return Jp2Reader.isJp2(b, offset, length);
    }

    /** As jpxref.c: a codestream starts FF 4F, anything else is read as JP2. */
    public static Jpeg2000Headers read(byte[] data, int offset, int length) throws JpxException {
        if (length >= 2 && (data[offset] & 0xff) == 0xff && (data[offset + 1] & 0xff) == 0x4f) {
            return new Jpeg2000Headers(null, CodestreamReader.read(data, offset, length), new ArrayList<>());
        }
        Jp2Header h = Jp2Reader.read(data, offset, length);
        Codestream cs = CodestreamReader.read(data, h.codestreamOffset, h.codestreamLength);
        // opj_j2k_read_siz
        long sizW = (long) cs.x1 - cs.x0, sizH = (long) cs.y1 - cs.y0;
        if (h.width != sizW || h.height != sizH) {
            throw new JpxException("Error with SIZ marker: IHDR w(" + h.width + ") h(" + h.height + ") vs. SIZ w("
                    + sizW + ") h(" + sizH + ")");
        }
        List<String> warnings = new ArrayList<>(h.warnings());
        if (h.bitsPerComponent != 255) {
            for (int c = 1; c < cs.components.length; c++) {
                if (cs.components[c].precision != cs.components[0].precision
                        || cs.components[c].signed != cs.components[0].signed) {
                    warnings.add("Despite JP2 BPC!=255, precision and/or sgnd values for comp[" + c
                            + "] is different than comp[0]");
                }
            }
        }
        return new Jpeg2000Headers(h, cs, warnings);
    }

    /**
     * The most resolution levels every tile-component can discard. OpenJPEG refuses a larger
     * reduce ("The number of resolutions to remove (%d) is greater or equal than the number of
     * resolutions of this component").
     */
    public int maxReduce() {
        int max = Codestream.MAX_RESOLUTIONS;
        for (int t = 0; t < codestream.numTiles(); t++) {
            for (Codestream.ComponentCoding cc : codestream.coding(t).components) {
                max = Math.min(max, cc.numResolutions - 1);
            }
        }
        return Math.max(0, max);
    }

    public List<String> warnings() {
        return warnings;
    }
}
