import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateSync } from 'node:zlib';
import { Window } from '../frontend/node_modules/happy-dom/lib/index.js';
import { BoxGeometry, BufferGeometry, Float32BufferAttribute, ExtrudeGeometry, Path, Shape } from '../frontend/node_modules/three/build/three.module.js';
import { SVGLoader } from '../frontend/node_modules/three/examples/jsm/loaders/SVGLoader.js';

export const lampVariants = ['botanical', 'forest', 'orbit'];
export const lampDimensions = { width: .152, height: .23, depth: .112, panelThickness: .003 };
const directory = new URL('../frontend/public/product/', import.meta.url);
const source = readFileSync(new URL('../frontend/src/product/studio/preview/lampPatterns.ts', import.meta.url), 'utf8');
// Import the small data-only TypeScript descriptor with Node's built-in stripper.
// There is one source of motif geometry for the sketch, thumbnails and mesh.
const { lampPatterns } = await import('../frontend/src/product/studio/preview/lampPatterns.ts');
const window = new Window();
globalThis.DOMParser = window.DOMParser;
const loader = new SVGLoader();
const step = .5, columns = 480, rows = 640;
const panelScale = .0005;

function shapePoints(shape) {
  if (shape.kind === 'circle') return Array.from({ length: 192 }, (_, i) => [shape.cx + Math.cos(i * Math.PI / 96) * shape.r, shape.cy + Math.sin(i * Math.PI / 96) * shape.r]);
  const parsed = loader.parse(`<svg xmlns="http://www.w3.org/2000/svg"><path d="${shape.d}"/></svg>`);
  return parsed.paths[0].subPaths[0].getPoints(48).map(point => [point.x, point.y]);
}
function inside(point, polygon) {
  const [x, y] = point;
  let result = false;
  for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
    const [a, b] = polygon[i], [c, d] = polygon[j];
    if ((b > y) !== (d > y) && x < (c - a) * (y - b) / (d - b) + a) result = !result;
  }
  return result;
}
function shapeContains(shape) {
  if (shape.kind === 'circle') return ([x,y]) => (x-shape.cx)**2+(y-shape.cy)**2 <= shape.r**2;
  const polygon = shapePoints(shape);
  const xs = polygon.map(point => point[0]), ys = polygon.map(point => point[1]);
  const left = Math.min(...xs), right = Math.max(...xs), top = Math.min(...ys), bottom = Math.max(...ys);
  return point => point[0] >= left && point[0] <= right && point[1] >= top && point[1] <= bottom && inside(point,polygon);
}
const masks = new Map();
export function panelMask(pattern) {
  assert.ok(lampVariants.includes(pattern));
  if (masks.has(pattern)) return { ...masks.get(pattern), mask: masks.get(pattern).mask.slice() };
  const descriptor = lampPatterns[pattern];
  const openings = descriptor.openings.map(shapeContains);
  const islands = descriptor.islands.map(island => ({ contains: shapeContains(island.shape), bridge: island.bridge }));
  const mask = new Uint8Array(columns * rows);
  for (let y = 0; y < rows; y++) for (let x = 0; x < columns; x++) {
    const point = [(x + .5) * step, (y + .5) * step];
    // Rounded top corners match the panel drawing; material bridges are a fixed
    // part of each prepared motif, never a buyer-controlled option.
    const rounded = point[1] >= 20 || point[0] >= 20 && point[0] <= 220 ||
      Math.hypot(point[0] - (point[0] < 20 ? 20 : 220), point[1] - 20) <= 20;
    const removed = openings.some(contains => contains(point));
    const retained = islands.some(({ contains, bridge: b }) => contains(point) ||
      point[0] >= b.x && point[0] <= b.x + b.width && point[1] >= b.y && point[1] <= b.y + b.height);
    mask[y * columns + x] = Number(rounded && (!removed || retained));
  }
  // Resolve diagonal-only sampling contacts toward retained material. Without
  // this sub-cell regularization a grid contour can touch itself at one vertex.
  // This is preview sampling, not a production kerf/bridge repair.
  let changes;
  do {
    changes = 0;
    for (let y = 0; y < rows - 1; y++) for (let x = 0; x < columns - 1; x++) {
      const at = y * columns + x, a = mask[at], b = mask[at + 1], c = mask[at + columns], d = mask[at + columns + 1];
      if (a && d && !b && !c) { mask[at + 1] = 1; changes++; }
      else if (b && c && !a && !d) { mask[at] = 1; changes++; }
    }
  } while (changes);
  masks.set(pattern, { mask, columns, rows, step });
  return { mask: mask.slice(), columns, rows, step };
}

