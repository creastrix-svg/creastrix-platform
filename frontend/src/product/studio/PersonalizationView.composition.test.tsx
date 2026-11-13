import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createAppStore } from '../../store';
import ProductExperience from '../ProductExperience';
import { personalizationCopy } from './personalizationCopy';
import { productCopy } from '../copy';
import { studioCompositionCopy } from './design/studioCompositionCopy';
import { studioExtensionCopy } from './studioExtensionCopy';
import { studioZoneCopy } from './studioZoneCopy';
import { loadStudioImage, StudioImageError } from './design/studioImage';
import type { StudioImage } from './design/studioImage';
import type { StudioObject } from './design/studioComposition';
import type { ModelDecoration } from './preview/personalizationModel';
import * as fonts from './design/studioFonts';

vi.mock('./design/studioImage', async original => ({ ...await original<typeof import('./design/studioImage')>(), loadStudioImage: vi.fn() }));
vi.mock('./preview/StudioLampPreview', () => ({ default: ({ baseObjects, panelObjects }: { baseObjects: readonly StudioObject[]; panelObjects: readonly StudioObject[] }) =>
  <div><output data-testid="base-objects">{JSON.stringify(baseObjects)}</output><output data-testid="panel-objects">{JSON.stringify(panelObjects)}</output></div> }));
vi.mock('../media/ModelPreview', async () => {
  const { createPortal } = await import('react-dom');
  return { default: ({ target, decoration }: { target: HTMLElement | null; decoration?: ModelDecoration }) => target ? createPortal(<output data-testid="composition-model">{JSON.stringify(decoration?.objects)}</output>, target) : null };
});

const p = personalizationCopy.en, c = studioCompositionCopy.en, e = studioExtensionCopy.en, z = studioZoneCopy.en, t = productCopy.en;
beforeEach(() => {
  vi.mocked(loadStudioImage).mockReset();
  vi.spyOn(fonts, 'createStudioFontLibrary').mockReturnValue({
    findCustom:vi.fn(),loadBuiltin: vi.fn(id => Promise.resolve({ id, label: id, family: fonts.studioFontFamily(id) })), loadCustom: vi.fn(), dispose: vi.fn(),
  });
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(() => ({
    font: '', measureText(this: { font: string }, text: string) { return { width: text.length * parseFloat(this.font) * .6 }; },
  }) as unknown as CanvasRenderingContext2D);
});
function show(product = 'loop') {
  const store = createAppStore('en');
  render(<Provider store={store}><MemoryRouter initialEntries={['/products/loop/personalize?variant=cobalt&product=' + product]}><ProductExperience /></MemoryRouter></Provider>);
  return { user: userEvent.setup(), store };
}
const button = (name: string) => screen.getByRole('button', { name });
const objectList = () => within(screen.getByRole('region', { name: c.objects }));
const objectButtons = () => within(objectList().getByRole('list')).getAllByRole('button');
const text = () => screen.getByRole('textbox', { name: p.textLabel });
const setText = (value: string) => fireEvent.change(text(), { target: { value } });
const file = () => new File(['image'], 'private-art.png', { type: 'image/png' });
// The DOM test environment has no OS chooser. Observe the real input opened by the visible
// action, then deliver a selection (or cancellation) to that exact input.
async function choosePicture(user: ReturnType<typeof userEvent.setup>, action: string, selection: File | null = file()) {
  const open = vi.spyOn(HTMLInputElement.prototype, 'click');
  let input: HTMLInputElement;
  try {
    await user.click(button(action));
    expect(open).toHaveBeenCalledTimes(1);
    input = screen.getByLabelText<HTMLInputElement>(e.uploadImage, { selector: 'input[type=file]' });
    expect(open.mock.contexts[0]).toBe(input);
  } finally { open.mockRestore(); }
  if (selection) await user.upload(input, selection);
  else fireEvent(input, new Event('cancel', { bubbles: true }));
}
const image = (id: number): StudioImage => ({ id, src: `data:image/png;base64,${id === 1 ? 'b25l' : 'dHdv'}`, aspect: 2, canvas: document.createElement('canvas') });
const stageObjects = () => [...document.querySelectorAll('.studio-preview-card [data-studio-object]')];
const zone = (id: 'base' | 'panel') => within(screen.getByRole('group', { name: z.zoneLabel })).getByRole('button', { name: `${z.zoneNames[id]} ${z.operations[id === 'panel' ? 'cut' : 'engrave']}` });
const lampObjects = (part: 'base' | 'panel'): StudioObject[] => JSON.parse(screen.getByTestId(part + '-objects').textContent!);

