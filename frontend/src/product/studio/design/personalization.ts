import type { VariantId } from '../../fixtures/loop';
import { isStudioFontId, studioFontFamily } from './studioFonts';
import type { StudioFontId } from './studioFonts';
import { lampBaseLetteringArea, lampCutTextArea, LETTERING_INSET, loopLetteringArea } from './studioGeometry';
import type { StudioArea } from './studioGeometry';
export { letteringFonts } from './studioFonts';

export type LetteringFont = StudioFontId;
export type LetteringTarget = VariantId | 'lamp' | 'lamp-panel';
export type LetteringColor = '#ffffff' | '#20251f' | '#b68a45';
export type Lettering = { text: string; font: LetteringFont; color: LetteringColor; size: number; x: number; y: number };
export const letteringColors: LetteringColor[] = ['#ffffff', '#20251f', '#b68a45'];
export const LETTERING_LIMIT = 32;
export const letteringArea = loopLetteringArea;

export function characters(text: string): string[] {
  return [...new Intl.Segmenter(undefined, { granularity: 'grapheme' }).segment(text)].map(part => part.segment);
}
export function cleanLetteringText(text: string, limits = { maxCharacters: LETTERING_LIMIT, maxLines: 2 }): string {
  // Control characters cannot become invisible layout instructions. Newlines alone
  // are permitted, with two lines and a bounded number of complete graphemes.
  const withoutControls = [...text.replace(/\r\n?/g, '\n')].filter(character => {
    const code = character.codePointAt(0)!;
    return character === '\n' || code >= 32 && (code < 127 || code > 159);
  }).join('');
  const lines = withoutControls.replace(/[\u202a-\u202e\u2066-\u2069]/g, '').split('\n').slice(0, limits.maxLines).join('\n');
  return characters(lines).slice(0, limits.maxCharacters).join('');
}
export function defaultLettering(variant: LetteringTarget): Lettering {
  if (variant === 'lamp-panel') return { text: '', font: 'stencil', color: '#20251f', size: 24, x: 240, y: 278 };
  return { text: '', font: 'sans', color: variant === 'lamp' || variant === 'chalk' || variant === 'cobalt-chalk' ? '#20251f' : '#ffffff', size: 24, x: 240, y: 278 };
}
export const sameLettering = (a: Lettering, b: Lettering) => a.text === b.text && a.font === b.font && a.color === b.color && a.size === b.size && a.x === b.x && a.y === b.y;
export function normalizeLettering(value: Lettering, target: LetteringTarget = 'cobalt'): Lettering {
  const bounded = (n: number, min: number, max: number, fallback: number) => Number.isFinite(n) ? Math.max(min, Math.min(max, n)) : fallback;
  const area = target === 'lamp' ? lampBaseLetteringArea : target === 'lamp-panel' ? lampCutTextArea : loopLetteringArea;
  const fallback = defaultLettering(target);
  // The reducer keeps a finite, target-specific envelope without requiring a
  // browser canvas. The zone inspector applies the measured text-size bounds.
  return {
    text: cleanLetteringText(value.text), font: isStudioFontId(value.font) ? value.font : fallback.font,
    color: letteringColors.includes(value.color) ? value.color : fallback.color,
    size: bounded(value.size, 18, 32, fallback.size),
    x: target === 'lamp-panel' ? fallback.x : bounded(value.x, area.left + LETTERING_INSET, area.right - LETTERING_INSET, fallback.x),
    y: target === 'lamp-panel' ? fallback.y : bounded(value.y, area.top + LETTERING_INSET, area.bottom - LETTERING_INSET, fallback.y),
  };
}

type History = { past: Lettering[]; present: Lettering; future: Lettering[]; group: string | null };
export type LetteringSession = { histories: Partial<Record<LetteringTarget, History>>; applied: Partial<Record<LetteringTarget, Lettering>> };
export type LetteringAction =
  | { type: 'edit'; variant: LetteringTarget; value: Lettering; group?: string }
  | { type: 'undo' | 'redo' | 'endGroup' | 'reset' | 'apply' | 'remove'; variant: LetteringTarget; allowEmpty?: boolean };
