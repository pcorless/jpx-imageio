#!/usr/bin/env python3
# Provenance: ORIGINAL. Replaces the //UPSTREAM// line in a Java file with the verbatim OpenJPEG
# copyright blocks of the given source files (see upstream_header.py).
# Usage: python3 -I tools/insert_upstream.py File.java upstream.c [upstream.h ...]
import os
import subprocess
import sys

java, ups = sys.argv[1], sys.argv[2:]
here = os.path.dirname(os.path.abspath(__file__))
block = subprocess.run([sys.executable, "-I", os.path.join(here, "upstream_header.py")] + ups,
                       capture_output=True, text=True, check=True).stdout.rstrip()
text = open(java, encoding="utf-8").read()
if "//UPSTREAM//" not in text:
    sys.exit("no //UPSTREAM// marker in " + java)
open(java, "w", encoding="utf-8").write(text.replace("//UPSTREAM//", block, 1))
