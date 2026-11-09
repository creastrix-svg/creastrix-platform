import { readFileSync } from 'node:fs';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { initialStudioComposition } from './studioComposition';
import { defaultLettering } from './personalization';
import { saveDesign, readDesign, decodedDesign, loadDesign } from './designSnapshot';
import { createAppStore } from '../store';
import { cartAdded, cartHydrated, cartQuantityChanged } from './demoStore';
import { createStudioFontLibrary, isStudioFontId } from './studioFonts';
import { restoreStudio, serializeStudio, MAX_LOCAL_DESIGN_CHARS } from './studioStorage';
import { loadStudioImage, STUDIO_IMAGE_BYTES } from './studioImage';

const libraries: ReturnType<typeof createStudioFontLibrary>[] = [];
const library = () => { const fonts = createStudioFontLibrary(); libraries.push(fonts); return fonts; };
const emptyCreative = () => ({ drafts: {}, applied: {}, customFonts: [] });
const emptyCart = () => ({ cart: [], cartSerial: 1 });
function png(size = 200, padding = 0) {
  const bytes = new Uint8Array(32 + padding); bytes.set([137,80,78,71,13,10,26,10]); bytes.set([73,72,68,82], 12);
  const view = new DataView(bytes.buffer); view.setUint32(16, size); view.setUint32(20, size);
  return 'data:image/png;base64,' + Buffer.from(bytes).toString('base64');
}
beforeEach(() => {
  vi.stubGlobal('createImageBitmap', vi.fn(async () => ({ width: 200, height: 200, close: vi.fn() })));
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ drawImage: vi.fn() } as unknown as CanvasRenderingContext2D);
  vi.spyOn(HTMLCanvasElement.prototype, 'toDataURL').mockReturnValue(png());
  Object.defineProperty(document, 'fonts', { configurable: true, value: { add: vi.fn(), delete: vi.fn() } });
  vi.stubGlobal('FontFace', class { async load() { return this; } });
});
afterEach(() => { for (const fonts of libraries.splice(0)) fonts.dispose(); vi.unstubAllGlobals(); });
it('restores current/applied designs for every zone, but no undo history; new IDs exceed restored IDs', async () => {
  const state = initialStudioComposition();
  const doc = { selectedId: 'studio-object-70', objects: [{ id: 'studio-object-70', kind: 'text' as const, value: { ...defaultLettering('lamp-panel'), text: 'PANEL' } }] };
  const next = { ...state, histories: { ...state.histories, 'lamp-panel': { present: doc, past: [doc], future: [doc], group: 'text' } }, applied: { 'lamp-panel': doc } };
  const raw = serializeStudio(next, { ...emptyCreative(), drafts: { 'lamp-panel': { artwork: { image: null, size: 85, x: 240, y: 278 }, content: 'pattern', pattern: 'forest', bridges: true, prompt: 'Local idea' } } }, emptyCart());
  const restored = await restoreStudio(raw, library());
  expect(restored.composition.nextId).toBe(71);
  expect(restored.composition.histories['lamp-panel']?.present).toEqual(doc);
  expect(restored.composition.histories['lamp-panel']?.past).toEqual([]);
  expect(restored.composition.histories['lamp-panel']?.future).toEqual([]);
  expect(restored.composition.histories['lamp-panel']?.group).toBeNull();
  expect(restored.composition.applied['lamp-panel']).toEqual(doc);
  expect(restored.drafts['lamp-panel']?.pattern).toBe('forest');
  expect(restored.composition.histories.lamp).toBeDefined();
});
it('deduplicates a large raster across draft/applied/cart, restores pixels through the loader, and allocates fresh image IDs', async () => {
  // A bounded-size fixture tests the envelope, not the browser PNG decoder.
  const src = png(200, 700_000), image = { id: 500, src, canvas: document.createElement('canvas'), aspect: 1 };
  const doc = { selectedId: 'studio-object-1', objects: [{ id: 'studio-object-1', kind: 'image' as const, fit: 'cover' as const, value: { image, size: 85, x: 240, y: 278 } }] };
  const state = initialStudioComposition(), design = saveDesign(doc)!;
  const raw = serializeStudio({ ...state, histories: { ...state.histories, cobalt: { present: doc, past: [], future: [], group: null } }, applied: { cobalt: doc } }, emptyCreative(),
    { cart: [1,2,3].map(i => ({ id: `design-${i}`, variant: 'cobalt' as const, quantity: 1, design })), cartSerial: 4 });
  expect(JSON.parse(raw).assets).toEqual([src]);
  expect(raw.length).toBeLessThan(src.length + 6000);
  const restored = await restoreStudio(raw, library());
  expect(createImageBitmap).toHaveBeenCalledTimes(1);
  const loaded = restored.composition.histories.cobalt!.present.objects[0];
  expect(loaded.kind).toBe('image');
  if (loaded.kind !== 'image') throw new Error('Wrong fixture');
  expect(loaded.value.image?.canvas).toBeInstanceOf(HTMLCanvasElement);
  expect(loaded.value.image?.id).not.toBe(500);
  const newer = await loadStudioImage(new File([Uint8Array.from(atob(png().split(',')[1]), char => char.charCodeAt(0))], 'new.png', { type: 'image/png' }));
  expect(newer.id).toBeGreaterThan(loaded.value.image!.id);
  expect(restored.lines.map(line => line.id)).toEqual(['design-1', 'design-2', 'design-3']);
  expect(restored.lines[0].design?.objects[0]).toEqual(design.objects[0]);
});
it('retains custom font bytes and label and remaps every draft/applied/cart reference without collisions', async () => {
  const before = library(), font = await before.loadCustom(new File([readFileSync('public/fonts/AllertaStencil.ttf')], 'Owner Font.ttf'));
  const doc = { selectedId: 'studio-object-1', objects: [{ id: 'studio-object-1', kind: 'text' as const, value: { ...defaultLettering('cobalt'), text: 'FONT', font: font.id } }] };
  const state = initialStudioComposition();
  const raw = serializeStudio({ ...state, histories: { ...state.histories, cobalt: { present: doc, past: [], future: [], group: null } }, applied: { cobalt: doc } }, { ...emptyCreative(), customFonts: [font] }, { cart: [{ id: 'design-1', variant: 'cobalt', quantity: 1, design: saveDesign(doc) }], cartSerial: 2 });
  before.dispose(); expect(isStudioFontId(font.id)).toBe(false);
  const after = library(), restored = await restoreStudio(raw, after), id = restored.customFonts[0].id;
  expect(id).not.toBe(font.id); expect(isStudioFontId(id)).toBe(true);
  expect(restored.customFonts[0].resource).toEqual(font.resource);
  expect(restored.customFonts[0].label).toBe('Owner Font');
  for (const object of [restored.composition.histories.cobalt!.present.objects[0], restored.composition.applied.cobalt!.objects[0], restored.lines[0].design!.objects[0]]) {
    expect(object.value).toMatchObject({ font: id, text: 'FONT' });
  }
  const newer = await after.loadCustom(new File([readFileSync('public/fonts/AllertaStencil.ttf')], 'New.ttf'));
  expect(newer.id).not.toBe(id); expect(newer.id).not.toBe(font.id);
});
it('restores plain legacy-shaped lines without inventing a prior persisted cart schema', async () => {
  const raw = serializeStudio(initialStudioComposition(), emptyCreative(), { cart: [{ variant: 'chalk', quantity: 2 }], cartSerial: 1 });
  const restored = await restoreStudio(raw, library());
  expect(restored.lines).toEqual([{ variant: 'chalk', quantity: 2 }]);
});
it('round-trips both products without treating Tide as a blue Loop', async () => {
  const cart = [{ variant: 'cobalt' as const, quantity: 4 }, { variant: 'tide-cobalt' as const, quantity: 3 }];
  const raw = serializeStudio(initialStudioComposition(), emptyCreative(), { cart, cartSerial: 1 });
  expect((await restoreStudio(raw, library())).lines).toEqual(cart);
});
it('restores normalized PNG above the compressed upload limit without relaxing the public uploader', async () => {
  const src = png(1536, STUDIO_IMAGE_BYTES + 100), image = { id: 900, src, canvas: document.createElement('canvas'), aspect: 1 };
  const doc = { selectedId: 'studio-object-1', objects: [{ id: 'studio-object-1', kind: 'image' as const, fit: 'cover' as const, value: { image, size: 85, x: 240, y: 278 } }] };
  const raw = serializeStudio({ ...initialStudioComposition(), applied: { cobalt: doc } }, emptyCreative(), emptyCart());
  const file = new File([Buffer.from(src.split(',')[1], 'base64')], 'original.png', { type: 'image/png' });
  await expect(loadStudioImage(file)).rejects.toMatchObject({ code: 'size' });
  vi.mocked(createImageBitmap).mockResolvedValue({ width: 1536, height: 1536, close: vi.fn() } as unknown as ImageBitmap);
  const restored = await restoreStudio(raw, library());
  expect(restored.composition.applied.cobalt!.objects[0].kind).toBe('image');
  expect(createImageBitmap).toHaveBeenCalledTimes(1);
});
it('round-trips complete graphemes rather than rejecting valid multi-codepoint text', async () => {
  const text = 'e\u0301'.repeat(32), doc = { objects: [{ id: 'studio-object-1', kind: 'text' as const, value: { ...defaultLettering('cobalt'), text } }], selectedId: 'studio-object-1' };
  const raw = serializeStudio({ ...initialStudioComposition(), applied: { cobalt: doc } }, emptyCreative(), emptyCart());
  expect((await restoreStudio(raw, library())).composition.applied.cobalt!.objects[0].value).toMatchObject({ text });
});
it.each([
  (v: Record<string, unknown>) => { v.version = 999; },
  (v: Record<string, unknown>) => { v.lines = [{ variant: 'chalk', quantity: 999 }]; },
  (v: Record<string, unknown>) => { v.lines = [{ variant: 'tide', quantity: 1 }]; },
  (v: Record<string, unknown>) => { v.lines = [{ variant: 'tide-cobalt', quantity: 19 }]; },
  (v: Record<string, unknown>) => { v.lines = [{ variant: 'tide-cobalt', quantity: 1, id: 'design-1', design: { objects: [] } }]; },
  (v: Record<string, unknown>) => { v.lines = [{ variant: 'chalk', quantity: 1 }, { variant: 'chalk', quantity: 1 }]; },
  (v: Record<string, unknown>) => { v.assets = ['https://example.com/image.png']; },
  (v: Record<string, unknown>) => { v.assets = ['data:image/svg+xml;base64,PHN2Zy8+']; },
  (v: Record<string, unknown>) => { v.fonts = [{ id: 'custom-1', label: 'X', resource: { name: 'font.svg', data: 'abcd' } }]; },
])('rejects corrupted/untrusted or out-of-bounds storage without writes (%#)', async mutate => {
  const raw = JSON.parse(serializeStudio(initialStudioComposition(), emptyCreative(), emptyCart())); mutate(raw);
  await expect(restoreStudio(JSON.stringify(raw), library())).rejects.toThrow();
});
it('rejects dangling assets, unknown fonts and executable image values in design records', () => {
  const base = { objects: [{ id: 'studio-object-1', kind: 'text', value: { ...defaultLettering('cobalt'), font: 'url(https://bad.example/font)' } }] };
  expect(() => readDesign(base)).toThrow();
  expect(() => readDesign({ objects: [{ id: 'studio-object-1', kind: 'image', fit: 'free', value: { x: 240, y: 278, size: 85, image: { src: 'javascript:alert(1)', aspect: 1 } } }] })).toThrow();
});
it('bounds the whole envelope before restoration', async () => {
  await expect(restoreStudio(' '.repeat(MAX_LOCAL_DESIGN_CHARS + 1), library())).rejects.toThrow('STUDIO_STORAGE_LIMIT');
});
it('rejects bitmap decoding failure instead of returning a partial cart', async () => {
  const image = { id: 99, src: png(), canvas: document.createElement('canvas'), aspect: 1 };
  const doc = { selectedId: 'studio-object-1', objects: [{ id: 'studio-object-1', kind: 'image' as const, fit: 'free' as const, value: { image, size: 85, x: 240, y: 278 } }] };
  const raw = serializeStudio(initialStudioComposition(), emptyCreative(), { cart: [{ variant: 'chalk', quantity: 1 }, { id: 'design-1', variant: 'cobalt', quantity: 1, design: saveDesign(doc) }], cartSerial: 2 });
  vi.mocked(createImageBitmap).mockRejectedValue(new Error('decoder failure'));
  await expect(restoreStudio(raw, library())).rejects.toMatchObject({ code: 'decode' });
});
it('rejects custom font decoder failure without substituting a fallback design', async () => {
  const before = library(), font = await before.loadCustom(new File([readFileSync('public/fonts/AllertaStencil.ttf')], 'Valid.ttf'));
  const raw = serializeStudio(initialStudioComposition(), { ...emptyCreative(), customFonts: [font] }, emptyCart());
  before.dispose(); vi.stubGlobal('FontFace', class { async load() { throw new Error('unavailable'); } });
  await expect(restoreStudio(raw, library())).rejects.toMatchObject({ code: 'invalid' });
});
it('rebinds live cart font IDs after storefront remount without replacing current quantities or plain lines', async () => {
  const original = library(), font = await original.loadCustom(new File([readFileSync('public/fonts/AllertaStencil.ttf')], 'Owner.ttf'));
  const design = saveDesign({ objects: [{ id: 'studio-object-1', kind: 'text', value: { ...defaultLettering('cobalt'), font: font.id, text: 'LIVE' } }], selectedId: 'studio-object-1' })!;
  const store = createAppStore('en'); store.dispatch(cartAdded({ variant: 'cobalt', quantity: 1, design }));
  const raw = serializeStudio(initialStudioComposition(), { ...emptyCreative(), customFonts: [font] }, store.getState().productDemo);
  store.dispatch(cartQuantityChanged({ variant: 'cobalt', id: 'design-1', quantity: 3 }));
  store.dispatch(cartAdded({ variant: 'chalk', quantity: 2 }));
  original.dispose(); expect(decodedDesign(design)).toBeUndefined();
  const restored = await restoreStudio(raw, library());
  store.dispatch(cartHydrated({ lines: restored.lines, serial: restored.serial, fontRemap: restored.fontRemap }));
  const cart = store.getState().productDemo.cart;
  expect(cart.map(line => line.quantity)).toEqual([3, 2]);
  expect(cart[0].id).toBe('design-1'); expect(cart[1]).toEqual({ variant: 'chalk', quantity: 2 });
  expect(cart[0].design!.objects[0].value).toMatchObject({ font: restored.customFonts[0].id, text: 'LIVE' });
  expect(decodedDesign(cart[0].design!)).toBeDefined();
});
it('does not adopt a different tab’s font bytes merely because its stored runtime ID matches', async () => {
  const original = library(), font = await original.loadCustom(new File([readFileSync('public/fonts/AllertaStencil.ttf')], 'Owner.ttf'));
  const design = saveDesign({ objects: [{ id: 'studio-object-1', kind: 'text', value: { ...defaultLettering('cobalt'), font: font.id, text: 'KEEP' } }], selectedId: 'studio-object-1' })!;
  const store = createAppStore('en'); store.dispatch(cartAdded({ variant: 'cobalt', quantity: 1, design }));
  const stored = JSON.parse(serializeStudio(initialStudioComposition(), { ...emptyCreative(), customFonts: [font] }, store.getState().productDemo));
  stored.fonts[0].resource.data = readFileSync('public/fonts/Caveat.ttf').toString('base64');
  original.dispose();
  const restored = await restoreStudio(JSON.stringify(stored), library());
  store.dispatch(cartHydrated({ lines: restored.lines, serial: restored.serial, fontRemap: restored.fontRemap }));
  const retained = store.getState().productDemo.cart[0].design!;
  expect(retained.objects[0].value).toMatchObject({ text: 'KEEP', font: font.id });
  expect(decodedDesign(retained)).toBeUndefined();
  await expect(loadDesign(retained)).rejects.toThrow('STUDIO_STORAGE_FONT_REFERENCE');
  expect(() => serializeStudio(restored.composition, restored, store.getState().productDemo)).toThrow('STUDIO_STORAGE_FONT_REFERENCE');
});
