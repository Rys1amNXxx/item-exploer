"""Deterministically preview the actual storage terminal JSON model and textures.

Requires Python 3, Pillow and NumPy. Minecraft resources are read from client.jar;
project resources always take precedence. Nothing is painted into the textures.
This software projection is not an in-game screenshot.
"""
from argparse import ArgumentParser
from io import BytesIO
from pathlib import Path
import json
import math
import os
from zipfile import ZipFile

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "src/main/resources"
NORMALS = {"north": [0, 0, -1], "south": [0, 0, 1], "east": [1, 0, 0],
           "west": [-1, 0, 0], "up": [0, 1, 0], "down": [0, -1, 0]}


class Resources:
    def __init__(self, jar):
        self.jar = ZipFile(jar) if jar else None
        self.textures = {}

    def read(self, resource, kind, extension):
        namespace, name = resource.split(":", 1) if ":" in resource else ("minecraft", resource)
        relative = f"assets/{namespace}/{kind}/{name}.{extension}"
        path = RESOURCES / relative
        if path.exists():
            return path.read_bytes()
        if self.jar:
            return self.jar.read(relative)
        raise FileNotFoundError(f"{relative}: pass --client-jar for vanilla assets")

    def model(self, resource):
        own = json.loads(self.read(resource, "models", "json"))
        parent = self.model(own["parent"]) if "parent" in own else {}
        return {**parent, **own, "textures": {**parent.get("textures", {}), **own.get("textures", {})},
                "elements": own.get("elements", parent.get("elements", []))}

    def texture(self, reference, mapping):
        visited = set()
        while reference.startswith("#"):
            if reference in visited:
                raise ValueError(f"Cyclic texture reference: {reference}")
            visited.add(reference)
            reference = mapping[reference[1:]]
        if reference not in self.textures:
            image = Image.open(BytesIO(self.read(reference, "textures", "png"))).convert("RGBA")
            # Animated Minecraft textures store frames vertically; show frame zero.
            if image.height > image.width:
                image = image.crop((0, 0, image.width, image.width))
            self.textures[reference] = np.asarray(image)
        return self.textures[reference]


def face_points(box, face):
    x, y, z = box["from"]
    X, Y, Z = box["to"]
    return np.array({
        "north": [[X, Y, z], [x, Y, z], [x, y, z], [X, y, z]],
        "south": [[x, Y, Z], [X, Y, Z], [X, y, Z], [x, y, Z]],
        "west": [[x, Y, z], [x, Y, Z], [x, y, Z], [x, y, z]],
        "east": [[X, Y, Z], [X, Y, z], [X, y, z], [X, y, Z]],
        "up": [[x, Y, z], [X, Y, z], [X, Y, Z], [x, Y, Z]],
        "down": [[x, y, Z], [X, y, Z], [X, y, z], [x, y, z]],
    }[face], dtype=float)


def default_uv(box, face):
    x, y, z = box["from"]
    X, Y, Z = box["to"]
    return {"down": [x, 16 - Z, X, 16 - z], "up": [x, z, X, Z],
            "north": [16 - X, 16 - Y, 16 - x, 16 - y],
            "south": [x, 16 - Y, X, 16 - y],
            "west": [z, 16 - Y, Z, 16 - y],
            "east": [16 - Z, 16 - Y, 16 - z, 16 - y]}[face]


def rotate_element(vertices, normal, settings):
    if not settings:
        return vertices, normal
    axis = "xyz".index(settings["axis"])
    theta = math.radians(settings["angle"])
    vector = np.eye(3)[axis]
    cross = np.array([[0, -vector[2], vector[1]], [vector[2], 0, -vector[0]],
                      [-vector[1], vector[0], 0]])
    matrix = math.cos(theta) * np.eye(3) + math.sin(theta) * cross + (1 - math.cos(theta)) * np.outer(vector, vector)
    origin = np.asarray(settings.get("origin", [8, 8, 8]), dtype=float)
    relative = vertices - origin
    if settings.get("rescale"):
        scale = np.full(3, 1 / abs(math.cos(theta)))
        scale[axis] = 1
        relative *= scale
    return relative @ matrix.T + origin, normal @ matrix.T


