import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { emptyImagePlacement, imageBounds, loadStudioImage, rasterDimensions, STUDIO_IMAGE_BYTES, STUDIO_IMAGE_PIXELS, StudioImageError } from './studioImage';
import type { StudioImage } from './studioImage';

// Header fixtures exercise the bounded parser, not the browser's real decoder.
function png(width = 200, height = 100) {
  const bytes = new Uint8Array(24);
  bytes.set([137, 80, 78, 71, 13, 10, 26, 10]); bytes.set([73, 72, 68, 82], 12);
  const view = new DataView(bytes.buffer); view.setUint32(16, width); view.setUint32(20, height);
  return bytes;
}
function jpeg(width: number, height: number, marker = 192) {
  return new Uint8Array([255, 216, 255, 224, 0, 4, 1, 2, 255, marker, 0, 8, 8, height >> 8, height & 255, width >> 8, width & 255, 1, 255, 217]);
}
function webp(kind: 'VP8X' | 'VP8 ' | 'VP8L', width: number, height: number) {
  const bytes = new Uint8Array(30), view = new DataView(bytes.buffer);
  bytes.set(new TextEncoder().encode('RIFF')); bytes.set(new TextEncoder().encode('WEBP' + kind), 8);
  if (kind === 'VP8X') {
    for (let index = 0; index < 3; index++) { bytes[24 + index] = ((width - 1) >> (index * 8)) & 255; bytes[27 + index] = ((height - 1) >> (index * 8)) & 255; }
  } else if (kind === 'VP8 ') {
    bytes.set([157, 1, 42], 23); view.setUint16(26, width, true); view.setUint16(28, height, true);
  } else { bytes[20] = 47; view.setUint32(21, ((height - 1) << 14) | (width - 1), true); }
  return bytes;
}
const file = (bytes = png(), type = 'image/png') => new File([bytes.buffer as ArrayBuffer], 'private-upload-name.png', { type });
const bitmap = (width = 200, height = 100) => ({ width, height, close: vi.fn() }) as unknown as ImageBitmap;
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done; }); return { promise, resolve }; }
const flush = async () => { for (let step = 0; step < 14; step++) await Promise.resolve(); };
let decode = vi.fn<typeof createImageBitmap>();
let draw = vi.fn();
beforeEach(() => {
  decode = vi.fn<typeof createImageBitmap>(); draw = vi.fn();
  vi.stubGlobal('createImageBitmap', decode);
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ drawImage: draw } as unknown as CanvasRenderingContext2D);
  vi.spyOn(HTMLCanvasElement.prototype, 'toDataURL').mockReturnValue('data:image/png;base64,cHJldmlldw==');
});
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });

describe('bounded raster header parsing', () => {
  it.each([
    ['PNG', png(1200, 800)], ['JPEG baseline', jpeg(1200, 800)], ['JPEG progressive', jpeg(1200, 800, 194)],
    ['WebP extended', webp('VP8X', 1200, 800)], ['WebP lossy', webp('VP8 ', 1200, 800)], ['WebP lossless', webp('VP8L', 1200, 800)],
  ])('reads %s without decoding pixels', (_name, bytes) => expect(rasterDimensions(bytes)).toEqual({ width: 1200, height: 800 }));
  it.each([new Uint8Array(), new Uint8Array([255, 216, 255]), new TextEncoder().encode('<svg></svg>'), png(0, 1), png(1, 0), jpeg(20, 10).slice(0, 13)])('rejects unsupported/truncated/zero dimensions', bytes => {
    expect(() => rasterDimensions(bytes)).toThrowError(new StudioImageError('type'));
  });
  it.each([png(8193, 1), png(1, 8193), png(4001, 4000)])('rejects over-limit dimensions before decoding', bytes => {
    expect(() => rasterDimensions(bytes)).toThrowError(new StudioImageError('size'));
  });
  it('accepts exact pixel/edge limits and respects a subarray byte offset', () => {
    expect(rasterDimensions(png(4000, 4000))).toEqual({ width: 4000, height: 4000 });
    expect(rasterDimensions(png(8192, 1))).toEqual({ width: 8192, height: 1 });
    const padded = new Uint8Array(30); padded.set(png(), 3);
    expect(rasterDimensions(padded.subarray(3, 27))).toEqual({ width: 200, height: 100 });
  });
});

