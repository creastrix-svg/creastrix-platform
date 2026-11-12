import type { CompositionSession, CompositionDocument } from '../design/studioComposition';
import { initialStudioComposition } from '../design/studioComposition';
import { base64Bytes, loadDesign, readDesign, record, saveDesign, studioTargets } from './designSnapshot';
import type { SavedDesign } from './designSnapshot';
import { cartLineId } from '../../demoStore';
import type { CartLine, ProductDemoState } from '../../demoStore';
import { cartSkuStock, isCartSku } from '../../cartSku';
import { catalogReady, connectedMode, isCatalogKey, isReservedCartLineId } from '../../catalog/localCatalog';
import { isVariantId } from '../../fixtures/loop';
import type { CreativeSession, CreativeDraft } from './studioCreativeContext';
import { emptyCreativeDraft } from './studioCreativeContext';
import type { LoadedStudioFont, createStudioFontLibrary, StudioFontId, BuiltinStudioFont } from '../design/studioFonts';
import { builtinStudioFonts } from '../design/studioFonts';
import { rasterDimensions, STUDIO_NORMALIZED_IMAGE_BYTES } from '../design/studioImage';
import type { StudioImage } from '../design/studioImage';

export const MAX_LOCAL_DESIGN_CHARS = 24 * 1024 * 1024;
const MAX_RASTER_CHARS = 22 + 4 * Math.ceil(STUDIO_NORMALIZED_IMAGE_BYTES / 3);
const MAX_CONNECTED_UNITS_PER_SKU = 1_000_000;
export type UnavailableCartLine = Readonly<{ variant: string; quantity: number }>;
export type RestoredStudio = { composition: CompositionSession; drafts: CreativeSession['drafts']; applied: CreativeSession['applied']; customFonts: LoadedStudioFont[]; lines: CartLine[]; unavailableLines: UnavailableCartLine[]; serial: number; fontRemap?: Record<string, StudioFontId> };
const creativeData = (source: CreativeSession['drafts']) => Object.fromEntries(Object.entries(source).map(([key, value]) => [key, { pattern: value!.pattern, prompt: value!.prompt }]));

/** One atomic, bounded record. Undo/redo, tombstones, auth state and checkout
 * contact data are deliberately omitted; the existing preferences key is untouched. */
export function serializeStudio(composition: CompositionSession, creative: Pick<CreativeSession, 'drafts' | 'applied' | 'customFonts'>, cart: Pick<ProductDemoState, 'cart' | 'cartSerial'>, unavailableLines: readonly UnavailableCartLine[] = []): string {
  const assets: string[] = [], indexes = new Map<string, number>();
  const fontIds = new Set(creative.customFonts.filter(font => font.resource?.data).map(font => font.id));
  let pixels = 0;
  const pack = (design: SavedDesign | undefined) => design && ({ objects: design.objects.map(object => {
    if (object.kind !== 'image') {
      if (!builtinStudioFonts.includes(object.value.font as BuiltinStudioFont) && !fontIds.has(object.value.font)) throw new Error('STUDIO_STORAGE_FONT_REFERENCE');
      return object;
    }
    const image = object.value.image;
    if (!indexes.has(image.src)) {
      if (!image.src.startsWith('data:image/png;base64,') || image.src.length > MAX_RASTER_CHARS) throw new Error('STUDIO_STORAGE_IMAGE');
      const size = rasterDimensions(base64Bytes(image.src.slice(22, 22 + 44)));
      pixels += size.width * size.height;
      if (size.width > 1536 || size.height > 1536 || pixels > 64_000_000) throw new Error('STUDIO_STORAGE_PIXELS');
      indexes.set(image.src, assets.length); assets.push(image.src);
    }
    return { ...object, value: { ...object.value, image: { asset: indexes.get(image.src), aspect: image.aspect } } };
  }) });
  const value = JSON.stringify({ version: 1,
    drafts: Object.fromEntries(studioTargets.flatMap(key => composition.histories[key] ? [[key, pack(saveDesign(composition.histories[key]!.present))]] : [])),
    applied: Object.fromEntries(studioTargets.flatMap(key => composition.applied[key] ? [[key, pack(saveDesign(composition.applied[key]))]] : [])),
    creativeDrafts: creativeData(creative.drafts), creativeApplied: creativeData(creative.applied),
    fonts: creative.customFonts.map(font => ({ id: font.id, label: font.label, resource: font.resource })),
    lines: [...cart.cart.map(line => ({ ...line, ...(line.design ? { design: pack(line.design) } : {}) })), ...unavailableLines], serial: cart.cartSerial, assets,
  });
  if (value.length > MAX_LOCAL_DESIGN_CHARS || assets.length > 48) throw new Error('STUDIO_STORAGE_LIMIT');
  return value;
}

