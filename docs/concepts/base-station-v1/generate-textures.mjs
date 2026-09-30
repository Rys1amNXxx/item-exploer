// Design-only, deterministic native pixel assets. Does not modify game resources.
// Run from any directory: node docs/concepts/base-station-v1/generate-textures.mjs
import { mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { deflateSync, inflateSync } from 'node:zlib';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('./', import.meta.url));
const textureRoot = fileURLToPath(new URL('./textures/', import.meta.url));
mkdirSync(textureRoot, { recursive: true });
const palette = {
  frame: '839098', highlight: 'c3cbd0', edge: '9aa7af', body: '68747d',
  bodyLight: '737f87', shadow: '414d56', recess: '29343d', black: '101d25',
  ivory: 'd7d9d1', ivoryLight: 'ebece3', ivoryShadow: 'a9b1ad',
  cyan: '4596a4', cyanLight: '97cfce', amber: 'edc66f', amberShadow: 'b9872c',
};
function crc32(data) {
  let c = -1;
  for (const b of data) { c ^= b; for (let i = 0; i < 8; i++) c = (c >>> 1) ^ ((c & 1) ? 0xedb88320 : 0); }
  return (c ^ -1) >>> 0;
}
function chunk(type, data) {
  const t = Buffer.from(type), out = Buffer.alloc(12 + data.length);
  out.writeUInt32BE(data.length); t.copy(out, 4); data.copy(out, 8);
  out.writeUInt32BE(crc32(Buffer.concat([t, data])), 8 + data.length);
  return out;
}
function canvas(width, height, background) {
  const pixels = Buffer.alloc(width * height * 4);
  const rect = (x, y, w, h, color) => {
    if (!/^[0-9a-f]{6}$/i.test(color)) throw new Error(`Invalid RGB: ${color}`);
    const rgba = [0, 2, 4].map(i => parseInt(color.slice(i, i + 2), 16)).concat(255);
    for (let j = Math.max(0, y); j < Math.min(height, y + h); j++)
      for (let i = Math.max(0, x); i < Math.min(width, x + w); i++) pixels.set(rgba, (j * width + i) * 4);
  };
  rect(0, 0, width, height, background);
  return { width, height, pixels, rect };
}
function png(image) {
  const { width, height, pixels } = image;
  const rows = Buffer.alloc(height * (width * 4 + 1));
  for (let y = 0; y < height; y++) pixels.copy(rows, y * (width * 4 + 1) + 1, y * width * 4, (y + 1) * width * 4);
  const header = Buffer.alloc(13); header.writeUInt32BE(width); header.writeUInt32BE(height, 4); header[8] = 8; header[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', header), chunk('IDAT', deflateSync(rows)), chunk('IEND', Buffer.alloc(0))]);
}
const assets = [];
function texture(name, title, paint) {
  const image = canvas(32, 32, palette.body);
  paint(image.rect);
  writeFileSync(textureRoot + name + '.png', png(image));
  assets.push({ name, title, image });
}
function panel(r, fill = palette.body) {
  r(0, 0, 32, 32, palette.shadow); r(1, 1, 30, 30, palette.frame);
  r(1, 1, 30, 1, palette.highlight); r(1, 2, 1, 29, palette.edge);
  r(3, 3, 26, 26, fill); r(3, 28, 26, 1, palette.shadow);
}
function screws(r) {
  for (const [x, y] of [[3, 3], [27, 3], [3, 27], [27, 27]]) {
    r(x, y, 2, 2, palette.recess); r(x, y, 1, 1, palette.highlight);
  }
}
function folder(r, x, y) {
  r(x, y + 1, 7, 5, palette.amberShadow); r(x, y, 3, 2, palette.amber);
  r(x + 1, y + 2, 6, 3, palette.amber); r(x + 1, y + 2, 5, 1, 'ffe3a1');
}
texture('casing_side', '机壳侧面', r => {
  panel(r); screws(r); r(6, 7, 20, 11, palette.bodyLight); r(6, 7, 20, 1, palette.frame);
  for (let y = 21; y <= 25; y += 2) { r(7, y, 18, 1, palette.shadow); r(8, y, 16, 1, palette.recess); }
});
texture('casing_top', '机壳顶面 / 通用底面', r => {
  panel(r, palette.bodyLight); screws(r);
  r(7, 7, 18, 18, palette.body); r(7, 7, 18, 1, palette.edge); r(7, 7, 1, 18, palette.frame);
  r(7, 24, 18, 1, palette.shadow); r(24, 8, 1, 16, palette.shadow);
});
const states = [
  ['off', '离线', '4e5c65', '71818b'], ['online', '在线', '4a9e57', 'a7e581'],
  ['active', '传输中', '298fa4', '97eeed'], ['error', '异常', 'ac4545', 'ffaaa0'],
];
for (const [state, label, color, light] of states) texture('controller_front_' + state, '控制器正面 · ' + label, r => {
  panel(r, palette.recess); screws(r); folder(r, 7, 5);
  r(22, 6, 4, 4, palette.black); r(23, 7, 2, 2, color); r(23, 7, 1, 1, light);
  r(6, 13, 20, 10, palette.shadow); r(7, 14, 18, 8, palette.black);
  const ink = state === 'off' ? '50636e' : palette.cyanLight;
  // A small network diagram; neither an energy meter nor a power symbol.
  r(10, 18, 12, 1, ink); r(15, 16, 2, 4, ink);
  for (const [x, y] of [[9, 17], [20, 17], [14, 15]]) { r(x, y, 3, 3, ink); r(x + 1, y + 1, 1, 1, palette.black); }
  r(7, 25, 10, 1, palette.frame); r(19, 25, 6, 1, palette.shadow);
});
texture('data_port', '数据接线口正面', r => {
  panel(r); screws(r); r(8, 6, 16, 1, palette.shadow);
  r(10, 11, 12, 12, palette.frame); r(11, 12, 10, 10, palette.highlight);
  r(12, 13, 8, 8, palette.recess); r(13, 14, 6, 6, palette.black);
  r(13, 19, 6, 1, palette.cyan); r(13, 19, 1, 1, palette.cyanLight);
  r(12, 25, 8, 1, palette.shadow); r(14, 26, 4, 1, palette.edge);
});
texture('blank_module', '空白扩展模块正面', r => {
  panel(r, palette.recess); screws(r);
  r(6, 6, 20, 20, palette.bodyLight); r(6, 6, 20, 1, palette.edge); r(6, 25, 20, 1, palette.shadow);
  // An unmarked removable cover. No upgrade type or installed-module light.
  r(10, 11, 12, 3, palette.shadow); r(11, 12, 10, 1, palette.frame);
  r(9, 20, 14, 1, palette.body); r(9, 22, 14, 1, palette.body);
});
texture('mast_side', '立柱侧面', r => {
  r(0, 0, 32, 32, palette.shadow); r(2, 0, 28, 32, palette.body);
  r(3, 0, 3, 32, palette.edge); r(6, 0, 2, 32, palette.frame);
  r(11, 0, 10, 32, palette.recess); r(13, 0, 1, 32, palette.black);
  r(23, 0, 3, 32, palette.frame); r(26, 0, 3, 32, palette.bodyLight);
  r(2, 0, 28, 2, palette.highlight); r(2, 30, 28, 2, palette.shadow);
  r(15, 26, 2, 2, palette.cyan); r(15, 26, 1, 1, palette.cyanLight);
});
texture('antenna_face', '天线板外侧', r => {
  r(0, 0, 32, 32, palette.shadow); r(1, 1, 30, 30, palette.ivoryShadow);
  r(2, 2, 28, 28, palette.ivory); r(2, 2, 28, 1, palette.ivoryLight);
  r(2, 3, 1, 27, palette.ivoryLight); r(29, 3, 1, 27, palette.ivoryShadow);
  for (const x of [8, 13, 18, 23]) { r(x, 6, 1, 20, palette.ivoryShadow); r(x + 1, 6, 1, 20, 'bec5be'); }
  r(6, 4, 20, 1, palette.ivoryLight); r(6, 27, 20, 1, palette.ivoryShadow);
  r(4, 15, 1, 2, palette.shadow); r(27, 15, 1, 2, palette.shadow);
});
texture('antenna_back', '天线板内侧 / 窄侧面', r => {
  panel(r, palette.bodyLight); screws(r);
  for (const x of [7, 22]) { r(x, 5, 3, 22, palette.shadow); r(x, 5, 1, 22, palette.edge); }
  r(5, 13, 22, 6, palette.shadow); r(5, 13, 22, 1, palette.highlight);
  r(12, 11, 8, 10, palette.recess); r(14, 13, 4, 6, palette.frame); r(15, 14, 2, 4, palette.bodyLight);
});
texture('antenna_cap', '顶帽上表面', r => {
  panel(r, palette.bodyLight); screws(r);
  r(7, 7, 18, 18, palette.body); r(7, 7, 18, 1, palette.edge);
  r(7, 7, 1, 18, palette.frame); r(24, 8, 1, 17, palette.shadow); r(8, 24, 16, 1, palette.shadow);
  r(14, 14, 4, 4, palette.recess); r(15, 15, 2, 2, palette.cyan); r(15, 15, 1, 1, palette.cyanLight);
});

const glyphs = {
  A:['010','101','111','101','101'], B:['110','101','110','101','110'], C:['011','100','100','100','011'],
  D:['110','101','101','101','110'], E:['111','100','110','100','111'], F:['111','100','110','100','100'],
  G:['011','100','101','101','011'], H:['101','101','111','101','101'], I:['111','010','010','010','111'],
  J:['001','001','001','101','010'], K:['101','101','110','101','101'], L:['100','100','100','100','111'],
  M:['101','111','111','101','101'], N:['101','111','111','111','101'], O:['010','101','101','101','010'],
  P:['110','101','110','100','100'], Q:['010','101','101','111','011'], R:['110','101','110','101','101'],
  S:['011','100','010','001','110'], T:['111','010','010','010','010'], U:['101','101','101','101','111'],
  V:['101','101','101','101','010'], W:['101','101','111','111','101'], X:['101','101','010','101','101'],
  Y:['101','101','010','010','010'], Z:['111','001','010','100','111'],
  '0':['111','101','101','101','111'], '1':['010','110','010','010','111'], '2':['110','001','010','100','111'],
  '3':['110','001','010','001','110'], '4':['101','101','111','001','001'], '5':['111','100','110','001','110'],
  '6':['011','100','111','101','111'], '7':['111','001','010','010','010'], '8':['111','101','111','101','111'],
  '9':['111','101','111','001','110'], ' ':['000','000','000','000','000'],
  '_':['000','000','000','000','111'], '/':['001','001','010','100','100'], '-':['000','000','111','000','000'],
};
function label(image, value, x, y, scale = 2, color = 'c3cbd0') {
  for (const ch of value.toUpperCase()) {
    if (!glyphs[ch]) throw new Error(`Missing glyph ${ch}`);
    glyphs[ch].forEach((row, j) => [...row].forEach((bit, i) => { if (bit === '1') image.rect(x + i * scale, y + j * scale, scale, scale, color); }));
    x += scale * 4;
  }
}
function copyNearest(source, target, x, y, scale) {
  for (let j = 0; j < source.height; j++) for (let i = 0; i < source.width; i++) {
    const p = (j * source.width + i) * 4;
    target.rect(x + i * scale, y + j * scale, scale, scale, source.pixels.subarray(p, p + 3).toString('hex'));
  }
}
const sheet = canvas(1000, 856, '182129');
label(sheet, 'BASE STATION / TEXTURE DRAFT V1', 28, 26, 3, 'ebece3');
label(sheet, '32 X 32 / OPAQUE / 5X NEAREST PREVIEW', 28, 57, 2, '97cfce');
assets.forEach((asset, index) => {
  const x = 28 + (index % 4) * 244, y = 92 + Math.floor(index / 4) * 244;
  sheet.rect(x, y, 228, 228, '222e37');
  sheet.rect(x, y, 228, 1, '414d56');
  label(sheet, String(index + 1).padStart(2, '0'), x + 9, y + 9, 2, 'edc66f');
  copyNearest(asset.image, sheet, x + 34, y + 31, 5);
  label(sheet, asset.name, x + Math.floor((228 - (asset.name.length * 8 - 2)) / 2), y + 205, 2);
});
label(sheet, 'DESIGN ONLY / NO GAME REGISTRATION / NO EMISSIVE MAP', 28, 833, 2, '839098');
writeFileSync(root + 'texture-sheet.png', png(sheet));
writeFileSync(root + 'texture-manifest.json', JSON.stringify({
  version: 1, size: [32, 32], format: 'RGBA8 PNG', alpha: 255, emissive: false,
  usage: 'Design drafts only; no game block registration.',
  textures: assets.map(({ name, title }, i) => ({ number: i + 1, file: 'textures/' + name + '.png', title })),
}, null, 2) + '\n');

// Read back the PNG files to verify encoded size and every alpha channel.
for (const asset of assets) {
  const bytes = readFileSync(textureRoot + asset.name + '.png');
  if (bytes.readUInt32BE(16) !== 32 || bytes.readUInt32BE(20) !== 32) throw new Error(`Incorrect size: ${asset.name}`);
  const compressed = [];
  for (let offset = 8; offset < bytes.length;) {
    const length = bytes.readUInt32BE(offset), type = bytes.toString('ascii', offset + 4, offset + 8);
    if (type === 'IDAT') compressed.push(bytes.subarray(offset + 8, offset + 8 + length));
    offset += 12 + length;
  }
  const rows = inflateSync(Buffer.concat(compressed));
  for (let y = 0; y < 32; y++) for (let x = 0; x < 32; x++)
    if (rows[y * 129 + 1 + x * 4 + 3] !== 255) throw new Error(`Nonopaque pixel: ${asset.name}`);
}
console.log(`Generated and verified ${assets.length} opaque 32x32 texture drafts, manifest, and nearest-neighbor contact sheet.`);
