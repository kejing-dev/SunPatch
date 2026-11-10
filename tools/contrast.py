#!/usr/bin/env python3
"""
D13 contrast table (Drone Sight), the build.md pre-QA gate: every text colour x background pair the panels and 3D labels draw.

* Fixed colours (the design palette roles interaction / passable / alert / error, the ink #111418, the brand colours of the
  3D boards and tags) are computed exactly with the WCAG 2.x relative-luminance formula.
* The SpatialUI vibrant roles (labelPrimary, fillSecondary, fillTertiary, the Regular / Thick glass) have no fixed sRGB
  value: the app's `PALETTE` log line prints them as #FF000000 because the glass vibrancy draws them. They are measured on
  emulator screenshots instead: in a box around one piece of text, the background is the median colour of the pixels in
  the background side of the median, the text colour is the median of the stroke cores (stroke pixels = past the midpoint
  between the background and the most extreme pixel; cores = the 10 % of them nearest the extreme, so anti-aliased edges
  do not count and the result does not depend on how much empty background the box holds).

Usage: python3 tools/contrast.py SHOT_DIR [--md OUT.md]   exit 1 when any pair is below its threshold.
Needs Pillow (python3 without -I picks up the user site-packages).
"""
import sys

from PIL import Image


def lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def lum(rgb):
    r, g, b = rgb[:3]
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)


