import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createAppStore } from '../../store';
import ProductExperience from '../ProductExperience';
import { personalizationCopy } from './personalizationCopy';
import { productCopy } from '../copy';
import { loopPricingCopy } from '../loopPricingCopy';
import { money } from '../productDisplay';
import { storefrontLocales } from '../locale';
import type { StorefrontLocale } from '../locale';
import type { ModelDecoration } from './preview/personalizationModel';
import { studioExtensionCopy } from './studioExtensionCopy';
import { studioCompositionCopy } from './design/studioCompositionCopy';
import { studioZoneCopy } from './studioZoneCopy';
import type { Lettering } from './design/personalization';
import type { CreativeDraft } from './session/studioCreativeContext';
import { lampBaseLetteringArea, loopLetteringArea, LETTERING_INSET } from './design/studioGeometry';

// The adapter and real viewer have separate lifecycle/geometry tests. Here we
// verify only the UI's variant/value/visibility contract at that boundary.
vi.mock('../media/ModelPreview', async () => {
  const { createPortal } = await import('react-dom');
  const { useId } = await import('react');
  return { default: function ModelFixture({ target, decoration }: { target: HTMLElement | null; decoration: ModelDecoration }) {
    const instance = useId();
    return target ? createPortal(<div data-testid="model-decoration" data-instance={instance} data-variant={decoration.variant} data-x={decoration.x} data-y={decoration.y} data-show-area={decoration.showArea}>{decoration.text}</div>, target) : null;
  } };
});
// The renderer separately proves mesh picking. These fixture clicks exercise the
// real studio's capability selection and draft ownership after a surface pick.
vi.mock('./preview/StudioLampPreview', () => ({
  default: function LampPickingFixture({ value, draft, zone, onSelectZone, onMoveObject, selectedId, onMoveEnd, highlightTextBand }: {
    value: Lettering; draft: CreativeDraft; zone: 'panel' | 'base';
    onSelectZone: (zone: 'panel' | 'base') => void;
    onMoveObject?: (id: string, x: number, y: number) => void; selectedId?: string | null; onMoveEnd?: () => void; highlightTextBand?: boolean;
  }) {
    return <div data-testid="lamp-picking-fixture" data-zone={zone} data-pattern={draft.pattern} data-text-band={highlightTextBand}>
      <button type="button" onClick={() => onSelectZone('panel')}>Pick panel surface</button>
      <button type="button" onClick={() => onSelectZone('base')}>Pick base surface</button>
      <output data-testid="lamp-base-text" data-x={value.x} data-y={value.y}>{value.text}</output>
      {zone === 'base' && onMoveObject && selectedId && <button type="button" onClick={() => { onMoveObject(selectedId, -1000, 1000); onMoveEnd?.(); }}>Drag base to lower left</button>}
    </div>;
  },
}));
beforeEach(() => {
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(() => ({ font: '', measureText(this: { font: string }, text: string) { return { width: [...text].length * parseFloat(this.font) * .6 }; } }) as unknown as CanvasRenderingContext2D);
});
let key = 0;
function show(path = '/products/loop/personalize?variant=cobalt', locale: StorefrontLocale = 'en') {
  const store = createAppStore(locale === 'de' ? 'de' : 'en');
  const view = render(<Provider store={store}><MemoryRouter initialEntries={[{ pathname: path.split('?')[0], search: path.includes('?') ? '?' + path.split('?')[1] : '', key: 'studio-' + ++key }]}><ProductExperience /></MemoryRouter></Provider>);
  return { ...view, store, user: userEvent.setup() };
}
const p = personalizationCopy.en, t = productCopy.en;
const e = studioExtensionCopy.en, z = studioZoneCopy.en;
const text = () => screen.getByRole('textbox', { name: p.textLabel });
const apply = () => screen.getByRole('button', { name: p.apply });

