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
 * Provenance: ORIGINAL. Test-vector generator: OpenJPEG's own tag-tree and packet-header bit
 * ENCODERS (opj_tgt_*, opj_bio_*, linked from libopenjp2.a) run the inclusion / zero-bit-plane
 * protocol of a packet header (T.800 B.10.4, B.10.5) over random trees, with raw bits mixed in.
 * No OpenJPEG code is copied. Dev tool, never shipped. Build like mqvec.c.
 *
 * Output, one stanza per tree:
 *   T <w> <h> <layers>
 *   I <first-inclusion layer per leaf, 999 = never>
 *   Z <zero bit-planes per leaf>
 *   R <3-bit raw value written after each first inclusion, in coding order>
 *   B <hex bytes>
 */
#include "opj_includes.h"
#include <stdio.h>

static unsigned int rng = 613;
static unsigned int next(void) { rng = rng * 1103515245u + 12345u; return (rng >> 8) & 0xffffff; }

static void vector(OPJ_UINT32 w, OPJ_UINT32 h, int layers) {
    opj_event_mgr_t mgr;
    memset(&mgr, 0, sizeof mgr);
    opj_tgt_tree_t *incl = opj_tgt_create(w, h, &mgr), *imsb = opj_tgt_create(w, h, &mgr);
    OPJ_UINT32 n = w * h;
    int *first = opj_malloc(sizeof(int) * n), *zero = opj_malloc(sizeof(int) * n), *raw = opj_malloc(sizeof(int) * n);
    int nraw = 0;
    for (OPJ_UINT32 i = 0; i < n; i++) {
        first[i] = (next() % 8) == 0 ? 999 : (int) (next() % (unsigned) layers);
        zero[i] = (int) (next() % 13);
        opj_tgt_setvalue(incl, i, first[i]);
        opj_tgt_setvalue(imsb, i, zero[i]);
    }
    static OPJ_BYTE buf[1 << 16];
    opj_bio_t *bio = opj_bio_create();
    opj_bio_init_enc(bio, buf, sizeof buf);
    for (int l = 0; l < layers; l++) {
        for (OPJ_UINT32 i = 0; i < n; i++) {
            if (first[i] < l) continue; /* already included: a real header codes one bit, not the tree */
            opj_tgt_encode(bio, incl, i, l + 1);
            if (first[i] == l) {
                opj_tgt_encode(bio, imsb, i, 999);
                raw[nraw] = (int) (next() % 8);
                opj_bio_write(bio, (OPJ_UINT32) raw[nraw++], 3);
            }
        }
    }
    opj_bio_flush(bio);
    ptrdiff_t len = opj_bio_numbytes(bio);
    printf("T %u %u %d\nI", w, h, layers);
    for (OPJ_UINT32 i = 0; i < n; i++) printf(" %d", first[i]);
    printf("\nZ");
    for (OPJ_UINT32 i = 0; i < n; i++) printf(" %d", zero[i]);
    printf("\nR");
    for (int i = 0; i < nraw; i++) printf(" %d", raw[i]);
    printf("\nB ");
    for (ptrdiff_t i = 0; i < len; i++) printf("%02x", buf[i]);
    printf("\n");
    opj_bio_destroy(bio);
    opj_tgt_destroy(incl);
    opj_tgt_destroy(imsb);
    opj_free(first);
    opj_free(zero);
    opj_free(raw);
}

int main(void) {
    printf("# tag-tree + packet-header bit vectors from OpenJPEG v2.5.4 (6c4a29b0), tools/vectors/tgtvec.c\n");
    vector(1, 1, 1);
    vector(1, 1, 4);
    vector(2, 1, 3);
    vector(1, 7, 3);
    vector(3, 3, 5);
    vector(8, 8, 6);
    vector(9, 5, 4);
    vector(16, 16, 3);
    vector(31, 2, 7);
    for (int k = 0; k < 20; k++) vector(1 + next() % 12, 1 + next() % 12, 1 + (int) (next() % 6));
    return 0;
}
