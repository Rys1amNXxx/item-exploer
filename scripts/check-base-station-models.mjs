// Resource integrity and geometry regression checks for the assembled base station.
import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { inflateSync } from 'node:zlib';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../src/main/resources/', import.meta.url));
const assets = root + 'assets/itemexplorer/';
const parts = ['casing', 'controller', 'network_port', 'module', 'mast', 'antenna', 'cap'];
const facings = ['north', 'east', 'south', 'west'];
const read = path => JSON.parse(readFileSync(path, 'utf8'));
const modelPath = id => assets + 'models/' + id.replace('itemexplorer:', '') + '.json';
function model(id, visited = []) {
  assert(!visited.includes(id), `Cyclic model parent: ${id}`);
  const own = read(modelPath(id));
  const parent = own.parent?.startsWith('itemexplorer:') ? model(own.parent, [...visited, id]) : {};
  return { ...parent, ...own, textures: { ...parent.textures, ...own.textures }, elements: own.elements ?? parent.elements ?? [] };
}
function resolveTexture(ref, textures, visited = []) {
  assert(!visited.includes(ref), `Cyclic texture alias: ${ref}`);
  assert(ref, 'Missing texture');
  return ref.startsWith('#') ? resolveTexture(textures[ref.slice(1)], textures, [...visited, ref]) : ref;
}
const planes = { west: [0, 0], east: [0, 1], down: [1, 0], up: [1, 1], north: [2, 0], south: [2, 1] };
const normal = { north: [0, 0, -1], east: [1, 0, 0], south: [0, 0, 1], west: [-1, 0, 0], up: [0, 1, 0], down: [0, -1, 0] };
function rotatePoint(point, angle) {
  let [x, y, z] = point;
  for (let turn = 0; turn < angle / 90; turn++) [x, z] = [16 - z, x];
  return [x, y, z];
}
function rotateBox(box, angle) {
  const points = [rotatePoint(box.from, angle), rotatePoint(box.to, angle)];
  const faces = {};
  for (const [direction, face] of Object.entries(box.faces)) {
    const index = facings.indexOf(direction);
    faces[index < 0 ? direction : facings[(index + angle / 90) % 4]] = face;
  }
  return { from: [0, 1, 2].map(axis => Math.min(...points.map(p => p[axis]))), to: [0, 1, 2].map(axis => Math.max(...points.map(p => p[axis]))), faces };
}
function geometry(label, boxes) {
  const faces = boxes.flatMap((box, index) => Object.keys(box.faces).map(direction => {
    const [axis, end] = planes[direction];
    return { axis, at: (end ? box.to : box.from)[axis], box, label: index + ':' + direction };
  }));
  for (let i = 0; i < faces.length; i++) for (let j = i + 1; j < faces.length; j++) {
    const a = faces[i], b = faces[j];
    if (a.axis !== b.axis || Math.abs(a.at - b.at) > 1 / 32) continue;
    const overlap = [0, 1, 2].filter(axis => axis !== a.axis).every(axis => Math.min(a.box.to[axis], b.box.to[axis]) - Math.max(a.box.from[axis], b.box.from[axis]) > 1e-6);
    assert(!overlap, `${label}: overlapping near-coplanar faces ${a.label} / ${b.label}`);
  }
  for (let i = 0; i < boxes.length; i++) for (let j = i + 1; j < boxes.length; j++) {
    const overlap = [0, 1, 2].every(axis => Math.min(boxes[i].to[axis], boxes[j].to[axis]) - Math.max(boxes[i].from[axis], boxes[j].from[axis]) > 1e-6);
    assert(!overlap, `${label}: overlapping solid cuboids ${i} / ${j}`);
  }
}
function selected(part, state) {
  const blockstate = read(assets + 'blockstates/base_station_' + part + '.json');
  if (blockstate.multipart) return blockstate.multipart.filter(entry => !entry.when || Object.entries(entry.when).every(([key, value]) => state[key] === value)).map(entry => entry.apply);
  const matches = Object.entries(blockstate.variants).filter(([key]) => key.split(',').every(pair => { const [name, value] = pair.split('='); return state[name] === value; }));
  assert.equal(matches.length, 1, `Incomplete/ambiguous blockstate ${part}: ${JSON.stringify(state)}`);
  return matches.map(([, value]) => value);
}
function boxesFor(part, state) { return selected(part, state).flatMap(apply => model(apply.model).elements.map(box => rotateBox(box, apply.y ?? 0))); }

let models = 0;
for (const directory of ['block', 'item']) for (const name of readdirSync(assets + 'models/' + directory).filter(name => name.startsWith('base_station_'))) {
  const value = model('itemexplorer:' + directory + '/' + name.slice(0, -5));
  for (const ref of Object.values(value.textures)) {
    const resolved = resolveTexture(ref, value.textures);
    assert(resolved.startsWith('itemexplorer:block/base_station/'), `Unexpected texture dependency ${name}: ${resolved}`);
    assert(existsSync(assets + 'textures/' + resolved.replace('itemexplorer:', '') + '.png'), `${name}: missing texture ${resolved}`);
  }
  for (const box of value.elements) {
    assert(box.from.length === 3 && box.to.length === 3);
    for (let axis = 0; axis < 3; axis++) assert(box.from[axis] >= 0 && box.to[axis] <= 16 && box.from[axis] <= box.to[axis], `${name}: geometry outside its block`);
    for (const [direction, face] of Object.entries(box.faces)) {
      assert(planes[direction], `${name}: bad face direction`);
      assert(face.uv.length === 4 && face.uv.every(value => value >= 0 && value <= 16), `${name}: UV outside texture`);
      resolveTexture(face.texture, value.textures);
      if (face.cullface) {
        const [axis, end] = planes[face.cullface];
        assert.equal((end ? box.to : box.from)[axis], end ? 16 : 0, `${name}: cullface inside the block`);
      }
    }
  }
  geometry(name, value.elements); models++;
}

