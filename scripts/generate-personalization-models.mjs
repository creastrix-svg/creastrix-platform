import assert from 'node:assert/strict';
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import { generateLoopGlb, loopHeightMeters, packGlb } from './loop-model.mjs';

export const variants = ['cobalt', 'coral', 'chalk', 'cobalt-chalk'];
export const exteriorNames = ['Studio exterior lower', 'Studio exterior upper'];
const productDirectory = new URL('../frontend/public/product/', import.meta.url);

export function readGlb(bytes) {
  assert.equal(bytes.readUInt32LE(0), 0x46546c67);
  assert.equal(bytes.readUInt32LE(4), 2);
  assert.equal(bytes.readUInt32LE(8), bytes.length);
  assert.equal(bytes.readUInt32LE(16), 0x4e4f534a);
  const jsonLength = bytes.readUInt32LE(12);
  assert.equal(bytes.readUInt32LE(24 + jsonLength), 0x004e4942);
  return { json: JSON.parse(bytes.subarray(20, 20 + jsonLength).toString()), binary: bytes.subarray(28 + jsonLength) };
}

export function accessorValues(model, index) {
  const accessor = model.json.accessors[index], view = model.json.bufferViews[accessor.bufferView];
  assert.equal(accessor.sparse, undefined);
  assert.equal(view.byteStride, undefined);
  const width = { SCALAR: 1, VEC2: 2, VEC3: 3 }[accessor.type];
  assert.ok(width);
  assert.ok([5123, 5126].includes(accessor.componentType));
  const bytes = accessor.componentType === 5126 ? 4 : 2;
  const offset = (view.byteOffset ?? 0) + (accessor.byteOffset ?? 0);
  return Array.from({ length: accessor.count }, (_, item) => Array.from({ length: width }, (_, channel) => {
    const at = offset + (item * width + channel) * bytes;
    return bytes === 4 ? model.binary.readFloatLE(at) : model.binary.readUInt16LE(at);
  }));
}

// This only partitions the existing triangles and adds editor UVs/materials. No
// vertex, normal, triangle winding, dimensions, or original asset is rewritten.
// These UVs are a visual prototype, not a manufacturing or print-area contract.
export function deriveEditorGlb(input) {
  const model = readGlb(input), json = structuredClone(model.json);
  assert.equal(json.meshes.length, 1);
  assert.equal(json.meshes[0].primitives.length, 2);
  assert.equal(json.materials.length, 2);
  assert.deepEqual(json.nodes, [{ name: 'Loop open rounded pot', mesh: 0 }]);
  const positions = accessorValues(model, 0), normals = accessorValues(model, 1), oldUv = accessorValues(model, 2);
  assert.ok(positions.length > 0 && positions.length <= 65536);
  assert.equal(normals.length, positions.length);
  assert.equal(oldUv.length, positions.length);
  const chunks = [model.binary];
  let byteLength = model.binary.length;
  function append(data, componentType, type, count, target) {
    const padding = (4 - byteLength % 4) % 4;
    if (padding) { chunks.push(Buffer.alloc(padding)); byteLength += padding; }
    const bufferView = json.bufferViews.length;
    json.bufferViews.push({ buffer: 0, byteOffset: byteLength, byteLength: data.length, target });
    chunks.push(data); byteLength += data.length;
    const index = json.accessors.length;
    json.accessors.push({ bufferView, componentType, count, type });
    return index;
  }
  const uvBytes = Buffer.alloc(positions.length * 8);
  positions.forEach((position, index) => {
    // The source uses angular U on both walls, and a separate repeating print UV.
    // Home camera theta=30° faces source u=1/6. This maps it to u=.5,
    // increasing to the viewer's right. Back/seam coordinates clamp to plain
    // background; they never wrap. CanvasTexture has flipY=true in v4.0.0.
    uvBytes.writeFloatLE(2 / 3 - oldUv[index][0], index * 8);
    uvBytes.writeFloatLE(position[1] / loopHeightMeters, index * 8 + 4);
  });
  const editorUv = append(uvBytes, 5126, 'VEC2', positions.length, 34962);
  const primitives = [];
  for (const [materialIndex, primitive] of json.meshes[0].primitives.entries()) {
    assert.equal(primitive.material, materialIndex);
    assert.deepEqual(primitive.attributes, { POSITION: 0, NORMAL: 1, TEXCOORD_0: 2 });
    const sourceIndices = accessorValues(model, primitive.indices).flat(), inside = [], outside = [];
    for (let at = 0; at < sourceIndices.length; at += 3) {
      const triangle = sourceIndices.slice(at, at + 3);
      const outward = triangle.reduce((total, index) => total + positions[index][0] * normals[index][0] + positions[index][2] * normals[index][2], 0);
      (outward > 1e-8 ? outside : inside).push(...triangle);
    }
    assert.ok(inside.length && outside.length);
    const exterior = structuredClone(json.materials[materialIndex]);
    exterior.name = exteriorNames[materialIndex];
    // The normal map retains its original repeated UVs on channel 1.
    exterior.normalTexture.texCoord = 1;
    const exteriorMaterial = json.materials.length;
    json.materials.push(exterior);
    for (const [indices, isExterior] of [[inside, false], [outside, true]]) {
      const data = Buffer.alloc(indices.length * 2);
      indices.forEach((index, at) => data.writeUInt16LE(index, at * 2));
      primitives.push({
        attributes: isExterior ? { POSITION: 0, NORMAL: 1, TEXCOORD_0: editorUv, TEXCOORD_1: 2 } : { ...primitive.attributes },
        indices: append(data, 5123, 'SCALAR', indices.length, 34963),
        material: isExterior ? exteriorMaterial : materialIndex,
      });
    }
  }
  json.meshes[0].primitives = primitives;
  json.asset.extras = { ...json.asset.extras, creastrixStudioPreview: 1 };
  json.buffers[0].byteLength = byteLength;
  return packGlb(json, Buffer.concat(chunks));
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  for (const variant of variants) {
    const source = new URL(`loop-shaped-${variant}.glb`, productDirectory);
    const destination = new URL(`studio-loop-${variant}.glb`, productDirectory);
    const product = generateLoopGlb(variant);
    writeFileSync(source, product);
    writeFileSync(destination, deriveEditorGlb(product));
    console.log(`Generated ${fileURLToPath(source)} and ${fileURLToPath(destination)}`);
  }
}
