import { describe, expect, it } from 'vitest';
import { standardStudioFonts } from './design/studioFonts';
import { canPersonalize, canUseStudioFont, canUseStudioTool, getStudioProduct, getStudioZone, studioProducts } from './studioProducts';
import type { StudioProduct, StudioZone } from './studioProducts';

describe('configured studio product capabilities', () => {
  it('separates a ready-made surface application from lamp cut and engraving zones', () => {
    const loop = getStudioProduct('loop'), lamp = getStudioProduct('lamp');
    expect(loop.readyMade).toBe(true);
    expect(lamp.readyMade).toBe(false);
    expect(loop.zones.map(zone => [zone.id, zone.operation])).toEqual([['surface', 'print']]);
    expect(lamp.zones.map(zone => [zone.id, zone.operation])).toEqual([['panel', 'cut'], ['base', 'engrave']]);
    expect(Object.values(studioProducts).every(canPersonalize)).toBe(true);
  });

  it('offers only seller-configured tools in each zone', () => {
    const loop = getStudioProduct('loop'), lamp = getStudioProduct('lamp');
    expect(getStudioZone(loop, 'surface')?.tools).toEqual(['text', 'image', 'ai']);
    expect(getStudioZone(lamp, 'panel')?.tools).toEqual(['pattern', 'text', 'image', 'ai']);
    expect(getStudioZone(lamp, 'base')?.tools).toEqual(['text', 'image']);
    expect(canUseStudioTool(lamp, 'panel', 'image')).toBe(true);
    expect(canUseStudioTool(lamp, 'panel', 'text')).toBe(true);
    expect(canUseStudioTool(lamp, 'base', 'ai')).toBe(false);
    expect(canUseStudioTool(lamp, 'base', 'pattern')).toBe(false);
    expect(canUseStudioTool(loop, 'surface', 'text')).toBe(true);
    expect(canUseStudioTool(loop, 'base', 'text')).toBe(false);
  });

  it('keeps font and colour controls specific to the configured operation', () => {
    const surface = getStudioZone(getStudioProduct('loop'), 'surface')!;
    const base = getStudioZone(getStudioProduct('lamp'), 'base')!;
    const panel = getStudioZone(getStudioProduct('lamp'), 'panel')!;
    expect(surface.fonts).toEqual(standardStudioFonts);
    expect(base.fonts).toEqual(['sans', 'serif', 'mono']);
    expect(surface.allowCustomFonts).toBe(true);
    expect(base.allowCustomFonts).toBe(false);
    expect(surface.inkColors).toHaveLength(3);
    expect(base.inkColors).toEqual(['#20251f']);
    expect(panel).toMatchObject({ fonts: ['stencil'], inkColors: [], allowCustomFonts: false, allowPosition: true, visualComposition: true,
      textLimits: { maxCharacters: 14, maxLines: 1, minSize: 18, maxSize: 28 } });
    expect(base.textLimits).toEqual({ maxCharacters: 32, maxLines: 2, minSize: 18, maxSize: 32 });
  });

  it('does not infer personalization merely from a ready-made or made-to-order source', () => {
    for (const source of Object.values(studioProducts)) {
      const disabled = { ...source, enabled: false };
      expect(canPersonalize(disabled)).toBe(false);
      for (const zone of source.zones) {
        expect(getStudioZone(disabled, zone.id)).toBeUndefined();
        for (const tool of zone.tools) expect(canUseStudioTool(disabled, zone.id, tool)).toBe(false);
      }
      expect(canPersonalize({ ...source, zones: [] })).toBe(false);
    }
  });

  it('fails closed for missing products, unknown zones and unsupported tools', () => {
    expect(getStudioProduct('unknown')).toBeUndefined();
    expect(getStudioProduct('__proto__')).toBeUndefined();
    expect(canPersonalize(undefined)).toBe(false);
    expect(getStudioZone(undefined, 'surface')).toBeUndefined();
    expect(getStudioZone(getStudioProduct('loop'), 'unknown')).toBeUndefined();
    expect(canUseStudioTool(getStudioProduct('loop'), 'surface', 'remove-bridges')).toBe(false);
    expect(canUseStudioTool(undefined, 'surface', 'text')).toBe(false);
  });

  it('rejects empty, ambiguous or internally incompatible fixture configuration', () => {
    const loop = getStudioProduct('loop'), surface = loop.zones[0];
    const withZone = (override: Partial<StudioZone>): StudioProduct => ({ ...loop, zones: [{ ...surface, ...override }] });
    expect(canPersonalize({ ...loop, zones: [surface, surface] })).toBe(false);
    expect(canPersonalize(withZone({ tools: [] }))).toBe(false);
    expect(canPersonalize(withZone({ tools: ['text', 'text'] }))).toBe(false);
    expect(canPersonalize(withZone({ fonts: [] }))).toBe(false);
    expect(canPersonalize(withZone({ inkColors: [] }))).toBe(false);
    expect(canPersonalize(withZone({ operation: 'cut', tools: ['image'] }))).toBe(false);
    expect(canPersonalize(withZone({ fonts: ['sans', 'sans'] }))).toBe(false);
    expect(canPersonalize(withZone({ inkColors: ['#ffffff', '#ffffff'] }))).toBe(false);
    expect(canPersonalize(withZone({ textLimits: undefined }))).toBe(false);
    expect(canPersonalize(withZone({ tools: ['image'], fonts: [], allowCustomFonts: false }))).toBe(false);
    expect(canPersonalize(withZone({ tools: ['image'], fonts: [], allowCustomFonts: false, textLimits: undefined }))).toBe(true);
  });

  it('checks font permission independently of visible picker options', () => {
    const lamp = getStudioProduct('lamp'), loop = getStudioProduct('loop');
    expect(canUseStudioFont(lamp, 'panel', 'stencil')).toBe(true);
    expect(canUseStudioFont(lamp, 'panel', 'sans')).toBe(false);
    expect(canUseStudioFont(lamp, 'base', 'serif')).toBe(true);
    expect(canUseStudioFont(lamp, 'base', 'handwritten')).toBe(false);
    expect(canUseStudioFont(loop, 'surface', 'stencil')).toBe(false);
    expect(canUseStudioFont(loop, 'surface', 'custom-999999')).toBe(false);
    expect(canUseStudioFont(undefined, 'surface', 'sans')).toBe(false);
    expect(canUseStudioFont(lamp, 'base', '__proto__')).toBe(false);
  });

  it('bounds text settings and prevents arbitrary fonts or ink on a cut fixture', () => {
    const lamp = getStudioProduct('lamp'), panel = lamp.zones[0];
    const configured = (override: Partial<StudioZone>): StudioProduct => ({ ...lamp, zones: [{ ...panel, ...override }] });
    for (const override of [
      { fonts: ['sans'] }, { allowCustomFonts: true }, { visualComposition: false }, { inkColors: ['#20251f'] },
      { textLimits: { ...panel.textLimits!, maxCharacters: 15 } },
      { textLimits: { ...panel.textLimits!, maxLines: 2 } },
      { textLimits: { ...panel.textLimits!, maxSize: 29 } },
      { textLimits: { ...panel.textLimits!, minSize: 17 } },
      { textLimits: { ...panel.textLimits!, minSize: 29 } },
      { textLimits: { ...panel.textLimits!, maxCharacters: 0 } },
      { textLimits: { ...panel.textLimits!, minSize: Number.NaN } },
      { textLimits: { ...panel.textLimits!, maxSize: 24.5 } },
    ] as Partial<StudioZone>[]) expect(canPersonalize(configured(override)), JSON.stringify(override)).toBe(false);
    expect(canPersonalize(configured({ tools: ['text'] }))).toBe(true);
    expect(canUseStudioTool(configured({ tools: ['text'] }), 'panel', 'ai')).toBe(false);
    expect(canPersonalize(configured({ tools: ['pattern'], fonts: [], textLimits: undefined }))).toBe(true);
  });
});
