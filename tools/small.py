#!/usr/bin/env python3
"""Downscale a screenshot for a quick look: tools/small.py IN.png OUT.png [factor=3] [crop x0,y0,x1,y1]"""
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB")
if len(sys.argv) > 4:
    im = im.crop(tuple(int(v) for v in sys.argv[4].split(",")))
k = float(sys.argv[3]) if len(sys.argv) > 3 else 3
im.resize((int(im.width / k), int(im.height / k))).save(sys.argv[2])
