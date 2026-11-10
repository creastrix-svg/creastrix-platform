import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { builtinStudioFonts, standardStudioFonts, supportsStencilText, hasBundledStudioFont, createStudioFontLibrary, isStudioFontId, letteringFonts, MAX_CUSTOM_FONT_BYTES, STUDIO_FONT_TIMEOUT_MS, StudioFontError, studioFontFamily, validateStudioFontBytes } from './studioFonts';
import { defaultLettering, emptyLetteringSession, letteringHistory, letteringLayout, letteringReducer, normalizeLettering } from './personalization';

function fontBytes(extension = 'ttf'): ArrayBuffer {
  const bytes = new ArrayBuffer(extension === 'woff2' ? 64 : 32), view = new DataView(bytes);
  view.setUint32(0, extension === 'otf' ? 0x4f54544f : extension === 'woff2' ? 0x774f4632 : 0x00010000);
  if (extension === 'woff2') { view.setUint32(4, 0x00010000); view.setUint32(8, bytes.byteLength); view.setUint16(12, 1); view.setUint32(16, 512); }
  else view.setUint16(4, 1);
  return bytes;
}
const file = (name = 'My font.ttf') => new File([fontBytes(name.split('.').at(-1)?.toLowerCase())], name);
function deferred() { let resolve!: () => void, reject!: (reason: Error) => void; const promise = new Promise<void>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }

