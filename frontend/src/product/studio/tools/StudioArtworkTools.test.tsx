import { act, fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, afterEach, expect, it, vi } from 'vitest';
import StudioArtworkTools from './StudioArtworkTools';
import { emptyImagePlacement } from '../design/studioImage';
import type { ImagePlacement, StudioImage } from '../design/studioImage';
import { useStudioImagePicker } from './useStudioImagePicker';
import { studioExtensionCopy } from '../studioExtensionCopy';
import { studioCompositionCopy } from '../design/studioCompositionCopy';
import { studioArea, MAX_STUDIO_OBJECTS } from '../design/studioComposition';
import type { StudioObject } from '../design/studioComposition';
import { storefrontLocales } from '../../locale';
import StudioObjectTools from './StudioObjectTools';
import { defaultLettering } from '../design/personalization';

vi.mock('../../useProductLocale', () => ({ useProductLocale: () => 'en' }));
const copy = studioExtensionCopy.en;
function source() {
  const bytes = new Uint8Array(24); bytes.set([137, 80, 78, 71, 13, 10, 26, 10]); bytes.set([73, 72, 68, 82], 12);
  const view = new DataView(bytes.buffer); view.setUint32(16, 200); view.setUint32(20, 100);
  return new File([bytes], 'private-personal-picture.png', { type: 'image/png' });
}
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done; }); return { promise, resolve }; }
const bitmap = () => ({ width: 200, height: 100, close: vi.fn() }) as unknown as ImageBitmap;
const flush = async () => act(async () => { for (let step = 0; step < 16; step++) await Promise.resolve(); });
let decode = vi.fn<typeof createImageBitmap>();
beforeEach(() => {
  decode = vi.fn<typeof createImageBitmap>(); vi.stubGlobal('createImageBitmap', decode);
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ drawImage: vi.fn() } as unknown as CanvasRenderingContext2D);
  vi.spyOn(HTMLCanvasElement.prototype, 'toDataURL').mockReturnValue('data:image/png;base64,cHJldmlldw==');
});
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });
const input = () => screen.getByLabelText(copy.uploadImage);
const addButton = () => screen.getByRole('button', { name: studioCompositionCopy.en.addImage });
const replaceButton = () => screen.getByRole('button', { name: copy.replaceImage });
function PickerHarness({ publish, value = emptyImagePlacement(), lamp = false }: {
  publish: (image: StudioImage) => void; value?: ImagePlacement; lamp?: boolean;
}) {
  const { input: fileInput, status, open, select } = useStudioImagePicker(), pending = status === 'pending';
  return <>
    <button type="button" disabled={pending} onClick={() => open('add', publish)}>{studioCompositionCopy.en.addImage}</button>
    <input hidden ref={fileInput} type="file" aria-label={copy.uploadImage} disabled={pending}
      onChange={event => { const file = event.currentTarget.files?.[0]; event.currentTarget.value = ''; void select(file); }} />
    <output>{status}</output>
    <StudioArtworkTools value={value} lamp={lamp} onChange={vi.fn()} pending={pending} onReplace={() => open('replace', publish)} />
  </>;
}
function choose(file = source(), opener = addButton) {
  const openNative = vi.spyOn(input(), 'click');
  fireEvent.click(opener());
  expect(openNative).toHaveBeenCalledOnce();
  openNative.mockRestore();
  fireEvent.change(input(), { target: { files: [file] } });
}

it('decodes a local file, disables duplicate upload while pending, and enables controls immediately on completion', async () => {
  const pending = deferred<ImageBitmap>(), decoded = bitmap(), change = vi.fn(); decode.mockReturnValue(pending.promise);
  render(<PickerHarness publish={change} />);
  choose(); await flush(); expect(input()).toBeDisabled(); expect(addButton()).toBeDisabled();
  expect(screen.getByRole('status')).toHaveTextContent('pending');
  await act(async () => { pending.resolve(decoded); }); await flush();
  expect(input()).toBeEnabled(); expect(addButton()).toBeEnabled();
  expect(screen.getByRole('status')).toHaveTextContent('added');
  expect(change).toHaveBeenCalledOnce();
  expect(change.mock.calls[0][0]).toMatchObject({ src: 'data:image/png;base64,cHJldmlldw==', aspect: 2 });
  expect(decoded.close).toHaveBeenCalledOnce(); expect(input()).toHaveValue('');
  expect(document.body).not.toHaveTextContent('private-personal-picture.png');
});

