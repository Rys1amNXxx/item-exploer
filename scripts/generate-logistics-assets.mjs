// Deterministic pixel textures and native Minecraft cuboid models; no image service required.
import { mkdirSync, writeFileSync } from 'node:fs';
import { deflateSync } from 'node:zlib';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../src/main/resources/', import.meta.url));
function json(path, value) { const file = root + path; mkdirSync(file.slice(0, file.lastIndexOf('/')), { recursive: true }); writeFileSync(file, JSON.stringify(value, null, 2) + '\n'); }
function crc32(data) { let c = -1; for (const b of data) { c ^= b; for (let i = 0; i < 8; i++) c = (c >>> 1) ^ ((c & 1) ? 0xedb88320 : 0); } return (c ^ -1) >>> 0; }
function chunk(type, data) { const t = Buffer.from(type); const out = Buffer.alloc(12 + data.length); out.writeUInt32BE(data.length); t.copy(out, 4); data.copy(out, 8); out.writeUInt32BE(crc32(Buffer.concat([t, data])), 8 + data.length); return out; }
function texture(name, size, paint) {
    const pixels = Buffer.alloc(size * size * 4);
    const color = hex => [parseInt(hex.slice(0, 2), 16), parseInt(hex.slice(2, 4), 16), parseInt(hex.slice(4, 6), 16), 255];
    const rect = (x, y, w, h, hex) => { const c = color(hex); for (let j = y; j < y + h; j++) for (let i = x; i < x + w; i++) if (i >= 0 && j >= 0 && i < size && j < size) pixels.set(c, (j * size + i) * 4); };
    paint(rect);
    const rows = Buffer.alloc(size * (size * 4 + 1));
    for (let y = 0; y < size; y++) pixels.copy(rows, y * (size * 4 + 1) + 1, y * size * 4, (y + 1) * size * 4);
    const header = Buffer.alloc(13); header.writeUInt32BE(size); header.writeUInt32BE(size, 4); header[8] = 8; header[9] = 6;
    const png = Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]), chunk('IHDR', header), chunk('IDAT', deflateSync(rows)), chunk('IEND', Buffer.alloc(0))]);
    const path = root + 'assets/itemexplorer/textures/' + name + '.png'; mkdirSync(path.slice(0, path.lastIndexOf('/')), { recursive: true }); writeFileSync(path, png);
}
const glyphs = { '0':['111','101','101','101','111'], '1':['010','110','010','010','111'], '2':['110','001','010','100','111'], '4':['101','101','111','001','001'], '5':['111','100','110','001','110'], '6':['011','100','111','101','111'], K:['101','101','110','101','101'], M:['10001','11011','10101','10001','10001'] };
function text(rect, label, x, y, color) { for (const ch of label) { const rows = glyphs[ch]; rows.forEach((row, j) => [...row].forEach((bit, i) => { if (bit === '1') rect(x + i, y + j, 1, 1, color); })); x += rows[0].length + 1; } }
texture('block/hardware_metal', 16, r => { r(0,0,16,16,'909ba2'); for (let y=0;y<16;y+=4) r(0,y,16,1,'98a3aa'); r(0,0,16,1,'cad2d5'); r(0,0,1,16,'b9c3c9'); r(0,15,16,1,'5c676f'); r(15,0,1,16,'69747b'); });
texture('block/hardware_dark', 16, r => { r(0,0,16,16,'303a42'); r(0,0,16,1,'505d67'); r(0,0,1,16,'48545e'); r(0,15,16,1,'202830'); r(15,0,1,16,'202830'); });
texture('block/hardware_gold', 16, r => { r(0,0,16,16,'b48835'); for(let x=1;x<16;x+=3){r(x,1,1,14,'e6c56d'); r(x+1,1,1,14,'ceb055');} });
const indicators = [['off','4e5c65','71818b'],['online','4a9e57','a7e581'],['active','298fa4','97eeed'],['error','ac4545','ffaaa0']];
function portFace(name, base, light) { texture(name, 32, r => {
    r(0,0,32,32,'839098'); r(1,1,30,30,'c3cbd0'); r(2,2,28,28,'9aa7af'); r(4,4,24,24,'36434c'); r(6,6,20,20,'29343d');
    for(const [x,y] of [[3,3],[26,3],[3,26],[26,26]]){r(x,y,3,3,'526069');r(x,y,2,2,'d5dce0');r(x,y+1,2,1,'84939d');}
    r(9,9,14,14,'101d25'); r(10,10,12,12,'172630');
    r(5,23,7,5,'b9872c');r(5,22,3,2,'e3b855');r(6,24,6,3,'edc66f');r(6,24,5,1,'ffe3a1');
    r(24,24,4,4,'18252d');r(25,25,2,2,base);r(25,25,1,1,light);
}); }
// Status pixels share the actual front surface instead of floating in front of it.
portFace('block/port_face', indicators[0][1], indicators[0][2]);
for(const [state,base,light] of indicators) portFace('block/port_face_' + state, base, light);
texture('block/port_socket',16,r=>{r(0,0,16,16,'85959f');r(1,1,14,14,'c1cbd1');r(2,2,12,12,'1b2a34');r(3,3,10,10,'101c25');r(3,3,10,1,'374e5d');});
const tiers = [['64k','64K','74b85b','b1dc86'],['256k','256K','38aab3','98e1dc'],['1m','1M','976dca','d3b2ee'],['16m','16M','d28b45','f1c485']];
for (const [id,label,base,light] of tiers) texture('item/disk_' + id, 32, r => {
    r(0,0,32,32,'333e48');r(1,1,30,30,'a8b2b9');r(2,2,28,28,'c3ccd1');r(3,3,26,26,'adb9c1');
    for(let y=4;y<29;y+=3) r(4,y,24,1,'b5c0c7');
    // Capacity label, separate tier stripe, serial marks and stamped platter cover.
    r(5,4,22,12,'667780');r(5,4,22,11,'e5e8dd');r(5,4,22,3,base);r(6,4,20,1,light);
    const width = [...label].reduce((sum,ch)=>sum+glyphs[ch][0].length+1,0)-1;
    text(r,label,Math.floor((32-width)/2),8,'293c49');
    for(let x=7;x<25;x+=2) r(x,14,1,(x%3)?1:2,'849192');
    r(9,19,12,8,'7a8b95');r(7,21,16,4,'7a8b95');r(10,18,10,10,'7a8b95');
    r(10,20,10,6,'cad4d8');r(9,22,12,2,'dce2e3');r(13,21,4,4,'697e8c');r(14,22,2,2,'abbec7');
    r(23,19,3,8,'586d7c');r(19,24,6,2,'6e8390');r(22,20,2,2,'e0e5e5');
    for(const [x,y] of [[2,2],[28,2],[2,28],[28,28]]){r(x,y,2,2,'50636e');r(x,y,1,1,'edf1ed');}
    r(8,29,16,3,'283a43');for(let x=9;x<24;x+=3){r(x,30,2,2,'dcba63');r(x,30,1,1,'ffe29a');}
});
const directions = ['north','south','east','west','up','down'];
function cube(from,to,texture,faces=directions,uv=[0,0,16,16],shade=true){return{from,to,shade,faces:Object.fromEntries(faces.map(f=>[f,{texture:'#'+texture,uv}]))};}
const hardware = {metal:'itemexplorer:block/hardware_metal',dark:'itemexplorer:block/hardware_dark',gold:'itemexplorer:block/hardware_gold'};
// One shell, one face per surface: the old side trims duplicated the shell faces.
const diskBody = cube([3,1,6],[13,15,8.5],'metal');
diskBody.faces.north.texture = '#front'; diskBody.faces.south.texture = '#dark';
const diskElements = [diskBody,cube([5,.5,6.5],[11,1,8],'gold',directions.filter(f=>f!=='up'))];
json('assets/itemexplorer/models/item/storage_disk.json',{parent:'minecraft:block/block',textures:{...hardware,particle:'#metal'},elements:diskElements,display:{gui:{rotation:[12,-18,0],translation:[0,0,0],scale:[1,1,1]},ground:{rotation:[0,0,0],translation:[0,3,0],scale:[.45,.45,.45]},fixed:{rotation:[0,180,0],scale:[.9,.9,.9]},thirdperson_righthand:{rotation:[0,0,0],translation:[0,2,0],scale:[.55,.55,.55]},firstperson_righthand:{rotation:[0,-90,20],translation:[1,3,1],scale:[.65,.65,.65]}}});
for(const [id] of tiers) json('assets/itemexplorer/models/item/disk_'+id+'.json',{parent:'itemexplorer:item/storage_disk',textures:{front:'itemexplorer:item/disk_'+id}});
const textures = {...hardware,front:'itemexplorer:block/port_face',socket:'itemexplorer:block/port_socket',particle:'#metal'};
// A 6 x 6 x 1 plate and a 3 x 3 x 1 socket. The face has a real hole under the socket.
// NORTH's U axis runs from maximum X to minimum X; crop every panel from the same UV map.
function panel(x1,y1,x2,y2) { return cube([x1,y1,15],[x2,y2,15],'front',['north'],[(11-x2)*16/6,(11-y2)*16/6,(11-x1)*16/6,(11-y1)*16/6]); }
const socket = cube([6.5,6.5,14],[9.5,9.5,15],'dark',directions.filter(f=>f!=='south'));
socket.faces.north.texture = '#socket';
const plate=[cube([5,5,15],[11,11,16],'metal',directions.filter(f=>f!=='north')),
    panel(5,5,11,6.5),panel(5,9.5,11,11),panel(5,6.5,6.5,9.5),panel(9.5,6.5,11,9.5),socket];