describe('personalisation studio', () => {
  it('selects the configured lamp area through a surface pick without exposing unsupported tools', async () => {
    const { user } = show('/products/loop/personalize?variant=cobalt&product=lamp');
    expect(screen.getByTestId('lamp-picking-fixture')).toHaveAttribute('data-zone', 'panel');
    expect(screen.getByRole('button', { name: z.patternTab })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: e.textTab })).toBeVisible();
    expect(screen.getByRole('button', { name: e.imageTab })).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Pick base surface' }));
    expect(screen.getByTestId('lamp-picking-fixture')).toHaveAttribute('data-zone', 'base');
    expect(screen.getByRole('button', { name: e.textTab })).toHaveAttribute('aria-pressed', 'true');
    expect(text()).toHaveValue('');
    expect(screen.queryByRole('button', { name: z.patternTab })).toBeNull();
    expect(screen.queryByRole('button', { name: e.aiTab })).toBeNull();
    expect(screen.getByRole('button', { name: e.imageTab })).toBeDisabled();
    expect(screen.getByRole('button', { name: studioCompositionCopy.en.addImage })).toBeEnabled();
    await user.click(screen.getByRole('button', { name: 'Pick base surface' }));
    expect(screen.getByRole('button', { name: e.textTab })).toHaveAttribute('aria-pressed', 'true');
    expect(text()).toHaveValue('');
  });

  it('preserves independent panel and base drafts when choosing areas on the model', async () => {
    const { user, store } = show('/products/loop/personalize?variant=cobalt&product=lamp');
    await user.click(within(screen.getByRole('group', { name: e.cutout })).getByRole('button', { name: e.patternForest }));
    await user.click(screen.getByRole('button', { name: e.aiTab }));
    fireEvent.change(screen.getByRole('textbox', { name: e.aiPrompt }), { target: { value: 'A quiet forest' } });
    await user.click(screen.getByRole('button', { name: 'Pick panel surface' }));
    expect(screen.getByRole('button', { name: e.aiTab })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('textbox', { name: e.aiPrompt })).toHaveValue('A quiet forest');
    await user.click(screen.getByRole('button', { name: 'Pick base surface' }));
    fireEvent.change(text(), { target: { value: 'OUR HOME' } });
    expect(screen.getByTestId('lamp-base-text')).toHaveTextContent('OUR HOME');
    expect(screen.getByTestId('lamp-picking-fixture')).toHaveAttribute('data-pattern', 'forest');
    await user.click(screen.getByRole('button', { name: 'Pick panel surface' }));
    expect(screen.getByRole('button', { name: z.patternTab })).toHaveAttribute('aria-pressed', 'true');
    expect(within(screen.getByRole('group', { name: e.cutout })).getByRole('button', { name: e.patternForest })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('lamp-base-text')).toHaveTextContent('OUR HOME');
    await user.click(screen.getByRole('button', { name: e.aiTab }));
    expect(screen.getByRole('textbox', { name: e.aiPrompt })).toHaveValue('A quiet forest');
    await user.click(screen.getByRole('button', { name: 'Pick base surface' }));
    expect(text()).toHaveValue('OUR HOME');
    expect(screen.queryByRole('button', { name: e.aiTab })).toBeNull();
    expect(store.getState().productDemo.cart).toEqual([]);
    expect(JSON.stringify(store.getState())).not.toContain('OUR HOME');
  });

  it('starts blank and asks for text or a picture before applying', () => {
    const { store } = show();
    expect(text()).toHaveValue(''); expect(apply()).toBeDisabled(); expect(screen.getByText(p.textRequired)).toBeVisible();
    expect(screen.getByRole('button', { name: p.undo })).toBeDisabled();
    expect(document.querySelector('.studio-sketch')).not.toHaveTextContent('MK');
    expect(store.getState().productDemo.cart).toEqual([]);
    expect(document.title).toBe(p.eyebrow + ' · Loop · Creastrix');
  });
  it.each(['loop','lamp'])('opens only the selected %s product without demo controls, presets or a price borrowed from another product', product => {
    show('/products/loop/personalize?variant=cobalt&product=' + product);
    const studio=document.querySelector('.personalization-studio') as HTMLElement;
    expect(studio).toHaveAttribute('data-studio-product',product);
    expect(document.title).toBe(p.eyebrow + ' · ' + (product === 'lamp' ? e.lampName : 'Loop') + ' · Creastrix');
    expect(within(studio).queryByRole('group',{ name:e.chooseExample })).toBeNull();
    expect(studio.querySelector('.studio-example-picker, .studio-quiet-tag, .studio-inspiration, .studio-stage-caption, .studio-price')).toBeNull();
    if (product === 'loop') expect(studio.querySelector('.studio-price-summary')).toHaveTextContent(loopPricingCopy.en.from(money(500, 'en')));
    else expect(studio.querySelector('.studio-price-summary')).toBeNull();
    if (product === 'lamp') expect(within(studio).getByText(p.savedNote)).toBeInTheDocument();
    else expect(within(studio).queryByText(p.savedNote)).toBeNull();
    expect(within(studio).getByText(p.disclosureTitle,{ selector:'summary' })).toBeVisible();
  });
  it('previews one fee per item for multiple layers; only an applied design affects the card and removal leaves the cart snapshot intact', async () => {
    const { user, store } = show();
    const quote = () => document.querySelector('.studio-price-summary');
    expect(quote()).toHaveTextContent('€24.00');
    fireEvent.change(text(), { target: { value: 'ONE' } });
    expect(quote()).toHaveTextContent('€29.00');
    await user.click(screen.getByRole('link', { name: p.back }));
    expect(document.querySelector('.product-price')).toHaveTextContent('€24.00');
    await user.click(screen.getByRole('link', { name: t.personalButton }));
    await user.click(screen.getByRole('button', { name: studioCompositionCopy.en.addText }));
    fireEvent.change(text(), { target: { value: 'TWO' } });
    expect(quote()).toHaveTextContent('€29.00');
    await user.click(apply());
    await user.click(screen.getByRole('link', { name: p.back }));
    expect(document.querySelector('.product-price')).toHaveTextContent('€29.00');
    expect(document.querySelector('.product-price-breakdown')).toHaveTextContent(loopPricingCopy.en.fee('€5.00'));
    expect(screen.queryByText(t.without)).toBeNull();
    await user.click(screen.getByRole('button', { name: t.increase }));
    expect(document.querySelector('.product-subtotal')).toHaveTextContent('€58.00');
    await user.click(screen.getByRole('button', { name: t.add }));
    const inCart = t.alreadyInCart.replace('{n}', '2');
    expect(screen.getByText(inCart)).toBeVisible();
    const savedLines = store.getState().productDemo.cart;
    expect(savedLines[0]?.design?.objects).toHaveLength(2);
    await user.click(screen.getByRole('button', { name: e.removeDesign }));
    expect(screen.queryByText(inCart)).toBeNull();
    expect(document.querySelector('.product-price')).toHaveTextContent('€24.00');
    expect(document.querySelector('.product-subtotal')).toHaveTextContent('€24.00');
    expect(store.getState().productDemo.cart).toEqual(savedLines);
    await user.click(screen.getByRole('link', { name: t.personalButton }));
    expect(text()).toHaveValue('TWO');
    await user.click(apply());
    await user.click(screen.getByRole('link', { name: p.back }));
    expect(document.querySelector('.product-price')).toHaveTextContent('€29.00');
    expect(screen.getByText(inCart)).toBeVisible();
  });
  it.each(['unknown','constructor','lamp&product=loop','lamp&product=lamp'])('uses the same safe product for rendering and metadata on invalid/duplicate product %s', query => {
    show('/products/loop/personalize?product=' + query);
    expect(document.querySelector('.personalization-studio')).toHaveAttribute('data-studio-product','loop');
    expect(document.title).toBe(p.eyebrow + ' · Loop · Creastrix');
    expect(text()).toHaveValue('');
  });
  it.each(['unknown', 'constructor', 'chalk&variant=coral'])('uses safe cobalt for invalid/duplicate variant %s', query => {
    show('/products/loop/personalize?variant=' + query);
    expect(document.querySelector('[data-studio-variant]')).toHaveAttribute('data-studio-variant', 'cobalt');
  });
  it('edits live, supports grouped undo/redo, and applies an isolated snapshot', async () => {
    const { user, store } = show(), originalAuth = store.getState().authentication;
    fireEvent.change(text(), { target: { value: 'M' } }); fireEvent.change(text(), { target: { value: 'MIRO' } });
    expect(document.querySelector('.studio-sketch')).toHaveTextContent('MIRO');
    await user.click(screen.getByRole('button', { name: p.undo })); expect(text()).toHaveValue('');
    await user.click(screen.getByRole('button', { name: p.redo })); expect(text()).toHaveValue('MIRO');
    await user.click(apply()); expect(screen.getByRole('button', { name: p.applied })).toBeDisabled();
    fireEvent.change(text(), { target: { value: 'DRAFT' } });
    await user.click(screen.getByRole('link', { name: p.back }));
    expect(document.querySelector('.product-applied-lettering')).toHaveTextContent('MIRO');
    expect(document.querySelector('.product-applied-lettering')).not.toHaveTextContent('DRAFT');
    await user.click(screen.getByRole('link', { name: t.personalButton })); expect(text()).toHaveValue('DRAFT');
    expect(store.getState().authentication).toBe(originalAuth); expect(store.getState().productDemo.cart).toEqual([]);
    expect(JSON.stringify(localStorage)).not.toContain('MIRO'); expect(JSON.stringify(store.getState())).not.toContain('DRAFT');
  });
  it('keeps per-colour drafts and applied state separate, and removal does not erase the draft', async () => {
    const { user } = show();
    fireEvent.change(text(), { target: { value: 'BLUE' } }); await user.click(apply());
    await user.click(screen.getByRole('link', { name: p.back }));
    await user.click(screen.getByRole('button', { name: 'Coral' }));
    expect(document.querySelector('.product-applied-lettering')).toBeNull();
    await user.click(screen.getByRole('link', { name: t.personalButton })); expect(text()).toHaveValue('');
    fireEvent.change(text(), { target: { value: 'CORAL' } });
    await user.click(screen.getByRole('link', { name: p.back }));
    await user.click(screen.getByRole('button', { name: 'Cobalt blue' }));
    await user.click(screen.getByRole('button', { name: e.removeDesign })); expect(document.querySelector('.product-applied-lettering')).toBeNull();
    expect(screen.getByRole('link', { name: t.personalButton })).toHaveFocus();
    await user.click(screen.getByRole('link', { name: t.personalButton })); expect(text()).toHaveValue('BLUE');
  });
  it('validates overflow, preserves editable text, and never sends invalid text to 3D', async () => {
    const { user } = show();
    fireEvent.change(text(), { target: { value: 'WWWWWWWWWWWWWWWWWW' } });
    expect(text()).toHaveAttribute('aria-invalid', 'true'); expect(apply()).toBeDisabled();
    expect(screen.getByRole('alert')).toHaveTextContent(p.textTooWide);
    await user.click(screen.getByRole('button', { name: p.model }));
    expect(await screen.findByTestId('model-decoration')).toBeEmptyDOMElement();
    fireEvent.change(text(), { target: { value: 'MIRO' } });
    expect(screen.getByTestId('model-decoration')).toHaveTextContent('MIRO'); expect(apply()).toBeEnabled();
  });
  it('supports authored text, fonts, inks, size and keyboard-position controls', async () => {
    const { user } = show();
    fireEvent.change(text(), { target:{ value:'MK' } }); expect(text()).toHaveValue('MK');
    await user.click(screen.getByRole('button', { name: 'Aa ' + p.mono }));
    await user.click(screen.getByRole('button', { name: p.goldInk }));
    fireEvent.change(screen.getByRole('slider', { name: p.sizeLabel }), { target: { value: '20' } });
    const rendered = document.querySelector('.studio-sketch text')!;
    expect(rendered).toHaveAttribute('fill', '#b68a45'); expect(rendered).toHaveAttribute('font-size', '20');
    expect(rendered.getAttribute('font-family')).toContain('Courier');
    await user.click(screen.getByText(p.positionLabel, { selector: 'summary' }));
    fireEvent.change(screen.getByRole('slider', { name: p.horizontal }), { target: { value: '250' } });
    expect(rendered.querySelector('tspan')).toHaveAttribute('x', '250');
    await user.click(screen.getByRole('button', { name: p.center })); expect(rendered.querySelector('tspan')).toHaveAttribute('x', '240');
  });
  it.each(['loop', 'lamp'] as const)('reaches the complete %s text area, preserves the position, and undoes a gesture', async product => {
    const { user } = show('/products/loop/personalize?product=' + product);
    if (product === 'lamp') await user.click(screen.getByRole('button', { name: 'Pick base surface' }));
    fireEvent.change(text(), { target: { value: 'Hi' } });
    fireEvent.change(screen.getByRole('slider', { name: p.sizeLabel }), { target: { value: '20' } });
    await user.click(screen.getByText(p.positionLabel, { selector: 'summary' }));
    const x = screen.getByRole('slider', { name: p.horizontal }) as HTMLInputElement;
    const y = screen.getByRole('slider', { name: p.vertical }) as HTMLInputElement;
    const area = product === 'lamp' ? lampBaseLetteringArea : loopLetteringArea;
    // The fixture measures two 20px characters at 12px each; its two-dimensional
    // text block, not the old arbitrary centre limits, sets the edge clearance.
    expect(Number(x.min)).toBeCloseTo(area.left + 12 + LETTERING_INSET);
    expect(Number(x.max)).toBeCloseTo(area.right - 12 - LETTERING_INSET);
    expect(Number(y.min)).toBeCloseTo(area.top + 12 + LETTERING_INSET);
    expect(Number(y.max)).toBeCloseTo(area.bottom - 12 - LETTERING_INSET);
    fireEvent.change(x, { target: { value: x.min } }); fireEvent.pointerUp(x);
    fireEvent.change(y, { target: { value: y.max } }); fireEvent.pointerUp(y);
    expect(Number(x.value)).toBeCloseTo(Number(x.min));
    expect(Number(y.value)).toBeCloseTo(Number(y.max));
    await user.click(screen.getByRole('button', { name: p.undo }));
    expect(Number(y.value)).toBeCloseTo(278);
    await user.click(screen.getByRole('button', { name: p.redo }));
    if (product === 'lamp') {
      await user.click(screen.getByRole('button', { name: 'Pick panel surface' }));
      expect(screen.queryByRole('button', { name: 'Drag base to lower left' })).toBeNull();
      await user.click(screen.getByRole('button', { name: e.textTab }));
      expect(screen.getByTestId('lamp-picking-fixture')).toHaveAttribute('data-text-band', 'true');
      await user.click(screen.getByRole('button', { name: 'Pick base surface' }));
      expect(screen.getByTestId('lamp-picking-fixture')).toHaveAttribute('data-text-band', 'false');
      await user.click(screen.getByRole('button', { name: 'Drag base to lower left' }));
      expect(Number(screen.getByTestId('lamp-base-text').getAttribute('data-x'))).toBeCloseTo(Number(x.min));
      expect(Number(screen.getByTestId('lamp-base-text').getAttribute('data-y'))).toBeCloseTo(Number(y.max));
    } else {
      await user.click(screen.getByRole('button', { name: p.model }));
      const model = await screen.findByTestId('model-decoration');
      expect(Number(model.getAttribute('data-x'))).toBeCloseTo(Number(x.min));
      expect(Number(model.getAttribute('data-y'))).toBeCloseTo(Number(y.max));
      expect(model).toHaveAttribute('data-show-area', 'true');
      await user.click(screen.getByRole('button', { name: p.original }));
      expect(model).toHaveAttribute('data-show-area', 'false');
    }
  });
  it('includes visible glyph overhang in the area limits instead of trusting advance width alone', async () => {
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(() => ({ font: '', measureText() {
      return { width: 30, actualBoundingBoxLeft: 25, actualBoundingBoxRight: 35, actualBoundingBoxAscent: 20, actualBoundingBoxDescent: 16 };
    } }) as unknown as CanvasRenderingContext2D);
    const { user } = show();
    fireEvent.change(text(), { target: { value: 'Hi' } });
    await user.click(screen.getByText(p.positionLabel, { selector: 'summary' }));
    expect(Number((screen.getByRole('slider', { name: p.horizontal }) as HTMLInputElement).min)).toBe(157 + 35 + 4);
    expect(Number((screen.getByRole('slider', { name: p.vertical }) as HTMLInputElement).min)).toBe(223 + 20 + 4);
  });
  it('compares with an actually blank product, then restores the design', async () => {
    const { user } = show(); fireEvent.change(text(), { target: { value: 'HELLO' } });
    await user.click(screen.getByRole('button', { name: p.original }));
    expect(document.querySelector('.studio-stage .studio-sketch')).not.toHaveTextContent('HELLO');
    await user.click(screen.getByRole('button', { name: p.original }));
    expect(document.querySelector('.studio-sketch')).toHaveTextContent('HELLO'); expect(text()).toHaveValue('HELLO');
  });
  it('confirms reset, makes it undoable, and preserves the applied preview', async () => {
    const { user } = show(); fireEvent.change(text(), { target: { value: 'KEEP' } }); await user.click(apply());
    await user.click(screen.getByRole('button', { name: p.reset }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: p.cancel })); expect(text()).toHaveValue('KEEP');
    await user.click(screen.getByRole('button', { name: p.reset }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: p.confirmReset })); expect(text()).toHaveValue('');
    await user.click(screen.getByRole('button', { name: p.undo })); expect(text()).toHaveValue('KEEP');
    await user.click(screen.getByRole('link', { name: p.back })); expect(document.querySelector('.product-applied-lettering')).toHaveTextContent('KEEP');
  });
  it('keeps the expanded frame stable across modes and restores focus on Escape', async () => {
    const { user } = show(); fireEvent.change(text(), { target: { value: 'HI' } });
    const opener = screen.getByRole('button', { name: p.expand }); await user.click(opener);
    const dialog = screen.getByRole('dialog'), frame = dialog.querySelector('.studio-stage');
    await user.click(within(dialog).getByRole('button', { name: p.model }));
    expect(await within(dialog).findByTestId('model-decoration')).toHaveTextContent('HI');
    await user.click(within(dialog).getByRole('button', { name: p.edit })); expect(dialog.querySelector('.studio-stage')).toBe(frame);
    await user.keyboard('{Escape}'); expect(screen.queryByRole('dialog')).toBeNull(); expect(opener).toHaveFocus();
  });
  it('preserves the loaded model instance through the initially empty modal host', async () => {
    const { user } = show(); fireEvent.change(text(), { target: { value: 'HI' } });
    await user.click(screen.getByRole('button', { name: p.model }));
    const identity = (await screen.findByTestId('model-decoration')).getAttribute('data-instance');
    await user.click(screen.getByRole('button', { name: p.expand }));
    expect(await within(screen.getByRole('dialog')).findByTestId('model-decoration')).toHaveAttribute('data-instance', identity);
    await user.keyboard('{Escape}'); expect(screen.getByTestId('model-decoration')).toHaveAttribute('data-instance', identity);
  });
  it('starts a fresh private draft after unmount rather than restoring text from browser storage', () => {
    const first = show(); fireEvent.change(text(), { target: { value: 'PRIVATE' } }); first.unmount();
    show(); expect(text()).toHaveValue(''); expect(apply()).toBeDisabled(); expect(screen.getByText(p.textRequired)).toBeVisible();
  });
  it.each(['undo', 'reset', 'edit'] as const)('announces truncated input and clears that transient notice after %s', async action => {
    const { user } = show(); fireEvent.change(text(), { target: { value: 'one\ntwo\nthree' } });
    expect(text()).toHaveValue('one\ntwo'); expect(screen.getByText('Text limit for this area: 32 characters · Max. lines: 2.')).toBeVisible();
    if (action === 'undo') await user.click(screen.getByRole('button', { name: p.undo }));
    else if (action === 'edit') fireEvent.change(text(), { target:{ value:'MY HOME' } });
    else {
      await user.click(screen.getByRole('button', { name: p.reset }));
      await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: p.confirmReset }));
    }
    expect(screen.queryByText('Text limit for this area: 32 characters · Max. lines: 2.')).toBeNull();
  });
  it.each(storefrontLocales)('keeps the draft across %s translation and localises its controls', async locale => {
    const { user } = show(); fireEvent.change(text(), { target: { value: 'Миро' } });
    await user.selectOptions(screen.getByRole('combobox', { name: t.language }), locale);
    const local = personalizationCopy[locale];
    expect(screen.getByRole('heading', { name: local.title })).toBeVisible();
    expect(screen.getByRole('textbox', { name: local.textLabel })).toHaveValue('Миро');
    expect(screen.getByRole('button', { name: local.apply })).toBeEnabled();
    expect(document.documentElement.lang).toBe(locale);
  });
});
