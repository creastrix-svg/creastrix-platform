export const standardStudioFonts = ['sans', 'serif', 'mono', 'rounded', 'editorial', 'handwritten'] as const;
export const builtinStudioFonts = [...standardStudioFonts, 'stencil'] as const;
export type BuiltinStudioFont = typeof builtinStudioFonts[number];
export type StudioFontId = BuiltinStudioFont | `custom-${number}`;
export type LoadedStudioFont = Readonly<{ id: StudioFontId; label: string; family: string; resource?: { name: string; data: string } }>;
export const MAX_CUSTOM_FONT_BYTES = 2 * 1024 * 1024;
export const MAX_CUSTOM_FONTS = 3;
export const STUDIO_FONT_TIMEOUT_MS = 8_000;

export const letteringFonts: Record<BuiltinStudioFont, string> = {
  sans: 'Arial, sans-serif', serif: 'Georgia, serif', mono: '"Courier New", monospace',
  rounded: '"Creastrix Nunito", sans-serif', editorial: '"Creastrix Cormorant Garamond", serif', handwritten: '"Creastrix Caveat", cursive',
  stencil: '"Creastrix Allerta Stencil", sans-serif',
};
const fontAssets: Partial<Record<BuiltinStudioFont, { family: string; path: string; weight: string }>> = {
  rounded: { family: 'Creastrix Nunito', path: '/fonts/Nunito.ttf', weight: '200 1000' },
  editorial: { family: 'Creastrix Cormorant Garamond', path: '/fonts/CormorantGaramond.ttf', weight: '300 700' },
  handwritten: { family: 'Creastrix Caveat', path: '/fonts/Caveat.ttf', weight: '400 700' },
  stencil: { family: 'Creastrix Allerta Stencil', path: '/fonts/AllertaStencil.ttf', weight: '400' },
};

/** This cut-text fixture deliberately offers a tested subset of the bundled
 * font's cmap, not every glyph the browser might substitute from a fallback.
 * Font coverage is separate from physical bridge/kerf/manufacturing validation.
 */
export function supportsStencilText(text: string): boolean {
  return /^[A-Za-z0-9 -]*$/u.test(text);
}
export function hasBundledStudioFont(id: BuiltinStudioFont): boolean {
  return Object.hasOwn(fontAssets, id);
}
const customFamilies = new Map<StudioFontId, string>();
const customResources = new Map<StudioFontId, string>();
export const studioFontResource = (id: StudioFontId) => customResources.get(id);
let nextCustomFont = 1;

export type StudioFontErrorCode = 'unsupported' | 'tooLarge' | 'limit' | 'invalid' | 'timeout' | 'cancelled' | 'unavailable';
export class StudioFontError extends Error {
  readonly code: StudioFontErrorCode;
  constructor(code: StudioFontErrorCode) { super(`STUDIO_FONT_${code.toUpperCase()}`); this.name = 'StudioFontError'; this.code = code; }
}
export function isStudioFontId(value: unknown): value is StudioFontId {
  return typeof value === 'string' && (Object.hasOwn(letteringFonts, value) || customFamilies.has(value as StudioFontId));
}
export function studioFontFamily(id: StudioFontId): string {
  if (Object.hasOwn(letteringFonts, id)) return letteringFonts[id as BuiltinStudioFont];
  const family = customFamilies.get(id);
  if (!family) throw new StudioFontError('unavailable');
  return family;
}

/** Header and size gates are not a font sanitizer. The browser's FontFace parser
 * must still accept the full file before the caller may select it. Collections,
 * SVG fonts and network font URLs are deliberately outside this upload surface.
 */
export function validateStudioFontBytes(name: string, bytes: ArrayBuffer): void {
  if (bytes.byteLength > MAX_CUSTOM_FONT_BYTES) throw new StudioFontError('tooLarge');
  if (bytes.byteLength < 12) throw new StudioFontError('invalid');
  const header = new DataView(bytes), signature = header.getUint32(0);
  const extension = name.toLowerCase().match(/\.(ttf|otf|woff2)$/)?.[1];
  if (!extension) throw new StudioFontError('unsupported');
  if (extension === 'woff2') {
    if (signature !== 0x774f4632 || bytes.byteLength < 48 || header.getUint32(8) !== bytes.byteLength) throw new StudioFontError('invalid');
    if (![0x00010000, 0x4f54544f].includes(header.getUint32(4))) throw new StudioFontError('invalid');
    // Bound the advertised expanded size before the browser decompresses WOFF2.
    const expanded = header.getUint32(16), tables = header.getUint16(12);
    if (expanded < 12 || expanded > 8 * 1024 * 1024 || tables === 0 || tables > 256) throw new StudioFontError('invalid');
  } else {
    if (signature !== (extension === 'otf' ? 0x4f54544f : 0x00010000)) throw new StudioFontError('invalid');
    const tables = header.getUint16(4);
    if (tables === 0 || tables > 256 || 12 + tables * 16 > bytes.byteLength) throw new StudioFontError('invalid');
  }
}

type Options = { signal?: AbortSignal };
type OwnedFont = { value: LoadedStudioFont; face: FontFace };

