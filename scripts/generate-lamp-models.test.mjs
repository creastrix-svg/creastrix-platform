import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { generateLampGlb, lampVariants, panelMask, panelGeometry } from './generate-lamp-models.mjs';
import { readGlb, accessorValues } from './generate-personalization-models.mjs';

const outputs = new Map();
for (const pattern of lampVariants) {
  test(`${pattern}: prepared material is connected, holes remain open and the extruded panel is watertight`, () => {
    const { mask, columns, rows } = panelMask(pattern), seen = new Set();
    const start = mask.indexOf(1), queue = [start]; seen.add(start);
    for (let i = 0; i < queue.length; i++) {
      const current = queue[i], x = current % columns, y = Math.floor(current / columns);
      for (const [a,b] of [[x-1,y],[x+1,y],[x,y-1],[x,y+1]]) {
        const next = b * columns + a;
        if (a >= 0 && a < columns && b >= 0 && b < rows && mask[next] && !seen.has(next)) { seen.add(next); queue.push(next); }
      }
    }
    assert.equal(seen.size, mask.reduce((sum,value) => sum+value,0));
    assert.ok(mask.filter(value => !value).length > 20_000);
    const { geometry, holes } = panelGeometry(pattern);
    assert.ok(holes >= 4); assert.ok(geometry.attributes.position.count > 1000);
    const vertices = geometry.attributes.position.array, normals = geometry.attributes.normal.array, edges = new Map();
    const vertexKey = index => Array.from(vertices.slice(index*3,index*3+3)).map(n => n.toFixed(7)).join(',');
    for (let i = 0; i < vertices.length; i++) assert.ok(Number.isFinite(vertices[i]));
    for (let i = 0; i < normals.length; i += 3) assert.ok(Math.abs(Math.hypot(...normals.slice(i,i+3))-1) < 1e-5);
    for (let i = 0; i < vertices.length/3; i += 3) for (const [a,b] of [[i,i+1],[i+1,i+2],[i+2,i]]) {
      const key = [vertexKey(a),vertexKey(b)].sort().join('|'); edges.set(key,(edges.get(key)??0)+1);
    }
    assert.ok([...edges.values()].every(count => count === 2), 'Every welded edge belongs to exactly two triangles.');
    geometry.computeBoundingBox();
    assert.ok(Math.abs(geometry.boundingBox.min.z-.049)<1e-7); assert.ok(Math.abs(geometry.boundingBox.max.z-.052)<1e-7);
    geometry.dispose();
  });
  test(`${pattern}: deterministic checked-in self-contained GLB has actual front, fixed sides, base and light`, () => {
    const bytes = generateLampGlb(pattern), stored = readFileSync(new URL(`../frontend/public/product/studio-lamp-${pattern}.glb`,import.meta.url));
    assert.deepEqual(bytes,stored); assert.ok(bytes.length < 3_000_000);
    const model = readGlb(bytes); outputs.set(pattern,model);
    assert.equal(model.json.asset.extras.manufacturingValidated,false);
    assert.equal(model.json.asset.extras.bridges,'fixed');
    assert.equal(model.json.asset.extras.panelSamplingMillimetres,.25);
    for (const image of model.json.images) { assert.equal(image.mimeType,'image/png'); assert.equal(image.uri,undefined); assert.ok(image.bufferView>=0); }
    assert.equal(model.json.buffers[0].uri,undefined);
    for (const name of ['Front cut panel','Fixed back panel','Fixed right panel','Fixed left panel','Solid birch base','Warm frosted inner light','Base engraving face']) assert.ok(model.json.nodes.some(node => node.name===name));
    const base = model.json.meshes.find(mesh => mesh.name==='Base engraving face');
    assert.equal(base.primitives.length,1); assert.equal(base.primitives[0].material,3);
    const normal = accessorValues(model,base.primitives[0].attributes.NORMAL);
    assert.ok(normal.every(value => value[2]===1), 'Only the outward front face can receive engraving.');
    const prepared = new Set(['Studio lamp front panel','Studio lamp front cut edge','Studio lamp base engraving']);
    const front = model.json.meshes.find(mesh => mesh.name==='Front cut panel');
    assert.deepEqual(new Set(front.primitives.map(primitive => model.json.materials[primitive.material].name)),new Set(['Studio lamp front panel','Studio lamp front cut edge']));
    for (const mesh of model.json.meshes) {
      if (mesh===front || mesh===base) continue;
      for (const primitive of mesh.primitives) assert.ok(!prepared.has(model.json.materials[primitive.material].name), `${mesh.name} must not select or highlight a prepared zone.`);
    }
    const oldFace = model.json.materials[0], newFace = model.json.materials[5];
    assert.deepEqual(oldFace.pbrMetallicRoughness,newFace.pbrMetallicRoughness,'Splitting material identity preserves wood appearance.');
    for (const accessor of model.json.accessors) {
      assert.ok(accessor.count>0);
      if (accessor.min) assert.ok([...accessor.min,...accessor.max].every(Number.isFinite));
    }
  });
}
test('choosing a front pattern never changes fixed side/back geometry', () => {
  const models = [...outputs.values()]; assert.equal(models.length,3);
  for (const name of ['Fixed back panel','Fixed right panel','Fixed left panel','Solid birch base']) {
    const values = models.map(model => model.json.meshes.find(mesh => mesh.name===name).primitives.map(primitive => accessorValues(model,primitive.attributes.POSITION)));
    assert.deepEqual(values[0],values[1]); assert.deepEqual(values[0],values[2]);
  }
});
