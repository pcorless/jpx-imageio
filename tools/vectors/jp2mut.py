#!/usr/bin/env python3
# Copyright 2026 Patrick Corless
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Provenance: ORIGINAL. Box-level mutants of the JP2 fixtures, each decoded by libopenjp2 (via
# jpxref, the same oracle as the fixtures) to record what OpenJPEG does with it: "fail", or
# "ok" plus a SHA-1 of the decoded components. Jp2MutantTest replays them.
#
# Usage: python3 -I tools/vectors/jp2mut.py   (OPJ_BIN as for the fixtures; default
#        ~/dev/git/openjpeg/build-strict/bin). Writes src/test/resources/jp2mut/.
import hashlib
import os
import struct
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
FIX = os.path.join(ROOT, "src", "test", "resources", "fixtures")
OUT = os.path.join(ROOT, "src", "test", "resources", "jp2mut")
OPJ = os.environ.get("OPJ_BIN", os.path.expanduser("~/dev/git/openjpeg/build-strict/bin"))
JPXREF = os.path.join(OPJ, "jpxref")


def box(t, payload):
    return struct.pack(">I", 8 + len(payload)) + t + payload


def parse(b):
    """Top-level boxes as [type, payload]; the last box's payload runs to EOF."""
    out, p = [], 0
    while p < len(b):
        n, t = struct.unpack(">I4s", b[p:p + 8])
        n = len(b) - p if n == 0 else n
        out.append([t, b[p + 8:p + n]])
        p += n
    return out


def build(boxes):
    return b"".join(box(t, d) for t, d in boxes)


def get(boxes, t):
    return next(d for bt, d in boxes if bt == t)


def put(boxes, t, d):
    return [[bt, d if bt == t else bd] for bt, bd in boxes]


def sub(boxes):
    return parse(get(boxes, b"jp2h"))


def with_jp2h(boxes, subs):
    return put(boxes, b"jp2h", build(subs))


def fixture(name):
    return parse(open(os.path.join(FIX, name + ".jp2"), "rb").read())


def colr_enum(cs):
    return box(b"colr", struct.pack(">BBBI", 1, 0, 0, cs))


def ihdr(h, w, nc, bpc=7):
    return box(b"ihdr", struct.pack(">IIHBBBB", h, w, nc, bpc, 7, 0, 0))


def mutants():
    rgb, gray, pclr, rgba, icc = (fixture(n) for n in
                                  ("jp2-rgb", "jp2-gray", "jp2-pclr-rgb", "jp2-cdef-rgba", "jp2-icc-gray"))
    m = []

    def add(name, data, why):
        m.append((name, data, why))

    jp, ftyp, jp2h, jp2c = (rgb[i] for i in range(4))
    add("ok-rgb", build(rgb), "control")
    add("jp-bad-magic", build([[b"jP  ", b"\r\n\x87\x0b"]] + rgb[1:]), "signature magic")
    add("jp-size-8", build([[b"jP  ", b"\r\n\x87\n\0\0\0\0"]] + rgb[1:]), "signature box size")
    add("ftyp-first", build([ftyp, jp] + rgb[2:]), "ftyp before signature")
    add("ftyp-missing", build([jp] + rgb[2:]), "no ftyp")
    add("ftyp-twice", build([jp, ftyp, ftyp] + rgb[2:]), "second ftyp")
    add("ftyp-odd-size", build([jp, [b"ftyp", ftyp[1] + b"xx"]] + rgb[2:]), "ftyp compat list not x4")
    add("ftyp-short", build([jp, [b"ftyp", b"jp2 "]] + rgb[2:]), "ftyp < 8 bytes")
    add("jp2h-missing", build([jp, ftyp, jp2c]), "no jp2h")
    add("jp2c-before-jp2h", build([jp, ftyp, jp2c, jp2h]), "codestream before header")
    add("unknown-before-ftyp", build([jp, [b"xml ", b"<a/>"], ftyp] + rgb[2:]), "unknown box before ftyp")
    add("unknown-between", build([jp, ftyp, [b"xml ", b"<a/>"], jp2h, [b"uuid", b"\0" * 20], jp2c]),
        "unknown boxes skipped")
    s = sub(rgb)
    add("ihdr-missing", build(with_jp2h(rgb, [b for b in s if b[0] != b"ihdr"])), "jp2h without ihdr")
    add("ihdr-twice", build(with_jp2h(rgb, [s[0]] + [[b"ihdr", struct.pack(">IIHBBBB", 9, 9, 1, 7, 7, 0, 0)]]
                                      + s[1:])), "second ihdr ignored")
    add("ihdr-size-13", build(with_jp2h(rgb, [[b"ihdr", s[0][1][:13]]] + s[1:])), "ihdr bad size")
    add("ihdr-zero-comps", build(with_jp2h(rgb, [[b"ihdr", struct.pack(">IIHBBBB", 64, 64, 0, 7, 7, 0, 0)]]
                                           + s[1:])), "ihdr nc 0")
    add("ihdr-width-mismatch", build(with_jp2h(rgb, [[b"ihdr", struct.pack(">IIHBBBB", 64, 63, 3, 7, 7, 0, 0)]]
                                               + s[1:])), "ihdr vs SIZ")
    add("ihdr-comps-mismatch", build(with_jp2h(rgb, [[b"ihdr", struct.pack(">IIHBBBB", 64, 64, 4, 7, 7, 0, 0)]]
                                               + s[1:])), "ihdr nc vs SIZ (not checked by OpenJPEG)")
    add("ihdr-c-not-7", build(with_jp2h(rgb, [[b"ihdr", struct.pack(">IIHBBBB", 64, 64, 3, 7, 3, 0, 0)]]
                                        + s[1:])), "compression type")
    add("bpcc-ok", build(with_jp2h(rgb, [[b"ihdr", struct.pack(">IIHBBBB", 64, 64, 3, 255, 7, 0, 0)]]
                                   + s[1:] + [[b"bpcc", b"\x07\x07\x07"]])), "bpcc with BPC 255")
    add("bpcc-bad-size", build(with_jp2h(rgb, s + [[b"bpcc", b"\x07\x07"]])), "bpcc size != nc")
    add("bpcc-before-ihdr", build(with_jp2h(rgb, [[b"bpcc", b"\x07\x07\x07"]] + s)), "bpcc before ihdr")
    colr = [b for b in s if b[0] == b"colr"][0]
    add("colr-missing", build(with_jp2h(rgb, [b for b in s if b[0] != b"colr"])), "no colr")
    add("colr-short", build(with_jp2h(rgb, s + [[b"colr", b"\x01\0"]])), "later colr < 3 bytes")
    add("colr-meth1-short", build(with_jp2h(rgb, [s[0], [b"colr", b"\x01\0\0\0"]])), "meth 1 < 7 bytes")
    add("colr-meth3-then-gray", build(with_jp2h(rgb, [s[0], [b"colr", b"\x03\0\0\0\0\0\x10"],
                                                     [b"colr", b"\x01\0\0\0\0\0\x11"]])), "meth 3 ignored")
    add("colr-two", build(with_jp2h(rgb, s + [[b"colr", b"\x01\0\0\0\0\0\x11"]])), "second colr ignored")
    add("colr-icc-empty", build(with_jp2h(rgb, [s[0], [b"colr", b"\x02\0\0"]])), "empty ICC")
    add("colr-cielab-35", build(with_jp2h(rgb, [s[0], [b"colr", b"\x01\0\0" + struct.pack(">I", 14)
                                                      + struct.pack(">7I", 100, 0, 255, 128, 255, 128,
                                                                    0x00443530)]])), "CIELab params")
    add("colr-misplaced-before", build([jp, ftyp, colr, jp2h, jp2c]), "colr before jp2h ignored")
    add("colr-misplaced-after", build([jp, ftyp, jp2h, [b"colr", b"\x01\0\0\0\0\0\x11"], jp2c]),
        "colr after jp2h (already have one)")
    add("res-ok", build(with_jp2h(rgb, s + [[b"res ", box(b"resc", struct.pack(">HHHHbb", 300, 1, 300, 1, 2, 2))
                                            + box(b"resd", struct.pack(">HHHHbb", 72, 1, 72, 1, 0, 0))]])),
        "res box")
    add("res-garbage", build(with_jp2h(rgb, s + [[b"res ", b"\0\0\0\x03abcd"]])), "malformed res")
    add("jp2h-sub-overrun", build(with_jp2h(rgb, s)[:2] + [[b"jp2h", get(rgb, b"jp2h")
                                                            + struct.pack(">I", 99) + b"xml "]] + [jp2c]),
        "jp2h sub-box past end")
    add("jp2h-trailing-4", build([jp, ftyp, [b"jp2h", get(rgb, b"jp2h") + b"\0\0\0\0"], jp2c]),
        "jp2h with 4 stray bytes")
    add("jp2h-sub-zero", build([jp, ftyp, [b"jp2h", get(rgb, b"jp2h") + b"\0\0\0\0xml "], jp2c]),
        "jp2h sub-box length 0")
    xl = build([jp, ftyp])
    xl += struct.pack(">I4sII", 1, b"jp2h", 0, 16 + len(get(rgb, b"jp2h"))) + get(rgb, b"jp2h")
    add("jp2h-xl", xl + box(b"jp2c", get(rgb, b"jp2c")), "XL box length")
    xlhi = build([jp, ftyp])
    xlhi += struct.pack(">I4sII", 1, b"jp2h", 1, 16 + len(get(rgb, b"jp2h"))) + get(rgb, b"jp2h")
    add("jp2h-xl-high", xlhi + box(b"jp2c", get(rgb, b"jp2c")), "XL box > 2^32")
    add("jp2c-length-0", build(rgb[:3]) + struct.pack(">I", 0) + b"jp2c" + get(rgb, b"jp2c"),
        "jp2c to end of file")
    add("jp2c-length-short", build(rgb[:3]) + struct.pack(">I", 100) + b"jp2c" + get(rgb, b"jp2c"),
        "jp2c length too small: ignored")
    add("box-length-4", build([jp, ftyp]) + struct.pack(">I", 4) + b"xml " + build(rgb[2:]), "box length < 8")
    add("box-past-eof", build(rgb[:3]) + struct.pack(">I", 1 << 20) + b"xml " + build(rgb[3:]),
        "unknown box past EOF")
    add("truncated-in-jp2h", build(rgb)[:60], "file ends in jp2h")
    add("trailing-box-after-jp2c", build(rgb) + box(b"xml ", b"<a/>"), "box after the codestream")
    add("trailing-bad-colr", build(rgb) + struct.pack(">I", 1 << 20) + b"colr",
        "truncated known box after the codestream")

    # palettes: jp2-pclr-rgb is 1 component, 16 entries, 3 columns
    s = sub(pclr)
    names = [b[0] for b in s]
    pc, cm = s[names.index(b"pclr")], s[names.index(b"cmap")]
    add("pclr-ok", build(pclr), "control")
    add("pclr-without-cmap", build(with_jp2h(pclr, [b for b in s if b[0] != b"cmap"])), "pclr ignored")
    add("cmap-without-pclr", build(with_jp2h(pclr, [b for b in s if b[0] != b"pclr"])), "cmap needs pclr")
    add("cmap-before-pclr", build(with_jp2h(pclr, [b for b in s if b[0] not in (b"pclr", b"cmap")] + [cm, pc])),
        "cmap before pclr")
    add("pclr-twice", build(with_jp2h(pclr, s + [pc])), "second pclr")
    add("cmap-twice", build(with_jp2h(pclr, s + [cm])), "second cmap")
    ne, npc = struct.unpack(">HB", pc[1][:3])
    hdr = pc[1][3:3 + npc]
    add("pclr-ne-0", build(with_jp2h(pclr, [[b"pclr", b"\0\0" + pc[1][2:]] if b[0] == b"pclr" else b for b in s])),
        "NE 0")
    add("pclr-ne-1025", build(with_jp2h(pclr, [[b"pclr", struct.pack(">H", 1025) + pc[1][2:]]
                                               if b[0] == b"pclr" else b for b in s])), "NE 1025")
    add("pclr-npc-0", build(with_jp2h(pclr, [[b"pclr", pc[1][:2] + b"\0"] if b[0] == b"pclr" else b for b in s])),
        "NPC 0")
    add("pclr-truncated", build(with_jp2h(pclr, [[b"pclr", pc[1][:-5]] if b[0] == b"pclr" else b for b in s])),
        "entries cut short")
    four = struct.pack(">HB", 4, npc) + hdr + pc[1][3 + npc:3 + npc + 4 * npc]
    add("pclr-index-clamped", build(with_jp2h(pclr, [[b"pclr", four] if b[0] == b"pclr" else b for b in s])),
        "indices above NE-1 clamp")
    wide = struct.pack(">HB", ne, npc) + bytes([0x8b, 0x0f, 0x1f])  # 12s, 16u, 32u
    for j in range(ne):
        wide += struct.pack(">hHI", j * 100 - 800, j * 4000, j * 123456789)
    add("pclr-wide-signed", build(with_jp2h(pclr, [[b"pclr", wide] if b[0] == b"pclr" else b for b in s])),
        "12-bit signed, 16 and 32-bit columns")
    def cmap(*entries):
        return [b"cmap", b"".join(struct.pack(">HBB", *e) for e in entries)]
    def with_cmap(c):
        return build(with_jp2h(pclr, [c if b[0] == b"cmap" else b for b in s]))
    add("cmap-cmp-range", with_cmap(cmap((0, 1, 0), (1, 1, 1), (0, 1, 2))), "cmp >= numcomps")
    add("cmap-mtyp-2", with_cmap(cmap((0, 1, 0), (0, 2, 1), (0, 1, 2))), "MTYP 2")
    add("cmap-pcol-swap", with_cmap(cmap((0, 1, 1), (0, 1, 0), (0, 1, 2))), "pcol != i")
    add("cmap-pcol-range", with_cmap(cmap((0, 1, 0), (0, 1, 1), (0, 1, 7))), "pcol >= NPC")
    add("cmap-all-direct", with_cmap(cmap((0, 0, 0), (0, 0, 0), (0, 0, 0))), "weird cmap corrected")
    add("cmap-direct-pcol", with_cmap(cmap((0, 0, 1), (0, 1, 1), (0, 1, 2))), "direct use with pcol 1")
    add("cmap-mixed", with_cmap(cmap((0, 0, 0), (0, 1, 1), (0, 1, 2))), "direct + palette")
    add("cmap-short", with_cmap([b"cmap", cm[1][:10]]), "cmap too short")
    add("pclr-misplaced-after", build([pclr[0], pclr[1], with_jp2h(pclr, [b for b in s if b[0] not in
                                                                             (b"pclr", b"cmap")])[2], pc, cm,
                                       pclr[3]]), "pclr+cmap after jp2h are applied")

    # channel definitions: jp2-cdef-rgba is RGB + opacity
    s = sub(rgba)
    def cdef(*entries):
        return [b"cdef", struct.pack(">H", len(entries)) + b"".join(struct.pack(">HHH", *e) for e in entries)]
    def with_cdef(c):
        return build(with_jp2h(rgba, [b for b in s if b[0] != b"cdef"] + ([c] if c else [])))
    add("cdef-ok", build(rgba), "control")
    add("cdef-swap", with_cdef(cdef((0, 0, 3), (1, 0, 2), (2, 0, 1), (3, 1, 0))), "colour order 3,2,1")
    add("cdef-opacity-first", with_cdef(cdef((0, 1, 0), (1, 0, 1), (2, 0, 2), (3, 0, 3))), "alpha in channel 0")
    add("cdef-premultiplied", with_cdef(cdef((0, 0, 1), (1, 0, 2), (2, 0, 3), (3, 2, 0))), "premultiplied")
    add("cdef-unspecified", with_cdef(cdef((0, 0, 1), (1, 0, 2), (2, 0, 3), (3, 65535, 65535))), "unspecified")
    add("cdef-incomplete", with_cdef(cdef((0, 0, 1), (1, 0, 2), (3, 1, 0))), "channel 2 missing")
    add("cdef-cn-range", with_cdef(cdef((0, 0, 1), (1, 0, 2), (2, 0, 3), (3, 1, 0), (4, 1, 0))), "cn 4")
    add("cdef-asoc-range", with_cdef(cdef((0, 0, 1), (1, 0, 2), (2, 0, 3), (3, 1, 9))), "asoc 9")
    add("cdef-duplicate", with_cdef(cdef((0, 0, 1), (1, 0, 2), (2, 0, 3), (3, 1, 0), (0, 0, 1))), "cn 0 twice")
    add("cdef-n-0", with_cdef([b"cdef", b"\0\0"]), "N 0")
    add("cdef-short", with_cdef([b"cdef", cdef((0, 0, 1), (1, 0, 2))[1][:9]]), "cdef too short")
    add("cdef-twice", build(with_jp2h(rgba, s + [b for b in s if b[0] == b"cdef"])), "second cdef")
    # pclr + cdef: cdef indexes the palette channels
    s2 = sub(pclr)
    add("pclr-cdef", build(with_jp2h(pclr, s2 + [cdef((0, 0, 3), (1, 0, 2), (2, 0, 1))])), "cdef on cmap channels")
    add("pclr-cdef-range", build(with_jp2h(pclr, s2 + [cdef((0, 0, 1), (1, 0, 2), (2, 0, 3), (3, 1, 0))])),
        "cdef channel past NPC")
    return m


def digest(prefix, n):
    """SHA-1 over each component as w, h, prec, sgnd (u32 x3, u8) then samples as s32, all BE."""
    h = hashlib.sha1()
    for c in range(n):
        b = open("%s.%d.pgx" % (prefix, c), "rb").read()
        nl = b.index(b"\n")
        f = b[:nl].split()
        sgnd, prec, w, hh = f[2] == b"-", int(f[3]), int(f[4]), int(f[5])
        k = 1 if prec <= 8 else 2 if prec <= 16 else 4
        h.update(struct.pack(">IIIB", w, hh, prec, 1 if sgnd else 0))
        p = nl + 1
        for i in range(w * hh):
            v = int.from_bytes(b[p:p + k], "big", signed=sgnd and k < 4)
            if k == 4:
                v = int.from_bytes(b[p:p + 4], "big", signed=True)
            h.update(struct.pack(">i", v))
            p += k
    return h.hexdigest()


def main():
    if not os.access(JPXREF, os.X_OK):
        sys.exit("missing " + JPXREF)
    os.makedirs(OUT, exist_ok=True)
    rows = ["# name\toutcome\tcomponents\tsha1\twhat (libopenjp2 v2.5.4 via jpxref, strict build)"]
    with tempfile.TemporaryDirectory() as tmp:
        for name, data, why in mutants():
            path = os.path.join(OUT, name + ".jp2")
            open(path, "wb").write(data)
            prefix = os.path.join(tmp, name)
            r = subprocess.run([JPXREF, path, prefix], capture_output=True, text=True, timeout=60)
            if r.returncode == 0:
                n = int(r.stdout.strip())
                rows.append("\t".join((name, "ok", str(n), digest(prefix, n), why)))
            else:
                rows.append("\t".join((name, "fail", "", "", why)))
    open(os.path.join(OUT, "expected.tsv"), "w").write("\n".join(rows) + "\n")
    print("\n".join(rows))


if __name__ == "__main__":
    main()
