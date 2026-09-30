// Editable, deterministic 32px artwork for the storage terminal.
// UVs stay in Minecraft's 0..16 model coordinates. The front is a single face,
// so the folder, glass and bezel share one pixel grid without overlapping quads.
import { mkdirSync, writeFileSync } from 'node:fs';
import { deflateSync } from 'node:zlib';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../src/main/resources/assets/itemexplorer/', import.meta.url));
const size = 32;
const palette = {
  edge: '222d35', shadow: '35434c', body: '4b5b64', panel: '53656e',
  raised: '637780', light: '869ca3', glint: 'b3c5c7', seam: '3d4e58',
  black: '111e26', glass: '17343e', glassMid: '193b45', glassLight: '1e424b',
  teal: '4c8990', cyan: '88b9b8', contact: 'c49a54',
};
function crc32(bytes) {
  let value = -1;
  for (const byte of bytes) {
    value ^= byte;
    for (let bit = 0; bit < 8; bit++) value = (value >>> 1) ^ ((value & 1) ? 0xedb88320 : 0);
  }
  return (value ^ -1) >>> 0;
}
function chunk(type, data) {
  const name = Buffer.from(type), result = Buffer.alloc(data.length + 12);
  result.writeUInt32BE(data.length); name.copy(result, 4); data.copy(result, 8);
  result.writeUInt32BE(crc32(Buffer.concat([name, data])), data.length + 8);
  return result;
}
function pixels(name, paint) {
  const data = Buffer.alloc(size * size * 4);
  const rect = (x, y, width, height, color) => {
    const hex = palette[color] ?? color;
    const rgba = [0, 2, 4].map(offset => parseInt(hex.slice(offset, offset + 2), 16)).concat(255);
    for (let j = Math.max(0, y); j < Math.min(size, y + height); j++)
      for (let i = Math.max(0, x); i < Math.min(size, x + width); i++)
        data.set(rgba, (j * size + i) * 4);
  };
  paint(rect);
  const stride = size * 4, rows = Buffer.alloc(size * (stride + 1));
  for (let y = 0; y < size; y++) data.copy(rows, y * (stride + 1) + 1, y * stride, (y + 1) * stride);
  const header = Buffer.alloc(13);
  header.writeUInt32BE(size); header.writeUInt32BE(size, 4); header[8] = 8; header[9] = 6;
  mkdirSync(root + 'textures/block/storage_terminal', { recursive: true });
  writeFileSync(root + `textures/block/storage_terminal/${name}.png`, Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    chunk('IHDR', header), chunk('IDAT', deflateSync(rows)), chunk('IEND', Buffer.alloc(0)),
  ]));
}
function shell(r) {
  r(0, 0, 32, 32, 'edge');
  r(1, 1, 30, 30, 'body');
  r(1, 1, 30, 1, 'light'); r(1, 2, 1, 28, 'raised');
  r(2, 2, 28, 1, 'raised'); r(30, 2, 1, 29, 'shadow');
  r(2, 30, 28, 1, 'shadow');
}
function screw(r, x, y) {
  r(x, y, 2, 2, 'edge'); r(x, y, 1, 1, 'light');
}
function screws(r) {
  for (const [x, y] of [[3, 3], [27, 3], [3, 27], [27, 27]]) screw(r, x, y);
}
function panel(r) {
  shell(r);
  r(5, 4, 22, 24, 'shadow'); r(6, 5, 20, 22, 'panel');
  r(6, 5, 20, 1, 'raised'); r(6, 6, 1, 20, 'raised');
  r(7, 26, 19, 1, 'seam');
  screws(r);
}
function vents(r, x, y, width, rows) {
  for (let i = 0; i < rows; i++) {
    r(x, y + i * 3, width, 1, 'black');
    r(x, y + i * 3 + 1, width, 1, 'raised');
  }
}
function socket(r) {
  // Centered on the existing 2x2-model-unit cable endpoint on every utility face.
  r(10, 10, 12, 12, 'shadow'); r(10, 10, 12, 1, 'light');
  r(10, 11, 1, 10, 'raised'); r(21, 11, 1, 11, 'edge');
  r(11, 11, 10, 10, 'teal'); r(12, 12, 8, 8, 'black');
  r(13, 13, 6, 6, 'shadow'); r(14, 14, 4, 4, 'black');
  for (const x of [12, 19]) for (const y of [13, 16, 18]) r(x, y, 1, 1, 'contact');
  r(14, 11, 4, 1, 'cyan'); r(14, 20, 4, 1, 'seam');
}

