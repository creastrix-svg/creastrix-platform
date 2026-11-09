import { describe, expect, it } from 'vitest';
import { cleanLetteringText, defaultLettering, emptyLetteringSession, letteringHistory, letteringReducer } from './personalization';
import { getStudioProduct } from './studioProducts';
import { inspectZoneLettering } from './studioLettering';
import { lampBaseLetteringArea, LETTERING_INSET } from './studioGeometry';
import { studioArea } from './studioComposition';
const lamp = getStudioProduct('lamp');
const measure = (text: string, font: string) => text.length * parseFloat(font) * .6;
const panel = { ...defaultLettering('lamp-panel'), text: 'MIROSLAV' };

describe('zone lettering contract', () => {
  it('has separate panel and base defaults, histories and applied snapshots', () => {
    let state = emptyLetteringSession();
    state = letteringReducer(state, { type: 'edit', variant: 'lamp-panel', value: panel });
    state = letteringReducer(state, { type: 'edit', variant: 'lamp', value: { ...defaultLettering('lamp'), text: 'Home' } });
    state = letteringReducer(state, { type: 'apply', variant: 'lamp-panel' });
    state = letteringReducer(state, { type: 'reset', variant: 'lamp-panel' });
    expect(letteringHistory(state, 'lamp-panel').present).toEqual(defaultLettering('lamp-panel'));
    expect(letteringHistory(state, 'lamp').present.text).toBe('Home');
    expect(state.applied['lamp-panel']).toEqual(panel);
    state = letteringReducer(state, { type: 'undo', variant: 'lamp-panel' });
    expect(letteringHistory(state, 'lamp-panel').present).toEqual(panel);
    expect(letteringHistory(state, 'lamp').present.text).toBe('Home');
  });

  it('bounds cut input by its configured length and single line without changing ordinary text', () => {
    expect(cleanLetteringText('ABCDEFGHIJKLMNO\nSECOND', lamp.zones[0].textLimits)).toBe('ABCDEFGHIJKLMN');
    expect(cleanLetteringText('ONE\nTWO\nTHREE')).toBe('ONE\nTWO');
    // Unsupported letters remain editable, never silently transliterated.
    expect(cleanLetteringText('Мир', lamp.zones[0].textLimits)).toBe('Мир');
  });

  it('requires a successfully loaded, supported stencil and a fitting reserved band', () => {
    const inspected = inspectZoneLettering(lamp, 'panel', panel, measure, 'ready');
    expect(inspected.valid).toBe(true);
    expect(inspected.shown.x).toBe(240);
    expect(inspected.shown.y - inspected.layout.height / 2).toBeGreaterThanOrEqual(studioArea('lamp-panel').top + LETTERING_INSET);
    expect(inspectZoneLettering(lamp, 'panel', panel, measure, 'pending').issue).toBe('loading');
    expect(inspectZoneLettering(lamp, 'panel', panel, measure, 'failed').issue).toBe('font');
    expect(inspectZoneLettering(lamp, 'panel', { ...panel, text: 'Мир' }, measure, 'ready').issue).toBe('unsupported');
    expect(inspectZoneLettering(lamp, 'panel', { ...panel, font: 'serif' }, measure, 'ready').issue).toBe('font');
    expect(inspectZoneLettering(lamp, 'panel', { ...panel, text: 'W'.repeat(14) }, measure, 'ready').issue).toBe('fit');
    expect(inspectZoneLettering(lamp, 'panel', { ...panel, size: 32 }, measure, 'ready').issue).toBe('limits');
    expect(inspectZoneLettering(lamp, 'panel', { ...panel, text: 'A\nB' }, measure, 'ready').issue).toBe('unsupported');
  });

  it('does not block an empty panel or engraving while stencil assets are unavailable', () => {
    expect(inspectZoneLettering(lamp, 'panel', defaultLettering('lamp-panel'), measure, 'failed').valid).toBe(true);
    expect(inspectZoneLettering(lamp, 'base', { ...defaultLettering('lamp'), text: 'Мир' }, measure, 'failed').valid).toBe(true);
    expect(inspectZoneLettering(lamp, 'base', { ...defaultLettering('lamp'), text: 'Home', font: 'handwritten' }, measure, 'ready').issue).toBe('font');
  });

  it('uses the whole base face and clamps each edge by measured text dimensions', () => {
    for (const text of ['A', 'HOME', 'ONE\nTWO']) {
      const value = { ...defaultLettering('lamp'), text };
      const topLeft = inspectZoneLettering(lamp, 'base', { ...value, x: -999, y: -999 }, measure, 'ready');
      const bottomRight = inspectZoneLettering(lamp, 'base', { ...value, x: 999, y: 999 }, measure, 'ready');
      expect(topLeft.valid).toBe(true);
      expect(bottomRight.valid).toBe(true);
      expect(topLeft.shown.x - topLeft.layout.width / 2).toBeCloseTo(lampBaseLetteringArea.left + LETTERING_INSET);
      expect(topLeft.shown.y - topLeft.layout.height / 2).toBeCloseTo(lampBaseLetteringArea.top + LETTERING_INSET);
      expect(bottomRight.shown.x + bottomRight.layout.width / 2).toBeCloseTo(lampBaseLetteringArea.right - LETTERING_INSET);
      expect(bottomRight.shown.y + bottomRight.layout.height / 2).toBeCloseTo(lampBaseLetteringArea.bottom - LETTERING_INSET);
    }
  });

  it('fits the full base and clamps visual panel composition to its reserved band', () => {
    expect(inspectZoneLettering(lamp, 'base', { ...defaultLettering('lamp'), text: 'LONG BASE LABEL' }, measure, 'ready').valid).toBe(true);
    const checked = inspectZoneLettering(lamp, 'panel', { ...panel, x: 60, y: 335 }, measure, 'ready');
    expect(checked.shown.x - checked.layout.width / 2).toBeCloseTo(studioArea('lamp-panel').left + LETTERING_INSET);
    expect(checked.shown.y + checked.layout.height / 2).toBeCloseTo(studioArea('lamp-panel').bottom - LETTERING_INSET);
  });

  it.each([NaN, Infinity, -Infinity])('rejects malformed geometry %s without leaking it into preview positions', invalid => {
    const checked = inspectZoneLettering(lamp, 'base', { ...defaultLettering('lamp'), text: 'A', size: invalid, x: invalid, y: invalid }, measure, 'ready');
    expect(checked.valid).toBe(false);
    expect([checked.shown.size, checked.shown.x, checked.shown.y].every(Number.isFinite)).toBe(true);
  });

  it('fails closed for missing or disabled zones even when the draft is empty', () => {
    expect(inspectZoneLettering(lamp, 'surface', defaultLettering('lamp'), measure, 'ready').valid).toBe(false);
    expect(inspectZoneLettering({ ...lamp, enabled: false }, 'base', defaultLettering('lamp'), measure, 'ready').valid).toBe(false);
  });
});
