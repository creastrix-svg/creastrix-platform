import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { Link, MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createAppStore } from '../../store';
import ProductExperience from '../ProductExperience';
import { personalizationCopy } from './personalizationCopy';
import { productCopy } from '../copy';
import { studioExtensionCopy } from './studioExtensionCopy';
import { studioCompositionCopy } from './design/studioCompositionCopy';
import { studioZoneCopy } from './studioZoneCopy';
import { loadStudioImage, StudioImageError } from './design/studioImage';
import type { StudioImage } from './design/studioImage';
import type { ModelDecoration } from './preview/personalizationModel';
import type { LampDecoration } from './preview/lampModel';
import * as studioProducts from './studioProducts';
import * as studioFonts from './design/studioFonts';
import type { BuiltinStudioFont, LoadedStudioFont } from './design/studioFonts';

// Decoder validation and native bitmap cleanup have separate unit tests. These
// tests exercise the UI's ownership, cancellation and applied-snapshot boundary.
vi.mock('./design/studioImage', async importOriginal => ({ ...await importOriginal<typeof import('./design/studioImage')>(), loadStudioImage: vi.fn() }));
vi.mock('../ModelPreview', async () => {
  const { createPortal } = await import('react-dom');
  return { default: function ModelFixture({ target, decoration, lampDecoration, source }: { target: HTMLElement | null; decoration?: ModelDecoration; lampDecoration?: LampDecoration; source?: string }) {
    return target ? createPortal(<div data-testid="creative-model" data-source={source} data-image={lampDecoration?.artwork?.image?.id ?? decoration?.artwork?.image?.id ?? ''}>{lampDecoration?.value.text ?? decoration?.text}</div>, target) : null;
  } };
});

const p = personalizationCopy.en, e = studioExtensionCopy.en, t = productCopy.en, z = studioZoneCopy.en, c = studioCompositionCopy.en;
let sequence = 0;
function show() {
  const store = createAppStore('en');
  // Test-only navigation exercises real URL ownership and session continuity;
  // the product editor itself must not offer a demonstration product switcher.
  const view = render(<Provider store={store}><MemoryRouter initialEntries={[{ pathname: '/products/loop/personalize', search: '?variant=cobalt', key: 'creative-' + ++sequence }]}>
    <nav aria-label="Fixture product navigation"><Link to="/products/loop/personalize?variant=cobalt&product=lamp">Open lamp product</Link><Link to="/products/loop/personalize?variant=cobalt">Open pot product</Link></nav>
    <ProductExperience /></MemoryRouter></Provider>);
  return { ...view, store, user: userEvent.setup() };
}
const lettering = () => screen.getByRole('textbox', { name: p.textLabel });
const lamp = () => screen.getByRole('link', { name: 'Open lamp product' });
const pot = () => screen.getByRole('link', { name: 'Open pot product' });
const tab = (name: string) => screen.getByRole('button', { name });
const upload = () => screen.getByLabelText(e.uploadImage, { selector: 'input' });
const zone = (id: 'panel' | 'base') => within(screen.getByRole('group', { name: z.zoneLabel })).getByRole('button', { name: `${z.zoneNames[id]} ${z.operations[id === 'panel' ? 'cut' : 'engrave']}` });
const mainLamp = () => screen.getByTestId('creative-model');
const cutPattern = (pattern: string) => expect(mainLamp()).toHaveAttribute('data-source', `/product/studio-lamp-${pattern}.glb`);
function image(id: number): StudioImage { return { id, src: `data:image/png;base64,${id === 1 ? 'Zmlyc3Q=' : 'c2Vjb25k'}`, aspect: 1, canvas: document.createElement('canvas') }; }
const pictureFile = () => new File(['fixture'], 'private-drawing.png', { type: 'image/png' });
beforeEach(() => {
  vi.mocked(loadStudioImage).mockReset();
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(() => ({ font: '', measureText(this: { font: string }, text: string) { return { width: [...text].length * parseFloat(this.font) * .6 }; } }) as unknown as CanvasRenderingContext2D);
});
afterEach(() => vi.unstubAllGlobals());

