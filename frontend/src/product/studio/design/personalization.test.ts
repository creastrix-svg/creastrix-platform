import { describe, expect, it, vi } from 'vitest';
import type { VariantId } from '../../fixtures/loop';
import { characters, cleanLetteringText, defaultLettering, emptyLetteringSession, LETTERING_LIMIT, letteringArea, letteringFonts, letteringHistory, letteringLayout, letteringReducer, normalizeLettering, sameLettering } from './personalization';
import type { Lettering, LetteringSession, LetteringTarget } from './personalization';
import { lampBaseLetteringArea, lampBaseMapping, lampBasePoint, LETTERING_INSET } from './studioGeometry';

function edit(state: LetteringSession, variant: LetteringTarget, patch: Partial<Lettering>, group?: string) {
  return letteringReducer(state, { type: 'edit', variant, value: { ...letteringHistory(state, variant).present, ...patch }, group });
}

describe('memory-only lettering history', () => {
  it('groups a continuous edit into one undo step and redoes its final value', () => {
    const initial = emptyLetteringSession();
    let state = edit(initial, 'cobalt', { text: 'L' }, 'typing');
    state = edit(state, 'cobalt', { text: 'Lo' }, 'typing');
    state = edit(state, 'cobalt', { text: 'Loop' }, 'typing');
    expect(letteringHistory(state, 'cobalt').past).toEqual([defaultLettering('cobalt')]);
    expect(letteringHistory(state, 'cobalt').group).toBe('typing');
    const typed = letteringHistory(state, 'cobalt').present;
    state = letteringReducer(state, { type: 'undo', variant: 'cobalt' });
    expect(letteringHistory(state, 'cobalt').present).toEqual(defaultLettering('cobalt'));
    expect(letteringHistory(state, 'cobalt').future).toEqual([typed]);
    expect(letteringHistory(state, 'cobalt').group).toBeNull();
    state = letteringReducer(state, { type: 'redo', variant: 'cobalt' });
    expect(letteringHistory(state, 'cobalt').present).toEqual(typed);
    expect(letteringHistory(state, 'cobalt').future).toEqual([]);
    expect(initial).toEqual({ histories: {}, applied: {} });
  });

  it('starts another step after a group ends, even when its identifier is reused', () => {
    let state = edit(emptyLetteringSession(), 'coral', { x: 250 }, 'position');
    state = edit(state, 'coral', { x: 260 }, 'position');
    state = letteringReducer(state, { type: 'endGroup', variant: 'coral' });
    state = edit(state, 'coral', { x: 270 }, 'position');
    expect(letteringHistory(state, 'coral').past).toHaveLength(2);
    state = letteringReducer(state, { type: 'undo', variant: 'coral' });
    expect(letteringHistory(state, 'coral').present.x).toBe(260);
    state = letteringReducer(state, { type: 'undo', variant: 'coral' });
    expect(letteringHistory(state, 'coral').present.x).toBe(240);
  });

  it('starts a separate step for a different control and discards redo after a new edit', () => {
    let state = edit(emptyLetteringSession(), 'cobalt', { text: 'One' }, 'typing');
    state = edit(state, 'cobalt', { size: 28 }, 'size');
    expect(letteringHistory(state, 'cobalt').past).toHaveLength(2);
    state = letteringReducer(state, { type: 'undo', variant: 'cobalt' });
    expect(letteringHistory(state, 'cobalt').present).toMatchObject({ text: 'One', size: 24 });
    state = edit(state, 'cobalt', { text: 'Two' }, 'typing');
    expect(letteringHistory(state, 'cobalt').future).toEqual([]);
    expect(letteringReducer(state, { type: 'redo', variant: 'cobalt' })).toBe(state);
  });

  it('retains at most 40 reversible steps and preserves their ordering', () => {
    let state = emptyLetteringSession();
    for (let step = 1; step <= 45; step++) {
      state = edit(state, 'cobalt', { text: `Step ${step}` });
      expect(letteringHistory(state, 'cobalt').past.length).toBeLessThanOrEqual(40);
    }
    expect(letteringHistory(state, 'cobalt').past[0].text).toBe('Step 5');
    for (let step = 44; step >= 5; step--) {
      state = letteringReducer(state, { type: 'undo', variant: 'cobalt' });
      expect(letteringHistory(state, 'cobalt').present.text).toBe(`Step ${step}`);
      expect(letteringHistory(state, 'cobalt').future.length).toBeLessThanOrEqual(40);
    }
    expect(letteringHistory(state, 'cobalt').past).toEqual([]);
    expect(letteringHistory(state, 'cobalt').future).toHaveLength(40);
    expect(letteringReducer(state, { type: 'undo', variant: 'cobalt' })).toBe(state);
    for (let step = 6; step <= 45; step++) {
      state = letteringReducer(state, { type: 'redo', variant: 'cobalt' });
      expect(letteringHistory(state, 'cobalt').present.text).toBe(`Step ${step}`);
      expect(letteringHistory(state, 'cobalt').past.length).toBeLessThanOrEqual(40);
    }
    expect(letteringHistory(state, 'cobalt').future).toEqual([]);
    expect(letteringReducer(state, { type: 'redo', variant: 'cobalt' })).toBe(state);
  });

  it('preserves state identity for identical edits and unavailable history actions', () => {
    const initial = emptyLetteringSession();
    expect(edit(initial, 'chalk', {})).toBe(initial);
    for (const type of ['undo', 'redo', 'endGroup', 'reset', 'apply'] as const) {
      expect(letteringReducer(initial, { type, variant: 'chalk' })).toBe(initial);
    }
    const state = edit(initial, 'chalk', { text: 'Stay' });
    expect(edit(state, 'chalk', { text: 'Stay' })).toBe(state);
  });

  it('isolates drafts, histories and applied snapshots by product variant', () => {
    const variants: VariantId[] = ['cobalt', 'coral', 'chalk', 'cobalt-chalk'];
    let state = emptyLetteringSession();
    for (const variant of variants) {
      state = edit(state, variant, { text: variant });
      state = letteringReducer(state, { type: 'apply', variant });
    }
    const untouched = state;
    state = edit(state, 'chalk', { text: 'Edited chalk' });
    state = letteringReducer(state, { type: 'undo', variant: 'chalk' });
    state = letteringReducer(state, { type: 'reset', variant: 'chalk' });
    state = letteringReducer(state, { type: 'remove', variant: 'chalk' });
    expect(state.applied.chalk).toBeUndefined();
    expect(letteringHistory(state, 'chalk').present).toEqual(defaultLettering('chalk'));
    for (const variant of variants.filter(variant => variant !== 'chalk')) {
      expect(state.histories[variant]).toBe(untouched.histories[variant]);
      expect(state.applied[variant]).toBe(untouched.applied[variant]);
    }
    expect(emptyLetteringSession()).toEqual({ histories: {}, applied: {} });
  });

  it('makes reset undoable without replacing the last applied snapshot', () => {
    let state = edit(emptyLetteringSession(), 'chalk', { text: 'Mila', font: 'serif', color: '#b68a45', size: 28, x: 250, y: 270 }, 'typing');
    state = letteringReducer(state, { type: 'apply', variant: 'chalk' });
    const applied = state.applied.chalk;
    const draft = letteringHistory(state, 'chalk').present;
    state = letteringReducer(state, { type: 'reset', variant: 'chalk' });
    expect(letteringHistory(state, 'chalk').present).toEqual(defaultLettering('chalk'));
    expect(letteringHistory(state, 'chalk').group).toBeNull();
    expect(state.applied.chalk).toBe(applied);
    state = letteringReducer(state, { type: 'undo', variant: 'chalk' });
    expect(letteringHistory(state, 'chalk').present).toEqual(draft);
    state = letteringReducer(state, { type: 'redo', variant: 'chalk' });
    expect(letteringHistory(state, 'chalk').present).toEqual(defaultLettering('chalk'));
    expect(state.applied.chalk).toBe(applied);
  });

  it('copies on apply, ends the edit group and leaves applied text unchanged by later draft edits', () => {
    let state = edit(emptyLetteringSession(), 'cobalt', { text: 'First' }, 'typing');
    const draft = letteringHistory(state, 'cobalt').present;
    state = letteringReducer(state, { type: 'apply', variant: 'cobalt' });
    expect(state.applied.cobalt).toEqual(draft);
    expect(state.applied.cobalt).not.toBe(draft);
    expect(letteringHistory(state, 'cobalt').group).toBeNull();
    const firstApplied = state.applied.cobalt;
    state = edit(state, 'cobalt', { text: 'Second', color: '#b68a45' }, 'typing');
    expect(state.applied.cobalt).toBe(firstApplied);
    state = letteringReducer(state, { type: 'apply', variant: 'cobalt' });
    expect(state.applied.cobalt).toMatchObject({ text: 'Second', color: '#b68a45' });
    expect(firstApplied?.text).toBe('First');
    state = letteringReducer(state, { type: 'undo', variant: 'cobalt' });
    expect(letteringHistory(state, 'cobalt').present.text).toBe('First');
    expect(state.applied.cobalt?.text).toBe('Second');
  });

  it.each(['', '   ', '\n', ' \n '])('does not replace an applied snapshot with blank text %j', text => {
    let state = edit(emptyLetteringSession(), 'coral', { text: 'Keep me' });
    state = letteringReducer(state, { type: 'apply', variant: 'coral' });
    state = edit(state, 'coral', { text });
    expect(letteringReducer(state, { type: 'apply', variant: 'coral' })).toBe(state);
    expect(state.applied.coral?.text).toBe('Keep me');
  });

  it('removes only the applied snapshot and retains the editable draft and history', () => {
    let state = edit(emptyLetteringSession(), 'coral', { text: 'Still here' });
    state = letteringReducer(state, { type: 'apply', variant: 'coral' });
    const history = state.histories.coral;
    const appliedState = state;
    state = letteringReducer(state, { type: 'remove', variant: 'coral' });
    expect(state.applied).toEqual({});
    expect(state.histories.coral).toBe(history);
    expect(letteringHistory(state, 'coral').present.text).toBe('Still here');
    expect(appliedState.applied.coral?.text).toBe('Still here');
  });

  it('preserves the full base movement range through edit, apply, reset, undo and redo', () => {
    let state = edit(emptyLetteringSession(), 'lamp', { text: 'A', x: 80, y: 235 });
    expect(letteringHistory(state, 'lamp').present).toMatchObject({ x: 80, y: 235 });
    state = edit(state, 'lamp', { x: 400, y: 320 });
    state = letteringReducer(state, { type: 'apply', variant: 'lamp' });
    expect(state.applied.lamp).toMatchObject({ x: 400, y: 320 });
    state = letteringReducer(state, { type: 'reset', variant: 'lamp' });
    state = letteringReducer(state, { type: 'undo', variant: 'lamp' });
    expect(letteringHistory(state, 'lamp').present).toMatchObject({ x: 400, y: 320 });
    state = letteringReducer(state, { type: 'undo', variant: 'lamp' });
    expect(letteringHistory(state, 'lamp').present).toMatchObject({ x: 80, y: 235 });
    state = letteringReducer(state, { type: 'redo', variant: 'lamp' });
    expect(letteringHistory(state, 'lamp').present).toMatchObject({ x: 400, y: 320 });
    expect(state.applied.lamp).toMatchObject({ x: 400, y: 320 });
    expect(letteringHistory(state, 'cobalt').present).toEqual(defaultLettering('cobalt'));
  });
});

