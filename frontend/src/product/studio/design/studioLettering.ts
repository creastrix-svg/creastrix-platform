import { cleanLetteringText, letteringLayout } from './personalization';
import type { Lettering, MeasureLettering } from './personalization';
import { supportsStencilText } from './studioFonts';
import { canUseStudioFont, getStudioZone } from '../studioProducts';
import type { StudioProduct, StudioZoneId } from '../studioProducts';
import { lampBaseLetteringArea, lampCutTextArea, loopLetteringArea } from './studioGeometry';
import { studioArea } from './studioComposition';

export type StencilStatus = 'pending' | 'ready' | 'failed';
export type LetteringIssue = 'font' | 'unsupported' | 'loading' | 'limits' | 'fit';
const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value));

/** Validate all authored zones before applying a product, including hidden ones.
 * The cut band is a preview placement contract, not toolpath validation. Missing
 * stencil fonts never fall back to a solid font with unsupported closed counters.
 */
export function inspectZoneLettering(product: StudioProduct, zoneId: StudioZoneId, value: Lettering,
  measure: MeasureLettering, stencilStatus: StencilStatus) {
  const zone = getStudioZone(product, zoneId), cut = zone?.operation === 'cut';
  const permittedFont = canUseStudioFont(product, zoneId, value.font);
  const area = cut ? zone?.visualComposition ? studioArea('lamp-panel') : lampCutTextArea : product.id === 'lamp' && zoneId === 'base' ? lampBaseLetteringArea : loopLetteringArea;
  const finiteGeometry = [value.size, value.x, value.y].every(Number.isFinite);
  const safeValue = { ...value, font: permittedFont ? value.font : zone?.fonts[0] ?? 'sans', size: Number.isFinite(value.size) ? value.size : 24 };
  const layout = letteringLayout(safeValue, measure, area);
  const shown = { ...safeValue,
    x: zone?.allowPosition ? clamp(Number.isFinite(value.x) ? value.x : 240, layout.minX, layout.maxX) : 240,
    y: zone?.allowPosition ? clamp(Number.isFinite(value.y) ? value.y : 278, layout.minY, layout.maxY) : 278 };
  let issue: LetteringIssue | undefined = !zone?.tools.includes('text') || !finiteGeometry ? 'limits' : undefined;
  if (!issue && value.text.trim()) {
    if (!permittedFont || cut && stencilStatus === 'failed') issue = 'font';
    else if (cut && !supportsStencilText(value.text)) issue = 'unsupported';
    else if (cut && stencilStatus !== 'ready') issue = 'loading';
    else if (!zone?.textLimits || cleanLetteringText(value.text, zone.textLimits) !== value.text
      || value.size < zone.textLimits.minSize || value.size > zone.textLimits.maxSize) issue = 'limits';
    else if (!layout.fits) issue = 'fit';
  }
  return { shown, layout, issue, valid: !issue };
}