describe('creative personalization integration', () => {
  it.each(['disabled', 'missing areas'] as const)('blocks direct editor entry when its profile has %s', reason => {
    const source = studioProducts.getStudioProduct('loop');
    vi.spyOn(studioProducts, 'getStudioProduct').mockReturnValue(reason === 'disabled'
      ? { ...source, enabled: false } : { ...source, zones: [] });
    show();
    expect(screen.getByText(z.unavailable)).toBeVisible();
    expect(screen.queryByRole('textbox', { name: p.textLabel })).toBeNull();
    expect(screen.queryByRole('button', { name: p.apply })).toBeNull();
    expect(screen.queryByRole('button', { name: e.imageTab })).toBeNull();
    expect(screen.queryByRole('button', { name: e.aiTab })).toBeNull();
  });

  it('keeps separate lamp and pot text drafts with independent undo and applied snapshots', async () => {
    const { user } = show();
    fireEvent.change(lettering(), { target: { value: 'POT' } });
    await user.click(screen.getByRole('button', { name: p.apply }));
    await user.click(lamp()); await user.click(zone('base')); expect(lettering()).toHaveValue('');
    fireEvent.change(lettering(), { target: { value: 'LAMP' } });
    expect(mainLamp()).toHaveTextContent('LAMP');
    await user.click(screen.getByRole('button', { name: p.apply }));
    await user.click(pot()); expect(lettering()).toHaveValue('POT');
    await user.click(screen.getByRole('button', { name: p.undo })); expect(lettering()).toHaveValue('');
    await user.click(lamp()); await user.click(zone('base')); expect(lettering()).toHaveValue('LAMP');
    await user.click(screen.getByRole('button', { name: p.undo })); expect(lettering()).toHaveValue('');
    await user.click(pot());
    await user.click(screen.getByRole('link', { name: p.back }));
    expect(document.querySelector('.product-applied-lettering')).toHaveTextContent('POT');
    expect(document.querySelector('.product-applied-lettering')).not.toHaveTextContent('LAMP');
  });

  it('ends the text undo group at product navigation while retaining the draft', async () => {
    const { user }=show();
    fireEvent.change(lettering(),{ target:{ value:'FIRST' } });
    await user.click(lamp()); await user.click(pot());
    expect(lettering()).toHaveValue('FIRST');
    fireEvent.change(lettering(),{ target:{ value:'SECOND' } });
    await user.click(screen.getByRole('button',{ name:p.undo }));
    expect(lettering()).toHaveValue('FIRST');
  });

  it('offers only configured area tools and keeps connecting material out of buyer controls', async () => {
    const { user } = show(); await user.click(lamp());
    await screen.findByTestId('creative-model'); cutPattern('botanical');
    expect(tab(z.patternTab)).toHaveAttribute('aria-pressed', 'true');
    expect(tab(e.aiTab)).toBeVisible();
    expect(screen.getByRole('button', { name: e.textTab })).toBeVisible();
    expect(screen.getByRole('button', { name: e.imageTab })).toBeVisible();
    expect(screen.queryByRole('button', { name: e.removeBridges })).toBeNull();
    expect(screen.queryByRole('button', { name: e.addBridges })).toBeNull();
    expect(screen.queryByRole('checkbox', { name: e.showBridges })).toBeNull();
    expect(screen.getByText(z.panelRestriction)).toBeVisible();
    await user.click(zone('base'));
    expect(tab(e.textTab)).toHaveAttribute('aria-pressed', 'true');
    expect(tab(e.imageTab)).toBeVisible();
    expect(screen.queryByRole('button', { name: z.patternTab })).toBeNull();
    expect(screen.queryByRole('button', { name: e.aiTab })).toBeNull();
    expect(screen.queryByRole('group', { name: e.cutout })).toBeNull();
    expect(screen.queryByRole('group', { name: p.inkLabel })).toBeNull();
    await user.click(screen.getByText(p.disclosureTitle, { selector: 'summary' }));
    expect(document.querySelector('.studio-disclosure')).toHaveTextContent(p.disclosureBody);
  });

  it('keeps AI explicitly disconnected, bounds the local prompt and distinguishes curated patterns', async () => {
    const { user, store } = show(), originalAuth = store.getState().authentication;
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch);
    const xhr = vi.fn(); vi.stubGlobal('XMLHttpRequest', xhr);
    const writes = vi.spyOn(Storage.prototype, 'setItem'); writes.mockClear();
    await user.click(tab(e.aiTab));
    expect(screen.getByText(e.aiUnavailable)).toBeVisible();
    expect(screen.queryByRole('button', { name: /^generate/i })).toBeNull();
    const prompt = screen.getByRole('textbox', { name: e.aiPrompt });
    fireEvent.change(prompt, { target: { value: 'PRIVATE IDEA '.repeat(70) } }); expect(prompt).toHaveValue('PRIVATE IDEA '.repeat(70).slice(0, 600));
    expect(screen.queryByRole('button', { name: e.explorePatterns })).toBeNull();
    await user.click(lamp()); await user.click(tab(e.aiTab));
    expect(screen.getByRole('textbox', { name: e.aiPrompt })).toHaveValue('');
    fireEvent.change(screen.getByRole('textbox', { name: e.aiPrompt }), { target: { value: 'PRIVATE LAMP IDEA' } });
    expect(screen.queryByRole('button', { name: e.explorePatterns })).toBeNull();
    await user.click(tab(z.patternTab));
    await user.click(within(screen.getByRole('group', { name: e.cutout })).getByRole('button', { name:e.patternForest }));
    expect(document.querySelector('[data-studio-product]')).toHaveAttribute('data-studio-product','lamp');
    cutPattern('forest');
    await user.click(pot()); await user.click(tab(e.aiTab));
    expect(screen.getByRole('textbox', { name: e.aiPrompt })).toHaveValue('PRIVATE IDEA '.repeat(70).slice(0, 600));
    await user.click(lamp()); await user.click(tab(e.aiTab));
    expect(screen.getByRole('textbox', { name: e.aiPrompt })).toHaveValue('PRIVATE LAMP IDEA');
    expect(fetch).not.toHaveBeenCalled(); expect(xhr).not.toHaveBeenCalled(); expect(writes).not.toHaveBeenCalled();
    expect(store.getState().authentication).toBe(originalAuth); expect(store.getState().productDemo.cart).toEqual([]);
    expect(JSON.stringify(store.getState())).not.toContain('PRIVATE IDEA');
    expect(JSON.stringify(localStorage)).not.toContain('PRIVATE IDEA'); expect(JSON.stringify(sessionStorage)).not.toContain('PRIVATE IDEA');
  });

  it('applies an image-only snapshot and retains it while a later image remains a draft', async () => {
    const first = image(1), second = image(2); vi.mocked(loadStudioImage).mockResolvedValueOnce(first).mockResolvedValueOnce(second);
    const { user, store } = show(); await user.click(tab(c.addImage));
    await user.upload(upload(), pictureFile());
    expect(await screen.findByText(e.imageLoaded, { selector: '.studio-upload-message' })).toBeVisible();
    expect(document.querySelector('.studio-sketch image')).toHaveAttribute('href', first.src);
    expect(screen.queryByRole('button', { name: p.original })).toBeNull();
    expect(screen.getByRole('group', { name: p.edit })).toBeVisible();
    await user.click(screen.getByRole('button', { name: e.withoutDesign }));
    expect(document.querySelector('.studio-preview-card .studio-sketch image')).toBeNull();
    expect(screen.getByRole('img', { name: `${p.edit} · ${e.withoutDesign}` })).toBeVisible();
    await user.click(screen.getByRole('button', { name: e.withoutDesign }));
    expect(document.querySelector('.studio-preview-card .studio-sketch image')).toHaveAttribute('href', first.src);
    expect(screen.getByRole('button', { name: p.apply })).toBeEnabled();
    await user.click(screen.getByRole('button', { name: p.apply }));
    await user.click(tab(e.replaceImage));
    await user.upload(upload(), pictureFile());
    expect(document.querySelector('.studio-sketch image')).toHaveAttribute('href', second.src);
    await user.click(screen.getByRole('link', { name: p.back }));
    expect(document.querySelector('.product-applied-lettering image')).toHaveAttribute('href', first.src);
    expect(screen.getByRole('button', { name: e.removeDesign })).toBeEnabled();
    expect(screen.getByText(t.personalBody)).toBeVisible();
    await user.click(screen.getByRole('link', { name: t.personalButton }));
    expect(document.querySelector('.studio-sketch image')).toHaveAttribute('href', second.src);
    await user.click(screen.getByRole('link', { name: p.back }));
    await user.click(screen.getByRole('button', { name: e.removeDesign }));
    expect(document.querySelector('.product-applied-lettering')).toBeNull();
    expect(screen.getByRole('link', { name: t.personalButton })).toHaveFocus();
    await user.click(screen.getByRole('link', { name: t.personalButton }));
    expect(document.querySelector('.studio-sketch image')).toHaveAttribute('href', second.src);
    expect(store.getState().productDemo.cart).toEqual([]); expect(JSON.stringify(store.getState())).not.toContain(first.src);
    expect(JSON.stringify(localStorage)).not.toContain(first.src);
  });

  it('preserves an existing picture when replacement decoding fails', async () => {
    const first = image(1); vi.mocked(loadStudioImage).mockResolvedValueOnce(first).mockRejectedValueOnce(new StudioImageError('decode'));
    const { user } = show(); await user.click(tab(c.addImage)); await user.upload(upload(), pictureFile());
    await user.click(screen.getByRole('button', { name: p.apply }));
    await user.click(tab(e.replaceImage));
    await user.upload(upload(), pictureFile());
    expect(await screen.findByRole('alert')).toHaveTextContent(e.imageError);
    expect(document.querySelector('.studio-sketch image')).toHaveAttribute('href', first.src);
    expect(screen.getByRole('button', { name: p.applied })).toBeDisabled();
  });

  it('disables repeated upload while pending and discards a late result after navigating to another product', async () => {
    let complete!: (value: StudioImage) => void;
    vi.mocked(loadStudioImage).mockImplementationOnce(() => new Promise(resolve => { complete = resolve; }));
    const { user } = show(); await user.click(tab(c.addImage)); await user.upload(upload(), pictureFile());
    expect(upload()).toBeDisabled();
    const signal = vi.mocked(loadStudioImage).mock.calls[0][1]!;
    await user.click(lamp()); expect(signal.aborted).toBe(true);
    await act(async () => { complete(image(1)); });
    expect(await screen.findByTestId('creative-model')).toHaveAttribute('data-image', '');
    await user.click(pot()); expect(document.querySelector('.studio-sketch image')).toBeNull();
    expect(screen.getByRole('button', { name: p.apply })).toBeDisabled();
  });

  it('discards a pending base upload after an area switch without changing the new panel pattern', async () => {
    let complete!: (value: StudioImage) => void;
    vi.mocked(loadStudioImage).mockImplementationOnce(() => new Promise(resolve => { complete = resolve; }));
    const { user } = show(); await user.click(lamp()); await user.click(zone('base')); await user.click(tab(c.addImage));
    await user.upload(upload(), pictureFile()); expect(upload()).toBeDisabled();
    const signal = vi.mocked(loadStudioImage).mock.calls[0][1]!;
    await user.click(zone('panel'));
    await user.click(within(screen.getByRole('group', { name: e.cutout })).getByRole('button', { name: e.patternForest }));
    cutPattern('forest');
    expect(signal.aborted).toBe(true);
    await act(async () => { complete(image(1)); });
    cutPattern('forest');
    expect(mainLamp()).toHaveAttribute('data-image', '');
    await user.click(zone('base')); expect(tab(c.addImage)).toBeEnabled(); expect(tab(e.imageTab)).toBeDisabled();
    cutPattern('forest'); expect(mainLamp()).toHaveAttribute('data-image', '');
  });

  it('keeps a pending font attached to the same text through row and active-tool reselection', async () => {
    let complete!: (value: LoadedStudioFont) => void;
    const delayed = new Promise<LoadedStudioFont>(resolve => { complete = resolve; });
    const loadBuiltin = vi.fn((id: BuiltinStudioFont, options?: { signal?: AbortSignal }) => {
      void options;
      return id === 'rounded' ? delayed : Promise.resolve({ id, label: id, family: studioFonts.studioFontFamily(id) });
    });
    vi.spyOn(studioFonts, 'createStudioFontLibrary').mockReturnValue({ loadBuiltin, loadCustom: vi.fn(), dispose: vi.fn() });
    const { user } = show(); fireEvent.change(lettering(), { target: { value: 'KEEP' } });
    await user.click(tab('Aa ' + e.fontRounded));
    const signal = loadBuiltin.mock.calls.filter(([id]) => id === 'rounded').at(-1)![1]!.signal!;
    await user.click(within(screen.getByRole('region', { name: c.objects })).getByRole('button', { name: /KEEP/ }));
    await user.click(tab(e.textTab)); expect(signal.aborted).toBe(false);
    await act(async () => complete({ id: 'rounded', label: 'rounded', family: studioFonts.studioFontFamily('rounded') }));
    expect(tab('Aa ' + e.fontRounded)).toHaveAttribute('aria-pressed', 'true');
    expect(lettering()).toHaveValue('KEEP');
  });

  it.each(['navigation', 'reset', 'undo', 'redo'] as const)('does not let delayed font selection overwrite a later %s choice', async action => {
    let complete!: (value: LoadedStudioFont) => void;
    const delayed = new Promise<LoadedStudioFont>(resolve => { complete = resolve; });
    const loadBuiltin = vi.fn((id: BuiltinStudioFont, options: { signal?: AbortSignal } = {}) => {
      void options;
      return id === 'rounded' ? delayed : Promise.resolve({ id, label: id, family: studioFonts.studioFontFamily(id) });
    });
    vi.spyOn(studioFonts, 'createStudioFontLibrary').mockReturnValue({ loadBuiltin, loadCustom: vi.fn(async () => { throw new Error('UNUSED_CUSTOM_FONT'); }), dispose: vi.fn() });
    const { user } = show(); fireEvent.change(lettering(), { target: { value: 'MIRO' } });
    if (action === 'redo') {
      await user.click(screen.getByRole('button', { name: 'Aa ' + p.serif }));
      await user.click(screen.getByRole('button', { name: p.undo }));
    }
    await user.click(screen.getByRole('button', { name: 'Aa ' + e.fontRounded }));
    const signal = loadBuiltin.mock.calls.filter(([id]) => id === 'rounded').at(-1)![1]!.signal!;
    expect(screen.getByRole('button', { name: 'Aa ' + p.serif })).toBeDisabled();
    if (action === 'navigation') { await user.click(lamp()); await user.click(pot()); }
    else if (action === 'reset') {
      await user.click(screen.getByRole('button', { name: p.reset }));
      await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: p.confirmReset }));
    } else await user.click(screen.getByRole('button', { name: action === 'undo' ? p.undo : p.redo }));
    expect(signal.aborted).toBe(true);
    const expectedFont = action === 'redo' ? 'serif' : 'sans';
    const expectedText = action === 'navigation' || action === 'redo' ? 'MIRO' : '';
    await act(async () => { complete({ id: 'rounded', label: 'rounded', family: studioFonts.studioFontFamily('rounded') }); });
    expect(lettering()).toHaveValue(expectedText);
    expect(screen.getByRole('button', { name: 'Aa ' + p[expectedFont] })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Aa ' + e.fontRounded })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByRole('button', { name: 'Aa ' + p[expectedFont] })).toBeEnabled();
  });

  it('keeps base artwork and lettering alongside an independently chosen panel pattern and applied state', async () => {
    const first = image(1); vi.mocked(loadStudioImage).mockResolvedValueOnce(first);
    const { user } = show(); await user.click(lamp()); await user.click(zone('base'));
    fireEvent.change(lettering(), { target: { value: 'OUR HOME' } });
    await user.click(tab(c.addImage));
    await user.upload(upload(), pictureFile());
    expect(await screen.findByText(e.imageLoaded, { selector: '.studio-upload-message' })).toBeVisible();
    cutPattern('botanical');
    expect(mainLamp()).toHaveAttribute('data-image', '1'); expect(mainLamp()).toHaveTextContent('OUR HOME');
    await user.click(zone('panel'));
    const patterns = screen.getByRole('group', { name: e.cutout });
    await user.click(within(patterns).getByRole('button', { name: new RegExp(e.patternOrbit) }));
    cutPattern('orbit');
    expect(mainLamp()).toHaveAttribute('data-image', '1'); expect(mainLamp()).toHaveTextContent('OUR HOME');
    await user.click(screen.getByRole('button', { name: p.apply }));
    expect(screen.getByRole('button', { name: p.applied })).toBeDisabled();
    await user.click(zone('base')); expect(lettering()).toHaveValue('OUR HOME');
    expect(screen.getByRole('button', { name: p.applied })).toBeDisabled();
    fireEvent.change(lettering(), { target: { value: 'NEW DRAFT' } });
    expect(screen.getByRole('button', { name: p.apply })).toBeEnabled();
    await user.click(zone('panel')); cutPattern('orbit'); expect(mainLamp()).toHaveAttribute('data-image', '1');
    expect(screen.getByRole('button', { name: p.apply })).toBeEnabled();
    await user.click(zone('base')); await user.click(screen.getByRole('button', { name: p.undo }));
    expect(lettering()).toHaveValue('OUR HOME');
    expect(screen.getByRole('button', { name: p.applied })).toBeDisabled();
    await user.click(pot());
    expect(lettering()).toHaveValue('');
    expect(screen.getByRole('button', { name: p.apply })).toBeDisabled();
    await user.click(lamp()); cutPattern('orbit'); expect(mainLamp()).toHaveAttribute('data-image', '1');
    expect(screen.getByRole('button', { name: p.applied })).toBeDisabled();
  });

  it('keeps real 3D and area previews in the same expanded frame and restores opener focus', async () => {
    const { user } = show(); await user.click(lamp());
    const opener = screen.getByRole('button', { name: p.expand }); await user.click(opener);
    const dialog = screen.getByRole('dialog'), stage = dialog.querySelector('.studio-stage');
    expect(within(dialog).getByTestId('creative-model')).toHaveAttribute('data-source', '/product/studio-lamp-botanical.glb');
    await user.click(within(dialog).getByRole('button', { name: z.flatView }));
    expect(dialog.querySelector('[data-lamp-zone]')).toHaveAttribute('data-lamp-zone', 'panel');
    expect(dialog.querySelector('.studio-stage')).toBe(stage);
    await user.click(within(dialog).getByRole('button', { name: z.modelView }));
    expect(within(dialog).getByTestId('creative-model')).toHaveAttribute('data-source', '/product/studio-lamp-botanical.glb');
    await user.keyboard('{Escape}'); expect(screen.queryByRole('dialog')).toBeNull(); expect(opener).toHaveFocus();
  });

  it('clears local images and AI ideas on a new storefront session', async () => {
    vi.mocked(loadStudioImage).mockResolvedValueOnce(image(1));
    const first = show(); await first.user.click(tab(c.addImage)); await first.user.upload(upload(), pictureFile());
    await first.user.click(tab(e.aiTab)); fireEvent.change(screen.getByRole('textbox', { name: e.aiPrompt }), { target: { value: 'PRIVATE' } });
    first.unmount();
    const next = show(); expect(document.querySelector('.studio-sketch image')).toBeNull();
    await next.user.click(tab(e.aiTab)); expect(screen.getByRole('textbox', { name: e.aiPrompt })).toHaveValue('');
  });
});