describe('bounded in-memory studio fonts', () => {
  const libraries: ReturnType<typeof createStudioFontLibrary>[] = [];
  const originalFonts = Object.getOwnPropertyDescriptor(document, 'fonts');
  let faces: { family: string; source: string | ArrayBuffer; descriptors: FontFaceDescriptors }[];
  let nativeLoad: ReturnType<typeof vi.fn<() => Promise<void>>>;
  let add: ReturnType<typeof vi.fn>, remove: ReturnType<typeof vi.fn>;
  const library = () => { const value = createStudioFontLibrary(); libraries.push(value); return value; };
  beforeEach(() => {
    faces = []; nativeLoad = vi.fn(async () => {}); add = vi.fn(); remove = vi.fn();
    Object.defineProperty(document, 'fonts', { configurable: true, value: { add, delete: remove } });
    vi.stubGlobal('FontFace', class {
      family: string; source: string | ArrayBuffer; descriptors: FontFaceDescriptors;
      constructor(family: string, source: string | ArrayBuffer, descriptors: FontFaceDescriptors) { this.family = family; this.source = source; this.descriptors = descriptors; faces.push(this); }
      async load() { await nativeLoad(); return this; }
    });
  });
  afterEach(() => {
    for (const value of libraries.splice(0)) value.dispose();
    if (originalFonts) Object.defineProperty(document, 'fonts', originalFonts);
    else Reflect.deleteProperty(document, 'fonts');
    vi.useRealTimers(); vi.unstubAllGlobals();
  });

  it('keeps six general styles separate from the configured stencil choice', async () => {
    expect(standardStudioFonts).toEqual(['sans', 'serif', 'mono', 'rounded', 'editorial', 'handwritten']);
    expect(builtinStudioFonts).toEqual([...standardStudioFonts, 'stencil']);
    for (const id of ['sans', 'serif', 'mono'] as const) expect(await library().loadBuiltin(id)).toEqual({ id, label: id, family: letteringFonts[id] });
    expect(faces).toHaveLength(0);
    expect(isStudioFontId('__proto__')).toBe(false);
    expect(isStudioFontId('custom-999999999')).toBe(false);
    expect(isStudioFontId(undefined)).toBe(false);
  });

  it('loads the local prepared stencil with no CDN or substituted family', async () => {
    expect(hasBundledStudioFont('stencil')).toBe(true);
    expect(hasBundledStudioFont('sans')).toBe(false);
    expect(isStudioFontId('stencil')).toBe(true);
    const loaded = await library().loadBuiltin('stencil');
    expect(faces[0]).toMatchObject({ family: 'Creastrix Allerta Stencil', source: 'url("/fonts/AllertaStencil.ttf") format("truetype")', descriptors: { weight: '400' } });
    expect(loaded.family).toBe(letteringFonts.stencil);
    expect(add).toHaveBeenCalledTimes(1);
  });

  it('rejects unsupported stencil text rather than silently accepting fallback glyphs', () => {
    expect(supportsStencilText('ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 -')).toBe(true);
    expect(supportsStencilText('')).toBe(true);
    for (const text of ['Привет', 'München', 'Café', 'Łódź', '東京', '🌿', 'a\nb', 'A\u00a0B', 'A\u200bB', '<B>', 'a.b', 'a_b']) {
      expect(supportsStencilText(text), text).toBe(false);
    }
  });

  it.each(['ttf', 'otf', 'woff2'])('accepts %s only after both header checks and the browser decoder resolve', async extension => {
    const decoder = deferred(); nativeLoad.mockReturnValue(decoder.promise);
    const input = file(`My font.${extension}`), fonts = library();
    const job = fonts.loadCustom(input);
    await vi.waitFor(() => expect(faces).toHaveLength(1));
    expect(add).not.toHaveBeenCalled();
    decoder.resolve();
    const loaded = await job;
    expect(loaded).toMatchObject({ label: 'My font' });
    expect(loaded.id).toMatch(/^custom-\d+$/);
    expect(Object.isFrozen(loaded)).toBe(true);
    expect(studioFontFamily(loaded.id)).toBe(loaded.family);
    expect(isStudioFontId(loaded.id)).toBe(true);
    expect(add).toHaveBeenCalledTimes(1);
    expect(faces[0].source).toBeInstanceOf(ArrayBuffer);
    expect(faces[0].family).not.toContain('My font');
    expect(normalizeLettering({ ...defaultLettering('cobalt'), font: loaded.id }).font).toBe(loaded.id);
    const measure = vi.fn(() => 20);
    letteringLayout({ ...defaultLettering('cobalt'), text: 'Hello', font: loaded.id }, measure);
    expect(measure).toHaveBeenCalledWith('Hello', `24px ${loaded.family}`);
    fonts.dispose();
    expect(isStudioFontId(loaded.id)).toBe(false);
    expect(() => studioFontFamily(loaded.id)).toThrow('STUDIO_FONT_UNAVAILABLE');
    expect(remove).toHaveBeenCalledWith(faces[0]);
  });

  it('uses fixed same-origin built-in URLs, caches successful loads and keeps exact ownership between sessions', async () => {
    const first = library(), second = library();
    const loaded = await first.loadBuiltin('rounded');
    expect(faces[0].source).toBe('url("/fonts/Nunito.ttf") format("truetype")');
    expect(await first.loadBuiltin('rounded')).toBe(loaded);
    expect(faces).toHaveLength(1);
    await second.loadBuiltin('rounded');
    expect(faces).toHaveLength(2);
    first.dispose();
    expect(remove.mock.calls).toEqual([[faces[0]]]);
    expect(await second.loadBuiltin('rounded')).toEqual(loaded);
  });

  it('coalesces registration when two pending built-in requests finish together', async () => {
    const decoder = deferred(); nativeLoad.mockReturnValue(decoder.promise);
    const fonts = library(), a = fonts.loadBuiltin('editorial'), b = fonts.loadBuiltin('editorial');
    await vi.waitFor(() => expect(faces).toHaveLength(2)); decoder.resolve();
    expect(await a).toBe(await b);
    expect(add).toHaveBeenCalledTimes(1);
  });

  it('refuses unavailable native APIs without pretending the requested font was selected', async () => {
    vi.stubGlobal('FontFace', undefined);
    await expect(library().loadCustom(file())).rejects.toMatchObject({ code: 'unavailable' });
    await expect(library().loadBuiltin('handwritten')).rejects.toMatchObject({ code: 'unavailable' });
    expect(add).not.toHaveBeenCalled();
  });

  it.each(['font.svg', 'font.woff', 'font.ttc', 'font.ttf.svg', 'https://example.test/font'])('rejects unsupported filename %s before reading', async name => {
    const input = file(name), read = vi.spyOn(input, 'arrayBuffer');
    await expect(library().loadCustom(input)).rejects.toMatchObject({ code: 'unsupported' });
    expect(read).not.toHaveBeenCalled(); expect(faces).toHaveLength(0);
  });

  it('rejects empty and oversized uploads before reading; a false MIME type does not override the bytes', async () => {
    const fonts = library();
    await expect(fonts.loadCustom(new File([], 'font.ttf'))).rejects.toMatchObject({ code: 'invalid' });
    const large = new File([new Uint8Array(MAX_CUSTOM_FONT_BYTES + 1)], 'font.ttf'), read = vi.spyOn(large, 'arrayBuffer');
    await expect(fonts.loadCustom(large)).rejects.toMatchObject({ code: 'tooLarge' });
    expect(read).not.toHaveBeenCalled();
    await expect(fonts.loadCustom(new File(['<svg>not a font</svg>'], 'font.ttf', { type: 'font/ttf' }))).rejects.toMatchObject({ code: 'invalid' });
    expect(faces).toHaveLength(0);
  });

  it('bounds both accepted and in-flight custom fonts, retaining their distinct immutable IDs', async () => {
    const decoder = deferred(); nativeLoad.mockReturnValue(decoder.promise);
    const fonts = library(), jobs = Array.from({ length: 3 }, () => fonts.loadCustom(file()));
    await expect(fonts.loadCustom(file())).rejects.toMatchObject({ code: 'limit' });
    decoder.resolve(); const loaded = await Promise.all(jobs);
    expect(new Set(loaded.map(font => font.id)).size).toBe(3);
    expect(new Set(loaded.map(font => font.family)).size).toBe(3);
    await expect(fonts.loadCustom(file())).rejects.toMatchObject({ code: 'limit' });
  });

  it('rejects malformed decoder output with a bounded reason and releases the failed slot', async () => {
    nativeLoad.mockRejectedValueOnce(new Error('PRIVATE USER FILENAME AND PARSER DETAILS'));
    const fonts = library();
    await expect(fonts.loadCustom(file())).rejects.toEqual(new StudioFontError('invalid'));
    expect(add).not.toHaveBeenCalled();
    for (let index = 0; index < 3; index++) await fonts.loadCustom(file());
    expect(add).toHaveBeenCalledTimes(3);
  });

  it('bounds reading and decoding by the same deadline and never publishes a late result', async () => {
    vi.useFakeTimers(); const decoder = deferred(); nativeLoad.mockReturnValue(decoder.promise);
    const fonts = library(), job = fonts.loadCustom(file());
    const failed = expect(job).rejects.toMatchObject({ code: 'timeout' });
    await vi.advanceTimersByTimeAsync(STUDIO_FONT_TIMEOUT_MS); await failed;
    decoder.resolve(); await Promise.resolve(); await Promise.resolve();
    expect(add).not.toHaveBeenCalled(); expect(vi.getTimerCount()).toBe(0);
  });

  it('also expires a stalled file read without constructing a decoder afterwards', async () => {
    vi.useFakeTimers(); let finish!: (bytes: ArrayBuffer) => void;
    const input = file(); vi.spyOn(input, 'arrayBuffer').mockReturnValue(new Promise(resolve => { finish = resolve; }));
    const job = library().loadCustom(input), failed = expect(job).rejects.toMatchObject({ code: 'timeout' });
    await vi.advanceTimersByTimeAsync(STUDIO_FONT_TIMEOUT_MS); await failed;
    finish(fontBytes()); await Promise.resolve(); await Promise.resolve();
    expect(faces).toHaveLength(0); expect(add).not.toHaveBeenCalled();
  });

  it('honours prior and in-flight cancellation and keeps successfully loaded older fonts intact', async () => {
    const fonts = library(), previous = await fonts.loadCustom(file()), controller = new AbortController();
    controller.abort();
    await expect(fonts.loadCustom(file(), { signal: controller.signal })).rejects.toMatchObject({ code: 'cancelled' });
    const active = new AbortController(), decoder = deferred(); nativeLoad.mockReturnValue(decoder.promise);
    const job = fonts.loadCustom(file(), { signal: active.signal }), failed = expect(job).rejects.toMatchObject({ code: 'cancelled' });
    await vi.waitFor(() => expect(faces).toHaveLength(2)); active.abort(); await failed;
    decoder.resolve(); await Promise.resolve();
    expect(add).toHaveBeenCalledTimes(1); expect(isStudioFontId(previous.id)).toBe(true);
  });

  it('supports cleanup replay, aborts unfinished work and never reuses a released custom ID', async () => {
    const fonts = library(), original = await fonts.loadCustom(file());
    fonts.dispose(); fonts.dispose();
    expect(remove).toHaveBeenCalledTimes(1);
    const next = await fonts.loadCustom(file()); expect(next.id).not.toBe(original.id);
    const decoder = deferred(); nativeLoad.mockReturnValue(decoder.promise);
    const job = fonts.loadBuiltin('handwritten'), failed = expect(job).rejects.toMatchObject({ code: 'cancelled' });
    await vi.waitFor(() => expect(faces).toHaveLength(3)); fonts.dispose(); await failed;
    decoder.resolve(); await Promise.resolve();
    expect(add).toHaveBeenCalledTimes(2); expect(isStudioFontId(next.id)).toBe(false);
  });

  it('keeps private labels bounded and separate from generated family identifiers', async () => {
    const loaded = await library().loadCustom(file(`${'a'.repeat(60)}\u202e\u0001.ttf`));
    expect(loaded.label).toHaveLength(48); expect(loaded.label).not.toContain('\u202e');
    expect(loaded.family).not.toContain('aaa');
  });

  it('keeps lamp and pot lettering/undo independent with a dark default for the plywood sample', () => {
    let state = letteringReducer(emptyLetteringSession(), { type: 'edit', variant: 'lamp', value: { ...defaultLettering('lamp'), text: 'Forest' } });
    expect(defaultLettering('lamp').color).toBe('#20251f');
    expect(letteringHistory(state, 'cobalt').present.text).toBe('');
    state = letteringReducer(state, { type: 'apply', variant: 'lamp' });
    expect(state.applied.lamp?.text).toBe('Forest'); expect(state.applied.cobalt).toBeUndefined();
    state = letteringReducer(state, { type: 'undo', variant: 'lamp' });
    expect(letteringHistory(state, 'lamp').present.text).toBe('');
  });
});

