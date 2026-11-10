"""QA #1 q1-1 / q1-2: WCAG contrast of every fixed text colour x fill pair the panels and the Stage plate draw (SpColors)."""
import sys


def lin(c):
    c /= 255
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def lum(h):
    h = h.lstrip("#")
    r, g, b = (int(h[i:i + 2], 16) for i in (0, 2, 4))
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)


def ratio(a, b):
    x, y = sorted([lum(a), lum(b)], reverse=True)
    return (x + 0.05) / (y + 0.05)


# (where, text / icon, fill, minimum)
PAIRS = [
    ("普通键（玻璃上）KeyInk / KeyFill", "#FFFFFF", "#3B4652", 4.5),
    ("普通键（暗条 / 提示牌上）OnFill / KeyFillLight", "#1F1A24", "#E6ECF1", 4.5),
    ("主键 OnFill / Interaction", "#1F1A24", "#F2A93B", 4.5),
    ("危险键 OnFill / DangerKey", "#1F1A24", "#F58A80", 4.5),
    ("分段未选 OnFill / 分段底 KeyFillLight", "#1F1A24", "#E6ECF1", 4.5),
    ("暗条文字 BarInk / BarBg", "#E6F1F7", "#1E2A33", 4.5),
    ("黄条文字（朝向待填、确认条）OnFill / Alert", "#1F1A24", "#F2B84B", 4.5),
    ("绿条文字（好了，去看光）OnFill / Passable", "#1F1A24", "#57C99A", 4.5),
    ("提示牌拒绝原因 ErrorOnDark / BarBg", "#FF8A80", "#1E2A33", 4.5),
    ("当月数字 InkOnGlass / Interaction", "#1F1A24", "#F2A93B", 4.5),
    ("分段选中 OnFill / Interaction", "#1F1A24", "#F2A93B", 4.5),
    ("欢迎页图标 SunPatch / BarBg（图标）", "#FFCF7A", "#1E2A33", 3.0),
    ("图例 1 h OnFill / Heat1", "#1F1A24", "#8FC9DE", 4.5),
    ("图例 2 h OnFill / Heat2", "#1F1A24", "#F2C96B", 4.5),
    ("图例 4 h+ OnFill / Heat3", "#1F1A24", "#F0963C", 4.5),
    ("普通键轮廓 KeyFill / 黄条 Alert（非文字）", "#3B4652", "#F2B84B", 3.0),
    ("普通键轮廓 KeyFillLight / 暗条 BarBg（非文字）", "#E6ECF1", "#1E2A33", 3.0),
]

if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    bad = 0
    print("| 位置 | 前景 | 底色 | 对比 | 门槛 | 结论 |\n|---|---|---|---|---|---|")
    for where, fg, bg, need in PAIRS:
        r = ratio(fg, bg)
        bad += r < need
        print(f"| {where} | {fg} | {bg} | {r:.2f}:1 | {need}:1 | {'达标' if r >= need else '不达标'} |")
    sys.exit(1 if bad else 0)
