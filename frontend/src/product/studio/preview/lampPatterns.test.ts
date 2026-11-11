import { describe, expect, it } from 'vitest';
import { lampPanel, lampPatternReport, lampPatterns } from './lampPatterns';
import type { LampPatternId } from './lampPatterns';

const ids: LampPatternId[] = ['botanical', 'forest', 'orbit'];

describe('curated lamp illustration descriptors', () => {
  it.each(ids)('%s derives both illustrative counts from the rendered islands', id => {
    const pattern = lampPatterns[id];
    expect(pattern.id).toBe(id);
    expect(new Set(pattern.islands.map(island => island.id)).size).toBe(pattern.islands.length);
    expect(lampPatternReport(id, false)).toEqual({ pattern: id, disconnectedIslands: pattern.islands.length, addedBridges: 0, manufacturingValidated: false });
    expect(lampPatternReport(id, true)).toEqual({ pattern: id, disconnectedIslands: 0, addedBridges: pattern.islands.length, manufacturingValidated: false });
  });

  it.each(ids)('%s contains bounded non-empty cut shapes and finite material bridges', id => {
    const { openings, islands } = lampPatterns[id];
    expect(openings.length).toBeGreaterThan(0);
    expect(islands.length).toBeGreaterThan(0);
    for (const shape of [...openings, ...islands.map(island => island.shape)]) {
      if (shape.kind === 'circle') {
        expect(shape.r).toBeGreaterThan(0);
        expect(shape.cx - shape.r).toBeGreaterThan(0);
        expect(shape.cx + shape.r).toBeLessThan(lampPanel.width);
        expect(shape.cy - shape.r).toBeGreaterThan(0);
        expect(shape.cy + shape.r).toBeLessThan(lampPanel.height);
      } else {
        expect(shape.d.startsWith('M')).toBe(true);
        expect(shape.d.endsWith('Z')).toBe(true);
        expect(shape.d).not.toMatch(/NaN|Infinity|undefined/);
      }
    }
    for (const { bridge } of islands) {
      expect(Object.values(bridge).every(Number.isFinite)).toBe(true);
      expect(bridge.width).toBeGreaterThan(0);
      expect(bridge.height).toBeGreaterThan(0);
      expect(bridge.x).toBeGreaterThan(0);
      expect(bridge.y).toBeGreaterThan(0);
      expect(bridge.x + bridge.width).toBeLessThan(lampPanel.width);
      expect(bridge.y + bridge.height).toBeLessThan(lampPanel.height);
    }
  });

  it('the planet bridges overlap their retained discs and reach past their openings', () => {
    const pattern = lampPatterns.orbit;
    for (const [index, island] of pattern.islands.entries()) {
      const opening = pattern.openings[index];
      expect(opening.kind).toBe('circle'); expect(island.shape.kind).toBe('circle');
      if (opening.kind !== 'circle' || island.shape.kind !== 'circle') throw new Error('Circle fixture changed');
      const { bridge } = island;
      const clampX = Math.max(bridge.x, Math.min(island.shape.cx, bridge.x + bridge.width));
      const clampY = Math.max(bridge.y, Math.min(island.shape.cy, bridge.y + bridge.height));
      expect(Math.hypot(clampX - island.shape.cx, clampY - island.shape.cy)).toBeLessThan(island.shape.r);
      const farCorner = Math.max(...[bridge.x, bridge.x + bridge.width].flatMap(x => [bridge.y, bridge.y + bridge.height].map(y => Math.hypot(x - opening.cx, y - opening.cy))));
      expect(farCorner).toBeGreaterThan(opening.r);
    }
  });

  it('keeps separate trees clear of one another and bridges their trunks to the surrounding panel', () => {
    const fixture = [
      { x: 77, top: 105, bottom: 198, spread: 18 },
      { x: 120, top: 88, bottom: 200, spread: 21 },
      { x: 163, top: 118, bottom: 200, spread: 18 },
    ];
    const hole = lampPatterns.forest.openings[0];
    if (hole.kind !== 'circle') throw new Error('Forest fixture changed');
    for (const [index, tree] of fixture.entries()) {
      const { bridge, shape } = lampPatterns.forest.islands[index];
      expect(shape.kind).toBe('path');
      if (shape.kind === 'path') expect(shape.d.startsWith(`M${tree.x} ${tree.top}`)).toBe(true);
      if (index > 0) expect(fixture[index - 1].x + fixture[index - 1].spread).toBeLessThan(tree.x - tree.spread);
      expect(bridge.x).toBeLessThan(tree.x);
      expect(bridge.x + bridge.width).toBeGreaterThan(tree.x);
      expect(bridge.y).toBeLessThan(tree.bottom);
      expect(bridge.y + bridge.height).toBeGreaterThan(hole.cy + hole.r);
    }
  });
});
