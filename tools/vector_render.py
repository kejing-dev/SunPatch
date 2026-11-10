#!/usr/bin/env python3
"""Renders Android vector drawables (the subset the app uses: path fills, groups with translate / scale) with PIL.

    python3 tools/vector_render.py sheet OUT.png            all res/drawable/ic_*.xml in one contact sheet (D23)
    python3 tools/vector_render.py icon OUT_512.png         the launcher icon (background colour + foreground)

Used by make_icon.py too. Even-odd filling per path (good enough for Material Symbols outlines).
"""
import math
import os
import re
import sys
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")
A = "{http://schemas.android.com/apk/res/android}"
TOK = re.compile(r"[MmLlHhVvQqCcZzTtSsAa]|-?\d*\.?\d+(?:[eE][-+]?\d+)?")


def parse_path(d):
    toks = TOK.findall(d)
    i, cmd = 0, None
    x = y = sx = sy = 0.0
    lqx = lqy = None
    polys, cur = [], []

    def num():
        nonlocal i
        v = float(toks[i])
        i += 1
        return v

    while i < len(toks):
        t = toks[i]
        if re.match(r"[A-Za-z]", t):
            cmd = t
            i += 1
            if cmd in "Zz":
                if cur:
                    polys.append(cur)
                cur = []
                x, y = sx, sy
                continue
        rel = cmd.islower()
        c = cmd.upper()
        if c == "M":
            nx, ny = num(), num()
            if rel:
                nx, ny = x + nx, y + ny
            if cur:
                polys.append(cur)
            cur = [(nx, ny)]
            x, y, sx, sy = nx, ny, nx, ny
            cmd = "l" if rel else "L"
        elif c == "L":
            nx, ny = num(), num()
            if rel:
                nx, ny = x + nx, y + ny
            cur.append((nx, ny))
            x, y = nx, ny
        elif c == "H":
            nx = num()
            x = x + nx if rel else nx
            cur.append((x, y))
        elif c == "V":
            ny = num()
            y = y + ny if rel else ny
            cur.append((x, y))
        elif c in "QT":
            if c == "Q":
                qx, qy = num(), num()
                if rel:
                    qx, qy = x + qx, y + qy
            else:
                qx, qy = (2 * x - lqx, 2 * y - lqy) if lqx is not None else (x, y)
            nx, ny = num(), num()
            if rel:
                nx, ny = x + nx, y + ny
            for k in range(1, 9):
                u = k / 8
                cur.append(((1 - u) ** 2 * x + 2 * (1 - u) * u * qx + u * u * nx, (1 - u) ** 2 * y + 2 * (1 - u) * u * qy + u * u * ny))
            lqx, lqy = qx, qy
            x, y = nx, ny
            continue
        elif c in "CS":
            if c == "C":
                c1x, c1y = num(), num()
                if rel:
                    c1x, c1y = x + c1x, y + c1y
            else:
                c1x, c1y = (x, y)
            c2x, c2y, nx, ny = num(), num(), num(), num()
            if rel:
                c2x, c2y, nx, ny = x + c2x, y + c2y, x + nx, y + ny
            for k in range(1, 11):
                u = k / 10
                a, b, cc, dd = (1 - u) ** 3, 3 * (1 - u) ** 2 * u, 3 * (1 - u) * u * u, u ** 3
                cur.append((a * x + b * c1x + cc * c2x + dd * nx, a * y + b * c1y + cc * c2y + dd * ny))
            x, y = nx, ny
        elif c == "A":
            rx, ry, rot, large, sweep, nx, ny = num(), num(), num(), num(), num(), num(), num()
            if rel:
                nx, ny = x + nx, y + ny
            cur.append((nx, ny))  # chord (rare in the icons used here)
            x, y = nx, ny
        lqx = lqy = None
    if cur:
        polys.append(cur)
    return polys


def color_of(s, default=(255, 255, 255, 255)):
    if not s or s.startswith("@") or s.startswith("?"):
        return default
    s = s.lstrip("#")
    if len(s) == 6:
        return tuple(int(s[k:k + 2], 16) for k in (0, 2, 4)) + (255,)
    if len(s) == 8:
        a = int(s[0:2], 16)
        return tuple(int(s[k:k + 2], 16) for k in (2, 4, 6)) + (a,)
    return default


