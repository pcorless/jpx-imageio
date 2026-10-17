#!/usr/bin/env python3
# Provenance: ORIGINAL. Prints the verbatim copyright/licence comment block of OpenJPEG source
# files, so derived Java files carry it unchanged (BSD-2 clause 1). With several files, the
# blocks must be identical or each is printed.
# Usage: python3 -I tools/upstream_header.py ~/dev/git/openjpeg/src/lib/openjp2/mqc.c [...]
import sys

blocks = []
for path in sys.argv[1:]:
    text = open(path, encoding="utf-8").read()
    end = text.index("*/") + 2
    block = text[:end].rstrip()
    if block not in blocks:
        blocks.append(block)
print("\n".join(blocks))
