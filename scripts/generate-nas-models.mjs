// Rebuild the NAS's code-native voxel assets: node scripts/generate-nas-models.mjs
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
const output = fileURLToPath(new URL('../src/main/resources/assets/itemexplorer/models/block/', import.meta.url));
const textures = {
  particle: 'minecraft:block/gray_concrete', case: 'minecraft:block/gray_concrete',
  trim: 'minecraft:block/light_gray_concrete', recess: 'minecraft:block/black_concrete',
  metal: 'minecraft:block/iron_block', white: 'minecraft:block/white_concrete'
};
const directions = ['north', 'south', 'east', 'west', 'up', 'down'];
// Keep the housing, unlit lens and lit overlay on separate planes to avoid z-fighting.
// All three surfaces stay within the block, with exact binary-fraction spacing.
const topHousingY = 15.9375, topLensY = 15.96875, topLightY = 16;
function cube(from, to, texture, options = {}) {
  const faces = Object.fromEntries((options.faces ?? directions).map(face => [face, {
    texture: '#' + texture, uv: options.uv ?? [2, 2, 14, 14],
    ...(options.tint ? { tintindex: 0 } : {})
  }]));
  return { from, to, ...(options.tint ? { shade: false } : {}), faces };
}
function save(name, elements, extra = {}) {
  writeFileSync(output + name + '.json', JSON.stringify({ parent: 'minecraft:block/block', textures, elements, ...extra }, null, 2) + '\n');
}
const shell = [
  cube([.125,.125,2.75], [15.875,15.875,15.875], 'case'),
  cube([0,0,0], [1.25,16,2.75], 'trim'), cube([14.75,0,0], [16,16,2.75], 'trim'),
  cube([1.25,14.5,0], [14.75,16,2.75], 'trim'), cube([1.25,0,0], [14.75,1.5,2.75], 'case'),
  cube([1.25,1.5,2.25], [14.75,14.5,2.75], 'recess'),
  cube([12.75,1.5,.75], [14.75,14.5,2.75], 'case'),
  // Front rails and small bottom feet keep the silhouette square, with an inset face.
  cube([.25,.25,.01], [1,.75,.25], 'recess'), cube([15,.25,.01], [15.75,.75,.25], 'recess'),
  cube([1.5,15,.01], [4.75,15.5,.25], 'case'),
  // A top status strip remains visible when a terminal obscures the front.
  cube([1.5,15.875,2.75], [14.5,topHousingY,6], 'recess'),
];
for (let i = 0; i < 4; i++) {
  const y = 11.5 - 3 * i;
  shell.push(cube([1.25,y-.5,.75], [12.75,y,2.75], 'case'));
  shell.push(cube([2,y,1.25], [12.25,y+.25,2.25], 'metal'));
  shell.push(cube([13.25,y+.75,.70], [14.25,y+1.75,.75], 'recess'));
  shell.push(cube([2.75+3*i,topHousingY,3.5], [4.25+3*i,topLensY,5], 'case', {faces:['up']}));
}
// Slotted vents on both sides; geometry is within the block's bounds.
for (let i = 0; i < 6; i++) {
  const y = 4 + 1.25 * i;
  shell.push(cube([.1,y,6], [.125,y+.5,13], 'recess', {faces:['west']}));
  shell.push(cube([15.875,y,6], [15.9,y+.5,13], 'recess', {faces:['east']}));
}
// Rear service panel and recessed connector; no external connection behaviour is added.
shell.push(cube([3,3,15.875], [13,13,15.9], 'recess', {faces:['south']}));
shell.push(cube([4,4,15.9], [12,12,15.925], 'case', {faces:['south']}));
shell.push(cube([5.5,5,15.925], [10.5,6.5,15.95], 'recess', {faces:['south']}));
save('nas', shell);
save('nas_tray', [
  cube([1.75,11.65,.7], [12.5,13.95,2.1], 'metal'),
  cube([2.25,12,.65], [12,13.6,.7], 'trim'),
  cube([3,12.15,.55], [9.25,13.35,.65], 'recess'),
  cube([3,12.1,.2], [3.75,13.4,.6], 'case'), cube([8.5,12.1,.2], [9.25,13.4,.6], 'case'),
  cube([3,12.8,.15], [9.25,13.4,.6], 'case'),
  cube([10,12.15,.6], [11.75,13.45,.65], 'recess'),
]);
save('nas_badge', [cube([10.25,12.4,.58], [11.5,13.2,.6], 'white', {faces:['north'],tint:true})]);
save('nas_front_led', [cube([13.4,12.4,.68], [14.1,13.1,.70], 'white', {faces:['north'],tint:true})], {ambientocclusion:false});
save('nas_top_led', [cube([3,topLensY,3.75], [4,topLightY,4.75], 'white', {faces:['up'],tint:true})], {ambientocclusion:false});
console.log('Generated NAS shell, tray, capacity badge and front/top indicators.');
