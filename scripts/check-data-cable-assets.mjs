import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../src/main/resources/', import.meta.url));
const read = p => JSON.parse(readFileSync(root + p, 'utf8'));
const prefix = 'assets/itemexplorer/';
const dirs = ['down','up','north','south','west','east'];
const multipart = read(prefix + 'blockstates/data_cable.json').multipart;
assert.equal(multipart.length, 12);
const model = id => read(prefix + 'models/' + id.replace('itemexplorer:', '') + '.json');
for (let mask = 0; mask < 64; mask++) {
  const state = Object.fromEntries(dirs.map((d,i) => [d, String(Boolean(mask & (1<<i)))]));
  const selected = multipart.filter(part => Object.entries(part.when).every(([k,v]) => state[k] === v));
  assert.equal(selected.length, 6);
  for (const part of selected) {
    const m = model(part.apply.model);
    for (const element of m.elements) {
      for (let i = 0; i < 3; i++) assert(element.from[i] >= 0 && element.to[i] <= 16 && element.from[i] < element.to[i]);
      for (const face of Object.values(element.faces)) {
        const texture = m.textures[face.texture.slice(1)];
        assert(existsSync(root + prefix + 'textures/' + texture.replace('itemexplorer:', '') + '.png'));
      }
    }
  }
  // Exactly one outer cap per direction when disconnected, otherwise a hollow-ended arm.
  for (const [i,d] of dirs.entries()) {
    const part = selected.find(p => d in p.when), m = model(part.apply.model);
    assert.equal(Object.keys(m.elements[0].faces).length, mask & (1<<i) ? 5 : 1);
  }
}
const item = read(prefix+'models/item/data_cable.json');
assert.equal(item.elements.length, 1);
assert.equal(read('data/itemexplorer/recipes/data_cable.json').result.count, 8);
assert.equal(read('data/itemexplorer/loot_tables/blocks/data_cable.json').pools[0].entries[0].name, 'itemexplorer:data_cable');
assert.equal(read('data/itemexplorer/advancements/recipes/data_cable.json').rewards.recipes[0], 'itemexplorer:data_cable');
for (const lang of ['zh_cn','en_us']) {
  const text = read(prefix+`lang/${lang}.json`);
  for (const status of ['checking','disconnected','no_station','incomplete','connected','conflict','unloaded','too_large','no_terminal'])
    assert(text['gui.itemexplorer.cable_'+status]);
}
console.log('Verified all 64 cable connection models, texture references, recipe/drop/unlock and bilingual statuses.');
