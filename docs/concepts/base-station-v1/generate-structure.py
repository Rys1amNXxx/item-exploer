"""Generate the exact construction sheet, independently of the concept illustration.

Requires Python 3 + Pillow. Run from any directory. Writes only beside this script.
Set STATION_FONT to a CJK font if Microsoft YaHei is not installed.
"""
from collections import Counter
from pathlib import Path
import json
import os

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent
FONT = os.environ.get("STATION_FONT", "C:/Windows/Fonts/msyh.ttc")
PARTS = {
    "F": ("基站机壳", "casing", "#798590", 5),
    "C": ("基站控制器", "controller", "#b68b37", 1),
    "P": ("基站网络接口", "network_port", "#367f93", 1),
    "B": ("空白扩展模块", "blank_module", "#5a7388", 2),
    "M": ("信号立柱", "mast", "#4b5966", 3),
    "A": ("天线板", "antenna", "#a2b9b1", 4),
    "T": ("天线顶帽", "cap", "#69aaa8", 1),
}
# Each layer is viewed from above: rear/south (+z) first, front/north (z=0) last.
LAYERS = [
    ["FPF", "BFB", "FCF"],
    ["...", ".M.", "..."],
    ["...", ".M.", "..."],
    [".A.", "AMA", ".A."],
    ["...", ".T.", "..."],
]

placements = []
for y, rows in enumerate(LAYERS):
    for row, line in enumerate(rows):
        z = 2 - row
        for x, key in enumerate(line):
            if key == ".":
                continue
            part = {"symbol": key, "part": PARTS[key][1], "position": [x, y, z]}
            if key in ("C", "P"):
                part["facing"] = "north" if key == "C" else "south"
            if key == "B":
                part["facing"] = "west" if x == 0 else "east"
            if key == "A":
                part["facing"] = "west" if x == 0 else "east" if x == 2 else "north" if z == 0 else "south"
            if key == "M":
                part["visual_state"] = "crosshead" if y == 3 else "straight"
            placements.append(part)

counts = Counter(p["symbol"] for p in placements)
assert counts == Counter({key: val[3] for key, val in PARTS.items()}), counts
assert len(placements) == len({tuple(p["position"]) for p in placements}) == 17
manifest = {
    "status": "design_proposal_only_not_registered_game_content",
    "bounds": [3, 5, 3],
    "axes": "x east, y up, z south; controller front is north; whole structure may rotate horizontally",
    "layers_back_to_front": LAYERS,
    "counts": {PARTS[key][1]: count for key, count in counts.items()},
    "placements": placements,
}
(ROOT / "structure.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

im = Image.new("RGB", (1680, 1080), "#f5f3ed")
d = ImageDraw.Draw(im)

def font(size):
    return ImageFont.truetype(FONT, size)

def text(x, y, label, size=26, color="#253743", anchor=None):
    d.text((x, y), label, font=font(size), fill=color, anchor=anchor)

text(64, 42, "机柜式信号基站 / 精确搭建图", 48)
text(66, 114, "占地 3 × 3 格 · 高 5 格 · 17 个方块 · 设计提案 v1", 26, "#596974")
d.line((64, 168, 1616, 168), fill="#c6cecb", width=2)
text(66, 189, "逐层俯视：从左至右，由地面向上搭建；每张图上方为背面，下方为控制器正面。", 25)

titles = ["第 1 层 · 底座", "第 2 层 · 立柱", "第 3 层 · 立柱", "第 4 层 · 天线", "第 5 层 · 顶帽"]
notes = ["9 块 / 地面层 y=0", "1 块 / y=1", "1 块 / y=2", "5 块 / y=3", "1 块 / y=4"]
for layer, rows in enumerate(LAYERS):
    ox, oy, cell = 65 + layer * 318, 296, 90
    text(ox + 135, 239, titles[layer], 27, anchor="mt")
    for row, line in enumerate(rows):
        for col, key in enumerate(line):
            left, top = ox + col * cell, oy + row * cell
            color = PARTS[key][2] if key in PARTS else "#e8e9e3"
            d.rounded_rectangle((left + 3, top + 3, left + cell - 3, top + cell - 3), radius=3, fill=color)
            if key in PARTS:
                text(left + cell / 2, top + cell / 2 - 2, key, 42, "#ffffff" if key not in "AT" else "#173b40", "mm")
            else:
                text(left + cell / 2, top + cell / 2 - 3, "·", 35, "#adb6b4", "mm")
            # Short edge notch marks the outward-facing front of functional blocks.
            stroke = "#e7f6f1"
            if key == "C" or key == "A" and row == 2:
                d.line((left + 29, top + cell - 8, left + cell - 29, top + cell - 8), fill=stroke, width=5)
            elif key == "P" or key == "A" and row == 0:
                d.line((left + 29, top + 8, left + cell - 29, top + 8), fill=stroke, width=5)
            elif key in "BA" and col == 0:
                d.line((left + 8, top + 29, left + 8, top + cell - 29), fill=stroke, width=5)
            elif key in "BA" and col == 2:
                d.line((left + cell - 8, top + 29, left + cell - 8, top + cell - 29), fill=stroke, width=5)
    text(ox + 135, 590, "↓ 正面", 24, "#667882", "mt")
    text(ox + 135, 631, notes[layer], 24, "#253743", "mt")

d.line((64, 691, 1616, 691), fill="#c6cecb", width=2)
for i, (key, (name, _, color, number)) in enumerate(PARTS.items()):
    x, y = 65 + (i % 4) * 397, 723 + (i // 4) * 76
    d.rounded_rectangle((x, y, x + 51, y + 51), radius=4, fill=color)
    text(x + 25, y + 23, key, 28, "#ffffff" if key not in "AT" else "#173b40", "mm")
    text(x + 66, y + 7, f"{name} × {number}", 25)

text(66, 910, "· 留空    白色短线 = 功能面朝外    第 4 层中央立柱带四向短臂，与天线板接实", 25)
text(66, 958, "接线：终端 ── 数据导线 ── 基站背面 P 接口   ≈ 无线 ≈   另一座基站", 27, "#367f93")
text(66, 1015, "无供电要求；导线与终端不计入 17 块。具体格位以此图和 structure.json 为准。", 23, "#63737d")
im.save(ROOT / "structure.png")
print("Verified 17 unique placements, 7 part types, exact expected counts; wrote structure.json and structure.png")