def ratio(a, b):
    la, lb = lum(a), lum(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def hexrgb(h):
    h = h.lstrip("#")
    if len(h) == 8:  # AARRGGBB
        h = h[2:]
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def over(fg_argb, bg):
    """Composite an #AARRGGBB colour over an opaque rgb."""
    h = fg_argb.lstrip("#")
    a = int(h[0:2], 16) / 255.0
    f = hexrgb(h)
    return tuple(round(a * f[i] + (1 - a) * bg[i]) for i in range(3))


def hx(rgb):
    return "#%02X%02X%02X" % tuple(rgb)


INK = hexrgb("#1E2A33")  # DsColors.OnFill / TagBg
INTERACTION = hexrgb("#FFB53D")
PASSABLE = hexrgb("#4FD18B")
ALERT = hexrgb("#FF8A3D")
ERROR = hexrgb("#FF6B6B")
ERROR_RENDERED = hexrgb("#ED7470")  # the error red as the emulator draws it (QA #2 measured)
ON_ERROR = hexrgb("#0A0D10")  # DsColors.OnError (v0.1.1)
DANGER_FILL = hexrgb("#FF8A85")  # DsColors.DangerFill (v0.1.1, danger keys only)
TAG_BG = hexrgb("#1E2A33")
TAG_INK = hexrgb("#E6F1F7")
WARN_BG = hexrgb("#4A2E0A")
WARN_INK = hexrgb("#FFD9A8")
ERR_BG = hexrgb("#4A1414")
ERR_INK = hexrgb("#FFC5C5")
SKY = hexrgb("#2B7FD9")
WHITE = (255, 255, 255)

# (pair, where it is used, fg rgb, bg rgb, threshold)
FIXED = [
    ("ink #1E2A33 / interaction #FFB53D", "primary keys (好，开始 / 放在这里 / 起飞 / 暂停 / 继续 / 重飞 / 好了 / 再试一次), selected segment items (mode, wind, judging, settings segments)", INK, INTERACTION, 4.5),
    ("ink #1E2A33 / passable #4FD18B", "result pill 过 ✓, switched-on toggles (虚拟摇杆 / 方向提示 when on)", INK, PASSABLE, 4.5),
    ("ink #1E2A33 / alert #FF8A3D", "result pill 差一点", INK, ALERT, 4.5),
    ("on-error #0A0D10 / danger fill #FF8A85", "danger keys 退出应用 / 退出 / 清掉 (20 sp bold; v0.1.1, was #1E2A33 on #FF6B6B 5.28, screenshot 4.1-4.2)", ON_ERROR, DANGER_FILL, 4.5),
    ("on-error #0A0D10 / error #FF6B6B", "result pill 再来, S13 error badge icon (v0.1.1; was #1E2A33 5.28)", ON_ERROR, ERROR, 4.5),
    ("on-error #0A0D10 / error as rendered #ED7470", "same, against the red the emulator actually draws", ON_ERROR, ERROR_RENDERED, 4.5),
    ("tag ink #E6F1F7 / tag #1E2A33", "status pills (chip 框内 12/20 s …), picker best chips, idle main key (准备中 / 着陆), chain ✓ pills, height tag, 3D labels (gate 1-4, 0°-270°, 右杆→ / 右杆↑)", TAG_INK, TAG_BG, 4.5),
    ("warn ink #FFD9A8 / warn #4A2E0A", "hint line 飞出场地了 / 飞机在你身边了 / 落得有点重", WARN_INK, WARN_BG, 4.5),
    ("err ink #FFC5C5 / err #4A1414", "hint line 落重了 · 飞机回垫子, chain ✗ pills", ERR_INK, ERR_BG, 4.5),
    ("white / sky blue #2B7FD9", "lesson pictogram glyphs on their disc (icons: >= 3:1)", WHITE, SKY, 3.0),
]


def measure(path, box):
    im = Image.open(path).convert("RGB").crop(box)
    px = list(im.getdata())
    ls = sorted((lum(p), p) for p in px)
    n = len(ls)
    med = ls[n // 2][0]
    lo, hi = ls[max(0, int(n * 0.005))][0], ls[min(n - 1, int(n * 0.995))][0]
    dark_text = (med - lo) > (hi - med)
    # background: the pixels on the background side of the median
    bgp = [p for _, p in (ls[int(n * 0.45):int(n * 0.95)] if dark_text else ls[int(n * 0.05):int(n * 0.55)])]
    bg = tuple(sorted(p[i] for p in bgp)[len(bgp) // 2] for i in range(3))
    # text: the stroke pixels, i.e. past the midpoint between the background and the extreme; their median colour
    lb = lum(bg)
    ext = lo if dark_text else hi
    cut = (lb + ext) / 2
    strokes = [p for l, p in ls if (l <= cut if dark_text else l >= cut)]
    strokes = strokes if len(strokes) >= 3 else [p for _, p in (ls[:3] if dark_text else ls[-3:])]
    # stroke cores: the 10 % of stroke pixels nearest the extreme (anti-aliased edges excluded), median colour
    strokes.sort(key=lum, reverse=not dark_text)
    core = strokes[: max(3, len(strokes) // 10)]
    fg = tuple(sorted(p[i] for p in core)[len(core) // 2] for i in range(3))
    return fg, bg


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    shots = sys.argv[1].rstrip("/")
    md_out = sys.argv[sys.argv.index("--md") + 1] if "--md" in sys.argv else None
    from contrast_samples import EXEMPT, SAMPLES  # (pair, where, shot, box, threshold)

    rows = []
    for pair, where, fg, bg, th in FIXED:
        r = ratio(fg, bg)
        rows.append((pair, where, "exact", hx(fg), hx(bg), r, th))
    for pair, where, shot, box, th in SAMPLES:
        fg, bg = measure(f"{shots}/{shot}.png", box)
        rows.append((pair, where, f"{shot}.png {box}", hx(fg), hx(bg), ratio(fg, bg), th))
    out = ["| # | 前景 / 底 | 用在哪 | 取法 | 前景 | 底 | 对比度 | 要求 | 结论 |", "|---|---|---|---|---|---|---|---|---|"]
    bad = 0
    for i, (pair, where, how, f, b, r, th) in enumerate(rows, 1):
        ok = r >= th
        bad += 0 if ok else 1
        out.append(f"| {i} | {pair} | {where} | {how} | {f} | {b} | {r:.2f} : 1 | ≥ {th} | {'过' if ok else '**不过**'} |")
    for where, shot, box in EXEMPT:
        fg, bg = measure(f"{shots}/{shot}.png", box)
        out.append(f"| – | （豁免）{where} | WCAG 1.4.3 不要求 | {shot}.png {box} | {hx(fg)} | {hx(bg)} | {ratio(fg, bg):.2f} : 1 | — | 豁免 |")
    text = "\n".join(out)
    print(text)
    print(f"\n{len(rows)} pairs, {bad} below threshold")
    if md_out:
        open(md_out, "w", encoding="utf-8").write(text + "\n")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