/** Scope the library to the storefront session, not the editor route: applied
 * previews and undo history retain each immutable custom family. dispose() only
 * deletes this library's exact FontFace objects. It is reusable after cleanup,
 * which also supports React's development effect setup/cleanup replay.
 * Native FontFace loading has no abort API; cancelled or late results are never
 * registered. Browser parser allocation itself is not synchronously disposable.
 */
export function createStudioFontLibrary() {
  const owned = new Map<StudioFontId, OwnedFont>(), pending = new Set<AbortController>();
  let customPending = 0;

  async function load(id: StudioFontId, label: string, family: string, source: () => Promise<string | ArrayBuffer>, weight: string, options: Options): Promise<LoadedStudioFont> {
    if (options.signal?.aborted) throw new StudioFontError('cancelled');
    const existing = owned.get(id);
    if (existing) return existing.value;
    if (typeof FontFace === 'undefined' || !document.fonts?.add) throw new StudioFontError('unavailable');
    if (pending.size >= 6) throw new StudioFontError('limit');
    const controller = new AbortController(); pending.add(controller);
    const cancel = () => controller.abort();
    options.signal?.addEventListener('abort', cancel, { once: true });
    let rejectCancellation: ((reason: StudioFontError) => void) | undefined;
    const cancelled = new Promise<never>((_, reject) => { rejectCancellation = reject; });
    const onAbort = () => rejectCancellation?.(new StudioFontError('cancelled'));
    controller.signal.addEventListener('abort', onAbort, { once: true });
    const timer = setTimeout(() => { rejectCancellation?.(new StudioFontError('timeout')); controller.abort(); }, STUDIO_FONT_TIMEOUT_MS);
    const assertCurrent = () => { if (controller.signal.aborted) throw new StudioFontError('cancelled'); };
    try {
      const face = await Promise.race([cancelled, (async () => {
        const input = await source(); assertCurrent();
        const candidate = new FontFace(family, input, { weight, style: 'normal', display: 'block' });
        await candidate.load(); assertCurrent();
        return candidate;
      })()]);
      assertCurrent();
      // Two callers can request the same built-in concurrently. Retain one face
      // without deleting or replacing another mounted session's font instance.
      const alreadyLoaded = owned.get(id);
      if (alreadyLoaded) return alreadyLoaded.value;
      const value = Object.freeze({ id, label, family: id.startsWith('custom-') ? `"${family}"` : studioFontFamily(id) });
      document.fonts.add(face);
      owned.set(id, { value, face });
      if (id.startsWith('custom-')) customFamilies.set(id, value.family);
      return value;
    } catch (error) {
      if (error instanceof StudioFontError) throw error;
      throw new StudioFontError('invalid');
    } finally {
      clearTimeout(timer); options.signal?.removeEventListener('abort', cancel);
      controller.signal.removeEventListener('abort', onAbort); pending.delete(controller);
    }
  }

  return {
    async loadBuiltin(id: BuiltinStudioFont, options: Options = {}): Promise<LoadedStudioFont> {
      if (options.signal?.aborted) throw new StudioFontError('cancelled');
      const asset = fontAssets[id];
      if (!asset) return Object.freeze({ id, label: id, family: studioFontFamily(id) });
      return load(id, id, asset.family, async () => `url("${asset.path}") format("truetype")`, asset.weight, options);
    },
    async loadCustom(file: File, options: Options = {}): Promise<LoadedStudioFont> {
      if (options.signal?.aborted) throw new StudioFontError('cancelled');
      if (!/\.(ttf|otf|woff2)$/i.test(file.name)) throw new StudioFontError('unsupported');
      if (file.size > MAX_CUSTOM_FONT_BYTES) throw new StudioFontError('tooLarge');
      if (file.size < 12) throw new StudioFontError('invalid');
      if ([...owned.keys()].filter(id => id.startsWith('custom-')).length + customPending >= MAX_CUSTOM_FONTS) throw new StudioFontError('limit');
      const id = `custom-${nextCustomFont++}` as const;
      // The user filename is only a bounded text label, never a CSS identifier,
      // URL, storage key, diagnostic field or network payload.
      const label = [...file.name.replace(/\.[^.]+$/, '').replace(/[\u202a-\u202e\u2066-\u2069]/g, '')].filter(character => {
        const code = character.codePointAt(0)!; return code >= 32 && (code < 127 || code > 159);
      }).slice(0, 48).join('').trim() || 'Custom';
      customPending++;
      try {
        let resource: LoadedStudioFont['resource'];
        const loaded = await load(id, label, `Creastrix Custom ${id}`, async () => {
          const bytes = await file.arrayBuffer(); validateStudioFontBytes(file.name, bytes);
          let binary = ''; for (const byte of new Uint8Array(bytes)) binary += String.fromCharCode(byte);
          resource = { name: 'font.' + file.name.split('.').at(-1)!.toLowerCase(), data: btoa(binary) };
          return bytes;
        }, '400', options);
        if (resource) customResources.set(id, resource.data);
        return Object.freeze({ ...loaded, resource });
      } finally { customPending--; }
    },
    dispose() {
      for (const controller of pending) controller.abort();
      for (const [id, entry] of owned) {
        document.fonts.delete(entry.face);
        if (id.startsWith('custom-')) { customFamilies.delete(id); customResources.delete(id); }
      }
      owned.clear();
    },
  };
}