describe('lettering text and value limits', () => {
  it('counts full accented, flag and joined-emoji graphemes instead of code units', () => {
    const glyphs = ['A\u0301', '🇩🇪', '👩🏽‍💻'];
    expect(characters(glyphs.join(''))).toEqual(glyphs);
    expect(cleanLetteringText(glyphs.join(''))).toBe(glyphs.join(''));
  });

  it('caps text at 32 complete graphemes without splitting an emoji or combining mark', () => {
    const glyphs = ['👩🏽‍💻', 'e\u0301', '🇵🇱'];
    for (const glyph of glyphs) {
      const text = `${glyph.repeat(LETTERING_LIMIT)}extra`;
      expect(cleanLetteringText(text)).toBe(glyph.repeat(LETTERING_LIMIT));
      expect(characters(cleanLetteringText(text))).toHaveLength(32);
    }
  });

  it('normalizes Windows and old-Mac newlines and retains only the first two lines', () => {
    expect(cleanLetteringText('First\r\nSecond\rThird\nFourth')).toBe('First\nSecond');
    expect(cleanLetteringText('\nSecond\nThird')).toBe('\nSecond');
    expect(cleanLetteringText(`${'x'.repeat(30)}\n👩🏽‍💻extra`)).toBe(`${'x'.repeat(30)}\n👩🏽‍💻`);
  });

  it('removes C0 and DEL controls while preserving the permitted line break', () => {
    const controls = Array.from({ length: 32 }, (_, value) => String.fromCodePoint(value)).join('');
    expect(cleanLetteringText(`A${controls}B\u007fC`)).toBe('A\n');
    expect(cleanLetteringText('A\u0000\tB\u001b\u001fC\u007f\nD')).toBe('ABC\nD');
  });

  it('removes C1 controls rather than accepting invisible layout instructions', () => {
    const controls = Array.from({ length: 32 }, (_, value) => String.fromCodePoint(0x80 + value)).join('');
    expect(cleanLetteringText(`A${controls}B`)).toBe('AB');
  });

  it('removes bidi embedding, override and isolate controls but preserves multilingual text', () => {
    expect(cleanLetteringText('\u202aA\u202bB\u202cC\u202dD\u202eE\u2066F\u2067G\u2068H\u2069I')).toBe('ABCDEFGHI');
    expect(cleanLetteringText('Привет Łódź 東京 مرحبا')).toBe('Привет Łódź 東京 مرحبا');
  });

  it.each<VariantId>(['cobalt', 'coral', 'chalk', 'cobalt-chalk'])('provides finite defaults and suitable contrasting ink for %s', variant => {
    const value = defaultLettering(variant);
    expect(value).toEqual({ text: '', font: 'sans', color: variant === 'chalk' || variant === 'cobalt-chalk' ? '#20251f' : '#ffffff', size: 24, x: 240, y: 278 });
    expect([value.size, value.x, value.y].every(Number.isFinite)).toBe(true);
    expect(defaultLettering(variant)).not.toBe(value);
  });

  it.each([NaN, Infinity, -Infinity])('replaces nonfinite size and position values %s with finite defaults', invalid => {
    const value = normalizeLettering({ ...defaultLettering('cobalt'), size: invalid, x: invalid, y: invalid });
    expect(value).toEqual(defaultLettering('cobalt'));
  });

  it('clamps finite geometry, normalizes text and rejects non-allowlisted font and ink values', () => {
    const source = { text: 'A\tB\r\nC\nD', font: 'constructor', color: 'red', size: 100, x: -50, y: 500 } as unknown as Lettering;
    expect(normalizeLettering(source)).toEqual({ text: 'AB\nC', font: 'sans', color: '#ffffff', size: 32, x: 161, y: 328 });
    expect(normalizeLettering({ ...defaultLettering('cobalt'), size: -1, x: 500, y: -50 })).toMatchObject({ size: 18, x: 319, y: 227 });
    const allowed: Lettering = { text: 'Mila', font: 'serif', color: '#b68a45', size: 25.5, x: 241.5, y: 277.5 };
    expect(normalizeLettering(allowed)).toEqual(allowed);
    expect(source.size).toBe(100);
  });

  it('normalizes each target against its own area while the prepared cut band stays fixed', () => {
    const base = normalizeLettering({ ...defaultLettering('lamp'), x: -999, y: 999 }, 'lamp');
    expect(base.x).toBeCloseTo(lampBaseLetteringArea.left + LETTERING_INSET);
    expect(base.y).toBeCloseTo(lampBaseLetteringArea.bottom - LETTERING_INSET);
    const panel = normalizeLettering({ ...defaultLettering('lamp-panel'), x: 100, y: 335 }, 'lamp-panel');
    expect(panel).toMatchObject({ x: 240, y: 278, font: 'stencil' });
  });

  it('compares every preview field rather than only text', () => {
    const value = defaultLettering('cobalt');
    expect(sameLettering(value, { ...value })).toBe(true);
    const changes: Partial<Lettering>[] = [{ text: 'A' }, { font: 'mono' }, { color: '#b68a45' }, { size: 26 }, { x: 250 }, { y: 280 }];
    for (const change of changes) expect(sameLettering(value, { ...value, ...change })).toBe(false);
  });
});