json('assets/itemexplorer/models/block/logistics_port.json',{parent:'minecraft:block/block',textures,elements:plate});
// Only a narrow bridge is needed to meet a device in the adjacent cell. No layered end cap.
json('assets/itemexplorer/models/block/logistics_port_connector.json',{parent:'minecraft:block/block',textures,elements:[cube([7.25,7.25,0],[8.75,8.75,14],'dark',directions.filter(f=>f!=='south'))]});
for(const [state] of indicators) json('assets/itemexplorer/models/block/logistics_port_'+state+'.json',{parent:'itemexplorer:block/logistics_port',textures:{front:'itemexplorer:block/port_face_'+state}});
const rotations={north:{},east:{y:90},south:{y:180},west:{y:270},up:{x:270},down:{x:90}};
const multipart=[];
for(const [facing,rotation] of Object.entries(rotations)){
    multipart.push({when:{facing,connected:'true'},apply:{model:'itemexplorer:block/logistics_port_connector',...rotation}});
    for(const [status,state] of ['off','online','active','error'].entries()) multipart.push({when:{facing,status:String(status)},apply:{model:'itemexplorer:block/logistics_port_'+state,...rotation}});
}
json('assets/itemexplorer/blockstates/logistics_port.json',{multipart});
json('assets/itemexplorer/models/item/logistics_port.json',{parent:'itemexplorer:block/logistics_port',display:{gui:{rotation:[15,-25,0],translation:[0,0,-5],scale:[1.15,1.15,1.15]},ground:{translation:[0,3,-3],scale:[.5,.5,.5]},fixed:{rotation:[0,180,0],translation:[0,0,6],scale:[1,1,1]},firstperson_righthand:{rotation:[0,-90,20],translation:[0,2,0],scale:[.7,.7,.7]}}});
json('data/itemexplorer/recipes/logistics_port.json',{type:'minecraft:crafting_shaped',category:'redstone',pattern:[' I ','RHC',' I '],key:{I:{item:'minecraft:iron_ingot'},R:{item:'minecraft:redstone'},H:{item:'minecraft:hopper'},C:{item:'minecraft:comparator'}},result:{item:'itemexplorer:logistics_port'}});
json('data/itemexplorer/loot_tables/blocks/logistics_port.json',{type:'minecraft:block',pools:[{rolls:1,entries:[{type:'minecraft:item',name:'itemexplorer:logistics_port'}],conditions:[{condition:'minecraft:survives_explosion'}]}]});
json('data/itemexplorer/advancements/recipes/logistics_port.json',{parent:'minecraft:recipes/root',criteria:{has_hopper:{trigger:'minecraft:inventory_changed',conditions:{items:[{items:['minecraft:hopper']}]}},has_the_recipe:{trigger:'minecraft:recipe_unlocked',conditions:{recipe:'itemexplorer:logistics_port'}}},requirements:[['has_hopper','has_the_recipe']],rewards:{recipes:['itemexplorer:logistics_port']}});
console.log('Generated four disk textures, shared disk model, six-way interface models and resources.');