def render(path, size, tint=None, bg=None):
    root = ET.parse(path).getroot()
    vw = float(root.get(A + "viewportWidth"))
    vh = float(root.get(A + "viewportHeight"))
    S = size * 4
    img = Image.new("RGBA", (S, S), bg or (0, 0, 0, 0))

    def walk(node, tx):
        for ch in node:
            tag = ch.tag
            if tag == "group":
                sx = float(ch.get(A + "scaleX", 1))
                sy = float(ch.get(A + "scaleY", 1))
                txx = float(ch.get(A + "translateX", 0))
                tyy = float(ch.get(A + "translateY", 0))
                px = float(ch.get(A + "pivotX", 0))
                py = float(ch.get(A + "pivotY", 0))

                def nt(p, tx=tx, sx=sx, sy=sy, txx=txx, tyy=tyy, px=px, py=py):
                    return tx(((p[0] - px) * sx + px + txx, (p[1] - py) * sy + py + tyy))
                walk(ch, nt)
            elif tag == "path":
                col = tint or color_of(ch.get(A + "fillColor"))
                alpha = float(ch.get(A + "fillAlpha", 1))
                col = col[:3] + (int(col[3] * alpha),)
                stroke = ch.get(A + "strokeColor")
                layer = Image.new("L", (S, S), 0)
                dr = ImageDraw.Draw(layer)
                for poly in parse_path(ch.get(A + "pathData")):
                    pts = [tx(p) for p in poly]
                    pts = [(p[0] / vw * S, p[1] / vh * S) for p in pts]
                    if stroke:
                        w = float(ch.get(A + "strokeWidth", 1)) / vw * S
                        dr.line(pts, fill=255, width=max(1, int(w)), joint="curve")
                    elif len(pts) > 2:
                        tmp = Image.new("L", (S, S), 0)
                        ImageDraw.Draw(tmp).polygon(pts, fill=255)
                        layer = Image.fromarray(__import__("numpy").bitwise_xor(__import__("numpy").array(layer), __import__("numpy").array(tmp)))
                        dr = ImageDraw.Draw(layer)
                if stroke and not tint:
                    col = color_of(stroke)
                solid = Image.new("RGBA", (S, S), col)
                img.alpha_composite(Image.composite(solid, Image.new("RGBA", (S, S), (0, 0, 0, 0)), layer))
    walk(root, lambda p: p)
    return img.resize((size, size), Image.LANCZOS)


def sheet(out):
    d = os.path.join(RES, "drawable")
    names = sorted(n for n in os.listdir(d) if n.startswith("ic_") and n.endswith(".xml"))
    cols = 6
    cell = 150
    rows = math.ceil(len(names) / cols)
    im = Image.new("RGB", (cols * cell, rows * cell), (40, 44, 52))
    dr = ImageDraw.Draw(im)
    for k, n in enumerate(names):
        r, c = divmod(k, cols)
        ic = render(os.path.join(d, n), 72, tint=(255, 255, 255, 255) if n != "ic_launcher_foreground.xml" else None)
        if n == "ic_launcher_foreground.xml":
            bgc = Image.new("RGBA", (72, 72), (154, 211, 245, 255))
            bgc.alpha_composite(ic)
            ic = bgc
        im.paste(ic, (c * cell + 39, r * cell + 20), ic)
        dr.text((c * cell + 8, r * cell + 112), n.replace(".xml", ""), fill=(230, 230, 230))
    im.save(out)
    print("sheet", out, len(names))


if __name__ == "__main__":
    if sys.argv[1] == "sheet":
        sheet(sys.argv[2])
    else:
        fg = render(os.path.join(RES, "drawable", "ic_launcher_foreground.xml"), int(sys.argv[3]) if len(sys.argv) > 3 else 512)
        bg = Image.new("RGBA", fg.size, (154, 211, 245, 255))
        bg.alpha_composite(fg)
        bg.save(sys.argv[2])
        print("icon", sys.argv[2])
