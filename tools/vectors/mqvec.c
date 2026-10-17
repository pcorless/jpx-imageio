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
 * Provenance: ORIGINAL. Test-vector generator: drives OpenJPEG's own MQ and raw (bypass)
 * ENCODER (internal API, linked from libopenjp2.a) on seeded pseudo-random symbols and prints
 * symbols + encoded bytes, so the Java decoder is checked against the reference implementation.
 * No OpenJPEG code is copied. Dev tool, never shipped.
 *
 * Build (from an OpenJPEG v2.5.4 build tree):
 *   gcc -O2 -o mqvec mqvec.c -I$OPJ/src/lib/openjp2 -I$OPJ/build/src/lib/openjp2 \
 *       $OPJ/build/bin/libopenjp2.a -lm -lpthread
 * Output format, one vector per stanza:
 *   V <name> <mq|raw> <count>
 *   S <ctx>:<bit> ...      (ctx is -1 for raw)
 *   B <hex bytes>
 */
#include "opj_includes.h"
#include <stdio.h>

static unsigned int rng = 613;
static unsigned int next(void) { rng = rng * 1103515245u + 12345u; return (rng >> 8) & 0xffffff; }

static void t1_init(opj_mqc_t *mqc) {
    /* the tier-1 context initialisation (T.800 Table D.7): UNI=18 state 46, AGG=17 state 3, ZC0 state 4 */
    opj_mqc_resetstates(mqc);
    opj_mqc_setstate(mqc, 18, 0, 46);
    opj_mqc_setstate(mqc, 17, 0, 3);
    opj_mqc_setstate(mqc, 0, 0, 4);
}

static void mq_vector(const char *name, int count, int skew, int nctx) {
    opj_mqc_t mqc;
    static OPJ_BYTE buf[1 << 20];
    int *ctx = opj_malloc(sizeof(int) * (size_t) count), *bit = opj_malloc(sizeof(int) * (size_t) count);
    memset(buf, 0, sizeof buf);
    opj_mqc_init_enc(&mqc, buf + 1);
    t1_init(&mqc);
    for (int i = 0; i < count; i++) {
        ctx[i] = (int) (next() % (unsigned) nctx);
        /* skewed source: each context has its own preferred symbol */
        int pref = ctx[i] & 1;
        bit[i] = (int) (next() % 1000) < skew ? pref : 1 - pref;
        opj_mqc_setcurctx(&mqc, ctx[i]);
        opj_mqc_t *m = &mqc;
        opj_mqc_encode_macro(m, m->curctx, m->a, m->c, m->ct, (OPJ_UINT32) bit[i]);
    }
    opj_mqc_flush(&mqc);
    OPJ_UINT32 n = opj_mqc_numbytes(&mqc);
    printf("V %s mq %d\nS", name, count);
    for (int i = 0; i < count; i++) printf(" %d:%d", ctx[i], bit[i]);
    printf("\nB ");
    for (OPJ_UINT32 i = 0; i < n; i++) printf("%02x", buf[1 + i]);
    printf("\n");
    opj_free(ctx);
    opj_free(bit);
}

static void raw_vector(const char *name, int count, int ffrich) {
    opj_mqc_t mqc;
    static OPJ_BYTE buf[1 << 20];
    int *bit = opj_malloc(sizeof(int) * (size_t) count);
    memset(buf, 0, sizeof buf);
    opj_mqc_init_enc(&mqc, buf + 1);
    /* bypass coding normally follows an MQ flush, which leaves bp at the next free byte;
       init_enc leaves it one before the start, so place it at the start (buf[0] stays 0) */
    mqc.bp = mqc.start;
    opj_mqc_bypass_init_enc(&mqc);
    for (int i = 0; i < count; i++) {
        /* ffrich: mostly ones, so 0xFF bytes and their bit stuffing are exercised */
        bit[i] = ffrich ? ((next() % 100) < 95) : (int) (next() & 1);
        opj_mqc_bypass_enc(&mqc, (OPJ_UINT32) bit[i]);
    }
    opj_mqc_bypass_flush_enc(&mqc, OPJ_FALSE);
    OPJ_UINT32 n = (OPJ_UINT32) (mqc.bp - mqc.start);
    printf("V %s raw %d\nS", name, count);
    for (int i = 0; i < count; i++) printf(" -1:%d", bit[i]);
    printf("\nB ");
    for (OPJ_UINT32 i = 0; i < n; i++) printf("%02x", buf[1 + i]);
    printf("\n");
    opj_free(bit);
}

int main(void) {
    printf("# MQ/raw test vectors from OpenJPEG v2.5.4 (6c4a29b0) encoder, tools/vectors/mqvec.c\n");
    mq_vector("mq-1ctx-balanced", 2000, 500, 1);
    mq_vector("mq-1ctx-skewed", 4000, 980, 1);
    mq_vector("mq-19ctx-mixed", 8000, 850, 19);
    mq_vector("mq-19ctx-extreme", 8000, 999, 19);
    mq_vector("mq-short", 3, 500, 2);
    raw_vector("raw-random", 3000, 0);
    raw_vector("raw-ff-rich", 3000, 1);
    return 0;
}