describe('font container gates', () => {
  it('pins the licensed stencil binary and verifies every permitted character has an actual glyph', () => {
    const bytes = readFileSync('public/fonts/AllertaStencil.ttf');
    expect(createHash('sha256').update(bytes).digest('hex')).toBe('8c504d1be20768941ab04251a389ad82d5782d647fa1c4029c811dc5bc2abd59');
    expect(readFileSync('public/fonts/AllertaStencil-OFL.txt', 'utf8')).toContain('SIL OPEN FONT LICENSE Version 1.1');
    let cmap = 0;
    for (let index = 0; index < bytes.readUInt16BE(4); index++) {
      const table = 12 + index * 16;
      if (bytes.toString('ascii', table, table + 4) === 'cmap') cmap = bytes.readUInt32BE(table + 8);
    }
    expect(cmap).toBeGreaterThan(0);
    let unicode = 0;
    for (let index = 0; index < bytes.readUInt16BE(cmap + 2); index++) {
      const table = cmap + 4 + index * 8;
      if (bytes.readUInt16BE(table) === 3 && bytes.readUInt16BE(table + 2) === 1) unicode = cmap + bytes.readUInt32BE(table + 4);
    }
    expect(unicode).toBeGreaterThan(0); expect(bytes.readUInt16BE(unicode)).toBe(4);
    const segments = bytes.readUInt16BE(unicode + 6) / 2, ends = unicode + 14;
    const starts = ends + 2 * segments + 2, deltas = starts + 2 * segments, offsets = deltas + 2 * segments;
    for (const character of 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 -') {
      const code = character.charCodeAt(0);
      let glyph = 0;
      for (let index = 0; index < segments; index++) {
        const start = bytes.readUInt16BE(starts + index * 2), end = bytes.readUInt16BE(ends + index * 2);
        if (code < start || code > end) continue;
        const offset = bytes.readUInt16BE(offsets + index * 2), delta = bytes.readInt16BE(deltas + index * 2);
        const raw = offset ? bytes.readUInt16BE(offsets + index * 2 + offset + 2 * (code - start)) : code;
        glyph = raw ? (raw + delta) & 0xffff : 0;
        break;
      }
      expect(glyph, character).toBeGreaterThan(0);
    }
  });
  it('rejects mismatched signatures and truncated or unbounded table directories', () => {
    expect(() => validateStudioFontBytes('font.otf', fontBytes())).toThrow('STUDIO_FONT_INVALID');
    const bytes = fontBytes(), view = new DataView(bytes);
    view.setUint16(4, 0); expect(() => validateStudioFontBytes('font.ttf', bytes)).toThrow('STUDIO_FONT_INVALID');
    view.setUint16(4, 257); expect(() => validateStudioFontBytes('font.ttf', bytes)).toThrow('STUDIO_FONT_INVALID');
    view.setUint16(4, 2); expect(() => validateStudioFontBytes('font.ttf', bytes)).toThrow('STUDIO_FONT_INVALID');
  });
  it('rejects WOFF2 length mismatch, collections and oversized advertised decompression', () => {
    const bytes = fontBytes('woff2'), view = new DataView(bytes);
    view.setUint32(8, 63); expect(() => validateStudioFontBytes('font.woff2', bytes)).toThrow('STUDIO_FONT_INVALID');
    view.setUint32(8, 64); view.setUint32(4, 0x74746366); expect(() => validateStudioFontBytes('font.woff2', bytes)).toThrow('STUDIO_FONT_INVALID');
    view.setUint32(4, 0x00010000); view.setUint32(16, 8 * 1024 * 1024 + 1);
    expect(() => validateStudioFontBytes('font.woff2', bytes)).toThrow('STUDIO_FONT_INVALID');
  });
});