function contours({ mask, columns: width, rows: height }) {
  const outgoing = new Map();
  const material = (x, y) => x >= 0 && y >= 0 && x < width && y < height && mask[y * width + x];
  const key = (x, y) => y * (width + 1) + x;
  function edge(x, y, a, b) {
    const start = key(x, y);
    assert.ok(!outgoing.has(start), 'A prepared contour must not self-touch at one vertex.');
    outgoing.set(start, key(a, b));
  }
  for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) if (material(x, y)) {
    if (!material(x, y - 1)) edge(x, y, x + 1, y);
    if (!material(x + 1, y)) edge(x + 1, y, x + 1, y + 1);
    if (!material(x, y + 1)) edge(x + 1, y + 1, x, y + 1);
    if (!material(x - 1, y)) edge(x, y + 1, x, y);
  }
  const rings = [];
  while (outgoing.size) {
    const start = outgoing.keys().next().value;
    let next = start;
    const ring = [];
    do {
      ring.push([next % (width + 1) * step, Math.floor(next / (width + 1)) * step]);
      const target = outgoing.get(next);
      assert.notEqual(target, undefined, 'Contour must be closed.');
      outgoing.delete(next); next = target;
    } while (next !== start);
    // Remove only exactly collinear points; do not simplify away a narrow bridge.
    rings.push(ring.filter((point, index) => {
      const before = ring[(index + ring.length - 1) % ring.length], after = ring[(index + 1) % ring.length];
      return (point[0] - before[0]) * (after[1] - point[1]) !== (point[1] - before[1]) * (after[0] - point[0]);
    }));
  }
  return rings;
}
function area(points) {
  return points.reduce((sum, point, i) => {
    const next = points[(i + 1) % points.length];
    return sum + point[0] * next[1] - next[0] * point[1];
  }, 0) / 2;
}
/** Earcut may connect holes with a long cap edge overlapping shorter boundary
 * edges. Split those T-junctions at the existing vertices: same visible surface,
 * but every welded edge has two incident triangles instead of an overlap. */
