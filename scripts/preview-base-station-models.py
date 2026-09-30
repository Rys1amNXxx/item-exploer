"""Render the checked-in Minecraft JSON models and their actual PNG textures.

This deterministic orthographic software preview is not an in-game screenshot.
Requires Python 3, Pillow and NumPy; writes build/reports/base-station/model-preview.png.
It does not load or modify Minecraft, model resources, or concept artwork.
"""
from pathlib import Path
import json
import math
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/itemexplorer"
OUTPUT = ROOT / "build/reports/base-station/model-preview.png"
FACING = ["north", "east", "south", "west"]
NORMAL = {"north": [0, 0, -1], "south": [0, 0, 1], "east": [1, 0, 0], "west": [-1, 0, 0], "up": [0, 1, 0], "down": [0, -1, 0]}
TEXTURES = {}


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def model(resource):
    own = read(ASSETS / "models" / (resource.removeprefix("itemexplorer:") + ".json"))
    parent = model(own["parent"]) if own.get("parent", "").startswith("itemexplorer:") else {}
    return {**parent, **own, "textures": {**parent.get("textures", {}), **own.get("textures", {})}, "elements": own.get("elements", parent.get("elements", []))}


def texture(ref, mapping):
    while ref.startswith("#"):
        ref = mapping[ref[1:]]
    if ref not in TEXTURES:
        TEXTURES[ref] = np.asarray(Image.open(ASSETS / "textures" / (ref.removeprefix("itemexplorer:") + ".png")).convert("RGB"))
    return TEXTURES[ref]