export const emptyLetteringSession = (): LetteringSession => ({ histories: {}, applied: {} });
export function letteringHistory(state: LetteringSession, variant: LetteringTarget): History {
  return state.histories[variant] ?? { past: [], present: defaultLettering(variant), future: [], group: null };
}
export function letteringReducer(state: LetteringSession, action: LetteringAction): LetteringSession {
  const history = letteringHistory(state, action.variant);
  const replace = (next: History) => ({ ...state, histories: { ...state.histories, [action.variant]: next } });
  if (action.type === 'endGroup') return history.group ? replace({ ...history, group: null }) : state;
  if (action.type === 'edit' || action.type === 'reset') {
    const value = action.type === 'edit' ? normalizeLettering(action.value, action.variant) : defaultLettering(action.variant);
    if (sameLettering(value, history.present)) return state;
    const group = action.type === 'edit' ? action.group ?? null : null;
    const past = group && group === history.group ? history.past : [...history.past, history.present].slice(-40);
    return replace({ present: value, past, future: [], group });
  }
  if (action.type === 'undo') {
    const present = history.past.at(-1); if (!present) return state;
    return replace({ present, past: history.past.slice(0, -1), future: [history.present, ...history.future].slice(0, 40), group: null });
  }
  if (action.type === 'redo') {
    const present = history.future[0]; if (!present) return state;
    return replace({ present, past: [...history.past, history.present].slice(-40), future: history.future.slice(1), group: null });
  }
  if (action.type === 'apply') {
    if (!history.present.text.trim() && !action.allowEmpty) return state;
    return { ...replace({ ...history, group: null }), applied: { ...state.applied, [action.variant]: { ...history.present } } };
  }
  const applied = { ...state.applied }; delete applied[action.variant];
  return { ...state, applied };
}

/** Numeric measurements retain the line-height fallback. A browser can supply
 * centred ink envelopes to include italic overhangs and tall custom glyphs. */
export type LetteringMeasurement = number | Readonly<{ width: number; height: number }>;
export type MeasureLettering = (text: string, font: string) => LetteringMeasurement;

/** Layout checks are preview-only, not a manufacturing or commercial decision.
 * Limits reach the complete area's inset edges; they never reuse another
 * product's centre or historical drag bounds. Invalid measurements fail closed
 * while returning finite positions for sliders and drawing fallbacks. */
export function letteringLayout(value: Lettering, measure: MeasureLettering, area: StudioArea = letteringArea) {
  const lines = value.text.split('\n');
  const validArea = Object.values(area).every(Number.isFinite) && area.left < area.right && area.top < area.bottom;
  const bounds = validArea ? area : letteringArea;
  const areaWidth = bounds.right - bounds.left, areaHeight = bounds.bottom - bounds.top;
  const validSize = Number.isFinite(value.size) && value.size > 0;
  const size = validSize ? value.size : 24, lineHeight = size * 1.2;
  const metrics = lines.map(line => {
    const measured = measure(line, `${size}px ${studioFontFamily(value.font)}`);
    return typeof measured === 'number' ? { width: measured, height: lineHeight } : measured;
  });
  const validMetrics = metrics.every(metric => Number.isFinite(metric.width) && metric.width >= 0 && Number.isFinite(metric.height) && metric.height >= 0);
  const measuredWidth = validMetrics ? Math.max(0, ...metrics.map(metric => metric.width)) : areaWidth;
  const measuredHeight = validMetrics ? (lines.length - 1) * lineHeight + Math.max(lineHeight, ...metrics.map(metric => metric.height)) : areaHeight;
  const width = Number.isFinite(measuredWidth) ? measuredWidth : areaWidth;
  const height = Number.isFinite(measuredHeight) ? measuredHeight : areaHeight;
  const horizontal = Math.max(0, (areaWidth - width) / 2 - LETTERING_INSET);
  const vertical = Math.max(0, (areaHeight - height) / 2 - LETTERING_INSET);
  const centerX = (bounds.left + bounds.right) / 2, centerY = (bounds.top + bounds.bottom) / 2;
  const fits = validArea && validSize && validMetrics && Number.isFinite(measuredWidth) && Number.isFinite(measuredHeight)
    && width <= areaWidth - LETTERING_INSET * 2 && height <= areaHeight - LETTERING_INSET * 2;
  return { lines, width, height, fits,
    minX: centerX - horizontal, maxX: centerX + horizontal, minY: centerY - vertical, maxY: centerY + vertical };
}