def model_faces(resources, name):
    value = resources.model(name)
    result = []
    for element in value["elements"]:
        for face, settings in element["faces"].items():
            vertices, normal = rotate_element(face_points(element, face),
                                               np.array(NORMALS[face], dtype=float), element.get("rotation"))
            uv = settings.get("uv", default_uv(element, face))
            u, v, U, V = uv
            coords = np.array([[u, v], [U, v], [U, V], [u, V]], dtype=float)
            coords = np.roll(coords, -settings.get("rotation", 0) // 90, axis=0)
            result.append((vertices, normal, coords,
                           resources.texture(settings["texture"], value["textures"]), element.get("shade", True)))
    return result


def render(faces, camera, size=(560, 530)):
    width, height = size
    camera = np.array(camera, dtype=float)
    camera /= np.linalg.norm(camera)
    right = np.cross([0, 1, 0], camera)
    right /= np.linalg.norm(right)
    up = np.cross(camera, right)
    all_points = np.concatenate([face[0] for face in faces])
    center = (all_points.min(axis=0) + all_points.max(axis=0)) / 2
    relative = all_points - center
    xspan = np.ptp(relative @ right)
    yspan = np.ptp(relative @ up)
    scale = min((width - 80) / xspan, (height - 80) / yspan)
    pixels = np.full((height, width, 3), [23, 31, 39], dtype=np.uint8)
    depth = np.full((height, width), -np.inf)
    light = np.array([-0.25, 0.9, -0.5])
    light /= np.linalg.norm(light)
    for vertices, normal, coords, texture, shade in faces:
        if normal @ camera <= 0:
            continue
        relative = vertices - center
        screen = np.column_stack((width / 2 + relative @ right * scale,
                                  height / 2 - relative @ up * scale, relative @ camera))
        texcoords = coords * np.array([texture.shape[1], texture.shape[0]]) / 16
        brightness = (0.74 + 0.26 * max(0, normal @ light)) if shade else 1.0
        for triangle in ([0, 1, 2], [0, 2, 3]):
            points = screen[triangle]
            x0, y0 = np.maximum(0, np.floor(points[:, :2].min(axis=0)).astype(int))
            x1, y1 = np.minimum([width - 1, height - 1], np.ceil(points[:, :2].max(axis=0)).astype(int))
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
            tx = np.clip(np.floor(p * st[0, 0] + q * st[1, 0] + r * st[2, 0]).astype(int), 0, texture.shape[1] - 1)
            ty = np.clip(np.floor(p * st[0, 1] + q * st[1, 1] + r * st[2, 1]).astype(int), 0, texture.shape[0] - 1)
            sampled = texture[ty, tx]
            mask &= sampled[:, :, 3] >= 128
            rgb = np.clip(sampled[:, :, :3] * brightness, 0, 255).astype(np.uint8)
            area[mask] = z[mask]
            pixels[y0:y1 + 1, x0:x1 + 1][mask] = rgb[mask]
    return Image.fromarray(pixels)


def locate_client():
    version = "1.20.1"
    for base in [ROOT / ".gradle-user-home", Path.home() / ".gradle"]:
        candidate = base / f"caches/forge_gradle/minecraft_repo/versions/{version}/client.jar"
        if candidate.is_file():
            return candidate
    return None


def main():
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("--client-jar", type=Path, default=locate_client())
    parser.add_argument("--output", type=Path, default=ROOT / "build/reports/terminal-review/redesigned-terminal.png")
    parser.add_argument("--title", default="存储终端 · 当前资源模型")
    parser.add_argument("--compare-before", type=Path, help="Saved baseline board made by this script")
    args = parser.parse_args()
    resources = Resources(args.client_jar)
    faces = model_faces(resources, "itemexplorer:block/storage_terminal")
    board = Image.new("RGB", (1800, 790), "#10171f")
    draw = ImageDraw.Draw(board)
    font_path = os.environ.get("TERMINAL_FONT", "C:/Windows/Fonts/msyh.ttc")
    title = ImageFont.truetype(font_path, 32)
    body = ImageFont.truetype(font_path, 22)
    small = ImageFont.truetype(font_path, 18)
    draw.text((32, 24), args.title, font=title, fill="#eff0e8")
    draw.text((32, 73), "直接读取 JSON 模型与实际 PNG 贴图  /  固定光照  /  最近邻像素采样", font=small, fill="#9aacb8")
    for x, camera, label in [(30, [0, 0, -1], "01  正面"),
                              (620, [1, 0.82, -1.25], "02  正面 · 侧面 · 顶面"),
                              (1210, [-1, 0.72, 1.2], "03  背面 · 侧面 · 顶面")]:
        board.paste(render(faces, camera), (x, 150))
        draw.text((x + 14, 116), label, font=body, fill="#a7c5cc")
    draw.text((32, 712), "软件正投影预览 · 非游戏截图；不包含 Minecraft 环境光照、发光效果与相邻方块。", font=small, fill="#9aacb8")
    draw.text((32, 746), f"模型元素 {len(resources.model('itemexplorer:block/storage_terminal')['elements'])}  /  实际纹理 {len(resources.textures)}  /  单方块比例", font=small, fill="#677d8d")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    board.save(args.output)
    print(args.output)
    for name, texture in resources.textures.items():
        print(f"  {name}: {texture.shape[1]}x{texture.shape[0]}")
    if args.compare_before:
        baseline = Image.open(args.compare_before).convert("RGB")
        if baseline.size != board.size:
            raise ValueError("Baseline dimensions do not match this script's preview board")
        comparison = Image.new("RGB", (1220, 750), "#10171f")
        cd = ImageDraw.Draw(comparison)
        cd.text((32, 23), "存储终端 · 改版前后对比", font=title, fill="#eff0e8")
        cd.text((45, 90), "改版前  /  原始模型与纹理", font=body, fill="#9aacb8")
        cd.text((655, 90), "改版后  /  32 × 32 金属面板", font=body, fill="#a7c5cc")
        crop = (620, 150, 1180, 680)
        comparison.paste(baseline.crop(crop), (25, 135))
        comparison.paste(board.crop(crop), (635, 135))
        cd.text((32, 693), "实际资源 · 相同视角与固定光照 · 软件正投影预览，非游戏截图", font=small, fill="#9aacb8")
        comparison_path = args.output.parent / "comparison.png"
        comparison.save(comparison_path)
        print(comparison_path)


if __name__ == "__main__":
    main()