export async function restoreStudio(raw: string, fonts: ReturnType<typeof createStudioFontLibrary>, signal?: AbortSignal): Promise<RestoredStudio> {
  if (raw.length > MAX_LOCAL_DESIGN_CHARS) throw new Error('STUDIO_STORAGE_LIMIT');
  const data = record(JSON.parse(raw));
  if (!Array.isArray(data.assets) || data.assets.length > 48) throw new Error('STUDIO_STORAGE_ASSETS');
  let totalPixels = 0;
  const assets = data.assets.map(src => {
    if (typeof src !== 'string' || src.length > MAX_RASTER_CHARS || !/^data:image\/png;base64,[A-Za-z0-9+/]+={0,2}$/.test(src)) throw new Error('STUDIO_STORAGE_IMAGE');
    const size = rasterDimensions(base64Bytes(src.split(',')[1]));
    totalPixels += size.width * size.height;
    if (size.width > 1536 || size.height > 1536 || totalPixels > 64_000_000) throw new Error('STUDIO_STORAGE_PIXELS');
    return src;
  });
  // Version 1 already bounds the entire record on both write and read. A
  // separate read-only line cap would make valid larger carts unrecoverable.
  if (data.version !== 1 || !Array.isArray(data.fonts) || data.fonts.length > 3 || !Array.isArray(data.lines)
    || !Number.isSafeInteger(data.serial) || (data.serial as number) < 1 || (data.serial as number) >= Number.MAX_SAFE_INTEGER) throw new Error('STUDIO_STORAGE_VERSION');
  const remap = new Map<string, StudioFontId>(), customFonts: LoadedStudioFont[] = [];
  for (const rawFont of data.fonts) {
    const font = record(rawFont), resource = record(font.resource);
    if (typeof font.id !== 'string' || !/^custom-[1-9][0-9]{0,11}$/.test(font.id) || remap.has(font.id)
      || typeof font.label !== 'string' || font.label.length > 96 || [...font.label].some(char => char.charCodeAt(0) < 32 || char.charCodeAt(0) >= 127 && char.charCodeAt(0) <= 159) || /[\u202a-\u202e\u2066-\u2069]/.test(font.label)
      || !['font.ttf', 'font.otf', 'font.woff2'].includes(String(resource.name)) || typeof resource.data !== 'string'
      || resource.data.length > 2_800_000 || !/^[A-Za-z0-9+/]+={0,2}$/.test(resource.data)) throw new Error('STUDIO_STORAGE_FONT');
    const loaded = await fonts.loadCustom(new File([base64Bytes(resource.data)], resource.name as string), { signal });
    customFonts.push({ ...loaded, label: font.label }); remap.set(font.id, loaded.id);
  }
  const cache = new Map<string, StudioImage>(), builtin = new Set<BuiltinStudioFont>();
  const design = (value: unknown) => {
    // Remap only the closed font field, never arbitrary strings or external URLs.
    const copy = record(value);
    if (!Array.isArray(copy.objects)) throw new Error('STUDIO_STORAGE_OBJECTS');
    const objects = copy.objects.map(raw => {
      const object = record(raw), v = record(object.value);
      if (object.kind === 'text') {
        const font = remap.get(String(v.font)) ?? v.font;
        if (builtinStudioFonts.includes(font as BuiltinStudioFont)) builtin.add(font as BuiltinStudioFont);
        else if (!customFonts.some(item => item.id === font)) throw new Error('STUDIO_STORAGE_FONT_REFERENCE');
        return { ...object, value: { ...v, font } };
      }
      const image = record(v.image);
      if (!Number.isInteger(image.asset) || !assets[image.asset as number]) throw new Error('STUDIO_STORAGE_ASSET_REFERENCE');
      return { ...object, value: { ...v, image: { src: assets[image.asset as number], aspect: image.aspect } } };
    });
    return readDesign({ objects });
  };
  let nextId = 7;
  const documents = async (raw: unknown) => {
    const source = record(raw), result: Partial<Record<typeof studioTargets[number], CompositionDocument>> = {};
    if (Object.keys(source).some(key => !studioTargets.includes(key as typeof studioTargets[number]))) throw new Error('STUDIO_STORAGE_TARGET');
    for (const target of studioTargets) if (source[target]) {
      const saved = design(source[target]);
      if (target.startsWith('lamp') && saved.objects.some(object => object.kind === 'text' && (target === 'lamp-panel' ? object.value.font !== 'stencil' : !['sans', 'serif', 'mono'].includes(object.value.font)))) throw new Error('STUDIO_STORAGE_ZONE');
      result[target] = await loadDesign(saved, cache, signal);
      for (const object of saved.objects) nextId = Math.max(nextId, Number(object.id.split('-').at(-1)) + 1);
    }
    return result;
  };
  const drafts = await documents(data.drafts), applied = await documents(data.applied);
  const lines: CartLine[] = [], unavailableLines: UnavailableCartLine[] = [], ids = new Set<string>();
  const unitsBySku = new Map<string, number>();
  let serial = data.serial as number;
  for (const rawLine of data.lines) {
    const line = record(rawLine);
    if (typeof line.quantity !== 'number' || !Number.isSafeInteger(line.quantity) || line.quantity < 1) throw new Error('STUDIO_STORAGE_CART');
    if (!isCartSku(line.variant)) {
      // Version 1 stored SKU and units, not proof of a prior offer. Preserve
      // a well-formed unlisted row as inert data; never infer a price or link.
      if (!connectedMode || !catalogReady() || !isCatalogKey(line.variant) || isReservedCartLineId(line.variant)
        || Object.keys(line).some(key => key !== 'variant' && key !== 'quantity')) throw new Error('STUDIO_STORAGE_CART');
      if (line.quantity > MAX_CONNECTED_UNITS_PER_SKU) throw new Error('STUDIO_STORAGE_STOCK');
      if (ids.has(line.variant)) throw new Error('STUDIO_STORAGE_DUPLICATE');
      ids.add(line.variant); unavailableLines.push({ variant: line.variant, quantity: line.quantity });
      continue;
    }
    if (!isVariantId(line.variant) && line.design !== undefined) throw new Error('STUDIO_STORAGE_CART');
    const saved = line.design ? design(line.design) : undefined;
    if (saved && (typeof line.id !== 'string' || !/^design-[1-9][0-9]{0,11}$/.test(line.id))) throw new Error('STUDIO_STORAGE_LINE_ID');
    if (!saved && line.id !== undefined) throw new Error('STUDIO_STORAGE_LINE_ID');
    const next: CartLine = { variant: line.variant, quantity: line.quantity, ...(saved ? { id: line.id as string, design: saved } : {}) };
    const id = cartLineId(next);
    if (ids.has(id)) throw new Error('STUDIO_STORAGE_DUPLICATE');
    ids.add(id); lines.push(next);
    // A catalog refresh can reduce availability after a valid save. Preserve
    // the buyer's requested units and designs; the cart gates checkout until
    // shortages are resolved. The fixed offline preview has immutable stock.
    const units = (unitsBySku.get(next.variant) ?? 0) + next.quantity;
    if (units > (connectedMode ? MAX_CONNECTED_UNITS_PER_SKU : cartSkuStock(next.variant))) throw new Error('STUDIO_STORAGE_STOCK');
    unitsBySku.set(next.variant, units);
    if (saved) { await loadDesign(saved, cache, signal); serial = Math.max(serial, Number(id.slice(7)) + 1); }
  }
  for (const id of builtin) await fonts.loadBuiltin(id, { signal });
  const creative = (raw: unknown): CreativeSession['drafts'] => {
    const source = record(raw), result: CreativeSession['drafts'] = {};
    if (Object.keys(source).some(key => !studioTargets.includes(key as typeof studioTargets[number]))) throw new Error('STUDIO_STORAGE_TARGET');
    for (const target of studioTargets) if (source[target]) {
      const value = record(source[target]);
      if (!['botanical', 'forest', 'orbit'].includes(String(value.pattern)) || typeof value.prompt !== 'string' || value.prompt.length > 600) throw new Error('STUDIO_STORAGE_CREATIVE');
      result[target] = { ...emptyCreativeDraft(), pattern: value.pattern as CreativeDraft['pattern'], prompt: value.prompt };
    }
    return result;
  };
  const composition = initialStudioComposition();
  return { composition: { ...composition, nextId, applied, histories: { ...composition.histories, ...Object.fromEntries(Object.entries(drafts).map(([target, present]) => [target, { present, past: [], future: [], group: null }])) } },
    drafts: creative(data.creativeDrafts), applied: creative(data.creativeApplied), customFonts, lines, unavailableLines, serial, fontRemap: Object.fromEntries(remap) };
}

