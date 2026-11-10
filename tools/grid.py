#!/usr/bin/env python3
"""tools/grid.py IN.png OUT.png x0,y0,x1,y1 [step=50]: crop at 1:1 with a labelled pixel grid (to pick contrast boxes)."""
import sys
from PIL import Image, ImageDraw
im = Image.open(sys.argv[1]).convert("RGB")
x0, y0, x1, y1 = (int(v) for v in sys.argv[3].split(","))
step = int(sys.argv[4]) if len(sys.argv) > 4 else 50
c = im.crop((x0, y0, x1, y1))
d = ImageDraw.Draw(c)
for x in range((x0 // step + 1) * step, x1, step):
    d.line((x - x0, 0, x - x0, c.height), fill=(255, 0, 255), width=1)
    d.text((x - x0 + 2, 2), str(x), fill=(255, 0, 255))
for y in range((y0 // step + 1) * step, y1, step):
    d.line((0, y - y0, c.width, y - y0), fill=(0, 200, 255), width=1)
    d.text((2, y - y0 + 2), str(y), fill=(0, 200, 255))
c.save(sys.argv[2])
