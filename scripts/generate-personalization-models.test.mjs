import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { accessorValues, deriveEditorGlb, exteriorNames, readGlb, variants } from './generate-personalization-models.mjs';
import { generateLoopGlb, loopHeightMeters, loopShape } from './loop-model.mjs';

for (const variant of variants) test(`${variant}: deterministic exterior-only UVs preserve all geometry and original inside/cap materials`, () => {
  const sourceBytes = readFileSync(new URL(`../frontend/public/product/loop-shaped-${variant}.glb`, import.meta.url));
  const editorBytes = readFileSync(new URL(`../frontend/public/product/studio-loop-${variant}.glb`, import.meta.url));
  assert.deepEqual(sourceBytes, generateLoopGlb(variant));
  assert.deepEqual(editorBytes, deriveEditorGlb(sourceBytes));
  const source = readGlb(sourceBytes), editor = readGlb(editorBytes);
  assert.deepEqual(editor.binary.subarray(0, source.binary.length), source.binary);
  assert.deepEqual(editor.json.materials.slice(0, 2), source.json.materials);
  assert.deepEqual(editor.json.nodes, source.json.nodes);
  assert.deepEqual(editor.json.images, source.json.images);
  assert.deepEqual(editor.json.textures, source.json.textures);
  const positions = accessorValues(source, 0), normals = accessorValues(source, 1), oldUv = accessorValues(source, 2);
  const triangles = (model, primitive) => {
    const indices = accessorValues(model, primitive.indices).flat(), result = [];
    for (let at = 0; at < indices.length; at += 3) result.push(indices.slice(at, at + 3));
    return result;
  };
  for (let material = 0; material < 2; material++) {
    const primitives = editor.json.meshes[0].primitives.filter(primitive => primitive.material === material || primitive.material === material + 2);
    const originalTriangles = triangles(source, source.json.meshes[0].primitives[material]).map(triangle => triangle.join(',')).sort();
    assert.deepEqual(primitives.flatMap(primitive => triangles(editor, primitive)).map(triangle => triangle.join(',')).sort(), originalTriangles);
    assert.equal(editor.json.materials[material + 2].name, exteriorNames[material]);
    assert.deepEqual(editor.json.materials[material + 2].pbrMetallicRoughness, source.json.materials[material].pbrMetallicRoughness);
    assert.deepEqual(editor.json.materials[material + 2].normalTexture, { ...source.json.materials[material].normalTexture, texCoord: 1 });
    for (const primitive of primitives) {
      const isExterior = primitive.material >= 2;
      assert.equal(primitive.attributes.POSITION, 0); assert.equal(primitive.attributes.NORMAL, 1);
      if (isExterior) assert.equal(primitive.attributes.TEXCOORD_1, 2);
      else assert.deepEqual(primitive.attributes, source.json.meshes[0].primitives[material].attributes);
      const uv = accessorValues(editor, primitive.attributes.TEXCOORD_0);
      for (const triangle of triangles(editor, primitive)) {
        const radial = triangle.reduce((sum, index) => sum + positions[index][0] * normals[index][0] + positions[index][2] * normals[index][2], 0);
        assert.equal(radial > 1e-8, isExterior);
        if (isExterior) for (const index of triangle) {
          assert.ok(Math.abs(uv[index][0] - (2 / 3 - oldUv[index][0])) < 1e-7);
          assert.ok(Math.abs(uv[index][1] - positions[index][1] / loopHeightMeters) < 1e-7);
          assert.ok(uv[index][1] >= 0 && uv[index][1] <= 1);
        }
      }
      if (isExterior) {
        const front = oldUv.findIndex((point, index) => Math.abs(point[0] - 1 / 6) < 1e-6 && positions[index][1] > .04 && normals[index][0] > 0);
        assert.ok(front > 0); assert.ok(Math.abs(uv[front][0] - .5) < 1e-6);
        // Moving right as viewed by theta=30 decreases source U, increases new U:
        // letters are not mirrored. Higher world points increase canvas UV V.
        assert.ok(uv[front - 1][0] > uv[front][0]);
      }
    }
  }
});