it('keys upload ownership by target: remount cancels an old decode and leaves the new target usable', async () => {
  const oldRequest = deferred<ImageBitmap>(), newRequest = deferred<ImageBitmap>(), oldImage = bitmap(), newImage = bitmap();
  decode.mockReturnValueOnce(oldRequest.promise).mockReturnValueOnce(newRequest.promise);
  const oldChange = vi.fn(), newChange = vi.fn();
  const { rerender } = render(<PickerHarness key="cobalt" publish={oldChange} />);
  choose(); await flush(); expect(input()).toBeDisabled();
  rerender(<PickerHarness key="lamp" lamp publish={newChange} />);
  expect(input()).toBeEnabled(); expect(screen.getByText(copy.engravingReference)).toBeInTheDocument();
  choose(); await flush(); expect(input()).toBeDisabled();
  await act(async () => { oldRequest.resolve(oldImage); }); await flush();
  expect(oldImage.close).toHaveBeenCalledOnce(); expect(oldChange).not.toHaveBeenCalled(); expect(newChange).not.toHaveBeenCalled();
  expect(input()).toBeDisabled();
  await act(async () => { newRequest.resolve(newImage); }); await flush();
  expect(input()).toBeEnabled(); expect(newChange).toHaveBeenCalledOnce(); expect(newImage.close).toHaveBeenCalledOnce();
});

it('unmount cancels a pending decode and cannot publish the late result', async () => {
  const pending = deferred<ImageBitmap>(), decoded = bitmap(), change = vi.fn(); decode.mockReturnValue(pending.promise);
  const { unmount } = render(<PickerHarness publish={change} />);
  choose(); await flush(); unmount();
  await act(async () => { pending.resolve(decoded); }); await flush();
  expect(decoded.close).toHaveBeenCalledOnce(); expect(change).not.toHaveBeenCalled();
});

it('timeout restores upload controls, retains the previous image, and allows a successful retry', async () => {
  vi.useFakeTimers(); decode.mockReturnValueOnce(new Promise<ImageBitmap>(() => {})).mockResolvedValueOnce(bitmap());
  const image = { id: 1, src: 'data:image/png;base64,b2xk', canvas: document.createElement('canvas'), aspect: 2 } satisfies StudioImage;
  const value = { ...emptyImagePlacement(), image }, change = vi.fn();
  render(<PickerHarness value={value} publish={change} />);
  choose(source(), replaceButton); await flush(); expect(replaceButton()).toBeDisabled(); expect(addButton()).toBeDisabled();
  await act(async () => { await vi.advanceTimersByTimeAsync(12_000); });
  expect(screen.getByText('decode', { selector: 'output' })).toBeInTheDocument(); expect(input()).toBeEnabled();
  expect(replaceButton().querySelector('img')).toHaveAttribute('src', image.src); expect(change).not.toHaveBeenCalled();
  choose(source(), replaceButton); await flush();
  expect(change).toHaveBeenCalledOnce(); expect(screen.getByText('replaced', { selector: 'output' })).toBeInTheDocument(); expect(vi.getTimerCount()).toBe(0);
});

it('reports the rejected file type and ignores a cancelled chooser without invoking the decoder', async () => {
  const change = vi.fn(); render(<PickerHarness publish={change} />);
  fireEvent.click(addButton()); fireEvent(input(), new Event('cancel', { bubbles: true })); await flush();
  expect(decode).not.toHaveBeenCalled(); expect(screen.getByRole('status')).toHaveTextContent('idle');
  choose(new File(['<svg/>'], 'private.svg', { type: 'image/svg+xml' })); await flush();
  expect(screen.getByRole('status')).toHaveTextContent('type'); expect(input()).toBeEnabled();
  expect(decode).not.toHaveBeenCalled(); expect(change).not.toHaveBeenCalled();
});

it('offers only compact replacement in artwork controls and deletes the selected layer from the object list', () => {
  const image = { id: 1, src: 'data:image/png;base64,cHJldmlldw==', canvas: document.createElement('canvas'), aspect: 2 } satisfies StudioImage;
  const change = vi.fn(), replace = vi.fn(), remove = vi.fn(), value = { ...emptyImagePlacement(), image };
  render(<><StudioObjectTools objects={[{ kind: 'image', id: 'picture', value, fit: 'free' }]} selectedId="picture"
    onSelect={vi.fn()} onAdd={vi.fn()} onDelete={remove} onReorder={vi.fn()} canAddText canAddImage />
    <StudioArtworkTools value={value} lamp onChange={change} onReplace={replace} pending={false} /></>);
  expect(screen.queryByText(copy.imagePosition)).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: copy.removeImage })).not.toBeInTheDocument();
  expect(replaceButton().querySelector('img')).toHaveAttribute('src', image.src);
  fireEvent.click(replaceButton()); expect(replace).toHaveBeenCalledOnce();
  fireEvent.click(screen.getByRole('button', { name: studioCompositionCopy.en.deleteObject }));
  expect(remove).toHaveBeenCalledOnce(); expect(change).not.toHaveBeenCalled();
});