pixels('front', r => {
  shell(r);
  // The blue-grey chassis and warm folder are shared with the surrounding devices.
  r(3, 3, 26, 21, 'shadow'); r(3, 3, 26, 1, 'raised');
  r(3, 4, 26, 19, 'black');
  r(4, 5, 24, 17, 'glass'); r(5, 6, 22, 6, 'glassMid');
  r(5, 6, 22, 1, 'glassLight'); r(5, 7, 1, 13, 'glassLight');
  r(4, 22, 24, 1, 'light'); r(28, 5, 1, 17, 'seam');
  // Small screen header, kept subordinate to the recognizable folder silhouette.
  r(7, 7, 5, 1, 'teal'); r(13, 7, 2, 1, 'glassLight');
  r(24, 7, 1, 1, 'teal');
  // Folder tab is on the left in the north face's explicit UV orientation.
  r(10, 11, 6, 3, '8c6637'); r(11, 12, 13, 9, '102a32');
  r(9, 13, 14, 7, 'ba873e'); r(10, 11, 5, 3, 'deb868');
  r(10, 11, 5, 1, 'f2d493'); r(10, 13, 12, 6, 'dbad58');
  r(10, 13, 12, 1, 'f2d493'); r(9, 14, 1, 5, 'ebc779');
  r(10, 18, 12, 1, 'cc9948'); r(10, 19, 12, 1, '9a6d33');
  // Recessed ventilation and an engraved cyan key, not a fake online-state LED.
  r(4, 25, 16, 4, 'shadow');
  for (let x = 5; x <= 17; x += 3) {
    r(x, 26, 2, 1, 'black'); r(x, 27, 2, 1, 'raised');
  }
  r(24, 25, 4, 4, 'edge'); r(24, 25, 4, 1, 'raised');
  r(25, 26, 2, 2, 'teal'); r(25, 26, 1, 1, 'cyan');
});
pixels('side', r => {
  panel(r); socket(r);
  r(9, 7, 7, 1, 'raised'); r(9, 8, 4, 1, 'seam');
  vents(r, 9, 23, 14, 1);
});
pixels('back', r => {
  panel(r); socket(r);
  vents(r, 8, 7, 16, 1); vents(r, 8, 24, 16, 1);
  r(23, 12, 1, 5, 'seam'); r(24, 12, 1, 5, 'raised');
});
pixels('top', r => {
  panel(r); socket(r);
  r(9, 7, 14, 1, 'seam'); r(9, 8, 14, 1, 'raised');
  r(9, 24, 8, 1, 'raised'); r(20, 24, 3, 1, 'contact');
});
pixels('bottom', r => {
  shell(r); r(5, 5, 22, 22, 'shadow'); socket(r);
  for (const [x, y] of [[3, 3], [25, 3], [3, 25], [25, 25]]) {
    r(x, y, 4, 4, 'black'); r(x, y, 4, 1, 'seam');
  }
});

const texture = name => 'itemexplorer:block/storage_terminal/' + name;
const faces = { north: 'front', south: 'back', east: 'side', west: 'side', up: 'top', down: 'bottom' };
const model = {
  parent: 'minecraft:block/block',
  textures: Object.fromEntries(['front', 'back', 'side', 'top', 'bottom'].map(name => [name, texture(name)])),
  elements: [{
    from: [0, 0, 0], to: [16, 16, 16],
    faces: Object.fromEntries(Object.entries(faces).map(([face, name]) => [face, {
      uv: [0, 0, 16, 16], texture: '#' + name, cullface: face,
    }])),
  }],
};
model.textures.particle = texture('side');
writeFileSync(root + 'models/block/storage_terminal.json', JSON.stringify(model, null, 2) + '\n');
console.log('Generated five 32x32 terminal textures and one cube model with explicit UVs.');
