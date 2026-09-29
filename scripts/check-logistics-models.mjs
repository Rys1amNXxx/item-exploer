// Geometry regression for the reported depth fighting, including composed blockstate models.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const assets = new URL('../src/main/resources/assets/itemexplorer/', import.meta.url);
const read = path => JSON.parse(readFileSync(new URL(path, assets), 'utf8'));
function elements(id) {
    const model = read('models/' + id.replace('itemexplorer:', '') + '.json');
    return model.elements ?? (model.parent?.startsWith('itemexplorer:') ? elements(model.parent) : []);
}
const planes = { west:[0,0], east:[0,1], down:[1,0], up:[1,1], north:[2,0], south:[2,1] };
function check(name, boxes) {
    const faces = boxes.flatMap((box, index) => Object.keys(box.faces).map(direction => {
        const [axis, end] = planes[direction];
        return { axis, at:(end ? box.to : box.from)[axis], box, label:index + ':' + direction };
    }));
    for (let i = 0; i < faces.length; i++) for (let j = i + 1; j < faces.length; j++) {
        const a = faces[i], b = faces[j];
        if (a.axis !== b.axis || Math.abs(a.at - b.at) > 1 / 32) continue;
        const overlap = [0,1,2].filter(axis => axis !== a.axis).every(axis =>
            Math.min(a.box.to[axis], b.box.to[axis]) - Math.max(a.box.from[axis], b.box.from[axis]) > 1e-6);
        assert(!overlap, `${name}: overlapping near-coplanar faces ${a.label} / ${b.label} (distance ${Math.abs(a.at-b.at)})`);
    }
}
let checked = 0;
for (const tier of ['64k','256k','1m','16m']) { check('disk_' + tier, elements('item/disk_' + tier)); checked++; }
check('interface item', elements('item/logistics_port')); checked++;
const parts = read('blockstates/logistics_port.json').multipart;
for (let status = 0; status < 4; status++) for (const connected of [false,true]) {
    const state = { facing:'north', status:String(status), connected:String(connected) };
    const selected = parts.filter(part => Object.entries(part.when).every(([key,value]) => state[key] === value));
    check(`port ${status} / connected=${connected}`, selected.flatMap(part => elements(part.apply.model))); checked++;
}
console.log(`Checked ${checked} disk / interface variants: no overlapping coplanar or near-coplanar faces.`);
