// Native pixel/model resources for the shared data cable. No inventory or station-only visuals.
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateSync } from 'node:zlib';
const root = fileURLToPath(new URL('../src/main/resources/', import.meta.url));
const assets = 'assets/itemexplorer/';
function write(path, value) {
  mkdirSync(dirname(root + path), { recursive: true });
  writeFileSync(root + path, Buffer.isBuffer(value) ? value : JSON.stringify(value, null, 2) + '\n');
}
function crc32(bytes) {
  let c = -1;
  for (const b of bytes) { c ^= b; for (let i = 0; i < 8; i++) c = (c >>> 1) ^ ((c & 1) ? 0xedb88320 : 0); }
  return (c ^ -1) >>> 0;
}
function chunk(type, data) {
  const name = Buffer.from(type), out = Buffer.alloc(data.length + 12);
  out.writeUInt32BE(data.length); name.copy(out, 4); data.copy(out, 8);
  out.writeUInt32BE(crc32(Buffer.concat([name, data])), data.length + 8); return out;
}
function png(paint) {
  const rows = Buffer.alloc(16 * 65);
  for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
    const rgb = paint(x, y).match(/../g).map(s => parseInt(s, 16));
    rows.set([...rgb, 255], y * 65 + 1 + x * 4);
  }
  const header = Buffer.alloc(13); header.writeUInt32BE(16); header.writeUInt32BE(16, 4); header[8] = 8; header[9] = 6;
  return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]), chunk('IHDR', header), chunk('IDAT', deflateSync(rows)), chunk('IEND', Buffer.alloc(0))]);
}
write(assets + 'textures/block/data_cable.png', png((x, y) => y % 8 === 0 ? '7e9ea1' : x % 2 ? '316c76' : '25424c'));
write(assets + 'textures/block/terminal_data_socket.png', png((x,y) => {
  if (x >= 5 && x <= 10 && y >= 5 && y <= 10) {
    if (x === 5 || x === 10 || y === 5 || y === 10) return '6a9ba4';
    return x === 7 || x === 8 ? '1e353f' : 'b8934c';
  }
  return ['3c3c40','404044','46464a'][(x * 13 + y * 7) % 3];
}));
const bounds = {
  down: [[7,0,7],[9,7,9]], up: [[7,9,7],[9,16,9]], north: [[7,7,0],[9,9,7]],
  south: [[7,7,9],[9,9,16]], west: [[0,7,7],[7,9,9]], east: [[9,7,7],[16,9,9]]
};
const opposite = {down:'up',up:'down',north:'south',south:'north',west:'east',east:'west'};
const dirs = Object.keys(bounds);
const textures = {wire:'itemexplorer:block/data_cable',particle:'itemexplorer:block/data_cable'};
const multipart = [];
for (const dir of dirs) {
  const [from,to] = bounds[dir];
  const faces = Object.fromEntries(dirs.filter(d => d !== opposite[dir]).map(d => [d, {texture:'#wire', ...(d === dir ? {cullface:dir} : {})}]));
  write(assets + `models/block/data_cable_${dir}.json`, {parent:'minecraft:block/block',textures,elements:[{from,to,faces}]});
  write(assets + `models/block/data_cable_cap_${dir}.json`, {parent:'minecraft:block/block',textures,elements:[{from:[7,7,7],to:[9,9,9],faces:{[dir]:{texture:'#wire'}}}]});
  multipart.push({when:{[dir]:'true'},apply:{model:`itemexplorer:block/data_cable_${dir}`}},
    {when:{[dir]:'false'},apply:{model:`itemexplorer:block/data_cable_cap_${dir}`}});
}
write(assets + 'blockstates/data_cable.json', {multipart});
write(assets + 'models/item/data_cable.json', {parent:'minecraft:block/block',textures,
  elements:[{from:[7,0,7],to:[9,16,9],faces:Object.fromEntries(dirs.map(d=>[d,{texture:'#wire'}]))}],
  display:{gui:{rotation:[25,35,15],translation:[0,0,0],scale:[1.4,0.8,1.4]},fixed:{rotation:[0,0,0],translation:[0,0,0],scale:[1,1,1]}}});
write('data/itemexplorer/recipes/data_cable.json', {type:'minecraft:crafting_shaped',category:'redstone',pattern:['III','RRR','III'],
  key:{I:{item:'minecraft:iron_nugget'},R:{item:'minecraft:redstone'}},result:{item:'itemexplorer:data_cable',count:8}});
