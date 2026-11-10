#!/usr/bin/env python3
"""Sun Patch icon: PICO 3D icon layers + the 512 export, drawn from the adaptive icon itself
(`res/drawable/ic_launcher_foreground.xml` on `@color/ic_launcher_background` #1F5F8A evening blue, design appIcon).

    python3 tools/make_icon.py [export.png]

Layer 0: the blue tile. Layer 1: the glyph (window + sun patch). sdf_N = the layer's alpha in white.
"""
import os
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vector_render  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S = 1024
SKY = (31, 95, 138, 255)


def tile():
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(im).rounded_rectangle((40, 40, S - 40, S - 40), radius=220, fill=SKY)
    return im


def front():
    return vector_render.render(os.path.join(RES, "drawable", "ic_launcher_foreground.xml"), S)


def alpha_white(im):
    w = Image.new("RGBA", im.size, (255, 255, 255, 0))
    w.putalpha(im.split()[3])
    return w


def main():
    l0, l1 = tile(), front()
    draw = os.path.join(RES, "drawable")
    l0.save(os.path.join(draw, "icon_3d_layer_0.png"))
    l1.save(os.path.join(draw, "icon_3d_layer_1.png"))
    alpha_white(l0).save(os.path.join(draw, "icon_3d_sdf_0.png"))
    alpha_white(l1).save(os.path.join(draw, "icon_3d_sdf_1.png"))
    if len(sys.argv) > 1:
        full = Image.new("RGBA", (S, S), SKY)
        full.alpha_composite(l1)
        full.resize((512, 512), Image.LANCZOS).save(sys.argv[1])
    print("icon layers written")


if __name__ == "__main__":
    main()
