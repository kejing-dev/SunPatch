#!/usr/bin/env python3
"""Contact sheet: tools/sheet.py OUT.png COLS W SHOT1.png SHOT2.png ... (each scaled to W px wide, name on top)"""
import os
import sys
from PIL import Image, ImageDraw
out, cols, w = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
ims = []
for p in sys.argv[4:]:
    im = Image.open(p).convert("RGB")
    h = int(im.height * w / im.width)
    im = im.resize((w, h))
    d = ImageDraw.Draw(im)
    d.rectangle((0, 0, w, 22), fill=(0, 0, 0))
    d.text((6, 4), os.path.basename(p), fill=(255, 255, 0))
    ims.append(im)
rows = (len(ims) + cols - 1) // cols
H = max(i.height for i in ims)
sheet = Image.new("RGB", (cols * w, rows * H), (40, 40, 40))
for k, im in enumerate(ims):
    sheet.paste(im, ((k % cols) * w, (k // cols) * H))
sheet.save(out)
