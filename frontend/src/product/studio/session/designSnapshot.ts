import type { Lettering, LetteringTarget } from '../design/personalization';
import type { CompositionDocument, CompositionSession, StudioObject, ImageFit } from '../design/studioComposition';
import { initialStudioComposition } from '../design/studioComposition';
import { loadNormalizedStudioImage, STUDIO_NORMALIZED_IMAGE_BYTES } from '../design/studioImage';
import { cleanLetteringText } from '../design/personalization';
import type { StudioImage } from '../design/studioImage';
import { isStudioFontId, studioFontResource } from '../design/studioFonts';
import type { StudioFontId } from '../design/studioFonts';
import { permanentCatalogMode } from '../../catalog/permanentCatalog';
import { transferableLoopFont } from '../design/studioFonts';

type SavedPicture = { src: string; aspect: number };
export type SavedObject = { id: string; kind: 'text'; value: Lettering }
  | { id: string; kind: 'image'; fit: ImageFit; value: { image: SavedPicture; size: number; x: number; y: number } };
export type SavedDesign = { objects: SavedObject[] };
const decoded = new WeakMap<SavedDesign, CompositionDocument>();
const fontSources = new WeakMap<SavedDesign, Map<StudioFontId, string>>();
function rememberFonts(design: SavedDesign) {
  fontSources.set(design, new Map(design.objects.flatMap(object => {
    const resource = object.kind === 'text' ? studioFontResource(object.value.font) : undefined;
    return resource && object.kind === 'text' ? [[object.value.font, resource]] : [];
  })));
  return design;
}
/** Runtime IDs alone are not resource identities across tabs. A surviving live
 * line can bind to a restored font only when its exact bytes match its snapshot. */
export function rebindDesignFonts(design: SavedDesign, remap: Record<string, StudioFontId>): SavedDesign | undefined {
  const sources = fontSources.get(design);
  for (const object of design.objects) if (object.kind === 'text' && remap[object.value.font]) {
    const before = sources?.get(object.value.font), after = studioFontResource(remap[object.value.font]);
    if (!before || before !== after) return undefined;
  }
  return rememberFonts({ objects: design.objects.map(object => object.kind === 'text' && remap[object.value.font]
    ? { ...object, value: { ...object.value, font: remap[object.value.font] } } : object) });
}
export const decodedDesign = (design: SavedDesign) => design.objects.every(object => object.kind !== 'text' || isStudioFontId(object.value.font)) ? decoded.get(design) : undefined;
export const studioTargets = ['cobalt', 'coral', 'chalk', 'cobalt-chalk', 'lamp', 'lamp-panel'] as const;

/** Detached plain data, never a canvas, File, mutable editor array or account payload. */
export function saveDesign(document: CompositionDocument | undefined): SavedDesign | undefined {
  if (!document) return undefined;
  const saved: SavedDesign = { objects: document.objects.flatMap<SavedObject>(object => object.kind === 'text'
    ? [{ ...object, value: { ...object.value } }]
    : object.value.image ? [{ ...object, value: { ...object.value, image: { src: object.value.image.src, aspect: object.value.image.aspect } } }] : []) };
  decoded.set(saved, document); return rememberFonts(saved);
}
export const designKey = (design?: SavedDesign) => design ? JSON.stringify(design.objects.map(object => ({ kind: object.kind, value: object.value, ...(object.kind === 'image' ? { fit: object.fit } : {}) }))) : '';
export const visibleDesign = (design?: SavedDesign) => !!design?.objects.some(object => object.kind === 'image' || object.value.text.trim());
export const supportsAppliedFonts = (design:SavedDesign) => design.objects.every(o=>o.kind!=='text' || transferableLoopFont(o.value.font));
export function copyDesign(design: SavedDesign): SavedDesign {
  const copy = JSON.parse(JSON.stringify(design)) as SavedDesign, document = decoded.get(design);
  if (document) decoded.set(copy, document);
  const fonts = fontSources.get(design); if (fonts) fontSources.set(copy, fonts);
  return copy;
}

