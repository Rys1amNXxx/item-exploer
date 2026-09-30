// Deterministic Minecraft resources for the 17-block base station.
// Native cuboids and opaque pixels only; no renderer, image service or power system.
import { copyFileSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { deflateSync } from 'node:zlib';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../src/main/resources/', import.meta.url));
const drafts = fileURLToPath(new URL('../docs/concepts/base-station-v1/textures/', import.meta.url));
const assets = 'assets/itemexplorer/';
const textureDir = assets + 'textures/block/base_station/';
const id = part => 'base_station_' + part;
const resource = part => 'itemexplorer:' + id(part);
const texture = name => 'itemexplorer:block/base_station/' + name;
const model = part => 'itemexplorer:block/' + id(part);
const parts = ['casing', 'controller', 'network_port', 'module', 'mast', 'antenna', 'cap'];
const directions = ['north', 'east', 'south', 'west', 'up', 'down'];
const rotations = { north: 0, east: 90, south: 180, west: 270 };
function write(path, value) {
  const file = root + path;
  mkdirSync(file.slice(0, file.lastIndexOf('/')), { recursive: true });
  writeFileSync(file, typeof value === 'string' || Buffer.isBuffer(value) ? value : JSON.stringify(value, null, 2) + '\n');
}

// Keep the reviewed source textures, including the two states reserved for future transfers.
mkdirSync(root + textureDir, { recursive: true });
for (const name of readdirSync(drafts).filter(name => name.endsWith('.png')))
  copyFileSync(drafts + '/' + name, root + textureDir + name);

function crc32(bytes) {
  let crc = -1;
  for (const byte of bytes) { crc ^= byte; for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0); }
  return (crc ^ -1) >>> 0;
}
function chunk(type, bytes) {
  const name = Buffer.from(type), result = Buffer.alloc(bytes.length + 12);
  result.writeUInt32BE(bytes.length); name.copy(result, 4); bytes.copy(result, 8);
  result.writeUInt32BE(crc32(Buffer.concat([name, bytes])), bytes.length + 8);
  return result;
}
function pixels(name, paint) {
  const size = 32, data = Buffer.alloc(size * size * 4);
  const rect = (x, y, w, h, hex) => {
    const rgba = [0, 2, 4].map(offset => parseInt(hex.slice(offset, offset + 2), 16)).concat(255);
    for (let j = Math.max(0, y); j < Math.min(size, y + h); j++)
      for (let i = Math.max(0, x); i < Math.min(size, x + w); i++) data.set(rgba, (j * size + i) * 4);
  };
  rect(0, 0, size, size, '68747d'); paint(rect);
  const rows = Buffer.alloc(size * (size * 4 + 1));
  for (let y = 0; y < size; y++) data.copy(rows, y * 129 + 1, y * 128, (y + 1) * 128);
  const header = Buffer.alloc(13); header.writeUInt32BE(size); header.writeUInt32BE(size, 4); header[8] = 8; header[9] = 6;
  write(textureDir + name + '.png', Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', header), chunk('IDAT', deflateSync(rows)), chunk('IEND', Buffer.alloc(0))]));
}

// These four maps preserve two pixels per model unit on narrow parts. The reviewed
// square concept faces remain available, but are not squeezed onto slender sides.
pixels('mast_uv', r => {
  r(0, 0, 12, 32, '68747d'); r(0, 0, 1, 32, '9aa7af'); r(1, 0, 2, 32, '839098');
  r(4, 0, 4, 32, '29343d'); r(4, 0, 1, 32, '101d25'); r(9, 0, 2, 32, '737f87'); r(11, 0, 1, 32, '414d56');
  r(5, 25, 2, 2, '4596a4'); r(5, 25, 1, 1, '97cfce');
});
pixels('metal_uv', r => {
  r(0, 0, 32, 32, '839098');
  for (let y = 0; y < 32; y += 8) { r(0, y, 32, 1, 'c3cbd0'); r(0, y + 1, 32, 1, '9aa7af'); r(0, y + 7, 32, 1, '414d56'); }
  for (let x = 0; x < 32; x += 8) { r(x, 2, 1, 4, '9aa7af'); r(x + 7, 2, 1, 4, '68747d'); }
});
pixels('antenna_face_uv', r => {
  r(0, 0, 20, 28, '414d56'); r(1, 1, 18, 26, 'a9b1ad'); r(2, 2, 16, 24, 'd7d9d1');
  r(2, 2, 16, 1, 'ebece3'); r(2, 3, 1, 23, 'ebece3');
  for (const x of [5, 8, 11, 14]) { r(x, 5, 1, 18, 'a9b1ad'); r(x + 1, 5, 1, 18, 'bec5be'); }
  r(4, 3, 12, 1, 'ebece3'); r(4, 24, 12, 1, 'a9b1ad');
  r(2, 13, 1, 2, '414d56'); r(17, 13, 1, 2, '414d56');
});
pixels('antenna_back_uv', r => {
  r(0, 0, 20, 28, '414d56'); r(1, 1, 18, 26, '839098'); r(2, 2, 16, 24, '737f87');
  r(1, 1, 18, 1, 'c3cbd0');
  for (const x of [4, 14]) { r(x, 4, 2, 20, '414d56'); r(x, 4, 1, 20, '9aa7af'); }
  r(3, 11, 14, 6, '414d56'); r(3, 11, 14, 1, 'c3cbd0'); r(7, 10, 6, 8, '29343d');
  for (const [x, y] of [[2, 2], [16, 2], [2, 24], [16, 24]]) { r(x, y, 2, 2, '29343d'); r(x, y, 1, 1, 'c3cbd0'); }
});

function cuboid(from, to, tex, faces = directions, uv = [0, 0, 16, 16]) {
  return { from, to, faces: Object.fromEntries(faces.map(face => [face, { texture: '#' + tex, uv }])) };
}
function plane(from, to, face, tex, uv) { return cuboid(from, to, tex, [face], uv); }
function saveModel(name, elements, textures) {
  write(assets + 'models/block/' + id(name) + '.json', { parent: 'minecraft:block/block', textures, elements });
}
const casingTextures = { side: texture('casing_side'), top: texture('casing_top'), particle: texture('casing_side') };
const full = cuboid([0, 0, 0], [16, 16, 16], 'side');
for (const face of directions) full.faces[face].cullface = face;
full.faces.up.texture = full.faces.down.texture = '#top';
saveModel('casing', [full], casingTextures);
for (const [part, front] of [['controller', 'controller_front_off'], ['network_port', 'data_port'], ['module', 'blank_module']]) {
  const body = structuredClone(full); body.faces.north.texture = '#front';
  saveModel(part, [body], { ...casingTextures, front: texture(front) });
}
// The green source texture means "structure formed" here, not an online transport link.
write(assets + 'models/block/' + id('controller_formed') + '.json', { parent: model('controller'), textures: { front: texture('controller_front_online') } });

const hardware = { metal: texture('metal_uv'), mast: texture('mast_uv'), top: texture('casing_top'), particle: texture('casing_side') };
const mast = [];
// Horizontal rings expose only the area outside the central 6x6 pillar.
function squareRing(y, face, outerMin, outerMax, innerMin, innerMax, tex) {
  const boxes = [[outerMin, outerMin, outerMax, innerMin], [outerMin, innerMax, outerMax, outerMax], [outerMin, innerMin, innerMin, innerMax], [innerMax, innerMin, outerMax, innerMax]];
  return boxes.map(([x1, z1, x2, z2]) => plane([x1, y, z1], [x2, y, z2], face, tex, [x1, z1, x2, z2]));
}
mast.push(cuboid([4, 0, 4], [12, 2, 12], 'metal', ['north', 'east', 'south', 'west', 'down'], [0, 0, 8, 2]));
mast.push(...squareRing(2, 'up', 4, 12, 5, 11, 'top'));
mast.push(cuboid([4, 14, 4], [12, 16, 12], 'metal', ['north', 'east', 'south', 'west', 'up'], [0, 0, 8, 2]));
mast.push(...squareRing(14, 'down', 4, 12, 5, 11, 'top'));
mast[0].faces.down.uv = [4, 4, 12, 12];
mast[5].faces.up.uv = [4, 4, 12, 12];
// Face coordinates are continuous over four patches; the 2x2 attachment is a real hole.
const patches = [[5, 2, 11, 7], [5, 9, 11, 14], [5, 7, 7, 9], [9, 7, 11, 9]];
for (const [x1, y1, x2, y2] of patches) {
  mast.push(plane([x1, y1, 5], [x2, y2, 5], 'north', 'mast', [11 - x2, 16 - y2, 11 - x1, 16 - y1]));
  mast.push(plane([x1, y1, 11], [x2, y2, 11], 'south', 'mast', [x1 - 5, 16 - y2, x2 - 5, 16 - y1]));
  mast.push(plane([5, y1, x1], [5, y2, x2], 'west', 'mast', [x1 - 5, 16 - y2, x2 - 5, 16 - y1]));
  mast.push(plane([11, y1, x1], [11, y2, x2], 'east', 'mast', [11 - x2, 16 - y2, 11 - x1, 16 - y1]));
}
saveModel('mast_core', mast, hardware);
const cover = plane([7, 7, 5], [9, 9, 5], 'north', 'mast', [2, 7, 4, 9]);
saveModel('mast_cover', [cover], hardware);
// The inward end is absent. It meets the matching hole in the core without overlaying it.
const arm = cuboid([7, 7, 0], [9, 9, 5], 'metal', directions.filter(face => face !== 'south'), [0, 0, 2, 5]);
arm.faces.north.uv = [0, 0, 2, 2];
arm.faces.east.uv = arm.faces.west.uv = [0, 0, 5, 2];
saveModel('mast_arm', [arm], hardware);
// Inventory view is a complete standalone column, with the four arm sockets covered.
const covers = [cover,
  plane([11, 7, 7], [11, 9, 9], 'east', 'mast', [2, 7, 4, 9]),
  plane([7, 7, 11], [9, 9, 11], 'south', 'mast', [2, 7, 4, 9]),
  plane([5, 7, 7], [5, 9, 9], 'west', 'mast', [2, 7, 4, 9])];
saveModel('mast', [...mast, ...covers], hardware);

const antenna = cuboid([3, 1, 4], [13, 15, 6], 'metal', directions.filter(face => face !== 'south'), [0, 0, 2, 14]);
antenna.faces.north = { texture: '#front', uv: [0, 0, 10, 14] };
antenna.faces.up.uv = antenna.faces.down.uv = [0, 0, 10, 2];
const antennaElements = [antenna];
for (const [x1, y1, x2, y2] of [[3, 1, 13, 7], [3, 9, 13, 15], [3, 7, 7, 9], [9, 7, 13, 9]])
  antennaElements.push(plane([x1, y1, 6], [x2, y2, 6], 'south', 'back', [x1 - 3, 15 - y2, x2 - 3, 15 - y1]));
const antennaArm = cuboid([7, 7, 6], [9, 9, 16], 'metal', directions.filter(face => face !== 'north'), [0, 0, 2, 10]);
antennaArm.faces.south.uv = [0, 0, 2, 2];
antennaArm.faces.east.uv = antennaArm.faces.west.uv = [0, 0, 10, 2];
antennaElements.push(antennaArm);
saveModel('antenna', antennaElements, { ...hardware, front: texture('antenna_face_uv'), back: texture('antenna_back_uv') });

const cap = cuboid([4, 0, 4], [12, 4, 12], 'metal', directions.filter(face => face !== 'up'), [0, 0, 8, 4]);
cap.faces.down.uv = [4, 4, 12, 12];
const capElements = [cap, ...squareRing(4, 'up', 4, 12, 6.5, 9.5, 'cap')];
const rod = cuboid([6.5, 4, 6.5], [9.5, 16, 9.5], 'metal', directions.filter(face => face !== 'down'), [0, 0, 3, 12]);
rod.faces.up.uv = [0, 0, 3, 3]; capElements.push(rod);
saveModel('cap', capElements, { ...hardware, cap: texture('antenna_cap') });

for (const part of parts.filter(part => part !== 'mast')) {
  const variants = {};
  for (const [facing, y] of Object.entries(rotations)) {
    const rotation = ['casing', 'cap'].includes(part) || y === 0 ? {} : { y };
    if (part === 'controller') for (const formed of [false, true])
      variants[`facing=${facing},formed=${formed}`] = { model: model(formed ? 'controller_formed' : 'controller'), ...rotation };
    else variants[`facing=${facing}`] = { model: model(part), ...rotation };
  }
  write(assets + 'blockstates/' + id(part) + '.json', { variants });
}
const multipart = [{ apply: { model: model('mast_core') } }];
for (const [facing, y] of Object.entries(rotations)) for (const connected of [false, true])
  multipart.push({ when: { [facing]: String(connected) }, apply: { model: model(connected ? 'mast_arm' : 'mast_cover'), ...(y ? { y } : {}) } });
write(assets + 'blockstates/' + id('mast') + '.json', { multipart });

const items = {
  antenna: { gui: { rotation: [22, -35, 0], translation: [0, 0, 0], scale: [0.9, 0.9, 0.9] } },
  mast: { gui: { rotation: [25, -35, 0], translation: [0, 0, 0], scale: [0.9, 0.9, 0.9] } },
  cap: { gui: { rotation: [25, -35, 0], translation: [0, 0, 0], scale: [0.9, 0.9, 0.9] } },
};
for (const part of parts) {
  write(assets + 'models/item/' + id(part) + '.json', { parent: model(part), ...(items[part] ? { display: items[part] } : {}) });
  write('data/itemexplorer/loot_tables/blocks/' + id(part) + '.json', {
    type: 'minecraft:block', pools: [{ rolls: 1, entries: [{ type: 'minecraft:item', name: resource(part) }], conditions: [{ condition: 'minecraft:survives_explosion' }] }],
  });
}

// Starter quantities fit one station: 12 casing blanks give five structural blocks
// and four special base blocks, with three blanks left for another station/repairs.
const recipes = {
  casing: { pattern: ['ISI', 'S S', 'ISI'], key: { I: 'minecraft:iron_ingot', S: 'minecraft:smooth_stone' }, count: 6 },
  controller: { pattern: [' G ', 'RCR', ' I '], key: { G: 'minecraft:glass_pane', R: 'minecraft:redstone', C: resource('casing'), I: 'minecraft:iron_ingot' } },
  network_port: { pattern: [' R ', 'ICI', ' R '], key: { R: 'minecraft:redstone', I: 'minecraft:iron_nugget', C: resource('casing') } },
  module: { pattern: [' I ', ' C '], key: { I: 'minecraft:iron_nugget', C: resource('casing') } },
  mast: { pattern: [' I ', ' B ', ' I '], key: { I: 'minecraft:iron_ingot', B: 'minecraft:iron_bars' }, count: 3 },
  antenna: { pattern: ['IGI', ' R ', ' B '], key: { I: 'minecraft:iron_nugget', G: 'minecraft:glass_pane', R: 'minecraft:redstone', B: 'minecraft:iron_bars' }, count: 4 },
  cap: { pattern: [' I ', ' B ', 'NNN'], key: { I: 'minecraft:iron_nugget', B: 'minecraft:iron_bars', N: 'minecraft:iron_ingot' } },
};
for (const [part, recipe] of Object.entries(recipes)) {
  const { pattern, key, count = 1 } = recipe;
  write('data/itemexplorer/recipes/' + id(part) + '.json', {
    type: 'minecraft:crafting_shaped', category: 'redstone', pattern,
    key: Object.fromEntries(Object.entries(key).map(([symbol, item]) => [symbol, { item }])), result: { item: resource(part), count },
  });
  const ingredient = part === 'casing' ? 'minecraft:iron_ingot' : ['controller', 'network_port', 'module'].includes(part) ? resource('casing') : 'minecraft:iron_bars';
  write('data/itemexplorer/advancements/recipes/' + id(part) + '.json', {
    parent: 'minecraft:recipes/root', criteria: {
      has_material: { trigger: 'minecraft:inventory_changed', conditions: { items: [{ items: [ingredient] }] } },
      has_the_recipe: { trigger: 'minecraft:recipe_unlocked', conditions: { recipe: resource(part) } },
    }, requirements: [['has_material', 'has_the_recipe']], rewards: { recipes: [resource(part)] },
  });
}
// Merge the station group into the vanilla mining tag without dropping other entries.
write('data/itemexplorer/tags/blocks/base_station_parts.json', { replace: false, values: parts.map(resource) });
const miningPath = 'data/minecraft/tags/blocks/mineable/pickaxe.json';
const mining = JSON.parse(readFileSync(root + miningPath, 'utf8'));
const group = '#itemexplorer:base_station_parts';
if (!mining.values.includes(group)) mining.values.push(group);
write(miningPath, mining);

console.log('Generated 7 base station blocks/items, 11 block models, 16 textures, recipes, loot and recipe unlocks.');