const source = readGlb(generateLoopGlb('cobalt-chalk'));
const pos = accessorValues(source, 0), normal = accessorValues(source, 1);
const primitives = source.json.meshes[0].primitives;
const parts = primitives.map(p => accessorValues(source, p.indices).flat());
const minus = (a, b) => a.map((v, i) => v - b[i]);
const dot = (a, b) => a.reduce((sum, v, i) => sum + v*b[i], 0);
const cross = ([x, y, z], [u, v, w]) => [y*w-z*v, z*u-x*w, x*v-y*u];
const contour = pos.map(([x,y,z]) => [y / loopHeightMeters, Math.hypot(x,z)/loopShape.outerRadiusMeters]);

test('Loop is a hollow, correctly wound solid with a level open annular lip, an interior floor and its specified colour boundary', () => {
  const edgeCounts = new Map();
  const geometricKeys = pos.map(p => p.map(v => Math.round(v*1e8)).join(','));
  for (const [part, indices] of parts.entries()) {
    assert.equal(source.json.materials[part].name, part ? 'Cobalt PLA #244bcc' : 'Chalk PLA #f0eee8');
    for (let i = 0; i < indices.length; i += 3) {
      const [a,b,c] = indices.slice(i,i+3);
      const face = cross(minus(pos[b],pos[a]),minus(pos[c],pos[a]));
      for (const k of [a,b,c]) {
        assert.ok(dot(face, normal[k]) > 1e-14, 'No degenerate or reversed triangles');
        assert.ok(part ? contour[k][0] >= loopShape.splitHeight-1e-6 : contour[k][0] <= loopShape.splitHeight+1e-6);
        if (Math.abs(contour[k][0]-1) < 1e-6) assert.ok(contour[k][1] > .70 && contour[k][1] < .82, 'Open rim, never a top disk');
      }
      for (const [from,to] of [[a,b],[b,c],[c,a]]) {
        const edge = [geometricKeys[from], geometricKeys[to]].sort().join('/');
        edgeCounts.set(edge, 1 + (edgeCounts.get(edge) ?? 0));
      }
    }
  }
  assert.ok([...edgeCounts.values()].every(count => count === 2), 'Water-tight across UV and hard-normal seams');
  assert.ok(contour.some(([t, r], i) => t > 0 && t < .05 && r < 1e-6 && normal[i][1] > .99), 'Up-facing recessed floor');
  assert.ok(contour.some(([t, r], i) => t === 0 && r < 1e-6 && normal[i][1] < -.99), 'Closed underside');
  const lipRadii = contour.filter(([t]) => Math.abs(t-1) < 1e-6).map(([, r]) => r);
  assert.ok(Math.abs(Math.max(...lipRadii) - Math.min(...lipRadii) - loopShape.rimThickness) < 1e-5);
  for (const [t,r] of loopShape.profile) assert.ok(contour.some(([y,radius]) => Math.abs(y-t) < 1e-6 && Math.abs(r-radius) < 1e-6));
});

test('projected mesh follows the measured front photograph through the shoulder, belly, taper, opening and base', () => {
  // Independent scanline landmarks from the original 850×567 reference-asset-01;
  // the lens/perspective and a few pixels of asymmetric/shadowed edge are unknown.
  const pitch = 9.6 * Math.PI / 180, scale = 187 / .065;
  const points = pos.map(([x,y,z]) => [x * scale, 152 + ((loopHeightMeters-y) * Math.cos(pitch) + z * Math.sin(pitch)) * scale]);
  const ys = points.map(([,y]) => y);
  assert.ok(Math.abs(Math.min(...ys)-128) <= 3, 'Rear rim y');
  assert.ok(Math.abs(Math.max(...ys)-458) <= 3, 'Base y');
  for (const [y,width] of [[195,326],[225,346],[245,359],[265,369],[285,374],[320,361],[355,333],[395,291],[420,256],[430,237]]) {
    let left = Infinity, right = -Infinity;
    for (const indices of parts) for (let i = 0; i < indices.length; i += 3) {
      for (let k = 0; k < 3; k++) {
        const [x0,y0] = points[indices[i+k]], [x1,y1] = points[indices[i+(k+1)%3]];
        if (y < Math.min(y0,y1) || y > Math.max(y0,y1) || y0 === y1) continue;
        const x = x0 + (x1-x0)*(y-y0)/(y1-y0);
        left = Math.min(left,x); right = Math.max(right,x);
      }
    }
    assert.ok(Math.abs(right-left-width) < 9, `Photo row ${y}: expected width ≈${width}px, mesh ${(right-left).toFixed(1)}px`);
  }
});