write('data/itemexplorer/loot_tables/blocks/data_cable.json', {type:'minecraft:block',pools:[{rolls:1,entries:[{type:'minecraft:item',name:'itemexplorer:data_cable'}],conditions:[{condition:'minecraft:survives_explosion'}]}]});
write('data/itemexplorer/advancements/recipes/data_cable.json', {parent:'minecraft:recipes/root',criteria:{
  has_material:{trigger:'minecraft:inventory_changed',conditions:{items:[{items:['minecraft:redstone']}]}},
  has_the_recipe:{trigger:'minecraft:recipe_unlocked',conditions:{recipe:'itemexplorer:data_cable'}}},
  requirements:[['has_material','has_the_recipe']],rewards:{recipes:['itemexplorer:data_cable']}});
const languages = {
  zh_cn: {
    'block.itemexplorer.data_cable':'数据导线',
    checking:'有线接入：正在检查…', disconnected:'有线接入：未接线', no_station:'有线接入：未找到基站',
    incomplete:'有线接入：基站结构不完整', connected:'有线接入：已连接', conflict:'有线接入：多个基站接口冲突',
    unloaded:'有线接入：线路或基站区域未加载', too_large:'有线接入：超过 256 段导线上限', no_terminal:'有线接入：未连接终端',
    counts:'已检测导线：%s　终端：%s', station:'基站控制器：%s，%s，%s',
    terminal_hint:'终端与 NAS 六面均可接线；基站接背面接口。', local_only:'NAS 可直接通过导线访问；尚不能站间无线收发。',
    'gui.itemexplorer.nas_disk_label':'N%s·%s %s',
    'gui.itemexplorer.nas_disk_source':'NAS：%s，%s，%s · 盘位 %s',
    'message.itemexplorer.disk_offline':'当前硬盘已离线。请检查接线与盘位，或选择其他盘。',
    'gui.itemexplorer.local_nas_count':'可用连接：%s 台 NAS（其中有线 %s 台）',
    'gui.itemexplorer.local_wire_checking':'本地数据线：正在检查…',
    'gui.itemexplorer.local_wire_connected':'本地数据线：已连接',
    'gui.itemexplorer.local_wire_disconnected':'本地数据线：未接线',
    'gui.itemexplorer.local_wire_unloaded':'本地数据线：区域未加载，暂停访问有线 NAS',
    'gui.itemexplorer.local_wire_too_large':'本地数据线：超过 256 段，暂停访问有线 NAS'
  },
  en_us: {
    'block.itemexplorer.data_cable':'Data Cable',
    checking:'Wired: checking...', disconnected:'Wired: no cable', no_station:'Wired: no station found',
    incomplete:'Wired: incomplete station', connected:'Wired: connected', conflict:'Wired: multiple station ports',
    unloaded:'Wired: cable or station area unloaded', too_large:'Wired: exceeds 256 cables', no_terminal:'Wired: no terminal',
    counts:'Detected cables: %s   Terminals: %s', station:'Station controller: %s, %s, %s',
    terminal_hint:'Wire any terminal/NAS face; use the station rear port.', local_only:'NAS drives work over local cables; wireless transfers are not available yet.',
    'gui.itemexplorer.nas_disk_label':'N%s·%s %s',
    'gui.itemexplorer.nas_disk_source':'NAS: %s, %s, %s · Bay %s',
    'message.itemexplorer.disk_offline':'Disk offline. Check the cables and drive bay, or select another disk.',
    'gui.itemexplorer.local_nas_count':'Connected NAS: %s (wired: %s)',
    'gui.itemexplorer.local_wire_checking':'Local cable: checking...',
    'gui.itemexplorer.local_wire_connected':'Local cable: connected',
    'gui.itemexplorer.local_wire_disconnected':'Local cable: no cable',
    'gui.itemexplorer.local_wire_unloaded':'Local cable: area unloaded; wired NAS access paused',
    'gui.itemexplorer.local_wire_too_large':'Local cable: exceeds 256 cables; wired NAS access paused'
  }
};
for (const [lang, messages] of Object.entries(languages)) {
  const path = assets + `lang/${lang}.json`, data = JSON.parse(readFileSync(root + path, 'utf8'));
  for (const [key, value] of Object.entries(messages)) data[key.includes('.') ? key : 'gui.itemexplorer.cable_' + key] = value;
  for (const status of ['checking','disconnected','no_station','incomplete','connected','conflict','unloaded','too_large','no_terminal']) {
    const key = 'gui.itemexplorer.cable_' + status;
    data[key] = data[key].replace('有线接入：','基站接入：').replace('Wired:', 'Station:');
  }
  data['gui.itemexplorer.station_ready_hint'] = lang === 'zh_cn'
    ? '绿色指示灯表示结构完整。数据导线连接背面接口与终端任意面；当前仅提供本地有线接入，尚不能远程收发。'
    : 'The green light indicates a complete structure. Wire the rear port to any terminal face. Local wired access only; remote transfers are not available yet.';
  write(path, data);
}
console.log('Generated shared data cable models, textures, recipe, loot, unlock and bilingual connection messages.');
