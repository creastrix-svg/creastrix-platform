/** Local raster previews only. SVG/HTML are deliberately not accepted here. */
export type StudioImage = { id: number; src: string; canvas: HTMLCanvasElement; aspect: number };
export type StudioImageErrorCode = 'type' | 'size' | 'decode';
export class StudioImageError extends Error {
  constructor(readonly code: StudioImageErrorCode) { super('STUDIO_IMAGE_' + code.toUpperCase()); }
}
export const STUDIO_IMAGE_BYTES = 5 * 1024 * 1024;
// A 1536² normalized RGBA PNG can exceed the original compressed upload's 5 MiB.
// Internal restoration has a separate bounded envelope, not a relaxed uploader.
export const STUDIO_NORMALIZED_IMAGE_BYTES = 10 * 1024 * 1024;
export const STUDIO_IMAGE_PIXELS = 16_000_000;
let nextImageId = 0;

/** Read dimensions before decoding, so a tiny compressed file cannot request an
 * unbounded canvas. The browser remains the actual decoder; no uploaded markup
 * or filename is inserted into the document or sent to a remote service. */
export function rasterDimensions(bytes: Uint8Array): { width: number; height: number } {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const ascii = (at: number, text: string) => [...text].every((character, i) => bytes[at + i] === character.charCodeAt(0));
  let width = 0, height = 0;
  if (bytes.length >= 24 && bytes[0] === 137 && ascii(1, 'PNG\r\n\x1a\n') && ascii(12, 'IHDR')) {
    width = view.getUint32(16); height = view.getUint32(20);
  } else if (bytes.length >= 12 && ascii(0, 'RIFF') && ascii(8, 'WEBP')) {
    if (bytes.length >= 30 && ascii(12, 'VP8X')) {
      width = 1 + bytes[24] + (bytes[25] << 8) + (bytes[26] << 16);
      height = 1 + bytes[27] + (bytes[28] << 8) + (bytes[29] << 16);
    } else if (bytes.length >= 30 && ascii(12, 'VP8 ') && bytes[23] === 157 && bytes[24] === 1 && bytes[25] === 42) {
      width = view.getUint16(26, true) & 16383; height = view.getUint16(28, true) & 16383;
    } else if (bytes.length >= 25 && ascii(12, 'VP8L') && bytes[20] === 47) {
      const packed = view.getUint32(21, true); width = (packed & 16383) + 1; height = ((packed >>> 14) & 16383) + 1;
    }
  } else if (bytes[0] === 255 && bytes[1] === 216) {
    for (let at = 2; at + 3 < bytes.length;) {
      if (bytes[at++] !== 255) break;
      while (bytes[at] === 255) at++;
      const marker = bytes[at++];
      if (marker === 217 || marker === 218 || at + 2 > bytes.length) break;
      const size = view.getUint16(at);
      if (size < 2 || at + size > bytes.length) break;
      if ([192, 193, 194, 195, 197, 198, 199, 201, 202, 203, 205, 206, 207].includes(marker) && size >= 8) {
        height = view.getUint16(at + 3); width = view.getUint16(at + 5); break;
      }
      at += size;
    }
  }
  if (!width || !height) throw new StudioImageError('type');
  if (width > 8192 || height > 8192 || width * height > STUDIO_IMAGE_PIXELS) throw new StudioImageError('size');
  return { width, height };
}

export const loadStudioImage = (file: File, signal?: AbortSignal) => decodeStudioImage(file, signal, false);
export const loadNormalizedStudioImage = (file: File, signal?: AbortSignal) => decodeStudioImage(file, signal, true);
async function decodeStudioImage(file: File, signal: AbortSignal | undefined, normalized: boolean): Promise<StudioImage> {
  if (!file.size || file.size > (normalized ? STUDIO_NORMALIZED_IMAGE_BYTES : STUDIO_IMAGE_BYTES)) throw new StudioImageError('size');
  if (!(normalized ? ['image/png'] : ['image/png', 'image/jpeg', 'image/webp']).includes(file.type)) throw new StudioImageError('type');
  const aborted = () => new DOMException('Image loading cancelled', 'AbortError');
  if (signal?.aborted) throw aborted();
  const bytes = new Uint8Array(await new Promise<ArrayBuffer>((resolve, reject) => {
    let settled = false;
    const finish = (error?: Error, value?: ArrayBuffer) => {
      if (settled) return;
      settled = true; clearTimeout(deadline); signal?.removeEventListener('abort', abort);
      if (error) reject(error); else resolve(value!);
    };
    const abort = () => finish(aborted());
    const deadline = setTimeout(() => finish(new StudioImageError('decode')), 12_000);
    signal?.addEventListener('abort', abort, { once: true });
    void Promise.resolve().then(() => file.arrayBuffer()).then(value => finish(undefined, value), () => finish(new StudioImageError('decode')));
  }));
  if (signal?.aborted) throw aborted();
  const dimensions = rasterDimensions(bytes);
  if (normalized && (dimensions.width > 1536 || dimensions.height > 1536 || bytes[0] !== 137)) throw new StudioImageError('size');
  // A deadline owns late completion; an obsolete bitmap is closed, not applied.
  const bitmap = await new Promise<ImageBitmap>((resolve, reject) => {
    let settled = false;
    const finish = (error?: Error, image?: ImageBitmap) => {
      if (settled) { image?.close(); return; }
      settled = true; clearTimeout(deadline); signal?.removeEventListener('abort', abort);
      if (error) reject(error); else resolve(image!);
    };
    const abort = () => finish(aborted());
    const deadline = setTimeout(() => finish(new StudioImageError('decode')), 12_000);
    signal?.addEventListener('abort', abort, { once: true });
    void Promise.resolve().then(() => createImageBitmap(file)).then(image => finish(undefined, image), () => finish(new StudioImageError('decode')));
  });
  try {
    if (signal?.aborted) throw aborted();
    if (!bitmap.width || !bitmap.height || bitmap.width * bitmap.height > STUDIO_IMAGE_PIXELS) throw new StudioImageError('size');
    const scale = Math.min(1, 1536 / Math.max(bitmap.width, bitmap.height));
    const canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.round(bitmap.width * scale)); canvas.height = Math.max(1, Math.round(bitmap.height * scale));
    const context = canvas.getContext('2d');
    if (!context) throw new StudioImageError('decode');
    context.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    return { id: ++nextImageId, src: canvas.toDataURL('image/png'), canvas, aspect: canvas.width / canvas.height };
  } finally { bitmap.close(); }
}

export type ImagePlacement = { image: StudioImage | null; size: number; x: number; y: number };
export const emptyImagePlacement = (): ImagePlacement => ({ image: null, size: 85, x: 240, y: 278 });
/** Coordinates use the same bounded front-elevation space as the lettering. */
export function imageBounds(value: ImagePlacement) {
  const aspect = value.image?.aspect ?? 1;
  const width = Math.max(20, Math.min(150, value.size));
  const height = width / aspect;
  const scale = Math.min(1, 100 / height);
  const w = width * scale, h = height * scale;
  return { width: w, height: h, x: Math.max(160 + w / 2, Math.min(320 - w / 2, value.x)), y: Math.max(226 + h / 2, Math.min(329 - h / 2, value.y)) };
}
