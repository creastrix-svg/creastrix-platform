import type { LetteringColor } from './design/personalization';
import { connectedProduct } from '../catalog/localCatalog';
import { loopName } from '../fixtures/loop';
import { builtinStudioFonts, isStudioFontId, standardStudioFonts } from './design/studioFonts';
import type { BuiltinStudioFont } from './design/studioFonts';

export type StudioProductId = 'loop' | 'lamp';
export type StudioZoneId = 'surface' | 'panel' | 'base';
export type StudioTool = 'text' | 'image' | 'ai' | 'pattern';
export type StudioOperation = 'print' | 'engrave' | 'cut';
export type StudioTextLimits = Readonly<{ maxCharacters: number; maxLines: number; minSize: number; maxSize: number }>;

export type StudioZone = Readonly<{
  id: StudioZoneId;
  operation: StudioOperation;
  tools: readonly StudioTool[];
  fonts: readonly BuiltinStudioFont[];
  allowCustomFonts: boolean;
  inkColors: readonly LetteringColor[];
  allowPosition: boolean;
  /** Explicit visual composition capability; never an image-to-cut promise. */
  visualComposition?: boolean;
  textLimits?: StudioTextLimits;
}>;

export type StudioProduct = Readonly<{
  id: StudioProductId;
  name: string;
  readyMade: boolean;
  enabled: boolean;
  zones: readonly StudioZone[];
}>;

/** Local author-configured examples, not Listing data or manufacturing approval.
 * Content tools and physical operations are deliberately separate. A buyer may
 * select an offered tool, but cannot widen the configured zone's capabilities.
 */
export const studioProducts: Readonly<Record<StudioProductId, StudioProduct>> = {
  loop: {
    id: 'loop', get name() { return loopName(); }, readyMade: true,
    get enabled() { return connectedProduct('loop')?.personalizable ?? true; },
    zones: [{
      id: 'surface', operation: 'print', tools: ['text', 'image', 'ai'],
      fonts: standardStudioFonts, allowCustomFonts: true,
      inkColors: ['#ffffff', '#20251f', '#b68a45'], allowPosition: true,
      textLimits: { maxCharacters: 32, maxLines: 2, minSize: 18, maxSize: 32 },
    }],
  },
  lamp: {
    id: 'lamp', name: 'Luma', readyMade: false, enabled: true,
    zones: [{
      id: 'panel', operation: 'cut', tools: ['pattern', 'text', 'image', 'ai'],
      fonts: ['stencil'], allowCustomFonts: false, inkColors: [], allowPosition: true, visualComposition: true,
      textLimits: { maxCharacters: 14, maxLines: 1, minSize: 18, maxSize: 28 },
    }, {
      id: 'base', operation: 'engrave', tools: ['text', 'image'],
      fonts: ['sans', 'serif', 'mono'], allowCustomFonts: false,
      inkColors: ['#20251f'], allowPosition: true,
      textLimits: { maxCharacters: 32, maxLines: 2, minSize: 18, maxSize: 32 },
    }],
  },
};

export function getStudioProduct(id: StudioProductId): StudioProduct;
export function getStudioProduct(id: string): StudioProduct | undefined;
export function getStudioProduct(id: string): StudioProduct | undefined {
  return Object.hasOwn(studioProducts, id) ? studioProducts[id as StudioProductId] : undefined;
}

const allowedTools: Record<StudioOperation, readonly StudioTool[]> = {
  print: ['text', 'image', 'ai'],
  engrave: ['text', 'image', 'ai'],
  // This prototype has prepared cut patterns, not an arbitrary image-to-cut service.
  cut: ['pattern', 'text', 'ai'],
};
const zoneIds: readonly string[] = ['surface', 'panel', 'base'];
const inkColors: readonly string[] = ['#ffffff', '#20251f', '#b68a45'];

function isConfiguredZone(zone: StudioZone): boolean {
  const tools = Object.hasOwn(allowedTools, zone.operation) ? allowedTools[zone.operation] : undefined;
  const limits = zone.textLimits;
  const validText = zone.tools.includes('text')
    ? zone.fonts.length > 0 && !!limits
      && Object.values(limits).every(value => Number.isInteger(value))
      && limits.maxCharacters >= 1 && limits.maxCharacters <= 32
      && limits.maxLines >= 1 && limits.maxLines <= 2
      && limits.minSize >= 18 && limits.maxSize <= 32 && limits.minSize <= limits.maxSize
      && (zone.operation === 'cut' ? limits.maxCharacters <= 14 && limits.maxLines === 1 && limits.maxSize <= 28 : zone.inkColors.length > 0)
    : !limits && zone.fonts.length === 0 && !zone.allowCustomFonts;
  return zoneIds.includes(zone.id) && !!tools && zone.tools.length > 0
    && zone.tools.every(tool => tools.includes(tool) || zone.operation === 'cut' && zone.visualComposition === true && tool === 'image')
    && new Set(zone.tools).size === zone.tools.length
    && zone.fonts.every(font => builtinStudioFonts.includes(font))
    && new Set(zone.fonts).size === zone.fonts.length
    && zone.inkColors.every(color => inkColors.includes(color))
    && new Set(zone.inkColors).size === zone.inkColors.length
    && validText
    && (zone.operation !== 'cut' || !zone.allowCustomFonts && (!zone.allowPosition || zone.visualComposition === true) && zone.inkColors.length === 0 && zone.fonts.every(font => font === 'stencil'));
}

/** Missing or disabled configuration offers no editor, even for a direct URL.
 * This is a UI capability gate only; future server validation remains required.
 */
export function canPersonalize(product: StudioProduct | undefined): boolean {
  return !!product?.enabled && product.zones.length > 0
    && product.zones.every(isConfiguredZone)
    && new Set(product.zones.map(zone => zone.id)).size === product.zones.length;
}

export function getStudioZone(product: StudioProduct | undefined, id: string): StudioZone | undefined {
  return canPersonalize(product) ? product!.zones.find(zone => zone.id === id) : undefined;
}

export function canUseStudioTool(product: StudioProduct | undefined, zoneId: string, tool: string): boolean {
  return getStudioZone(product, zoneId)?.tools.some(allowed => allowed === tool) ?? false;
}

/** A retained draft or asynchronous font result must pass the same capability
 * check as a visible picker choice; hiding a control is not sufficient.
 */
export function canUseStudioFont(product: StudioProduct | undefined, zoneId: string, font: string): boolean {
  const zone = getStudioZone(product, zoneId);
  return !!zone?.tools.includes('text') && isStudioFontId(font)
    && (font.startsWith('custom-') ? zone.allowCustomFonts : zone.fonts.some(allowed => allowed === font));
}