function splitCapJunctions(geometry) {
  let triangles = [];
  const position = geometry.attributes.position.array, normal = geometry.attributes.normal.array, uv = geometry.attributes.uv.array;
  for (const group of geometry.groups) for (let i = group.start; i < group.start + group.count; i += 3) {
    triangles.push({ material: group.materialIndex, vertices: [i,i+1,i+2].map(index => ({ p:Array.from(position.slice(index*3,index*3+3)), n:Array.from(normal.slice(index*3,index*3+3)), uv:Array.from(uv.slice(index*2,index*2+2)) })) });
  }
  const key = vertex => vertex.p.map(value => value.toFixed(7)).join(',');
  for (let pass = 0; pass < 8; pass++) {
    const edges = new Map();
    for (const triangle of triangles) for (const [a,b] of [[0,1],[1,2],[2,0]]) {
      const vertices = [triangle.vertices[a],triangle.vertices[b]], id = vertices.map(key).sort().join('|');
      const entry = edges.get(id);
      if (entry) entry.count++; else edges.set(id,{ count:1,vertices });
    }
    const boundary = [...edges.values()].filter(edge => edge.count === 1);
    if (!boundary.length) break;
    const points = [...new Map(boundary.flatMap(edge => edge.vertices).map(vertex => [key(vertex),vertex.p])).values()];
    let changed = false;
    const next = [];
    for (const triangle of triangles) {
      let replacements = null;
      for (const [a,b,c] of [[0,1,2],[1,2,0],[2,0,1]]) {
        const A = triangle.vertices[a], B = triangle.vertices[b], C = triangle.vertices[c], delta = B.p.map((v,i) => v-A.p[i]);
        if (edges.get([key(A),key(B)].sort().join('|')).count !== 1) continue;
        const length = delta.reduce((sum,v) => sum+v*v,0);
        for (const point of points) {
          const t = point.reduce((sum,v,i) => sum+(v-A.p[i])*delta[i],0)/length;
          if (t <= 1e-5 || t >= 1-1e-5 || Math.hypot(...point.map((v,i) => v-A.p[i]-t*delta[i])) > 2e-8) continue;
          const middle = { p:point, n:A.n.map((v,i) => v+t*(B.n[i]-v)), uv:A.uv.map((v,i) => v+t*(B.uv[i]-v)) };
          replacements = [{ material:triangle.material,vertices:[A,middle,C] },{ material:triangle.material,vertices:[middle,B,C] }]; changed = true; break;
        }
        if (replacements) break;
      }
      next.push(...(replacements ?? [triangle]));
    }
    triangles = next;
    assert.ok(changed,'An unmatched edge must be an explicitly repairable cap junction.');
  }
  const result = new BufferGeometry(), positions=[], normals=[], uvs=[];
  for (const material of [0,1]) {
    const start=positions.length/3;
    for (const triangle of triangles.filter(triangle=>triangle.material===material)) for (const vertex of triangle.vertices) { positions.push(...vertex.p); normals.push(...vertex.n); uvs.push(...vertex.uv); }
    result.addGroup(start,positions.length/3-start,material);
  }
  result.setAttribute('position',new Float32BufferAttribute(positions,3)); result.setAttribute('normal',new Float32BufferAttribute(normals,3)); result.setAttribute('uv',new Float32BufferAttribute(uvs,2));
  geometry.dispose(); return result;
}
export function panelGeometry(pattern) {
  const rings = contours(panelMask(pattern)).sort((a, b) => Math.abs(area(b)) - Math.abs(area(a)));
  const toPath = (ring, Constructor) => {
    const path = new Constructor();
    // Triangulate in exact integer grid units. Tiny metre-coordinate rounding
    // otherwise turns collinear boundary points into zero-area cap triangles.
    ring.forEach(([x, y], index) => path[index ? 'lineTo' : 'moveTo']((x - 120) / step, (320 - y) / step));
    path.closePath();
    return path;
  };
  assert.ok(rings.length > 1);
  assert.equal(rings.filter(ring => area(ring) > 0).length, 1, 'All retained motif material must be attached to the panel.');
  const shape = toPath(rings[0], Shape);
  for (const ring of rings.slice(1)) shape.holes.push(toPath(ring, Path));
  const unit = panelScale * step;
  const geometry = new ExtrudeGeometry(shape, { depth: lampDimensions.panelThickness / unit, bevelEnabled: false, curveSegments: 1, steps: 1 });
  geometry.scale(unit,unit,unit);
  geometry.translate(0, .062, .049);
  return { geometry: splitCapJunctions(geometry), holes: rings.length - 1 };
}

// Small deterministic RGB wood texture, embedded in the GLB. No image service,
// external URI or runtime texture fetch is required.
function crc32(buffer) {
  let crc = -1;
  for (const byte of buffer) {
    crc ^= byte;
    for (let i = 0; i < 8; i++) crc = crc >>> 1 ^ (crc & 1 ? 0xedb88320 : 0);
  }
  return (crc ^ -1) >>> 0;
}
function pngChunk(name, bytes) {
  const type = Buffer.from(name), header = Buffer.alloc(4), crc = Buffer.alloc(4);
  header.writeUInt32BE(bytes.length); crc.writeUInt32BE(crc32(Buffer.concat([type, bytes])));
  return Buffer.concat([header, type, bytes, crc]);
}
function woodTexture() {
  const size = 256, rows = [];
  for (let y = 0; y < size; y++) {
    const row = Buffer.alloc(1 + size * 3);
    for (let x = 0; x < size; x++) {
      const grain = Math.sin(x * .31 + Math.sin(y * .025) * 2) * 3 + Math.sin(x * 1.37 + y * .004) * 1.8;
      [224, 187, 132].forEach((value, c) => { row[1 + x * 3 + c] = Math.round(value + grain); });
    }
    rows.push(row);
  }
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(size); ihdr.writeUInt32BE(size, 4); ihdr[8] = 8; ihdr[9] = 2;
  return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]), pngChunk('IHDR', ihdr), pngChunk('IDAT', deflateSync(Buffer.concat(rows))), pngChunk('IEND', Buffer.alloc(0))]);
}

