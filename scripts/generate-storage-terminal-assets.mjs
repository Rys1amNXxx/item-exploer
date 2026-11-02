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
// Each paint callback is one animation frame, stacked vertically as Minecraft expects.
function pixels(name, ...paints) {
  const height = size * paints.length, data = Buffer.alloc(size * height * 4);
  paints.forEach((paint, frame) => paint((x, y, width, height, color) => {
    const hex = palette[color] ?? color;
    const rgba = [0, 2, 4].map(offset => parseInt(hex.slice(offset, offset + 2), 16)).concat(255);
    for (let j = Math.max(0, y); j < Math.min(size, y + height); j++)
      for (let i = Math.max(0, x); i < Math.min(size, x + width); i++)
        data.set(rgba, ((frame * size + j) * size + i) * 4);
  }));
  const stride = size * 4, rows = Buffer.alloc(height * (stride + 1));
  for (let y = 0; y < height; y++) data.copy(rows, y * (stride + 1) + 1, y * stride, (y + 1) * stride);
  const header = Buffer.alloc(13);
  header.writeUInt32BE(size); header.writeUInt32BE(height, 4); header[8] = 8; header[9] = 6;
  mkdirSync(root + 'textures/block/storage_terminal', { recursive: true });
  writeFileSync(root + `textures/block/storage_terminal/${name}.png`, Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    chunk('IHDR', header), chunk('IDAT', deflateSync(rows)), chunk('IEND', Buffer.alloc(0)),
  ]));
}
function animation(name, frames) {
  writeFileSync(root + `textures/block/storage_terminal/${name}.png.mcmeta`,
    JSON.stringify({ animation: { frames } }, null, 2) + '\n');
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

// The glass occupies pixels 4..27 x 5..21, i.e. model x 2..14, y 5..13.5 on the north face.
// Screen textures repeat the whole front so mipmaps at the glass edge sample the same bezel.
function bezel(r) {
  shell(r);
  // The blue-grey chassis and warm folder are shared with the surrounding devices.
  r(3, 3, 26, 21, 'shadow'); r(3, 3, 26, 1, 'raised');
  r(3, 4, 26, 19, 'black');
  r(4, 22, 24, 1, 'light'); r(28, 5, 1, 17, 'seam');
  // Recessed ventilation and an engraved cyan key; the lit glass is the only state display.
  r(4, 25, 16, 4, 'shadow');
  for (let x = 5; x <= 17; x += 3) {
    r(x, 26, 2, 1, 'black'); r(x, 27, 2, 1, 'raised');
  }
  r(24, 25, 4, 4, 'edge'); r(24, 25, 4, 1, 'raised');
  r(25, 26, 2, 2, 'teal'); r(25, 26, 1, 1, 'cyan');
}
function idleGlass(r) {
  r(4, 5, 24, 17, 'glass'); r(5, 6, 22, 6, 'glassMid');
  r(5, 6, 22, 1, 'glassLight'); r(5, 7, 1, 13, 'glassLight');
  // Small screen header, kept subordinate to the recognizable folder silhouette.
  r(7, 7, 5, 1, 'teal'); r(13, 7, 2, 1, 'glassLight');
  r(24, 7, 1, 1, 'teal');
  // Folder tab is on the left in the north face's explicit UV orientation.
  r(10, 11, 6, 3, '8c6637'); r(11, 12, 13, 9, '102a32');
  r(9, 13, 14, 7, 'ba873e'); r(10, 11, 5, 3, 'deb868');
  r(10, 11, 5, 1, 'f2d493'); r(10, 13, 12, 6, 'dbad58');
  r(10, 13, 12, 1, 'f2d493'); r(9, 14, 1, 5, 'ebc779');
  r(10, 18, 12, 1, 'cc9948'); r(10, 19, 12, 1, '9a6d33');
}
// An open explorer window: path bar, folder tree and a grid of stored items.
function activeGlass(r, caret) {
  r(4, 5, 24, 17, 'glassMid'); r(5, 6, 22, 2, '225561');
  r(6, 7, 4, 1, 'cyan'); r(11, 7, 1, 1, 'teal'); r(13, 7, 5, 1, 'cyan');
  if (caret) r(19, 6, 1, 2, 'e3fbf8');
  r(5, 9, 6, 12, '15303a');
  for (const y of [10, 13, 16]) { r(6, y, 2, 2, 'dbad58'); r(8, y, 2, 1, 'teal'); }
  r(5, 12, 6, 4, '2a6470'); r(6, 13, 2, 2, 'f2d493'); r(8, 13, 2, 1, 'cyan');
  const items = ['c9d1d4', 'e6c050', 'c4473a', '4fbf73', '6fd6d0', '4a5157'];
  items.forEach((color, index) => {
    const x = 12 + (index % 3) * 5, y = 10 + Math.floor(index / 3) * 5;
    r(x, y, 4, 4, index === 0 ? 'cyan' : 'glassLight'); r(x + 1, y + 1, 2, 2, color);
  });
  r(12, 20, 14, 1, '1e424b'); r(12, 20, 5, 1, 'teal');
}
// Data protection: dark red glass and a warning sign that breathes between two brightnesses.
function lockedGlass(r, bright) {
  r(4, 5, 24, 17, '2a1216'); r(5, 6, 22, 6, '34171c');
  r(5, 6, 22, 1, '431d22'); r(5, 7, 1, 13, '431d22');
  r(7, 7, 5, 1, bright ? 'd0574a' : '8e3a33'); r(24, 7, 1, 1, bright ? 'd0574a' : '8e3a33');
  [2, 4, 4, 6, 8, 8, 10, 12, 12].forEach((width, row) => r(16 - width / 2, 9 + row, width, 1, bright ? 'eaa43d' : 'a8692a'));
  r(15, 11, 2, 3, '2a1216'); r(15, 15, 2, 1, '2a1216');
  for (const [x, width] of [[8, 3], [12, 6], [19, 4]]) r(x, 19, width, 1, bright ? 'c45a4f' : '7a2c2c');
}

pixels('front', r => { bezel(r); idleGlass(r); });
pixels('screen_active', r => { bezel(r); activeGlass(r, true); }, r => { bezel(r); activeGlass(r, false); });
animation('screen_active', [{ index: 0, time: 10 }, { index: 1, time: 10 }]);
pixels('screen_locked', r => { bezel(r); lockedGlass(r, true); }, r => { bezel(r); lockedGlass(r, false); });
animation('screen_locked', [{ index: 0, time: 16 }, { index: 1, time: 8 }]);
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
const faces = { south: 'back', east: 'side', west: 'side', up: 'top', down: 'bottom' };
// The north face is split into a bezel frame and a coplanar glass quad, so nothing overlaps or z-fights.
// North-face UVs mirror x: u = 16 - x, v = 16 - y.
const northQuad = (x1, y1, x2, y2, texture, extra = {}) => ({
  from: [x1, y1, 0], to: [x2, y2, 0], ...extra,
  faces: { north: { uv: [16 - x2, 16 - y2, 16 - x1, 16 - y1], texture, cullface: 'north' } },
});
const model = {
  parent: 'minecraft:block/block',
  textures: Object.fromEntries(['front', 'back', 'side', 'top', 'bottom'].map(name => [name, texture(name)])),
  elements: [
    {
      from: [0, 0, 0], to: [16, 16, 16],
      faces: Object.fromEntries(Object.entries(faces).map(([face, name]) => [face, {
        uv: [0, 0, 16, 16], texture: '#' + name, cullface: face,
      }])),
    },
    northQuad(0, 13.5, 16, 16, '#front'), northQuad(0, 0, 16, 5, '#front'),
    northQuad(0, 5, 2, 13.5, '#front'), northQuad(14, 5, 16, 13.5, '#front'),
    // Full-bright, unshaded glass: visible at night without lighting the surroundings.
    northQuad(2, 5, 14, 13.5, '#screen', {
      shade: false, forge_data: { block_light: 15, sky_light: 15, ambient_occlusion: false },
    }),
  ],
};
model.textures.screen = '#front';
model.textures.particle = texture('side');
writeFileSync(root + 'models/block/storage_terminal.json', JSON.stringify(model, null, 2) + '\n');
for (const screen of ['active', 'locked']) {
  writeFileSync(root + `models/block/storage_terminal_${screen}.json`, JSON.stringify({
    parent: 'itemexplorer:block/storage_terminal', textures: { screen: texture('screen_' + screen) },
  }, null, 2) + '\n');
}
const rotations = { north: 0, east: 90, south: 180, west: 270 };
const variants = {};
for (const [facing, y] of Object.entries(rotations)) for (const screen of ['idle', 'active', 'locked']) {
  variants[`facing=${facing},screen=${screen}`] = {
    model: 'itemexplorer:block/storage_terminal' + (screen === 'idle' ? '' : '_' + screen), ...(y ? { y } : {}),
  };
}
writeFileSync(root + 'blockstates/storage_terminal.json', JSON.stringify({ variants }, null, 2) + '\n');
console.log('Generated terminal textures, three screen models and the blockstate.');
