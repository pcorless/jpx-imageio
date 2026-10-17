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
 * Provenance: PORTED from OpenJPEG src/lib/openjp2/tgt.c @ 6c4a29b0 (BSD-2):
 *   opj_tgt_create, opj_tgt_reset, opj_tgt_decode. Decoder half only.
 *   Changed: nodes are parallel arrays (parent index, value, low) instead of structs with
 *   pointers; the unused "known" flag is dropped (only the encoder reads it).
 *   Algorithm: ITU-T T.800 B.10.2.
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
 * Copyright (c) 2008, 2011-2012, Centre National d'Etudes Spatiales (CNES), FR
 * Copyright (c) 2012, CS Systemes d'Information, France
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
 * Copyright (c) 2008, Jerome Fimes, Communications & Systemes <jerome.fimes@c-s.fr>
 * Copyright (c) 2011-2012, Centre National d'Etudes Spatiales (CNES), France
 * Copyright (c) 2012, CS Systemes d'Information, France
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
package org.icepdf.jpx.tier2;

/**
 * Tag tree (T.800 B.10.2): code-block inclusion and zero bit-plane counts in packet headers.
 */
public final class TagTree {

    /** opj_tgt_reset's initial node value, "not yet known". */
    private static final int UNKNOWN = 999;

    private final int leavesH;
    private final int leavesV;
    private final int[] parent;
    private final int[] value;
    private final int[] low;
    /** Path from a leaf up to the root; a tree never has more than 32 levels. */
    private final int[] stack = new int[32];

    /** opj_tgt_create: a tree over {@code leavesH x leavesV} leaves. */
    public TagTree(int leavesH, int leavesV) {
        if (leavesH <= 0 || leavesV <= 0) {
            throw new IllegalArgumentException("tag tree " + leavesH + "x" + leavesV);
        }
        this.leavesH = leavesH;
        this.leavesV = leavesV;
        int[] nplh = new int[32];
        int[] nplv = new int[32];
        nplh[0] = leavesH;
        nplv[0] = leavesV;
        long numNodes = 0;
        int numLevels = 0;
        long n;
        do {
            n = (long) nplh[numLevels] * nplv[numLevels];
            nplh[numLevels + 1] = (nplh[numLevels] + 1) / 2;
            nplv[numLevels + 1] = (nplv[numLevels] + 1) / 2;
            numNodes += n;
            ++numLevels;
        } while (n > 1);
        if (numNodes > Integer.MAX_VALUE / 4) {
            throw new IllegalArgumentException("tag tree " + leavesH + "x" + leavesV + " too large");
        }
        int count = (int) numNodes;
        parent = new int[count];
        value = new int[count];
        low = new int[count];

        int node = 0;
        int parentNode = leavesH * leavesV;
        int parentNode0 = parentNode;
        for (int i = 0; i < numLevels - 1; ++i) {
            for (int j = 0; j < nplv[i]; ++j) {
                int k = nplh[i];
                while (--k >= 0) {
                    parent[node++] = parentNode;
                    if (--k >= 0) {
                        parent[node++] = parentNode;
                    }
                    ++parentNode;
                }
                if ((j & 1) != 0 || j == nplv[i] - 1) {
                    parentNode0 = parentNode;
                } else {
                    parentNode = parentNode0;
                    parentNode0 += nplh[i];
                }
            }
        }
        parent[node] = -1; // the root
        reset();
    }

    public int leavesH() {
        return leavesH;
    }

    public int leavesV() {
        return leavesV;
    }

    /** opj_tgt_reset. */
    public void reset() {
        java.util.Arrays.fill(value, UNKNOWN);
        java.util.Arrays.fill(low, 0);
    }

    /**
     * opj_tgt_decode: reads bits until the leaf's value is known to be below {@code threshold}
     * or not. Returns true when the leaf's value is less than {@code threshold}.
     */
    public boolean decode(PacketBitReader bio, int leaf, int threshold) {
        int sp = 0;
        int node = leaf;
        while (parent[node] >= 0) {
            stack[sp++] = node;
            node = parent[node];
        }
        int lo = 0;
        for (;;) {
            if (lo > low[node]) {
                low[node] = lo;
            } else {
                lo = low[node];
            }
            while (lo < threshold && lo < value[node]) {
                if (bio.readBit() != 0) {
                    value[node] = lo;
                } else {
                    ++lo;
                }
            }
            low[node] = lo;
            if (sp == 0) {
                break;
            }
            node = stack[--sp];
        }
        return value[node] < threshold;
    }

    /** The leaf's decoded value, or 999 while unknown. */
    public int value(int leaf) {
        return value[leaf];
    }
}