export function generateLampGlb(pattern) {
  const { geometry: panel, holes } = panelGeometry(pattern);
  const material = (name, color, roughness = .85, extra = {}) => ({ name, pbrMetallicRoughness: { baseColorFactor: color, metallicFactor: 0, roughnessFactor: roughness }, ...extra });
  const json = {
    asset: { version: '2.0', generator: 'Creastrix prepared Luma preview', extras: { pattern, bridges: 'fixed', holes, manufacturingValidated: false, geometrySource: 'lampPatterns.ts', panelSamplingMillimetres: step * panelScale * 1000 } },
    scene: 0, scenes: [{ nodes: [] }], nodes: [], meshes: [], accessors: [], bufferViews: [], buffers: [{ byteLength: 0 }],
    materials: [
      { ...material('Birch plywood face', [1,1,1,1]), pbrMetallicRoughness: { baseColorFactor: [1,1,1,1], metallicFactor: 0, roughnessFactor: .82, baseColorTexture: { index: 0 } } },
      material('Dark cut plywood edge', [.22,.105,.035,1]),
      material('Warm inner diffuser', [1,.78,.42,1], .95, { emissiveFactor: [.55,.26,.055] }),
      material('Studio lamp base engraving', [.7454,.4969,.2307,1]),
      material('Brushed dark feet', [.065,.075,.06,1], .6),
      // Dedicated public material identities let ray picking select only the
      // prepared front zone, without tinting or admitting fixed side/back wood.
      { ...material('Studio lamp front panel', [1,1,1,1]), pbrMetallicRoughness: { baseColorFactor: [1,1,1,1], metallicFactor: 0, roughnessFactor: .82, baseColorTexture: { index: 0 } } },
      material('Studio lamp front cut edge', [.22,.105,.035,1]),
    ],
    textures: [{ sampler: 0, source: 0 }], samplers: [{ wrapS: 10497, wrapT: 10497, minFilter: 9987, magFilter: 9729 }], images: [],
  };
  const chunks = []; let length = 0;
  const append = (data, target) => {
    const padding = (4 - length % 4) % 4;
    if (padding) { chunks.push(Buffer.alloc(padding)); length += padding; }
    const index = json.bufferViews.length;
    json.bufferViews.push({ buffer: 0, byteOffset: length, byteLength: data.length, ...(target ? { target } : {}) });
    chunks.push(data); length += data.length; return index;
  };
  const accessor = (values, width, name) => {
    const bytes = Buffer.alloc(values.length * 4);
    values.forEach((value, index) => bytes.writeFloatLE(value, index * 4));
    const index = json.accessors.length;
    const entry = { bufferView: append(bytes, 34962), componentType: 5126, count: values.length / width, type: `VEC${width}` };
    if (name === 'POSITION') {
      entry.min = Array.from({ length: width }, (_, c) => Math.min(...values.filter((_, i) => i % width === c)));
      entry.max = Array.from({ length: width }, (_, c) => Math.max(...values.filter((_, i) => i % width === c)));
    }
    json.accessors.push(entry); return index;
  };
  function addMesh(name, geometry, faceMaterial = 0, edgeMaterial = faceMaterial, transform = {}) {
    const nonIndexed = geometry.index ? geometry.toNonIndexed() : geometry;
    const primitives = [];
    const groups = nonIndexed.groups.length ? nonIndexed.groups : [{ start: 0, count: nonIndexed.attributes.position.count, materialIndex: 0 }];
    for (const group of groups) {
      const attributes = {};
      for (const [sourceName, outputName] of [['position','POSITION'],['normal','NORMAL'],['uv','TEXCOORD_0']]) {
        const attribute = nonIndexed.attributes[sourceName];
        const values = Array.from(attribute.array).slice(group.start * attribute.itemSize, (group.start + group.count) * attribute.itemSize);
        // Extrusion's default UV uses metres. Use a whole, repeating wood grain.
        if (sourceName === 'uv' && name.includes('panel')) for (let i = 0; i < values.length; i++) values[i] *= 8;
        attributes[outputName] = accessor(values, attribute.itemSize, outputName);
      }
      primitives.push({ attributes, material: group.materialIndex === 1 ? edgeMaterial : faceMaterial });
    }
    json.scenes[0].nodes.push(json.nodes.length);
    json.nodes.push({ name, mesh: json.meshes.length, ...transform }); json.meshes.push({ name, primitives });
  }
  addMesh('Front cut panel', panel, 5, 6);
  function fixedPanel(width, depth) {
    const shape = new Shape();
    shape.moveTo(-width / 2,0); shape.lineTo(width / 2,0); shape.lineTo(width / 2,.16); shape.lineTo(-width / 2,.16); shape.closePath();
    for (const x of [-.025,0,.025]) {
      const slot = new Path(); slot.absellipse(x,.09,.0035,.045,0,Math.PI * 2,true);
      shape.holes.push(slot);
    }
    const geometry = new ExtrudeGeometry(shape, { depth: .003, bevelEnabled: false, curveSegments: 32 });
    geometry.translate(0,.062,depth); return geometry;
  }
  // Only the advertised front zone changes with the selected motif. Back and
  // side ventilation slots remain the prepared product's fixed construction.
  const back = fixedPanel(.12,.049), side = fixedPanel(.094,.058);
  addMesh('Fixed back panel', back, 0, 1, { rotation: [0,1,0,0] });
  addMesh('Fixed right panel', side, 0, 1, { rotation: [0,Math.SQRT1_2,0,Math.SQRT1_2] });
  addMesh('Fixed left panel', side, 0, 1, { rotation: [0,-Math.SQRT1_2,0,Math.SQRT1_2] });
  function box(name, size, position, mat) {
    const geometry = new BoxGeometry(...size); geometry.translate(...position); addMesh(name, geometry, mat);
  }
  box('Solid birch base', [.152,.054,.112], [0,.035,0], 0);
  box('Top cap', [.144,.008,.108], [0,.226,0], 0);
  box('Warm frosted inner light', [.096,.15,.074], [0,.14,0], 2);
  for (const x of [-.064,.064]) for (const z of [-.044,.044]) box('Small foot', [.014,.008,.014], [x,.004,z], 4);
  for (const x of [-.061,.061]) for (const z of [-.05,.05]) box('Corner frame', [.006,.164,.006], [x,.14,z], 0);
  // A dedicated outward-only face keeps base decoration off the sides/back.
  const base = new BoxGeometry(.13, .044, .0001);
  base.translate(0,.035,.0561);
  base.groups = base.groups.filter(group => group.materialIndex === 4);
  addMesh('Base engraving face', base, 3);
  json.images.push({ bufferView: append(woodTexture()), mimeType: 'image/png' });
  json.buffers[0].byteLength = length;
  const text = Buffer.from(JSON.stringify(json)), jsonChunk = Buffer.concat([text, Buffer.alloc((4 - text.length % 4) % 4, 32)]);
  const binary = Buffer.concat([...chunks, Buffer.alloc((4 - length % 4) % 4)]);
  const header = Buffer.alloc(20), binHeader = Buffer.alloc(8);
  header.writeUInt32LE(0x46546c67); header.writeUInt32LE(2,4); header.writeUInt32LE(28 + jsonChunk.length + binary.length,8);
  header.writeUInt32LE(jsonChunk.length,12); header.writeUInt32LE(0x4e4f534a,16);
  binHeader.writeUInt32LE(binary.length); binHeader.writeUInt32LE(0x004e4942,4);
  panel.dispose();
  return Buffer.concat([header,jsonChunk,binHeader,binary]);
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  assert.ok(source.includes('Hand-authored preview geometry'));
  for (const pattern of lampVariants) {
    const file = new URL(`studio-lamp-${pattern}.glb`, directory);
    const bytes = generateLampGlb(pattern); writeFileSync(file, bytes);
    console.log(`${pattern}: ${bytes.length} bytes`);
  }
}
