import { readFileSync } from 'node:fs';

// Both the rendered sketch and this lathe use this same measured visual profile.
// Radius is in units of maximum radius; the sample ordinate is a fraction of height.
export const loopShape = JSON.parse(readFileSync(new URL('../frontend/src/product/studio/preview/loopShape.json', import.meta.url), 'utf8'));
export const loopHeightMeters = loopShape.height * loopShape.outerRadiusMeters;
const segments = 96;
const colors = { cobalt: '#244bcc', coral: '#e17763', chalk: '#f0eee8' };
// The original Loop asset's 4×256 tangent-space normal tile: 32 fine horizontal
// ridges. This texture changes the light response, not the pot's silhouette.
const layerTile = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAQAAAEACAYAAACUF9fcAAAASklEQVR42u3OoQ3AMBQD0T9oeNfwGuVd03KopYaGHThmWW8kpxu9Tjf6nO6w+H08TjdaTndY4MCBAwcOHDhw4MCBAwcOHDhw3HFse8vwpupNbW0AAAAASUVORK5CYII=', 'base64');

function material(name) {
  const hex = colors[name];
  const baseColorFactor = hex.slice(1).match(/../g).map(channel => {
    const s = parseInt(channel, 16) / 255;
    return s <= .04045 ? s / 12.92 : ((s + .055) / 1.055) ** 2.4;
  });
  return {
    name: name[0].toUpperCase() + name.slice(1) + ' PLA ' + hex,
    pbrMetallicRoughness: { baseColorFactor: [...baseColorFactor, 1], metallicFactor: 0, roughnessFactor: .68 },
    // Gentle print relief in the highlights; mipmaps fade it at thumbnail sizes.
    normalTexture: { index: 0, scale: .64 }, doubleSided: false,
  };
}

export function packGlb(json, payload) {
  const text = Buffer.from(JSON.stringify(json));
  const padded = (data, fill = 0) => Buffer.concat([data, Buffer.alloc((4 - data.length % 4) % 4, fill)]);
  const jsonChunk = padded(text, 0x20), binary = padded(payload);
  const header = Buffer.alloc(20), binaryHeader = Buffer.alloc(8);
  header.writeUInt32LE(0x46546c67, 0); header.writeUInt32LE(2, 4);
  header.writeUInt32LE(28 + jsonChunk.length + binary.length, 8);
  header.writeUInt32LE(jsonChunk.length, 12); header.writeUInt32LE(0x4e4f534a, 16);
  binaryHeader.writeUInt32LE(binary.length, 0); binaryHeader.writeUInt32LE(0x004e4942, 4);
  return Buffer.concat([header, jsonChunk, binaryHeader, binary]);
}

function radiusAt(t) {
  const profile = loopShape.profile;
  const i = profile.findIndex(([y]) => y >= t);
  if (i <= 0) return profile[i < 0 ? profile.length - 1 : 0][1];
  const [a, ra] = profile[i - 1], [b, rb] = profile[i];
  return ra + (rb - ra) * (t - a) / (b - a);
}

