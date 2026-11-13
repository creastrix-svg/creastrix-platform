import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createAppStore } from '../../store';
import ProductExperience from '../ProductExperience';
import { personalizationCopy } from './personalizationCopy';
import { studioExtensionCopy } from './studioExtensionCopy';
import { studioZoneCopy } from './studioZoneCopy';
import * as fonts from './design/studioFonts';
import * as products from './studioProducts';
import type { Lettering } from './design/personalization';
import type { BuiltinStudioFont, LoadedStudioFont } from './design/studioFonts';

vi.mock('./preview/StudioLampPreview', () => ({ default: ({ value, panelText }: { value: Lettering; panelText?: Lettering }) =>
  <div><output data-testid="base-lettering">{value.text}</output><output data-testid="cut-lettering">{panelText?.text}</output></div> }));
const p = personalizationCopy.en, e = studioExtensionCopy.en, z = studioZoneCopy.en;
const loaded = (id: BuiltinStudioFont): LoadedStudioFont => ({ id, family: fonts.studioFontFamily(id), label: id });
const load = vi.fn((id: BuiltinStudioFont, options?: { signal?: AbortSignal }) => { void options; return Promise.resolve(loaded(id)); });
beforeEach(() => {
  load.mockReset().mockImplementation(id => Promise.resolve(loaded(id)));
  vi.spyOn(fonts, 'createStudioFontLibrary').mockReturnValue({ findCustom:vi.fn(),loadBuiltin: load, loadCustom: vi.fn(), dispose: vi.fn() });
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(() => ({ font: '', measureText(this: { font: string }, text: string) { return { width: text.length * parseFloat(this.font) * .6 }; } }) as unknown as CanvasRenderingContext2D);
});
function show(product = 'lamp') {
  render(<Provider store={createAppStore('en')}><MemoryRouter initialEntries={['/products/loop/personalize?product=' + product]}><ProductExperience /></MemoryRouter></Provider>);
  return userEvent.setup();
}
const zone = (id: 'panel' | 'base') => within(screen.getByRole('group', { name: z.zoneLabel })).getByRole('button', { name: `${z.zoneNames[id]} ${z.operations[id === 'panel' ? 'cut' : 'engrave']}` });
const cut = () => screen.getByRole('textbox', { name: z.cutTextLabel });
const base = () => screen.getByRole('textbox', { name: p.textLabel });
const apply = () => screen.getByRole('button', { name: p.apply });
const type = (node: HTMLElement, text: string) => fireEvent.change(node, { target: { value: text } });