it('uses the active zone for image bounds and groups resize and position gestures', () => {
  const image = { id: 1, src: 'data:image/png;base64,eA==', canvas: document.createElement('canvas'), aspect: 2 } satisfies StudioImage;
  const value = { ...emptyImagePlacement(), image }, change = vi.fn(), end = vi.fn();
  render(<StudioArtworkTools value={value} lamp area={studioArea('lamp')} fit="free" onChange={change} endGroup={end} pending={false} onReplace={vi.fn()} />);
  const size = screen.getByLabelText(copy.imageSize, { selector: 'input' }), x = screen.getByLabelText('Horizontal');
  expect(x).toBeEnabled();
  expect(Number(x.getAttribute('min'))).toBeLessThan(160);
  fireEvent.change(size, { target: { value: '110' } });
  expect(change).toHaveBeenLastCalledWith({ ...value, size: 110 }, 'image-size');
  fireEvent.pointerUp(size); expect(end).toHaveBeenCalledOnce();
  fireEvent.change(x, { target: { value: '140' } });
  expect(change).toHaveBeenLastCalledWith({ ...value, x: 140 }, 'image-position');
  fireEvent.blur(x); expect(end).toHaveBeenCalledTimes(2);
});

it.each(['contain', 'cover'] as const)('disables free transforms for %s while retaining an explicit return to free sizing', fit => {
  const image = { id: 1, src: 'data:image/png;base64,eA==', canvas: document.createElement('canvas'), aspect: 2 } satisfies StudioImage;
  const change = vi.fn(), changeFit = vi.fn(), end = vi.fn(), c = studioCompositionCopy.en;
  render(<StudioArtworkTools value={{ ...emptyImagePlacement(), image }} lamp area={studioArea('lamp-panel')} fit={fit} onChange={change} onFitChange={changeFit} endGroup={end} pending={false} onReplace={vi.fn()} />);
  expect(screen.getByLabelText(copy.imageSize, { selector: 'input' })).toBeDisabled();
  expect(screen.getByLabelText('Horizontal')).toBeDisabled();
  expect(screen.getByLabelText('Vertical')).toBeDisabled();
  expect(screen.getByRole('button', { name: c[fit] })).toHaveAttribute('aria-pressed', 'true');
  fireEvent.click(screen.getByRole('button', { name: c.free }));
  expect(changeFit).toHaveBeenCalledWith('free'); expect(end).toHaveBeenCalledOnce();
  expect(change).not.toHaveBeenCalled();
});

it('keeps ordered object selection distinct, caps additions, and respects capability switches', () => {
  const objects: StudioObject[] = Array.from({ length: MAX_STUDIO_OBJECTS }, (_, index) => ({ id: `object-${index}`, kind: 'text', value: { ...defaultLettering('cobalt'), text: `Text ${index}` } }));
  const select = vi.fn(), add = vi.fn(), remove = vi.fn(), reorder = vi.fn(), c = studioCompositionCopy.en;
  const { rerender } = render(<StudioObjectTools objects={objects} selectedId="object-0" onSelect={select} onAdd={add} onDelete={remove} onReorder={reorder} canAddText canAddImage />);
  expect(screen.getByRole('button', { name: c.addText })).toBeDisabled();
  expect(screen.getByRole('button', { name: c.addImage })).toBeDisabled();
  expect(screen.getByRole('button', { name: c.backward })).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: 'Text 2: Text 1' })); expect(select).toHaveBeenCalledWith('object-1');
  fireEvent.click(screen.getByRole('button', { name: c.forward })); expect(reorder).toHaveBeenCalledWith(1);
  fireEvent.click(screen.getByRole('button', { name: c.deleteObject })); expect(remove).toHaveBeenCalledOnce();
  rerender(<StudioObjectTools objects={[]} selectedId={null} onSelect={select} onAdd={add} onDelete={remove} onReorder={reorder} canAddText canAddImage={false} />);
  expect(screen.queryByRole('button', { name: c.addImage })).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: c.deleteObject })).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: c.addText })); expect(add).toHaveBeenCalledWith('text');
});

it('provides explicit composition and visual-only panel copy in all storefront languages', () => {
  for (const locale of storefrontLocales) {
    expect(Object.keys(studioCompositionCopy[locale])).toEqual(Object.keys(studioCompositionCopy.en));
    expect(Object.values(studioCompositionCopy[locale]).every(text => text.trim().length > 0)).toBe(true);
    if (locale !== 'en') expect(studioCompositionCopy[locale].panelPreview).not.toBe(studioCompositionCopy.en.panelPreview);
  }
});