export function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('STUDIO_STORAGE_SHAPE');
  return value as Record<string, unknown>;
}
const numberIn = (value: unknown, min: number, max: number): value is number => typeof value === 'number' && Number.isFinite(value) && value >= min && value <= max;
export function readDesign(raw: unknown): SavedDesign {
  const value = record(raw);
  if (!Array.isArray(value.objects) || value.objects.length > 8) throw new Error('STUDIO_STORAGE_OBJECTS');
  const ids = new Set<string>();
  const objects = value.objects.map((raw): SavedObject => {
    const object = record(raw), v = record(object.value);
    if (typeof object.id !== 'string' || !/^studio-object-[1-9][0-9]{0,11}$/.test(object.id) || ids.has(object.id)) throw new Error('STUDIO_STORAGE_ID');
    ids.add(object.id);
    if (!numberIn(v.x, -1000, 1000) || !numberIn(v.y, -1000, 1000)) throw new Error('STUDIO_STORAGE_POSITION');
    if (object.kind === 'text') {
      if (typeof v.text !== 'string' || cleanLetteringText(v.text) !== v.text || !isStudioFontId(v.font)
        || !['#ffffff', '#20251f', '#b68a45'].includes(String(v.color)) || !numberIn(v.size, 18, 32)) throw new Error('STUDIO_STORAGE_TEXT');
      return { id: object.id, kind: 'text', value: { text: v.text, font: v.font, color: v.color as Lettering['color'], size: v.size, x: v.x, y: v.y } };
    }
    const image = record(v.image);
    if (object.kind !== 'image' || !['free', 'contain', 'cover'].includes(String(object.fit)) || !numberIn(v.size, 20, 150)
      || !numberIn(image.aspect, 1 / 8192, 8192) || typeof image.src !== 'string' || image.src.length > 22 + 4 * Math.ceil(STUDIO_NORMALIZED_IMAGE_BYTES / 3)
      || !/^data:image\/png;base64,[A-Za-z0-9+/]+={0,2}$/.test(image.src)) throw new Error('STUDIO_STORAGE_IMAGE');
    return { id: object.id, kind: 'image', fit: object.fit as ImageFit, value: { image: { src: image.src, aspect: image.aspect }, size: v.size, x: v.x, y: v.y } };
  });
  return rememberFonts({ objects });
}

export function base64Bytes(data: string) {
  const binary = atob(data), bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}
/** Decode into fresh runtime identities, sharing each immutable bitmap within a
 * restore. Persisted counters can never collide with later native file loads. */
export async function loadDesign(design: SavedDesign, cache = new Map<string, StudioImage>(), signal?: AbortSignal): Promise<CompositionDocument> {
  if (design.objects.some(object => object.kind === 'text' && !isStudioFontId(object.value.font))) throw new Error('STUDIO_STORAGE_FONT_REFERENCE');
  const existing = decoded.get(design); if (existing) return existing;
  const objects: StudioObject[] = [];
  for (const object of design.objects) {
    if (object.kind === 'text') { objects.push({ ...object, value: { ...object.value } }); continue; }
    const saved = object.value.image;
    let image = cache.get(saved.src);
    if (!image) {
      const pixels = await loadNormalizedStudioImage(new File([base64Bytes(saved.src.split(',')[1])], 'preview.png', { type: 'image/png' }), signal);
      // Canvas encoding is not stable across reloads. Keep the guest's original
      // normalized file as the transfer identity; the decoded pixels are for preview.
      image = permanentCatalogMode ? {...pixels,src:saved.src} : pixels;
      cache.set(saved.src, image);
    }
    if (Math.abs(image.aspect - saved.aspect) > .01) throw new Error('STUDIO_STORAGE_ASPECT');
    objects.push({ ...object, value: { ...object.value, image } });
  }
  const document = { objects, selectedId: objects[0]?.id ?? null };
  decoded.set(design, document); return document;
}
export function sessionWithDocument(target: LetteringTarget, document: CompositionDocument): CompositionSession {
  const state = initialStudioComposition();
  return { ...state, nextId: Math.max(state.nextId, ...document.objects.map(object => Number(object.id.split('-').at(-1)) + 1)),
    histories: { ...state.histories, [target]: { present: document, past: [], future: [], group: null } } };
}