/** No cookies, network or account identifier. Completion means transaction
 * completion, not merely a successful request; a timeout aborts an owned write. */
let storageQueue: Promise<unknown> = Promise.resolve();
export function studioStorage(operation: 'read' | 'write', value?: string, expected?: string | null): Promise<string | null> {
  const next = storageQueue.then(() => storageOperation(operation, value, expected));
  storageQueue = next.catch(() => undefined);
  return next;
}
function storageOperation(operation: 'read' | 'write', value?: string, expected?: string | null): Promise<string | null> {
  return new Promise((resolve, reject) => {
    let db: IDBDatabase | undefined, transaction: IDBTransaction | undefined, settled = false, result: string | null = null;
    const finish = (error?: Error) => {
      if (settled) return; settled = true; clearTimeout(timer); db?.close();
      if (error) reject(error); else resolve(result);
    };
    const timer = setTimeout(() => { try { transaction?.abort(); } finally { finish(new Error('STUDIO_STORAGE_TIMEOUT')); } }, 5000);
    try {
      const open = indexedDB.open('creastrix-studio-local', 1);
      open.onupgradeneeded = () => { if (!open.result.objectStoreNames.contains('snapshot')) open.result.createObjectStore('snapshot'); };
      open.onerror = () => finish(new Error('STUDIO_STORAGE_UNAVAILABLE'));
      open.onblocked = () => finish(new Error('STUDIO_STORAGE_BLOCKED'));
      open.onsuccess = () => {
        db = open.result; if (settled) { db.close(); return; }
        db.onversionchange = () => db?.close();
        try {
          transaction = db.transaction('snapshot', operation === 'write' ? 'readwrite' : 'readonly');
          const request = transaction.objectStore('snapshot').get('current');
          request.onsuccess = () => {
            if (request.result !== undefined && typeof request.result !== 'string') { transaction?.abort(); return; }
            result = request.result ?? null;
            if (operation === 'write') {
              // A concurrent tab is not silently overwritten. The current tab
              // keeps its live work and reports that local saving failed.
              if (expected !== undefined && result !== expected) { transaction?.abort(); return; }
              transaction!.objectStore('snapshot').put(value, 'current');
            }
          };
          transaction.oncomplete = () => finish();
          transaction.onabort = transaction.onerror = () => finish(new Error('STUDIO_STORAGE_TRANSACTION'));
        } catch { finish(new Error('STUDIO_STORAGE_UNAVAILABLE')); }
      };
    } catch { finish(new Error('STUDIO_STORAGE_UNAVAILABLE')); }
  });
}