describe('local image decoding and ownership', () => {
  it('scales proportionally, canonicalizes to a local PNG and closes the bitmap without network/storage', async () => {
    const decoded = bitmap(3000, 1500), source = file(png(3000, 1500)), network = vi.fn(), storage = vi.spyOn(Storage.prototype, 'setItem');
    vi.stubGlobal('fetch', network); decode.mockResolvedValue(decoded);
    const result = await loadStudioImage(source);
    expect(decode).toHaveBeenCalledWith(source);
    expect([result.canvas.width, result.canvas.height, result.aspect]).toEqual([1536, 768, 2]);
    expect(draw).toHaveBeenCalledWith(decoded, 0, 0, 1536, 768);
    expect(result.src).toBe('data:image/png;base64,cHJldmlldw==');
    expect(result).not.toHaveProperty('name');
    expect(result.src).not.toContain(source.name); expect(result.id).toBeGreaterThan(0);
    expect(decoded.close).toHaveBeenCalledOnce(); expect(network).not.toHaveBeenCalled(); expect(storage).not.toHaveBeenCalled();
  });
  it('does not upscale a small image and assigns a new raster identity to each load', async () => {
    decode.mockImplementation(async () => bitmap());
    const first = await loadStudioImage(file()), second = await loadStudioImage(file());
    expect([first.canvas.width, first.canvas.height]).toEqual([200, 100]);
    expect(second.id).toBeGreaterThan(first.id);
  });
  it.each(['empty', 'bytes', 'mime', 'header', 'pixels'])('rejects %s before invoking the decoder', async failure => {
    const source = failure === 'empty' ? new File([], 'empty.png', { type: 'image/png' }) : failure === 'mime' ? file(png(), 'image/svg+xml') : failure === 'header' ? file(new Uint8Array([1, 2, 3])) : failure === 'pixels' ? file(png(4001, 4000)) : file();
    if (failure === 'bytes') vi.spyOn(source, 'size', 'get').mockReturnValue(STUDIO_IMAGE_BYTES + 1);
    await expect(loadStudioImage(source)).rejects.toBeInstanceOf(StudioImageError);
    expect(decode).not.toHaveBeenCalled();
  });
  it.each(['read-reject', 'read-throw', 'decode-reject', 'decode-throw'])('normalizes %s and removes timer/abort listener', async failure => {
    vi.useFakeTimers(); const source = file(), controller = new AbortController();
    const add = vi.spyOn(controller.signal, 'addEventListener'), remove = vi.spyOn(controller.signal, 'removeEventListener');
    if (failure === 'read-reject') vi.spyOn(source, 'arrayBuffer').mockRejectedValue(new Error('private-details'));
    else if (failure === 'read-throw') vi.spyOn(source, 'arrayBuffer').mockImplementation(() => { throw new Error('private-details'); });
    else if (failure === 'decode-reject') decode.mockRejectedValue(new Error('private-details'));
    else decode.mockImplementation(() => { throw new Error('private-details'); });
    await expect(loadStudioImage(source, controller.signal)).rejects.toEqual(new StudioImageError('decode'));
    expect(vi.getTimerCount()).toBe(0);
    for (const [, listener] of add.mock.calls) expect(remove).toHaveBeenCalledWith('abort', listener);
  });
  it.each(['read', 'decode'])('times out a stalled %s stage and cannot apply its late completion', async stage => {
    vi.useFakeTimers(); const source = file(), pendingRead = deferred<ArrayBuffer>(), pendingDecode = deferred<ImageBitmap>(), lateBitmap = bitmap();
    if (stage === 'read') vi.spyOn(source, 'arrayBuffer').mockReturnValue(pendingRead.promise);
    decode.mockReturnValue(pendingDecode.promise);
    const task = loadStudioImage(source); const failure = expect(task).rejects.toEqual(new StudioImageError('decode'));
    await flush(); await vi.advanceTimersByTimeAsync(12_000); await failure;
    if (stage === 'read') pendingRead.resolve(png().buffer); else pendingDecode.resolve(lateBitmap);
    await flush(); expect(draw).not.toHaveBeenCalled(); expect(vi.getTimerCount()).toBe(0);
    if (stage === 'read') expect(decode).not.toHaveBeenCalled(); else expect(lateBitmap.close).toHaveBeenCalledOnce();
  });
  it.each(['read', 'decode'])('aborts pending %s and closes a late bitmap without applying it', async stage => {
    vi.useFakeTimers(); const source = file(), controller = new AbortController(), pendingRead = deferred<ArrayBuffer>(), pendingDecode = deferred<ImageBitmap>(), lateBitmap = bitmap();
    if (stage === 'read') vi.spyOn(source, 'arrayBuffer').mockReturnValue(pendingRead.promise);
    decode.mockReturnValue(pendingDecode.promise);
    const task = loadStudioImage(source, controller.signal); const failure = expect(task).rejects.toMatchObject({ name: 'AbortError' });
    await flush(); controller.abort(); await failure;
    if (stage === 'read') pendingRead.resolve(png().buffer); else pendingDecode.resolve(lateBitmap);
    await flush(); expect(vi.getTimerCount()).toBe(0); expect(draw).not.toHaveBeenCalled();
    if (stage === 'read') expect(decode).not.toHaveBeenCalled(); else expect(lateBitmap.close).toHaveBeenCalledOnce();
  });
  it('rejects an already-cancelled upload without reading or decoding', async () => {
    const controller = new AbortController(), source = file(), read = vi.spyOn(source, 'arrayBuffer'); controller.abort();
    await expect(loadStudioImage(source, controller.signal)).rejects.toMatchObject({ name: 'AbortError' });
    expect(read).not.toHaveBeenCalled(); expect(decode).not.toHaveBeenCalled();
  });
  it.each(['zero', 'pixels', 'canvas', 'draw', 'serialize'])('closes its acquired bitmap after %s failure', async failure => {
    const decoded = failure === 'zero' ? bitmap(0, 100) : failure === 'pixels' ? bitmap(STUDIO_IMAGE_PIXELS, 2) : bitmap();
    decode.mockResolvedValue(decoded);
    if (failure === 'canvas') vi.mocked(HTMLCanvasElement.prototype.getContext).mockReturnValue(null);
    if (failure === 'draw') draw.mockImplementation(() => { throw new Error('draw failed'); });
    if (failure === 'serialize') vi.mocked(HTMLCanvasElement.prototype.toDataURL).mockImplementation(() => { throw new Error('serialize failed'); });
    await expect(loadStudioImage(file())).rejects.toBeInstanceOf(Error);
    expect(decoded.close).toHaveBeenCalledOnce();
  });
});

describe('bounded front-elevation image placement', () => {
  it.each([.02, .5, 1, 2, 40])('keeps aspect %s within the region at extreme positions and sizes', aspect => {
    const image = { aspect } as StudioImage;
    for (const size of [-10, 20, 85, 150, 999]) for (const x of [-999, 240, 999]) for (const y of [-999, 278, 999]) {
      const box = imageBounds({ image, size, x, y });
      expect(box.width / box.height).toBeCloseTo(aspect);
      expect(box.x - box.width / 2).toBeGreaterThanOrEqual(160);
      expect(box.x + box.width / 2).toBeLessThanOrEqual(320);
      expect(box.y - box.height / 2).toBeGreaterThanOrEqual(226);
      expect(box.y + box.height / 2).toBeLessThanOrEqual(329);
    }
  });
  it('creates independent empty placements with predictable default bounds', () => {
    const first = emptyImagePlacement(), second = emptyImagePlacement();
    expect(first).not.toBe(second); expect(first).toEqual({ image: null, size: 85, x: 240, y: 278 });
    expect(imageBounds(first)).toEqual({ width: 85, height: 85, x: 240, y: 278 });
  });
});