describe('configured cutting and engraving text', () => {
  it('shows only the panel stencil and author-curated engraving fonts', async () => {
    const user = show(); await user.click(screen.getByRole('button', { name: e.textTab }));
    expect(screen.getByRole('button', { name: 'Aa ' + z.fontStencil })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByLabelText(e.uploadFont)).toBeNull();
    expect(screen.getByText(p.positionLabel)).toBeVisible();
    expect(screen.queryByRole('group', { name: p.inkLabel })).toBeNull();
    expect(screen.getByRole('slider', { name: new RegExp(p.sizeLabel) })).toHaveAttribute('max', '28');
    type(cut(), 'MIRO\nSECOND'); expect(cut()).toHaveValue('MIRO');
    expect(await screen.findByText('Text limit for this area: 14 characters · Max. lines: 1.')).toBeVisible();
    type(cut(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'); expect(cut()).toHaveValue('ABCDEFGHIJKLMN');
    await user.click(zone('base'));
    for (const label of [p.sans, p.serif, p.mono]) expect(screen.getByRole('button', { name: 'Aa ' + label })).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Aa ' + z.fontStencil })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Aa ' + e.fontHandwritten })).toBeNull();
    expect(screen.queryByLabelText(e.uploadFont)).toBeNull();
    expect(screen.queryByRole('button', { name: e.aiTab })).toBeNull();
  });

  it('keeps both texts through area switching, apply, undo and per-area reset', async () => {
    const user = show(); await user.click(screen.getByRole('button', { name: e.textTab }));
    type(cut(), 'MIRO'); await waitFor(() => expect(screen.getByTestId('cut-lettering')).toHaveTextContent('MIRO'));
    await user.click(zone('base')); type(base(), 'Home');
    expect(screen.getByTestId('base-lettering')).toHaveTextContent('Home');
    expect(screen.getByTestId('cut-lettering')).toHaveTextContent('MIRO');
    await user.click(apply()); expect(screen.getByRole('button', { name: p.applied })).toBeDisabled();
    await user.click(zone('panel')); expect(screen.getByRole('button', { name: p.applied })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: e.textTab })); expect(cut()).toHaveValue('MIRO');
    await user.click(screen.getByRole('button', { name: p.reset }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: p.confirmReset }));
    expect(cut()).toHaveValue(''); expect(screen.getByTestId('base-lettering')).toHaveTextContent('Home');
    expect(screen.getByTestId('cut-lettering')).toBeEmptyDOMElement();
    await user.click(screen.getByRole('button', { name: p.undo })); expect(cut()).toHaveValue('MIRO');
    expect(screen.getByRole('button', { name: p.applied })).toBeDisabled();
    await user.click(zone('base')); expect(base()).toHaveValue('Home');
  });

  it('rejects unsupported cut letters without hiding the problem after a zone switch', async () => {
    const user = show(); await user.click(screen.getByRole('button', { name: e.textTab }));
    type(cut(), 'Мир'); expect(cut()).toHaveValue('Мир');
    expect(screen.getByRole('alert')).toHaveTextContent(z.cutUnsupported);
    expect(screen.getByTestId('cut-lettering')).toBeEmptyDOMElement(); expect(apply()).toBeDisabled();
    await user.click(zone('base')); type(base(), 'Home');
    expect(apply()).toBeDisabled(); expect(document.querySelector('.studio-apply-status')).toHaveTextContent(z.zoneNames.panel + ': ' + z.cutUnsupported);
    expect(screen.getByTestId('base-lettering')).toHaveTextContent('Home');
    await user.click(zone('panel')); await user.click(screen.getByRole('button', { name: e.textTab }));
    type(cut(), 'MIRO'); await waitFor(() => expect(apply()).toBeEnabled());
  });

  it('also blocks apply for oversized engraving while the cut panel is selected', async () => {
    const user = show(); await user.click(zone('base')); type(base(), 'W'.repeat(32));
    expect(base()).toHaveAttribute('aria-invalid', 'true');
    await user.click(zone('panel')); expect(apply()).toBeDisabled();
    expect(document.querySelector('.studio-apply-status')).toHaveTextContent(z.zoneNames.base + ': ' + p.textTooWide);
    expect(screen.getByTestId('base-lettering')).toBeEmptyDOMElement();
  });

  it('does not render or apply cut lettering in a fallback font while its asset loads', async () => {
    let finish!: (font: LoadedStudioFont) => void;
    const pending = new Promise<LoadedStudioFont>(resolve => { finish = resolve; }); load.mockReturnValue(pending);
    const user = show(); await user.click(screen.getByRole('button', { name: e.textTab })); type(cut(), 'MIRO');
    expect(screen.getByTestId('cut-lettering')).toBeEmptyDOMElement(); expect(apply()).toBeDisabled();
    expect(screen.getByText(z.cutFontLoading)).toBeVisible();
    await user.click(zone('base')); expect(apply()).toBeDisabled();
    await act(async () => finish(loaded('stencil')));
    expect(screen.getByTestId('cut-lettering')).toHaveTextContent('MIRO'); expect(apply()).toBeEnabled();
  });

  it('keeps a successful explicit font retry when an older automatic request later fails', async () => {
    let fail!: (error: Error) => void;
    const pending = new Promise<LoadedStudioFont>((_, reject) => { fail = reject; });
    load.mockReturnValueOnce(pending);
    const user = show(); await user.click(screen.getByRole('button', { name: e.textTab })); type(cut(), 'MIRO');
    await user.click(screen.getByRole('button', { name: 'Aa ' + z.fontStencil }));
    expect(screen.getByTestId('cut-lettering')).toHaveTextContent('MIRO');
    await act(async () => fail(new Error('LATE_INITIAL_FONT_FAILURE')));
    expect(screen.getByTestId('cut-lettering')).toHaveTextContent('MIRO'); expect(apply()).toBeEnabled();
  });

  it('restricts text-only areas to their configured font without preset shortcuts or AI', () => {
    const source = products.getStudioProduct('loop');
    vi.spyOn(products, 'getStudioProduct').mockReturnValue({ ...source, zones: [{ ...source.zones[0], fonts: ['sans'], tools: ['text'], allowCustomFonts: false }] });
    show('loop');
    expect(screen.getByText(p.textOnlyRequired)).toBeVisible();
    expect(screen.queryByRole('button', { name: 'MK ' + p.presetName })).toBeNull();
    expect(screen.queryByRole('button', { name: 'home. ' + p.presetMinimal })).toBeNull();
    expect(screen.queryByRole('button', { name: 'grow with love ' + p.presetPhrase })).toBeNull();
    expect(screen.getByRole('button', { name:'Aa ' + p.sans })).toBeVisible();
    expect(screen.queryByRole('button', { name:'Aa ' + p.serif })).toBeNull();
    expect(screen.queryByRole('button', { name:'Aa ' + p.mono })).toBeNull();
    expect(screen.queryByRole('button', { name: e.aiTab })).toBeNull();
    expect(screen.queryByLabelText(e.uploadFont)).toBeNull();
    expect(screen.queryByRole('button', { name: e.createWithAi })).toBeNull();
  });
});
