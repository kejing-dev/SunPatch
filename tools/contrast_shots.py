"""QA #1 q1-2: measured contrast of the keys and text QA flagged, from v0.1.1 emulator screenshots (2560 px).

In a box around one label: background = the most common colour; text = median of the 3 % of pixels farthest in
luminance from it (stroke cores, so anti-aliasing does not count). Prints a markdown table; exit 1 below threshold.
Usage: python -I tools/contrast_shots.py FINAL_DIR SETTINGS_DIR EARLY_DIR
(FINAL = the v0.1.1 handoff build; SETTINGS / EARLY = earlier v0.1.1 builds whose key colours are unchanged)
"""
import os
import sys
from collections import Counter

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from contrast_fixed import lum  # noqa: E402


def ratio_rgb(a, b):
    x, y = sorted([lum("%02x%02x%02x" % a), lum("%02x%02x%02x" % b)], reverse=True)
    return (x + 0.05) / (y + 0.05)


def measure(img, box):
    px = list(img.crop(box).getdata())
    bg = Counter(px).most_common(1)[0][0]
    lb = lum("%02x%02x%02x" % bg)
    far = sorted(px, key=lambda p: -abs(lum("%02x%02x%02x" % p) - lb))[: max(4, len(px) * 3 // 100)]
    fg = tuple(sorted(c[i] for c in far)[len(far) // 2] for i in range(3))
    return bg, fg, ratio_rgb(fg, bg)


BOXES = [
    ("F/exit-confirm.png", "设置 · 退出确认 [退出]（DangerKey）", (1508, 1455, 1550, 1481), 4.5),
    ("F/newgame-confirm.png", "S13 · 确认条 [清掉]（DangerKey）", (1592, 1403, 1642, 1429), 4.5),
    ("F/exit-confirm.png", "设置 · 分段未选「跟随系统」（浅色分段底）", (1351, 1041, 1411, 1067), 4.5),
    ("F/exit-confirm.png", "设置 · 分段已选「中文」", (1500, 1041, 1540, 1067), 4.5),
    ("F/v011-S03.png", "S06 · 朝向分段未选「北」（浅色分段底）", (950, 1366, 974, 1390), 4.5),
    ("F/v011-S03.png", "S06 · 朝向分段未选「东北」（浅色分段底）", (1030, 1366, 1068, 1390), 4.5),
    # (shot, where, box, threshold)
    ("S/exit-confirm.png", "设置 · 退出确认 [取消]（石板键白字）", (1175, 1450, 1262, 1494), 4.5),
    ("S/exit-confirm.png", "设置 · [叫回面前]", (1700, 1302, 1794, 1344), 4.5),
    ("S/exit-confirm.png", "设置 · [改用虚拟房间]", (1647, 1550, 1794, 1590), 4.5),
    ("S/exit-confirm.png", "设置 · 行标签「语言」（labelPrimary / 玻璃）", (1025, 1044, 1069, 1073), 4.5),
    ("S/exit-confirm.png", "设置 · 静音图标（图标）", (1760, 956, 1791, 988), 3.0),
    ("S/exit-confirm.png", "设置 · 退出确认问句", (1025, 1387, 1206, 1415), 4.5),
    ("S/newgame-confirm.png", "S13 · 确认条 [取消]", (1560, 1400, 1622, 1430), 4.5),
    ("E/v011-S03.png", "S03 · 暗条 [去对齐]（暗条上的浅键）", (1480, 1050, 1545, 1075), 4.5),
    ("E/v011-S03.png", "S03 · [放一扇标准窗]", (1060, 1145, 1170, 1168), 4.5),
    ("E/v011-S03.png", "S05 · [换]", (1525, 1233, 1550, 1257), 4.5),
    ("E/v011-S03.png", "S06 · [−5°]", (855, 1457, 890, 1478), 4.5),
    ("E/v011-S11.png", "S11 · 暗条 [取消]", (1490, 1280, 1540, 1305), 4.5),
    ("E/v011-S04.png", "S04 · 提示牌 [重来]", (1218, 1852, 1278, 1882), 4.5),
    ("E/v011-S04.png", "S04 · 提示牌 [取消]", (1400, 1852, 1460, 1882), 4.5),
    ("E/v011-S04.png", "S04 · 提示牌文字", (1160, 1772, 1500, 1800), 4.5),
]

if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    dirs = {"F": sys.argv[1], "S": sys.argv[2], "E": sys.argv[3]}
    bad = 0
    print("| 位置 | 截图 | 底色 | 字色 | 对比 | 门槛 | 结论 |\n|---|---|---|---|---|---|---|")
    for shot, where, box, need in BOXES:
        d, name = shot.split("/", 1)
        img = Image.open(os.path.join(dirs[d], name)).convert("RGB")
        bg, fg, r = measure(img, box)
        bad += r < need
        print(f"| {where} | {name} | #{'%02X%02X%02X' % bg} | #{'%02X%02X%02X' % fg} | {r:.2f}:1 | {need}:1 | {'达标' if r >= need else '不达标'} |")
    sys.exit(1 if bad else 0)