def rotate(points, angle):
    result = np.asarray(points, dtype=float).copy()
    for _ in range(angle // 90):
        old_x = result[..., 0].copy()
        result[..., 0] = 16 - result[..., 2]
        result[..., 2] = old_x
    return result


def face_points(box, face):
    x, y, z = box["from"]
    X, Y, Z = box["to"]
    return {
        "north": [[X, Y, z], [x, Y, z], [x, y, z], [X, y, z]],
        "south": [[x, Y, Z], [X, Y, Z], [X, y, Z], [x, y, Z]],
        "west": [[x, Y, z], [x, Y, Z], [x, y, Z], [x, y, z]],
        "east": [[X, Y, Z], [X, Y, z], [X, y, z], [X, y, Z]],
        "up": [[x, Y, z], [X, Y, z], [X, Y, Z], [x, Y, Z]],
        "down": [[x, y, Z], [X, y, Z], [X, y, z], [x, y, z]],
    }[face]


def applications(part, state):
    value = read(ASSETS / "blockstates" / ("base_station_" + part + ".json"))
    if "multipart" in value:
        return [piece["apply"] for piece in value["multipart"] if all(state.get(key) == expected for key, expected in piece.get("when", {}).items())]
    for variant, application in value["variants"].items():
        if all(state.get(pair.split("=")[0]) == pair.split("=")[1] for pair in variant.split(",")):
            return [application]
    raise ValueError(f"Missing blockstate {part}: {state}")


def station_faces():
    # Use the reviewed integer-cell placement list, never the AI concept image.
    design = read(ROOT / "docs/concepts/base-station-v1/structure.json")
    faces = []
    for part in design["placements"]:
        name = "module" if part["part"] == "blank_module" else part["part"]
        state = {"facing": part.get("facing", "north"), "formed": "true"}
        if name == "mast":
            state = {facing: "true" if part["position"][1] == 3 else "false" for facing in FACING}
        for application in applications(name, state):
            value = model(application["model"])
            angle = application.get("y", 0)
            for element in value["elements"]:
                for face, settings in element["faces"].items():
                    vertices = rotate(face_points(element, face), angle) + np.array(part["position"]) * 16
                    direction = FACING[(FACING.index(face) + angle // 90) % 4] if face in FACING else face
                    faces.append((vertices, np.array(NORMAL[direction]), settings["uv"], texture(settings["texture"], value["textures"])))
    return faces


def render(faces, camera, width=790, height=880, scale=9.6):
    camera = np.array(camera, dtype=float)
    camera /= np.linalg.norm(camera)
    right = np.cross([0, 1, 0], camera)
    right /= np.linalg.norm(right)
    up = np.cross(camera, right)
    pixels = np.full((height, width, 3), [26, 35, 43], dtype=np.uint8)
    depth = np.full((height, width), -np.inf)
    center = np.array([24, 0, 24])
    light = np.array([-0.2, 0.9, -0.4])
    light /= np.linalg.norm(light)
    for vertices, normal, uv, tex in faces:
        if normal @ camera <= 0:
            continue
        relative = vertices - center
        screen = np.column_stack((width / 2 + relative @ right * scale, height - 170 - relative @ up * scale, relative @ camera))
        u, v, U, V = uv
        texcoords = np.array([[u, v], [U, v], [U, V], [u, V]], dtype=float) * 2
        brightness = 0.70 + 0.30 * max(0, normal @ light)
        for triangle in ([0, 1, 2], [0, 2, 3]):
            points = screen[triangle]
            x0 = max(0, math.floor(points[:, 0].min()))
            x1 = min(width - 1, math.ceil(points[:, 0].max()))
            y0 = max(0, math.floor(points[:, 1].min()))
            y1 = min(height - 1, math.ceil(points[:, 1].max()))
            if x1 < x0 or y1 < y0:
                continue
            xx, yy = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
            a, b, c = points
            denominator = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
            if abs(denominator) < 1e-8:
                continue
            p = ((b[1] - c[1]) * (xx - c[0]) + (c[0] - b[0]) * (yy - c[1])) / denominator
            q = ((c[1] - a[1]) * (xx - c[0]) + (a[0] - c[0]) * (yy - c[1])) / denominator
            r = 1 - p - q
            z = p * a[2] + q * b[2] + r * c[2]
            area = depth[y0:y1 + 1, x0:x1 + 1]
            mask = (p >= -1e-7) & (q >= -1e-7) & (r >= -1e-7) & (z > area)
            if not mask.any():
                continue
            st = texcoords[triangle]
            tx = np.clip(np.floor(p * st[0, 0] + q * st[1, 0] + r * st[2, 0]).astype(int), 0, 31)
            ty = np.clip(np.floor(p * st[0, 1] + q * st[1, 1] + r * st[2, 1]).astype(int), 0, 31)
            rgb = np.minimum(255, tex[ty, tx] * brightness).astype(np.uint8)
            area[mask] = z[mask]
            pixels[y0:y1 + 1, x0:x1 + 1][mask] = rgb[mask]
    return Image.fromarray(pixels)


def main():
    board = Image.new("RGB", (1680, 1120), "#121b23")
    draw = ImageDraw.Draw(board)
    font_path = os.environ.get("STATION_FONT", "C:/Windows/Fonts/msyh.ttc")
    title = ImageFont.truetype(font_path, 32)
    body = ImageFont.truetype(font_path, 22)
    small = ImageFont.truetype(font_path, 18)
    draw.text((42, 28), "基站 · 正式 JSON 模型预览", font=title, fill="#ebece3")
    draw.text((42, 76), "3 × 3 九格底座  /  5 格高  /  17 个部件  /  无能耗", font=body, fill="#97cfce")
    faces = station_faces()
    board.paste(render(faces, [1.15, 0.95, -1.5]), (35, 130))
    board.paste(render(faces, [-1.15, 0.95, 1.5]), (855, 130))
    draw.text((58, 151), "正面 · 控制器", font=body, fill="#edc66f")
    draw.text((878, 151), "背面 · 预留数据接口", font=body, fill="#edc66f")
    draw.text((42, 1030), "直接读取游戏模型与贴图的确定性投影；此图不是游戏截图，不包含 Minecraft 的环境光照。", font=small, fill="#9aa7af")
    draw.text((42, 1063), "绿色灯仅表示结构完整；导线联网与远程物品传输仍需后续实现。", font=small, fill="#9aa7af")
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    board.save(OUTPUT)
    print(OUTPUT)


if __name__ == "__main__":
    main()