describe('multiple-object studio integration', () => {
  it('opens the picker from the picture tool when no image exists and leaves the text intact', async () => {
    vi.mocked(loadStudioImage).mockResolvedValueOnce(image(1));
    const { user } = show(); setText('KEEP');
    await choosePicture(user, e.imageTab);
    expect(document.querySelector('.studio-preview-card image')).toHaveAttribute('href', image(1).src);
    expect(stageObjects().map(node=>node.textContent)).toContain('KEEP');
    expect(button(e.imageTab)).toHaveAttribute('aria-pressed','true');
  });
  it.each(['add','replace'] as const)('does not apply old content during a pending picture %s', async mode => {
    const {user}=show();setText('KEEP');
    if(mode==='replace'){vi.mocked(loadStudioImage).mockResolvedValueOnce(image(1));await choosePicture(user,c.addImage);}
    let finish!: (i:StudioImage)=>void;
    vi.mocked(loadStudioImage).mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve;}));
    const field=screen.getByLabelText(e.uploadImage,{selector:'input'});
    await user.click(button(mode==='add'?c.addImage:e.replaceImage));
    expect(button(p.apply)).toBeDisabled();
    fireEvent.change(field,{target:{files:[file()]}});
    expect(button(p.apply)).toBeDisabled();
    await act(async()=>{finish(image(2));});
    expect(button(p.apply)).toBeEnabled();
    expect(document.querySelector('.studio-preview-card image')).toHaveAttribute('href',image(2).src);
  });
  it('selects, reorders and deletes independent text objects with shared undo and separate applied snapshots', async () => {
    const { user, store } = show();
    setText('ONE');
    await user.click(button(c.addText)); setText('TWO');
    expect(stageObjects().map(node => node.textContent)).toEqual(['ONE', 'TWO']);
    await user.click(objectButtons()[0]); expect(text()).toHaveValue('ONE');
    setText('FIRST'); expect(stageObjects().map(node => node.textContent)).toEqual(['FIRST', 'TWO']);
    await user.click(button(c.forward)); expect(stageObjects().map(node => node.textContent)).toEqual(['TWO', 'FIRST']);
    await user.click(button(p.apply));
    await user.click(button(c.deleteObject)); expect(stageObjects().map(node => node.textContent)).toEqual(['TWO']);
    await user.click(button(p.undo)); expect(stageObjects().map(node => node.textContent)).toEqual(['TWO', 'FIRST']);
    expect(text()).toHaveValue('FIRST');
    setText('DRAFT');
    await user.click(screen.getByRole('link', { name: p.back }));
    const applied = document.querySelector('.product-applied-lettering')!;
    expect(applied).toHaveTextContent('FIRST'); expect(applied).toHaveTextContent('TWO'); expect(applied).not.toHaveTextContent('DRAFT');
    await user.click(screen.getByRole('link', { name: t.personalButton }));
    expect(text()).toHaveValue('DRAFT'); expect(stageObjects().map(node => node.textContent)).toEqual(['TWO', 'DRAFT']);
    expect(store.getState().productDemo.cart).toEqual([]);
    expect(JSON.stringify(store.getState())).not.toContain('DRAFT');
  });

  it('retains multiple images and per-object fit/position across selection, shared undo, and the model envelope', async () => {
    vi.mocked(loadStudioImage).mockResolvedValueOnce(image(1)).mockResolvedValueOnce(image(2));
    const { user } = show(); setText('LABEL');
    await choosePicture(user, c.addImage);
    await user.click(button(c.contain));
    await choosePicture(user, c.addImage);
    await user.click(button(c.cover));
    expect(document.querySelectorAll('.studio-preview-card image')).toHaveLength(2);
    await user.click(objectButtons()[1]); expect(button(c.contain)).toHaveAttribute('aria-pressed', 'true');
    await user.click(button(c.free));
    fireEvent.change(screen.getByLabelText(e.imageSize, { selector: 'input' }), { target: { value: '40' } });
    fireEvent.pointerUp(screen.getByLabelText(e.imageSize, { selector: 'input' }));
    await user.click(button(p.undo)); expect(screen.getByLabelText(e.imageSize, { selector: 'input' })).toHaveValue('85');
    await user.click(objectButtons()[2]); expect(button(c.cover)).toHaveAttribute('aria-pressed', 'true');
    await user.click(button(p.model));
    const objects: StudioObject[] = JSON.parse((await screen.findByTestId('composition-model')).textContent!);
    expect(objects.map(object => object.kind)).toEqual(['text', 'image', 'image']);
    expect(objects.filter(object => object.kind === 'image').map(object => ({ fit: object.fit, id: object.value.image?.id }))).toEqual([{ fit: 'free', id: 1 }, { fit: 'cover', id: 2 }]);
  });

  it('blocks apply for invalid unselected text instead of validating only the selected object', async () => {
    const { user } = show(); setText('W'.repeat(32));
    expect(button(p.apply)).toBeDisabled();
    await user.click(button(c.addText)); setText('OK');
    expect(text()).toHaveAttribute('aria-invalid', 'false');
    expect(button(p.apply)).toBeDisabled();
    expect(document.querySelector('.studio-apply-status')).toHaveTextContent(p.textTooWide);
    await user.click(objectButtons()[0]); await user.click(button(c.deleteObject));
    expect(button(p.apply)).toBeEnabled();
  });

  it('moves text above a picture without moving the picture and sends the ordered result to 3D', async () => {
    vi.mocked(loadStudioImage).mockResolvedValueOnce(image(1));
    const { user } = show(); await choosePicture(user, c.addImage);
    const before = document.querySelector('.studio-preview-card image')!.outerHTML;
    await user.click(button(c.addText)); setText('TOP');
    fireEvent.change(screen.getByRole('slider', { name: p.sizeLabel }), { target: { value: '20' } });
    await user.click(screen.getByText(p.positionLabel, { selector: 'summary' }));
    fireEvent.change(screen.getByRole('slider', { name: p.horizontal }), { target: { value: '250' } });
    expect(stageObjects().map(node => node.getAttribute('data-object-kind'))).toEqual(['text', 'image', 'text']);
    expect(stageObjects()[2].querySelector('tspan')).toHaveAttribute('x', '250');
    expect(document.querySelector('.studio-preview-card image')!.outerHTML).toBe(before);
    await user.click(button(p.model));
    const objects: StudioObject[] = JSON.parse((await screen.findByTestId('composition-model')).textContent!);
    expect(objects[1]).toMatchObject({ kind: 'image', value: { image: { id: 1 } } });
    expect(objects[2]).toMatchObject({ kind: 'text', value: { text: 'TOP', x: 250, size: 20 } });
  });

  it('enables Original for the entire Loop composition and keeps the way back available when no layers have visible content', async () => {
    const { user } = show(); setText('VISIBLE'); await user.click(button(c.addText));
    expect(text()).toHaveValue(''); expect(button(p.original)).toBeEnabled();
    await user.click(button(p.original));
    expect(button(p.original)).toHaveAttribute('aria-pressed', 'true'); expect(stageObjects()).toHaveLength(0);
    await user.click(objectButtons()[0]); await user.click(button(c.deleteObject));
    expect(text()).toHaveValue(''); expect(button(p.original)).toBeEnabled();
    await user.click(button(p.original)); expect(button(p.original)).toHaveAttribute('aria-pressed', 'false');
    expect(button(p.original)).toBeDisabled(); expect(stageObjects()).toHaveLength(1);
  });

  it('describes the applied Loop design by its first nonblank lettering, not an empty first layer or a nonexistent picture', async () => {
    const { user } = show();
    await user.click(button(c.addText)); setText('ALBA'); await user.click(button(p.apply));
    await user.click(screen.getByRole('link', { name: p.back }));
    const card = document.querySelector<HTMLElement>('.product-applied-lettering')!;
    expect(card.querySelector('image')).toBeNull();
    expect(card.querySelector('svg.studio-sketch')).toHaveTextContent('ALBA');
    expect(card.querySelector('strong')).toHaveTextContent(/^ALBA$/);
    expect(within(card).getByRole('img', { name: p.applied + ' · ALBA' })).toBeInTheDocument();
  });

  it('keeps the Loop thumbnail and its expanded sketch on the same composition before, during and after Original', async () => {
    vi.mocked(loadStudioImage).mockResolvedValueOnce(image(1));
    const { user } = show(); setText('ALBA'); await choosePicture(user, c.addImage);
    const thumbnail = document.querySelector<HTMLButtonElement>('.studio-mobile-preview')!;
    const expectLayers = (host: Element, original: boolean) => {
      expect(host.querySelectorAll('[data-studio-object]')).toHaveLength(original ? 0 : 2);
      if (original) { expect(host).not.toHaveTextContent('ALBA'); expect(host.querySelector('image')).toBeNull(); }
      else { expect(host).toHaveTextContent('ALBA'); expect(host.querySelector('image')).toHaveAttribute('href', image(1).src); }
    };
    for (const [index, original] of [false, true, false].entries()) {
      if (index) await user.click(button(e.withoutDesign));
      expectLayers(thumbnail, original);
      await user.click(thumbnail);
      const dialog = screen.getByRole('dialog');
      expectLayers(dialog, original);
      await user.click(within(dialog).getByRole('button', { name: t.close }));
    }
  });

  it('leaves layers, selection and redo intact after a cancelled or failed add, then adds a decoded picture in one undo step', async () => {
    vi.mocked(loadStudioImage).mockRejectedValueOnce(new StudioImageError('decode')).mockResolvedValueOnce(image(1));
    const { user } = show(); setText('KEEP'); await user.click(button(c.addText)); await user.click(button(p.undo));
    await choosePicture(user, c.addImage, null);
    expect(loadStudioImage).not.toHaveBeenCalled(); expect(text()).toHaveValue('KEEP');
    expect(objectButtons()).toHaveLength(1); expect(button(p.redo)).toBeEnabled();
    await choosePicture(user, c.addImage);
    expect(await screen.findByRole('alert')).toHaveTextContent(e.imageError);
    expect(text()).toHaveValue('KEEP'); expect(objectButtons()).toHaveLength(1); expect(button(p.redo)).toBeEnabled();
    await choosePicture(user, c.addImage);
    expect(document.querySelector('.studio-preview-card image')).toHaveAttribute('href', image(1).src);
    expect(objectButtons()).toHaveLength(2); expect(objectButtons()[1]).toHaveAttribute('aria-pressed', 'true');
    expect(button(p.redo)).toBeDisabled();
    await user.click(button(p.undo)); expect(text()).toHaveValue('KEEP'); expect(objectButtons()).toHaveLength(1);
    await user.click(button(p.redo)); expect(document.querySelector('.studio-preview-card image')).toHaveAttribute('href', image(1).src);
  });

  it('announces add and replace distinctly and changes only the bitmap after a cancelled or failed replacement', async () => {
    vi.mocked(loadStudioImage).mockResolvedValueOnce(image(1)).mockRejectedValueOnce(new StudioImageError('decode')).mockResolvedValueOnce(image(2));
    const { user } = show(); setText('CAPTION');
    await choosePicture(user, c.addImage);
    expect(screen.getByText(e.imageLoaded, { selector: '[role="status"]' })).toBeVisible();
    fireEvent.change(screen.getByLabelText(e.imageSize, { selector: 'input' }), { target: { value: '60' } });
    await user.click(screen.getByText(e.imagePosition, { selector: 'summary' }));
    fireEvent.change(screen.getByRole('slider', { name: p.horizontal }), { target: { value: '250' } });
    await user.click(button(c.contain)); await user.click(button(c.backward)); await user.click(button(p.model));
    await screen.findByTestId('composition-model');
    const layers = (): StudioObject[] => JSON.parse(screen.getByTestId('composition-model').textContent!);
    const before = layers();
    expect(before).toMatchObject([{ kind: 'image', fit: 'contain', value: { size: 60, x: 250, image: { id: 1 } } }, { kind: 'text', value: { text: 'CAPTION' } }]);
    await choosePicture(user, e.replaceImage, null);
    expect(loadStudioImage).toHaveBeenCalledTimes(1); expect(layers()).toEqual(before);
    await choosePicture(user, e.replaceImage);
    expect(screen.getByRole('alert')).toHaveTextContent(e.imageError); expect(layers()).toEqual(before);
    await choosePicture(user, e.replaceImage);
    const [replaced, caption] = layers();
    expect(layers()).toHaveLength(2);
    expect(replaced).toMatchObject({ ...before[0], value: { ...before[0].value, image: { id: 2, src: image(2).src } } });
    expect(caption).toEqual(before[1]); expect(objectButtons()[0]).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByText(e.imageLoaded, { selector: '[role="status"]' })).toBeNull();
    expect(screen.getByText(e.imageReplaced, { selector: '[role="status"]' })).toBeVisible();
    await user.click(button(p.undo)); expect(layers()).toEqual(before);
  });

  it('preserves a pending replacement when the current picture row and tool are selected again', async () => {
    let complete!: (result: StudioImage) => void;
    vi.mocked(loadStudioImage).mockResolvedValueOnce(image(1)).mockImplementationOnce(() => new Promise(resolve => { complete = resolve; }));
    const { user } = show(); await choosePicture(user, c.addImage); await choosePicture(user, e.replaceImage);
    const signal = vi.mocked(loadStudioImage).mock.calls[1][1]!;
    await user.click(objectButtons()[1]); await user.click(button(e.imageTab));
    expect(signal.aborted).toBe(false); expect(document.querySelector('.studio-preview-card image')).toHaveAttribute('href', image(1).src);
    await act(async () => complete(image(2)));
    expect(document.querySelector('.studio-preview-card image')).toHaveAttribute('href', image(2).src);
    expect(screen.queryAllByText(e.imageLoaded)).toHaveLength(0);
    expect(objectButtons()).toHaveLength(2);
    await user.click(button(p.undo)); expect(document.querySelector('.studio-preview-card image')).toHaveAttribute('href', image(1).src);
  });

  it.each(['select', 'delete', 'undo'] as const)('cancels pending image ownership on %s and cannot publish the late result into another object', async action => {
    let complete!: (result: StudioImage) => void;
    vi.mocked(loadStudioImage).mockResolvedValueOnce(image(1)).mockImplementationOnce(() => new Promise(resolve => { complete = resolve; }));
    const { user } = show(); setText('KEEP');
    await choosePicture(user, c.addImage); await choosePicture(user, e.replaceImage);
    const signal = vi.mocked(loadStudioImage).mock.calls[1][1]!;
    if (action === 'select') await user.click(objectButtons()[0]);
    else await user.click(button(action === 'delete' ? c.deleteObject : p.undo));
    expect(signal.aborted).toBe(true);
    await act(async () => complete(image(2)));
    if (action === 'select') expect(document.querySelector('.studio-preview-card image')).toHaveAttribute('href', image(1).src);
    else expect(document.querySelector('.studio-preview-card image')).toBeNull();
    expect(text()).toHaveValue('KEEP');
    expect(stageObjects()[0]).toHaveTextContent('KEEP');
    expect(document.body).not.toHaveTextContent('private-art.png');
  });

  it('keeps panel visual text and images separate from the base and preserves both on apply', async () => {
    vi.mocked(loadStudioImage).mockResolvedValueOnce(image(1)).mockResolvedValueOnce(image(2));
    const { user } = show('lamp');
    expect(screen.getByText(c.panelPreview)).toBeVisible();
    await user.click(button(e.textTab));
    fireEvent.change(screen.getByRole('textbox', { name: z.cutTextLabel }), { target: { value: 'MIRO' } });
    await choosePicture(user, c.addImage); await user.click(button(c.contain));
    expect(lampObjects('panel').map(object => object.kind)).toEqual(['text', 'image']);
    const panel = lampObjects('panel');
    await user.click(zone('base')); setText('BASE');
    await choosePicture(user, c.addImage);
    expect(lampObjects('panel')).toEqual(panel);
    expect(lampObjects('base').filter(object => object.kind === 'image').map(object => object.value.image?.id)).toEqual([2]);
    await user.click(button(p.apply)); expect(button(p.applied)).toBeDisabled();
    await user.click(zone('panel')); expect(button(p.applied)).toBeDisabled();
    await user.click(objectButtons()[0]); expect(screen.getByRole('textbox', { name: z.cutTextLabel })).toHaveValue('MIRO');
    expect(lampObjects('base')[0]).toMatchObject({ kind: 'text', value: { text: 'BASE' } });
  });
});
