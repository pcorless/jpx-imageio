/*
 * Provenance: ORIGINAL. Times libopenjp2 decoding in-process, single-threaded: each file is
 * read into memory once, then decoded `passes` times; prints per-file best time in ms.
 * Usage: opjbench passes file...    Build: see tools/bench/README.md
 * Threads: OPJBENCH_THREADS (default 1), passed to opj_codec_set_threads.
 */
#include <openjpeg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

typedef struct { const unsigned char *data; size_t len, pos; } mem_t;

static OPJ_SIZE_T rd(void *buf, OPJ_SIZE_T n, void *u) {
    mem_t *m = u;
    if (m->pos >= m->len) return (OPJ_SIZE_T) -1;
    if (n > m->len - m->pos) n = m->len - m->pos;
    memcpy(buf, m->data + m->pos, n);
    m->pos += n;
    return n;
}
static OPJ_OFF_T sk(OPJ_OFF_T n, void *u) {
    mem_t *m = u;
    if (n < 0 || (size_t) n > m->len - m->pos) n = (OPJ_OFF_T) (m->len - m->pos);
    m->pos += (size_t) n;
    return n;
}
static OPJ_BOOL st(OPJ_OFF_T n, void *u) {
    mem_t *m = u;
    if (n < 0 || (size_t) n > m->len) return OPJ_FALSE;
    m->pos = (size_t) n;
    return OPJ_TRUE;
}
static void quiet(const char *msg, void *u) { (void) msg; (void) u; }

static int threads = 1;

static int decode(const unsigned char *data, size_t len) {
    mem_t m = {data, len, 0};
    OPJ_CODEC_FORMAT fmt = (len > 1 && data[0] == 0xFF && data[1] == 0x4F) ? OPJ_CODEC_J2K : OPJ_CODEC_JP2;
    opj_codec_t *codec = opj_create_decompress(fmt);
    opj_set_info_handler(codec, quiet, NULL);
    opj_set_warning_handler(codec, quiet, NULL);
    opj_set_error_handler(codec, quiet, NULL);
    opj_dparameters_t p;
    opj_set_default_decoder_parameters(&p);
    opj_setup_decoder(codec, &p);
    opj_codec_set_threads(codec, threads);
    opj_stream_t *s = opj_stream_default_create(OPJ_TRUE);
    opj_stream_set_user_data(s, &m, NULL);
    opj_stream_set_user_data_length(s, len);
    opj_stream_set_read_function(s, rd);
    opj_stream_set_skip_function(s, sk);
    opj_stream_set_seek_function(s, st);
    opj_image_t *img = NULL;
    int ok = opj_read_header(s, codec, &img) && opj_decode(codec, s, img) && opj_end_decompress(codec, s);
    if (img) opj_image_destroy(img);
    opj_stream_destroy(s);
    opj_destroy_codec(codec);
    return ok;
}

static double now(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1e3 + t.tv_nsec / 1e6;
}

int main(int argc, char **argv) {
    int passes = atoi(argv[1]);
    if (getenv("OPJBENCH_THREADS")) threads = atoi(getenv("OPJBENCH_THREADS"));
    for (int i = 2; i < argc; i++) {
        FILE *f = fopen(argv[i], "rb");
        if (!f) continue;
        fseek(f, 0, SEEK_END);
        long n = ftell(f);
        fseek(f, 0, SEEK_SET);
        unsigned char *b = malloc((size_t) n);
        if (fread(b, 1, (size_t) n, f) != (size_t) n) { fclose(f); free(b); continue; }
        fclose(f);
        double best = 1e30;
        int ok = 1;
        for (int k = 0; k < passes; k++) {
            double t0 = now();
            ok &= decode(b, (size_t) n);
            double t = now() - t0;
            if (t < best) best = t;
        }
        printf("%s\t%s\t%.3f\n", argv[i], ok ? "ok" : "fail", best);
        free(b);
    }
    return 0;
}