describe('preview-only lettering layout', () => {
  it('measures each line using the selected size and family and uses the widest line', () => {
    const measure = vi.fn((text: string) => text === 'Short' ? 50 : 90);
    const value = { ...defaultLettering('cobalt'), text: 'Short\nLonger', font: 'mono' as const, size: 25 };
    const layout = letteringLayout(value, measure);
    expect(measure.mock.calls).toEqual([['Short', `25px ${letteringFonts.mono}`], ['Longer', `25px ${letteringFonts.mono}`]]);
    expect(layout.lines).toEqual(['Short', 'Longer']);
    expect(layout.width).toBe(90);
    expect(layout.height).toBe(60);
    expect(layout.fits).toBe(true);
  });

  it('includes the four-pixel inset on both sides of the fit boundary', () => {
    const value = { ...defaultLettering('cobalt'), text: 'Wide' };
    const exact = letteringLayout(value, () => letteringArea.right - letteringArea.left - 8);
    expect(exact.fits).toBe(true);
    expect(exact.minX).toBe(240);
    expect(exact.maxX).toBe(240);
    const overflowing = letteringLayout(value, () => letteringArea.right - letteringArea.left - 7.9);
    expect(overflowing.fits).toBe(false);
    expect(overflowing.minX).toBe(240);
    expect(overflowing.maxX).toBe(240);
  });

  it('tightens movement bounds for wider text and taller two-line text', () => {
    const value = { ...defaultLettering('cobalt'), text: 'Name' };
    const narrow = letteringLayout(value, () => 50);
    const wide = letteringLayout(value, () => 150);
    expect([narrow.minX, narrow.maxX]).toEqual([186, 294]);
    expect([wide.minX, wide.maxX]).toEqual([236, 244]);
    const twoLines = letteringLayout({ ...value, text: 'Name\nHere', size: 32 }, () => 50);
    expect(twoLines.minY).toBeGreaterThan(narrow.minY);
    expect(twoLines.maxY).toBeLessThan(narrow.maxY);
    expect(twoLines.minY).toBeCloseTo(265.4);
    expect(twoLines.maxY).toBeCloseTo(289.6);
    expect(twoLines.fits).toBe(true);
  });

  it('keeps a fitting text block inside the inset area at every movement limit', () => {
    for (const width of [0, 10, 70, 100, 150, 158]) {
      for (const size of [18, 24, 32]) {
        for (const text of ['A', 'A\nB']) {
          const layout = letteringLayout({ ...defaultLettering('cobalt'), text, size }, () => width);
          expect(layout.fits).toBe(true);
          expect(layout.minX).toBeLessThanOrEqual(layout.maxX);
          expect(layout.minY).toBeLessThanOrEqual(layout.maxY);
          expect(layout.minX - layout.width / 2).toBeGreaterThanOrEqual(letteringArea.left + 4);
          expect(layout.maxX + layout.width / 2).toBeLessThanOrEqual(letteringArea.right - 4);
          expect(layout.minY - layout.height / 2).toBeGreaterThanOrEqual(letteringArea.top + 4 - 1e-10);
          expect(layout.maxY + layout.height / 2).toBeLessThanOrEqual(letteringArea.bottom - 4 + 1e-10);
        }
      }
    }
  });

  it('returns finite bounds for empty text and detects a vertically overflowing block', () => {
    const empty = letteringLayout(defaultLettering('cobalt'), () => 0);
    expect(empty.lines).toEqual(['']);
    expect(empty.width).toBe(0);
    expect([empty.minX, empty.maxX, empty.minY, empty.maxY].every(Number.isFinite)).toBe(true);
    const tooTall = letteringLayout({ ...defaultLettering('cobalt'), text: 'A\nB\nC', size: 32 }, () => 10);
    expect(tooTall.fits).toBe(false);
    expect(tooTall.minY).toBe(277.5);
    expect(tooTall.maxY).toBe(277.5);
  });

  it('reaches all four edges of every prepared area for short, long and multiline text', () => {
    for (const area of [letteringArea, lampBaseLetteringArea]) {
      for (const width of [12, 50, 150]) {
        for (const text of ['A', 'A\nB']) {
          const layout = letteringLayout({ ...defaultLettering('lamp'), text }, () => width, area);
          expect(layout.fits).toBe(true);
          expect(layout.minX - layout.width / 2).toBeCloseTo(area.left + LETTERING_INSET);
          expect(layout.maxX + layout.width / 2).toBeCloseTo(area.right - LETTERING_INSET);
          expect(layout.minY - layout.height / 2).toBeCloseTo(area.top + LETTERING_INSET);
          expect(layout.maxY + layout.height / 2).toBeCloseTo(area.bottom - LETTERING_INSET);
        }
      }
    }
  });

  it('centres bounds in the supplied area rather than assuming the Loop centre', () => {
    const area = { left: 10, right: 110, top: 20, bottom: 120 };
    const layout = letteringLayout({ ...defaultLettering('lamp'), text: 'A', size: 20 }, () => 20, area);
    expect(layout).toMatchObject({ minX: 24, maxX: 96, minY: 36, maxY: 104, fits: true });
    expect(letteringLayout({ ...defaultLettering('lamp'), text: 'Too wide' }, () => 100, area))
      .toMatchObject({ minX: 60, maxX: 60, fits: false });
  });

  it.each([NaN, Infinity, -Infinity, -1])('fails closed with finite movement bounds for malformed metrics %s', metric => {
    const layout = letteringLayout({ ...defaultLettering('lamp'), text: 'A' }, () => metric, lampBaseLetteringArea);
    expect(layout.fits).toBe(false);
    expect([layout.width, layout.height, layout.minX, layout.maxX, layout.minY, layout.maxY].every(Number.isFinite)).toBe(true);
  });

  it('includes custom glyph overhang and height in the measured block and edge limits', () => {
    const value = { ...defaultLettering('lamp'), text: 'Italic\nTall', size: 20 };
    const layout = letteringLayout(value, text => text === 'Italic' ? { width: 110, height: 42 } : { width: 60, height: 50 }, lampBaseLetteringArea);
    expect(layout).toMatchObject({ width: 110, height: 74, fits: true });
    expect(layout.minX - 55).toBeCloseTo(lampBaseLetteringArea.left + 4);
    expect(layout.maxX + 55).toBeCloseTo(lampBaseLetteringArea.right - 4);
    expect(layout.minY - 37).toBeCloseTo(lampBaseLetteringArea.top + 4);
    expect(layout.maxY + 37).toBeCloseTo(lampBaseLetteringArea.bottom - 4);
    expect(letteringLayout(value, () => ({ width: 20, height: 140 }), lampBaseLetteringArea).fits).toBe(false);
  });

  it.each([{ width: NaN, height: 20 }, { width: 10, height: Infinity }, { width: -1, height: 20 }, { width: 10, height: -1 }])('rejects malformed ink envelopes %o with finite bounds', metrics => {
    const layout = letteringLayout({ ...defaultLettering('cobalt'), text: 'A' }, () => metrics);
    expect(layout.fits).toBe(false);
    expect([layout.width, layout.height, layout.minX, layout.maxX, layout.minY, layout.maxY].every(Number.isFinite)).toBe(true);
  });

  it('maps the entire allowed lamp base area to the texture face boundaries', () => {
    expect(lampBasePoint(lampBaseLetteringArea.left, lampBaseLetteringArea.top)).toEqual({ x: 0, y: 0 });
    const bottomRight = lampBasePoint(lampBaseLetteringArea.right, lampBaseLetteringArea.bottom);
    expect(bottomRight.x).toBeCloseTo(lampBaseMapping.width);
    expect(bottomRight.y).toBeCloseTo(lampBaseMapping.height);
  });
});