let variants = 0;
for (const part of parts) {
  const states = part === 'mast' ? Array.from({ length: 16 }, (_, mask) => Object.fromEntries(facings.map((face, bit) => [face, String(Boolean(mask & 1 << bit))]))) : facings.flatMap(facing => part === 'controller' ? [{ facing, formed: 'false' }, { facing, formed: 'true' }] : [{ facing }]);
  for (const state of states) {
    const applications = selected(part, state);
    if (part === 'mast') assert.equal(applications.length, 5, 'Mast needs its core and one arm/cover for each direction');
    geometry(part + JSON.stringify(state), boxesFor(part, state)); variants++;
  }
  const recipe = read(root + 'data/itemexplorer/recipes/base_station_' + part + '.json');
  assert.equal(recipe.result.item, 'itemexplorer:base_station_' + part);
  assert(recipe.pattern.length > 0 && recipe.pattern.length <= 3 && recipe.pattern.every(row => row.length === recipe.pattern[0].length && row.length <= 3));
  for (const symbol of recipe.pattern.join('')) if (symbol !== ' ') assert(recipe.key[symbol], `Recipe has unknown symbol: ${part}`);
  for (const key of Object.keys(recipe.key)) assert(recipe.pattern.join('').includes(key), `Recipe has unused ingredient: ${part}`);
  const loot = read(root + 'data/itemexplorer/loot_tables/blocks/base_station_' + part + '.json');
  assert.equal(loot.pools[0].entries[0].name, recipe.result.item);
  const unlock = read(root + 'data/itemexplorer/advancements/recipes/base_station_' + part + '.json');
  assert(unlock.rewards.recipes.includes(recipe.result.item));
}

// Verify the actual model extents meet across every 16-unit cell boundary.
for (const facing of facings) {
  const allConnected = Object.fromEntries(facings.map(face => [face, 'true']));
  const column = boxesFor('mast', allConnected);
  const antenna = boxesFor('antenna', { facing }).map(box => ({ ...box, from: box.from.map((value, axis) => value + normal[facing][axis] * 16), to: box.to.map((value, axis) => value + normal[facing][axis] * 16) }));
  const [axis, end] = planes[facing], boundary = end ? 16 : 0;
  const center = column.find(box => box.from[1] === 7 && box.to[1] === 9 && (end ? box.to : box.from)[axis] === boundary);
  const opposite = antenna.find(box => box.from[1] === 7 && box.to[1] === 9 && (end ? box.from : box.to)[axis] === boundary);
  assert(center && opposite, `${facing}: missing connected arm at cell boundary`);
  for (const cross of [0, 1, 2].filter(value => value !== axis)) assert.deepEqual([center.from[cross], center.to[cross]], [opposite.from[cross], opposite.to[cross]], `${facing}: antenna arm does not align`);
}

let textures = 0;
for (const name of readdirSync(assets + 'textures/block/base_station/').filter(name => name.endsWith('.png'))) {
  const bytes = readFileSync(assets + 'textures/block/base_station/' + name);
  assert.equal(bytes.readUInt32BE(16), 32, `${name}: wrong width`); assert.equal(bytes.readUInt32BE(20), 32, `${name}: wrong height`);
  assert.equal(bytes[24], 8); assert.equal(bytes[25], 6);
  const data = [];
  for (let offset = 8; offset < bytes.length;) {
    const length = bytes.readUInt32BE(offset), type = bytes.toString('ascii', offset + 4, offset + 8);
    if (type === 'IDAT') data.push(bytes.subarray(offset + 8, offset + 8 + length));
    offset += length + 12;
  }
  const rows = inflateSync(Buffer.concat(data));
  for (let y = 0; y < 32; y++) {
    assert.equal(rows[y * 129], 0, 'Verifier expects deterministic unfiltered PNG rows');
    for (let x = 0; x < 32; x++) assert.equal(rows[y * 129 + x * 4 + 4], 255, `${name}: transparent pixel`);
  }
  textures++;
}
const mining = read(root + 'data/minecraft/tags/blocks/mineable/pickaxe.json');
assert(mining.values.includes('#itemexplorer:base_station_parts'));
const miningParts = read(root + 'data/itemexplorer/tags/blocks/base_station_parts.json');
for (const part of parts) assert(miningParts.values.includes('itemexplorer:base_station_' + part));
console.log(`Verified ${models} models, ${variants} complete blockstates, ${textures} opaque textures, 7 recipes/loot/unlocks, and 4 continuous antenna connections; no overlapping coplanar faces or solid cuboids.`);
