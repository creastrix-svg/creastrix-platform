import shape from './loopShape.json';

// Orthographic elevation of the same lathe sections used by the product and
// studio meshes. Drawing in existing editor units keeps stored designs intact.
const { profile, height, cameraElevationDegrees, sketch } = shape;
const { centerX, baseY, pixelsPerRadius } = sketch;
const angle = cameraElevationDegrees * Math.PI / 180;
const round = (v: number) => Number(v.toFixed(2));

function radiusAt(level: number) {
  const next = profile.findIndex(([at]) => at >= level);
  if (next <= 0) return profile[next === 0 ? 0 : profile.length - 1][1];
  const [a, r] = profile[next - 1], [b, s] = profile[next];
  return r + (s - r) * (level - a) / (b - a);
}

export function projectLoopRing(level: number, radius = radiusAt(level)) {
  const rx = round(radius * pixelsPerRadius), ry = round(radius * pixelsPerRadius * Math.sin(angle));
  const cy = round(baseY - level * height * pixelsPerRadius * Math.cos(angle));
  return { cx: centerX, cy, rx, ry, left: round(centerX - rx), right: round(centerX + rx) };
}

// Each sloped section meets a projected ring at a tangent, not its horizontal
// extremum. The distinction is especially visible where the body rolls into the foot.
const sectionAngle = (a: number, b: number) => Math.asin(Math.max(-1, Math.min(1,
  (radiusAt(b) - radiusAt(a)) / ((b - a) * height) * Math.tan(angle),
)));
type Ring = ReturnType<typeof projectLoopRing>;
function contact(r: Ring, at: number, side: -1 | 1) {
  return `${round(r.cx + side * r.rx * Math.cos(at))} ${round(r.cy + r.ry * Math.sin(at))}`;
}

function exteriorBelow(top: number) {
  const levels = [...profile.filter(([at]) => at < top).map(([at]) => at), top];
  const rings = levels.map(at => projectLoopRing(at));
  const angles = levels.slice(1).map((at, i) => sectionAngle(levels[i], at));
  // Trace both tangent edges; connect neighbouring slopes along their common ring.
  const edge = (from: number, to: number, step: -1 | 1) => {
    const path: string[] = [];
    for (let i = from; i !== to; i += step) {
      const j = i + step, a = angles[Math.min(i, j)], r = rings[j];
      path.push(`L ${contact(r, a, step)}`);
      if (j !== to) {
        const next = angles[Math.min(j, j + step)];
        if (a !== next) path.push(`A ${r.rx} ${r.ry} 0 0 ${(next - a) * step > 0 ? 1 : 0} ${contact(r, next, step)}`);
      }
    }
    return path.join(' ');
  };
  const last = rings.length - 1, rim = rings[last], foot = rings[0];
  const high = angles[last - 1], low = angles[0];
  return [
    `M ${contact(foot, low, 1)}`, edge(0, last, 1),
    `A ${rim.rx} ${rim.ry} 0 ${high < 0 ? 1 : 0} 1 ${contact(rim, high, -1)}`,
    edge(last, 0, -1), `A ${foot.rx} ${foot.ry} 0 ${low < 0 ? 1 : 0} 0 ${contact(foot, low, 1)} Z`,
  ].join(' ');
}

function printLayers(count: number) {
  return Array.from({ length: count }, (_, i) => {
    const at = (i + 1) / (count + 1), ring = projectLoopRing(at);
    const next = profile.findIndex(([level]) => level >= at);
    const a = sectionAngle(profile[next - 1][0], profile[next][0]);
    return `M ${contact(ring, a, -1)} A ${ring.rx} ${ring.ry} 0 ${a < 0 ? 1 : 0} 0 ${contact(ring, a, 1)}`;
  }).join(' ');
}

export const loopProjection = {
  body: exteriorBelow(1),
  lower: exteriorBelow(shape.splitHeight),
  rim: projectLoopRing(1),
  opening: projectLoopRing(1, radiusAt(1) - shape.rimThickness),
  foot: projectLoopRing(0),
  layers: printLayers(95),
};