export function generateLoopGlb(variant) {
  const { profile, height, outerRadiusMeters: scale, rimThickness, wallThickness, floorHeight, splitHeight } = loopShape;
  const pos = [], normals = [], uv = [], indices = [[], []];
  const floor = floorHeight / height;
  const levels = [...new Set([...profile.map(([t]) => t), splitHeight])].sort((a, b) => a - b);
  function vertex(x, y, z, nx, ny, nz, u, v) {
    const index = pos.length / 3;
    pos.push(x * scale, y * scale, z * scale);
    normals.push(nx, ny, nz); uv.push(u, v);
    return index;
  }
  function ring(t, r, slope, sign, planarY = 0) {
    const start = pos.length / 3;
    const norm = Math.hypot(1, slope);
    for (let i = 0; i <= segments; i++) {
      const u = i / segments, theta = i === segments ? 0 : u * Math.PI * 2;
      const c = Math.cos(theta), s = Math.sin(theta);
      vertex(r*c, t*height, r*s, planarY ? 0 : sign*c/norm, planarY || -sign*slope/norm, planarY ? 0 : sign*s/norm, u, t * loopShape.printedLayers / 32);
    }
    return start;
  }
  function walls(inside) {
    const ts = inside ? [floor, ...levels.filter(t => t > floor)] : levels;
    // The wall thickens slightly towards the photographed lip. Keep the hollow
    // opening, solid floor and exterior separate at their sharp shading edges.
    const radii = ts.map(t => radiusAt(t) - (inside ? wallThickness + (rimThickness - wallThickness) * t ** 4 : 0));
    const rings = ts.map((t, j) => {
      const a = Math.max(0, j - 1), b = Math.min(ts.length - 1, j + 1);
      return ring(t, radii[j], (radii[b] - radii[a]) / ((ts[b] - ts[a]) * height), inside ? -1 : 1);
    });
    for (let j = 0; j < ts.length - 1; j++) {
      const out = indices[ts[j] < splitHeight ? 0 : 1];
      for (let i = 0; i < segments; i++) {
        const a = rings[j] + i, b = rings[j + 1] + i;
        if (inside) out.push(a, a+1, b, a+1, b+1, b);
        else out.push(a, b, a+1, a+1, b, b+1);
      }
    }
    return radii[0];
  }
  const base = walls(false), floorRadius = walls(true);
  const lip = radiusAt(1);
  const inner = ring(1, lip-rimThickness, 0, 0, 1), outer = ring(1, lip, 0, 0, 1);
  for (let i = 0; i < segments; i++) {
    const a = inner+i, b = outer+i;
    indices[1].push(a, a+1, b, a+1, b+1, b);
  }
  for (const [t, r, ny] of [[0, base, -1], [floor, floorRadius, 1]]) {
    const start = ring(t, r, 0, 0, ny), center = vertex(0, t*height, 0, 0, ny, 0, .5, t);
    for (let i = 0; i < segments; i++)
      indices[0].push(center, start+i+(ny > 0 ? 1 : 0), start+i+(ny > 0 ? 0 : 1));
  }

  const chunks = [], views = [], accessors = [];
  let length = 0;
  function view(data, target) {
    const pad = (4 - length % 4) % 4;
    if (pad) { chunks.push(Buffer.alloc(pad)); length += pad; }
    const index = views.length;
    views.push({ buffer: 0, byteOffset: length, byteLength: data.length, ...(target ? { target } : {}) });
    chunks.push(data); length += data.length;
    return index;
  }
  function accessor(values, width, type, index = false) {
    const data = Buffer.alloc(values.length * (index ? 2 : 4));
    values.forEach((v, i) => index ? data.writeUInt16LE(v, i * 2) : data.writeFloatLE(v, i * 4));
    const result = accessors.length;
    accessors.push({ bufferView: view(data, index ? 34963 : 34962), componentType: index ? 5123 : 5126, count: values.length / width, type });
    return result;
  }
  const attributes = { POSITION: accessor(pos, 3, 'VEC3'), NORMAL: accessor(normals, 3, 'VEC3'), TEXCOORD_0: accessor(uv, 2, 'VEC2') };
  accessors[0].min = [-Math.fround(scale), 0, -Math.fround(scale)];
  accessors[0].max = [Math.fround(scale), Math.fround(loopHeightMeters), Math.fround(scale)];
  const primitives = indices.map((values, i) => ({ attributes, indices: accessor(values, 1, 'SCALAR', true), material: i }));
  const imageView = view(layerTile);
  const palette = variant === 'cobalt-chalk' ? ['chalk', 'cobalt'] : [variant, variant];
  return packGlb({
    asset: { version: '2.0', generator: 'Creastrix photo-profile Loop preview', copyright: 'Visual approximation, not manufacturing CAD' },
    scene: 0, scenes: [{ nodes: [0] }], nodes: [{ name: 'Loop open rounded pot', mesh: 0 }],
    meshes: [{ primitives }], materials: palette.map(material),
    images: [{ bufferView: imageView, mimeType: 'image/png', name: 'Subtle horizontal PLA layer normals' }],
    samplers: [{ magFilter: 9729, minFilter: 9987, wrapS: 10497, wrapT: 10497 }], textures: [{ sampler: 0, source: 0 }],
    buffers: [{ byteLength: length }], bufferViews: views, accessors,
  }, Buffer.concat(chunks));
}
