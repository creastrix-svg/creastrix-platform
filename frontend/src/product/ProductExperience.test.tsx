import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { Link, MemoryRouter, useLocation, useNavigate } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import App from '../App';
import { createAppStore, languageSelected } from '../store';
import { copy } from '../i18n';
import type { Language } from '../language';
import ProductExperience from './ProductExperience';
import { productCopy } from './copy';
import { cartAdded } from './demoStore';
import Carousel from './Carousel';
import indexSource from '../../index.html?raw';
import { storefrontLocales } from './locale';
import MediaGallery from './MediaGallery';
import { mediaForVariant, money, photoDescription, variantById } from './catalog';
import * as catalog from './catalog';

vi.mock('@google/model-viewer', () => ({}));
afterEach(() => { vi.unstubAllGlobals(); });
let fixtureKey = 0;
function readyPhotos() { document.querySelectorAll('img').forEach(image => fireEvent.load(image)); }
async function readyModel(label: string) {
  const viewer = await screen.findByLabelText(label, { selector: 'model-viewer' });
  let orbit = { theta: Math.PI / 6, phi: 68 * Math.PI / 180, radius: 1 }, target = { x: 0, y: .05, z: 0 };
  Object.assign(viewer, { interpolationDecay: 50, getCameraOrbit: () => ({ ...orbit }), getCameraTarget: () => ({ ...target }), jumpCameraToGoal: () => {
    const values = viewer.getAttribute('camera-orbit')?.split(' ').map(parseFloat); if (values) orbit = { theta: values[0], phi: values[1], radius: values[2] };
    const point = viewer.getAttribute('camera-target')?.split(' ').map(parseFloat); if (point) target = { x: point[0], y: point[1], z: point[2] };
  } });
  fireEvent(viewer, new Event('load')); return viewer;
}
function HistoryControls() {
  const navigate = useNavigate(), location = useLocation();
  return <><button type="button" onClick={() => navigate(-1)}>History back</button><button type="button" onClick={() => navigate(1)}>History forward</button>
    <Link to="/products/loop?variant=chalk">Explicit chalk</Link><Link to="/products/loop?variant=constructor">Invalid variant</Link><Link to="/products/loop?variant=chalk&variant=coral">Duplicate variant</Link><Link to="/products/loop">Default product</Link>
    <output data-testid="current-product-route">{location.pathname + location.search}</output></>;
}
function show(path = '/products/loop', language: Language = 'en', app = false, history = false, loadMedia = true) {
  const store = createAppStore(language);
  const initial = new URL(path, 'https://example.test');
  const view = render(<Provider store={store}><MemoryRouter initialEntries={[{ pathname: initial.pathname, search: initial.search, key: 'product-fixture-' + ++fixtureKey }]}>{app ? <App /> : <ProductExperience />}{history && <HistoryControls />}</MemoryRouter></Provider>);
  // Ordinary route tests start with explicitly loaded DOM-fixture photos. Separate
  // MediaImage/gallery tests below retain real pending/error/stale event control.
  if (loadMedia) readyPhotos();
  return { ...view, store, user: userEvent.setup(), t: productCopy[language] };
}
function returnScrollFixture() {
  const descriptor = Object.getOwnPropertyDescriptor(window, 'scrollY');
  let top = 0;
  const maximum = () => document.querySelector('.product-information') ? document.querySelector('.product-description details[open]') ? 2400 : 600 : 350;
  Object.defineProperty(window, 'scrollY', { configurable: true, get: () => Math.min(top, maximum()) });
  const scroll = vi.spyOn(window, 'scrollTo').mockImplementation((options?: ScrollToOptions | number) => { if (typeof options === 'object') top = Math.min(options.top ?? top, maximum()); });
  return { scroll, get top() { return window.scrollY; }, depart(value: number) { top = value; fireEvent.scroll(window); }, restore() { if (descriptor) Object.defineProperty(window, 'scrollY', descriptor); scroll.mockRestore(); } };
}

describe('bounded public product preferences and quiet presentation', () => {
  const key = 'creastrix.product.loop.preferences';
  const remembered = { version: 1, selectedVariant: 'cobalt-chalk', purchaseQuantities: { cobalt: 2, coral: 3, chalk: 4, 'cobalt-chalk': 3 }, mediaMode: 'model', descriptionOpen: true };
  it('restores colour, quantity, 3D intent and disclosure on a fresh store without restoring cart, favorite or identity', async () => {
    localStorage.setItem(key, JSON.stringify(remembered));
    const { store, t } = show();
    expect(screen.getByRole('button', { name: 'Cobalt & chalk' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('textbox', { name: t.quantity })).toHaveValue('3');
    expect(screen.getByRole('button', { name: t.model })).toHaveAttribute('aria-pressed', 'true');
    expect(document.querySelector('.product-description details')).toHaveAttribute('open');
    expect(store.getState().productDemo.cart).toEqual([]); expect(store.getState().productDemo.favorite).toBeNull();
    expect(store.getState().authentication).toMatchObject({ phase: 'CHECKING', user: null, owner: null });
    await readyModel(t.model);
  });
  it('persists only the four public preferences after ordinary UI actions and reloads them in a new store', async () => {
    const first = show(), { user, t } = first;
    const originalUi = first.store.getState().ui, originalAuth = first.store.getState().authentication;
    await user.click(screen.getByRole('button', { name: 'Coral' }));
    fireEvent.change(screen.getByRole('textbox', { name: t.quantity }), { target: { value: '3' } });
    await user.click(screen.getByRole('button', { name: t.model })); await readyModel(t.model);
    const details = document.querySelector<HTMLDetailsElement>('.product-description details')!;
    details.open = true; fireEvent(details, new Event('toggle'));
    await user.click(screen.getByRole('button', { name: t.favorite }));
    await user.click(screen.getByRole('button', { name: t.add }));
    expect(JSON.parse(localStorage.getItem(key)!)).toEqual({ ...remembered, selectedVariant: 'coral', purchaseQuantities: { cobalt: 1, coral: 3, chalk: 1, 'cobalt-chalk': 1 } });
    expect(first.store.getState().ui).toBe(originalUi); expect(first.store.getState().authentication).toBe(originalAuth);
    first.unmount();
    const second = show();
    expect(screen.getByRole('button', { name: 'Coral' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('textbox', { name: t.quantity })).toHaveValue('3');
    expect(screen.getByRole('button', { name: t.model })).toHaveAttribute('aria-pressed', 'true');
    expect(document.querySelector('.product-description details')).toHaveAttribute('open');
    expect(second.store.getState().productDemo.cart).toEqual([]); expect(second.store.getState().productDemo.favorite).toBeNull();
    await readyModel(t.model);
  });
  it.each([
    ['/products/loop', 'cobalt-chalk', '3'],
    ['/products/loop?variant=coral', 'coral', '3'],
    ['/products/loop?variant=unknown', 'cobalt', '2'],
    ['/products/loop?variant=coral&variant=chalk', 'cobalt', '2'],
  ])('keeps explicit URL priority and safe invalid/duplicate fallback over stored colour for %s', (path, variant, quantity) => {
    localStorage.setItem(key, JSON.stringify({ ...remembered, mediaMode: 'photo' }));
    const { store, t } = show(path);
    expect(screen.getByRole('button', { name: variantById(variant as catalog.VariantId).name.en })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('textbox', { name: t.quantity })).toHaveValue(quantity);
    expect(store.getState().productDemo.selectedVariant).toBe(variant);
    expect(JSON.parse(localStorage.getItem(key)!).selectedVariant).toBe(variant);
  });
  it.each(storefrontLocales)('removes persistent demo warnings in %s without removing functional unavailable controls', async locale => {
    const { user, store, container } = show();
    await user.selectOptions(screen.getByRole('combobox', { name: productCopy.en.language }), locale);
    const t = productCopy[locale];
    expect(container.querySelector('.product-demo-banner')).toBeNull();
    expect(container.querySelector('.product-media-badge')).toBeNull();
    expect(container.querySelector('.product-buy-feedback')).toBeEmptyDOMElement();
    expect(container.querySelector('.product-footer')).not.toHaveTextContent(t.footerNote);
    await user.click(screen.getByRole('button', { name: t.personalButton }));
    expect(within(screen.getByRole('dialog')).getByText(t.personalBody)).toBeVisible();
    expect(within(screen.getByRole('dialog')).queryByText(t.preview)).toBeNull();
    await user.click(screen.getByRole('button', { name: t.close }));
    act(() => store.dispatch(cartAdded({ variant: 'cobalt', quantity: 24 })));
    expect(screen.getByRole('button', { name: t.add })).toBeDisabled(); expect(screen.getByText(t.allInCart)).toBeVisible();
    await user.click(within(screen.getByRole('banner')).getByRole('link', { name: t.cart }));
    expect(screen.queryByText(t.cartBoundary)).toBeNull(); expect(screen.queryByText(t.preview)).toBeNull();
    await user.click(within(screen.getByRole('banner')).getByRole('link', { name: t.favorites }));
    expect(screen.queryByText(t.savedNote)).toBeNull(); expect(screen.getByText(t.emptySaved)).toBeVisible();
    await user.click(screen.getByRole('link', { name: t.back }));
    await user.click(screen.getByRole('link', { name: t.noReviews }));
    expect(screen.queryByText(t.reviewsBody)).toBeNull();
    await user.click(screen.getByRole('link', { name: t.writeReview }));
    expect(screen.getByRole('button', { name: t.reviewSubmit })).toBeDisabled(); expect(screen.getByText(t.reviewDemoNote)).toBeVisible();
  });
});

describe('localized native more/less disclosure', () => {
  it.each([
    ['en', 'Show more', 'Show less'], ['de', 'Mehr anzeigen', 'Weniger anzeigen'],
    ['ru', 'Показать больше', 'Показать меньше'], ['pl', 'Pokaż więcej', 'Pokaż mniej'],
    ['es', 'Mostrar más', 'Mostrar menos'], ['fr', 'Afficher plus', 'Afficher moins'],
    ['it', 'Mostra di più', 'Mostra di meno'],
  ] as const)('switches the %s label with native open state and restores the correct label after refresh', async (locale, more, less) => {
    const first = show(), { user } = first;
    await user.selectOptions(screen.getByRole('combobox', { name: productCopy.en.language }), locale);
    const summary = document.querySelector<HTMLElement>('.product-description summary')!, details = summary.parentElement!;
    expect(summary).toHaveTextContent(more); expect(summary).not.toHaveTextContent(less);
    expect(summary).not.toHaveAttribute('role'); expect(summary).not.toHaveAttribute('tabindex');
    expect(summary.querySelector('.product-summary-label')).toHaveAttribute('data-label', more);
    expect(summary.querySelector('.product-summary-label')).toHaveAttribute('data-label-less', less);
    act(() => summary.focus()); expect(summary).toHaveFocus();
    expect(fireEvent.keyDown(summary, { key: 'Enter' })).toBe(true); expect(fireEvent.keyDown(summary, { key: ' ' })).toBe(true);
    await user.click(summary); expect(details).toHaveAttribute('open');
    expect(await screen.findByText(less)).toBeVisible(); expect(summary).not.toHaveTextContent(more);
    expect(first.store.getState().productDemo.descriptionOpen).toBe(true);
    await user.click(screen.getByRole('button', { name: variantById('coral').name[locale] })); expect(summary).toHaveTextContent(less);
    first.unmount();
    const second = show();
    await second.user.selectOptions(screen.getByRole('combobox', { name: productCopy.en.language }), locale);
    const restored = document.querySelector<HTMLElement>('.product-description summary')!;
    expect(restored.parentElement).toHaveAttribute('open'); expect(restored).toHaveTextContent(less);
    await second.user.click(restored); expect(await screen.findByText(more)).toBeVisible();
    expect(restored.parentElement).not.toHaveAttribute('open'); expect(second.store.getState().productDemo.descriptionOpen).toBe(false);
    expect(JSON.parse(localStorage.getItem('creastrix.product.loop.preferences')!).descriptionOpen).toBe(false);
  });
  it('reserves both labels in the same grid cell at weight 600 while hover only underlines and focus stays visible', () => {
    const styles = readFileSync('src/product/product.css', 'utf8');
    expect(styles).toMatch(/\.product-summary-label > span, \.product-summary-label::before, \.product-summary-label::after\s*\{\s*grid-area:\s*1 \/ 1;\s*\}/);
    expect(styles).toMatch(/\.product-summary-label::before\s*\{[^}]*content:\s*attr\(data-label-less\);[^}]*visibility:\s*hidden;[^}]*font-weight:\s*600/s);
    expect(styles).toMatch(/\.product-summary-label::after\s*\{[^}]*content:\s*attr\(data-label\);[^}]*visibility:\s*hidden;[^}]*font-weight:\s*600/s);
    expect(styles).toMatch(/\.product-description summary\s*\{[^}]*font-weight:\s*600/s);
    expect(styles.match(/\.product-description summary:hover\s*\{([^}]+)\}/)?.[1].trim()).toBe('text-decoration: underline;');
    expect(styles).toMatch(/\.product-description summary:focus-visible\s*\{[^}]*outline:\s*2px solid/s);
    expect(styles).not.toMatch(/caret-color|user-select:\s*none/);
  });
});

describe('scoped product style regressions', () => {
  const styles = readFileSync('src/product/product.css', 'utf8');
  it('keeps an opaque sticky header, bounded overlays and measured main-focus/anchor clearance without scroll-driven resizing', () => {
    const header = styles.match(/\.product-header\s*\{([^}]+)\}/)?.[1] ?? '';
    expect(header).toMatch(/position:\s*sticky;[^}]*top:\s*0;[^}]*z-index:\s*100;[^}]*background:\s*var\(--product-bg\)/s);
    expect(styles).toMatch(/scroll-margin-top:\s*calc\(var\(--product-header-height, 88px\) \+ 12px\)/);
    expect(styles).toMatch(/\.product-search-suggestions\s*\{[^}]*max-height:[^}]*100dvh[^}]*overflow-y:\s*auto/s);
    expect(styles).toMatch(/\.product-theme-menu\s*\{[^}]*z-index:\s*110/s);
    expect(styles).toMatch(/\.product-overlay\s*\{[^}]*z-index:\s*1000/s);
    const mobile = styles.slice(styles.indexOf('@media (max-width: 480px)'), styles.indexOf('@media (max-width: 380px)'));
    expect(mobile).toMatch(/\.product-header\s*\{[^}]*grid-template-columns:\s*minmax\(0, 1fr\) auto/s);
    expect(mobile).toMatch(/\.product-header-actions\s*\{[^}]*grid-row:\s*1/s);
    expect(mobile).toMatch(/\.product-search\s*\{[^}]*grid-column:\s*1 \/ -1;[^}]*grid-row:\s*2/s);
  });
  it('resets inherited mobile nav ordering without editing the authentication stylesheet', () => {
    const rule = styles.match(/\.product-nav-inner nav\s*\{([^}]+)\}/)?.[1] ?? '';
    expect(rule).toMatch(/\border:\s*0\s*;/);
    expect(rule).toMatch(/\bflex-basis:\s*auto\s*;/);
  });
  it('gives both light media overlays an explicit dark foreground with sufficient selector specificity', () => {
    const rule = styles.match(/\.product-photo-open > \.product-photo-expand, \.product-storefront \.product-model-expand\s*\{([^}]+)\}/)?.[1] ?? '';
    expect(rule).toMatch(/\bcolor:\s*#29342d\s*;/);
    expect(rule).toMatch(/\bz-index:\s*3\s*;/);
    expect(rule).toMatch(/\bbackground:\s*#ffffffed\s*;/);
    const hover = styles.match(/\.product-photo-open:hover > \.product-photo-expand, \.product-storefront \.product-model-expand:hover\s*\{([^}]+)\}/)?.[1] ?? '';
    expect(hover).toMatch(/\bbackground:\s*#f0f5ef\s*;/);
  });
  it('keeps the top category links hidden outside any breakpoint while retaining the category drawer action', () => {
    const base = styles.slice(0, styles.indexOf('@media'));
    expect(base).toMatch(/\.product-nav-inner nav \.product-category-link\s*\{\s*display:\s*none;\s*\}/);
    const { t } = show();
    expect(screen.getByRole('button', { name: t.categories })).toHaveAttribute('aria-haspopup', 'dialog');
  });
  it('keeps shared quantity feedback enabled-only and swatch focus/hover rings out of layout', () => {
    expect(styles).toContain('.product-quantity button:not(:disabled):hover');
    expect(styles).toContain('.product-quantity button:not(:disabled):focus-visible');
    expect(styles).toContain('.product-quantity button:not(:disabled):active');
    expect(styles).toMatch(/\.product-swatch:focus-visible\s*\{[^}]*outline:[^}]*var\(--product-swatch-focus\)/s);
    expect(styles).toMatch(/\.product-swatch:hover\s*\{[^}]*box-shadow:[^}]*var\(--product-swatch-hover\)/s);
    const hover = styles.match(/\.product-swatch:hover\s*\{([^}]+)\}/)?.[1] ?? '';
    expect(hover).not.toMatch(/padding|border|width|height|transform/);
    expect(styles).not.toContain('.product-swatch:last-child');
    expect(styles).toContain('@media (hover: hover) and (pointer: fine)');
  });
  it('styles disclosure feedback and a bounded icon theme popup without removing narrow wrapping or accessibility', () => {
    const hover = styles.match(/\.product-description summary:hover\s*\{([^}]+)\}/)?.[1] ?? '';
    const summary = styles.match(/\.product-description summary\s*\{([^}]+)\}/)?.[1] ?? '';
    expect(summary).toMatch(/font-weight:\s*600\s*;/); expect(summary).toMatch(/text-decoration:\s*none/);
    expect(styles).toMatch(/\.product-buy-feedback\s*\{[^}]*min-height:\s*54px/s);
    expect(hover.trim()).toBe('text-decoration: underline;');
    expect(hover).not.toMatch(/font-weight|background|border|outline|padding|width|height|transform/);
    const fineHover = styles.slice(styles.indexOf('@media (hover: hover) and (pointer: fine)'));
    expect(fineHover).toMatch(/\.product-storefront \.product-language-wrap select:hover\s*\{\s*background:\s*var\(--product-soft\);\s*\}/);
    expect(fineHover).toMatch(/\.product-category-menu:hover\s*\{\s*text-decoration:\s*underline;\s*text-underline-offset:\s*4px;\s*\}/);
    expect(styles).toMatch(/\.product-summary-label::after\s*\{[^}]*content:\s*attr\(data-label\);[^}]*visibility:\s*hidden;[^}]*font-weight:\s*600/s);
    expect(styles).toMatch(/\.product-description summary:focus-visible\s*\{[^}]*outline/s);
    const sizes = [...styles.matchAll(/\.product-storefront \.product-language-wrap select, \.product-storefront \.product-theme-trigger\s*\{([^}]+)\}/g)].map(match => match[1]);
    expect(sizes[0]).toMatch(/\bheight:\s*32px;[^}]*\bmin-height:\s*32px;[^}]*box-sizing:\s*border-box/s);
    expect(sizes.slice(1).length).toBeGreaterThanOrEqual(1);
    for (const size of sizes.slice(1)) expect(size).toMatch(/\bheight:\s*44px;[^}]*\bmin-height:\s*44px/s);
    expect(styles).toMatch(/\.product-theme-menu\s*\{[^}]*right:\s*0;[^}]*max-width:\s*calc\(100vw - 24px\)/s);
    expect(styles).not.toContain('.product-theme-wrap select');
    expect(styles).not.toContain('.product-theme-option');
    const mobile = styles.slice(styles.indexOf('@media (max-width: 480px)'), styles.indexOf('@media (max-width: 380px)'));
    expect(mobile).toMatch(/\.product-nav-inner\s*\{[^}]*flex-wrap:\s*wrap/s);
    expect(mobile).toMatch(/\.product-utilities\s*\{[^}]*flex-wrap:\s*wrap/s);
    expect(styles).toContain('@media (forced-colors: active)');
    expect(styles).toContain('@media (prefers-reduced-motion: reduce)');
  });
  it('keeps compact independent star hit targets adjacent without overlap and preserves coarse-pointer target size', () => {
    expect(styles).toMatch(/\.product-stars\s*\{[^}]*gap:\s*0\s*;/s);
    expect(styles).toMatch(/\.product-stars svg\s*\{[^}]*width:\s*16px;[^}]*height:\s*16px/s);
    const button = styles.match(/\.product-star-choice\s*\{([^}]+)\}/)?.[1] ?? '';
    expect(button).toMatch(/width:\s*24px;[^}]*min-height:\s*44px;[^}]*padding:\s*4px/s);
    expect(button).not.toMatch(/margin|transform|position|left|right/);
    expect(styles.slice(styles.indexOf('@media (pointer: coarse)'))).toMatch(/\.product-star-choice\s*\{\s*width:\s*44px;/s);
  });
  it('separates softer swatch hover from unchanged focus and strengthens only highlighted unfilled star outlines', () => {
    expect(styles).toContain('--product-swatch-focus: #454b47');
    expect(styles).toContain('--product-swatch-focus: #d7e0d9');
    expect(styles).toContain('--product-swatch-hover: #747d76');
    expect(styles).toContain('--product-swatch-hover: #9eada3');
    const star = styles.match(/\.product-stars > \[data-highlighted="true"\] svg\s*\{([^}]+)\}/)?.[1] ?? '';
    expect(star).toMatch(/stroke-width:\s*2\.1/); expect(star).toContain('drop-shadow');
    expect(star).not.toMatch(/(?:^|[;\s])(?:fill|width|height|transform|transition|animation)\s*:/);
    const forced = styles.slice(styles.indexOf('@media (forced-colors: active)'), styles.indexOf('@keyframes'));
    expect(forced).toMatch(/\.product-stars > \[data-highlighted="true"\] svg\s*\{\s*filter:\s*none/s);
  });
});

describe('product card and public demo controls', () => {
  it('measures sticky-header clearance on resize without scrolling, keeps all header actions and disconnects its observer', () => {
    let height = 118;
    const original = HTMLElement.prototype.getBoundingClientRect;
    const rect = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) { return this.matches('.product-header') ? new DOMRect(0, 0, 390, height) : original.call(this); });
    const observations = new Map<Element, () => void>();
    vi.stubGlobal('ResizeObserver', class {
      elements: Element[] = [];
      callback: () => void;
      constructor(callback: () => void) { this.callback = callback; }
      observe(element: Element) { this.elements.push(element); observations.set(element, this.callback); }
      disconnect() { for (const element of this.elements) observations.delete(element); }
    });
    const scroll = vi.spyOn(window, 'scrollTo');
    try {
      const { container, t, unmount } = show();
      const header = screen.getByRole('banner'), shell = container.querySelector<HTMLElement>('.product-storefront')!;
      expect(shell.style.getPropertyValue('--product-header-height')).toBe('118px');
      for (const name of [t.account, t.favorites, t.cart]) expect(within(header).getByRole('link', { name })).toBeVisible();
      expect(within(header).getByRole('combobox', { name: t.search })).toBeVisible();
      scroll.mockClear(); height = 96; act(() => observations.get(header)!());
      expect(shell.style.getPropertyValue('--product-header-height')).toBe('96px'); expect(scroll).not.toHaveBeenCalled();
      unmount(); expect(observations.has(header)).toBe(false);
    } finally { rect.mockRestore(); scroll.mockRestore(); }
  });
  it('retains inline 3D intent across real colour changes but gives each model a fresh loading owner and default pose', async () => {
    const { user, t, container } = show();
    await user.click(screen.getByRole('button', { name: t.model })); const old = await readyModel(t.model);
    old.setAttribute('orientation', '10deg 20deg 30deg');
    const swatch = screen.getByRole('button', { name: 'Coral' }); await user.click(swatch);
    expect(swatch).toHaveFocus(); expect(screen.getByRole('button', { name: t.model })).toHaveAttribute('aria-pressed', 'true');
    expect(container.querySelector('.product-photo-open')).not.toBeInTheDocument();
    const fresh = await screen.findByLabelText(t.model, { selector: 'model-viewer' });
    expect(fresh).not.toBe(old); expect(fresh).toHaveAttribute('src', mediaForVariant('coral').model);
    expect(fresh).not.toHaveAttribute('orientation'); expect(fresh).toHaveAttribute('camera-orbit', '30deg 68deg 105%');
    fireEvent(old, new Event('load')); fireEvent(old, new Event('error'));
    expect(screen.getByRole('button', { name: t.enlarge })).toBeDisabled();
    await readyModel(t.model); expect(screen.getByRole('button', { name: t.enlarge })).toBeEnabled();
    await user.click(screen.getByRole('button', { name: t.photo })); readyPhotos();
    await user.click(screen.getByRole('button', { name: 'Cobalt blue' })); readyPhotos();
    expect(screen.getByRole('button', { name: t.photo })).toHaveAttribute('aria-pressed', 'true');
    await user.click(screen.getByRole('button', { name: t.photoNumber + ' 3' })); readyPhotos();
    await user.click(screen.getByRole('button', { name: 'Coral' })); await user.click(screen.getByRole('button', { name: 'Cobalt blue' })); readyPhotos();
    expect(screen.getByRole('img', { name: photoDescription(t.photoAlt, 'Cobalt blue') })).toHaveAttribute('src', mediaForVariant('cobalt').photos[0]);
  });
  it('retains 3D intent for a missing variant model, ends busy immediately and offers an explicit Photos return', async () => {
    const original = catalog.mediaForVariant;
    const spy = vi.spyOn(catalog, 'mediaForVariant').mockImplementation(id => id === 'coral' ? { photos: ['/product/loop-coral-01.webp'], model: null } : original(id));
    try {
      const { user, t, container } = show();
      await user.click(screen.getByRole('button', { name: t.model })); await readyModel(t.model);
      await user.click(screen.getByRole('button', { name: 'Coral' })); readyPhotos();
      expect(screen.getByRole('button', { name: t.model })).toHaveAttribute('aria-pressed', 'true');
      expect(screen.getByText(t.modelFailed)).toBeVisible(); expect(container.querySelector('model-viewer')).toBeNull();
      expect(container.querySelector('.product-photo-open')).toBeNull(); expect(container.querySelector('.product-spinner')).toBeNull();
      await user.click(screen.getByRole('button', { name: t.backToPhotos })); readyPhotos();
      expect(screen.getByRole('img', { name: photoDescription(t.photoAlt, 'Coral') })).toHaveAttribute('src', '/product/loop-coral-01.webp');
    } finally { spy.mockRestore(); }
  });
  it('uses a real icon menu-radio popup and preserves reactive system appearance without changing auth state', async () => {
    let dark = true;
    const listeners = new Set<() => void>();
    const media = { get matches() { return dark; }, addEventListener: (_: string, listener: () => void) => { listeners.add(listener); }, removeEventListener: (_: string, listener: () => void) => { listeners.delete(listener); } };
    vi.stubGlobal('matchMedia', vi.fn(query => query === '(prefers-color-scheme: dark)' ? media : { matches: false }));
    const { user, container, store, t } = show();
    const beforeAuth = store.getState().authentication, beforeUi = store.getState().ui;
    const trigger = screen.getByRole('button', { name: `${t.appearance}: ${t.appearanceLight}` });
    expect(trigger).toHaveAttribute('aria-haspopup', 'menu'); expect(trigger).toHaveAttribute('aria-expanded', 'false');
    await user.click(trigger);
    const menu = screen.getByRole('menu', { name: t.appearance });
    expect(within(menu).getAllByRole('menuitemradio')).toHaveLength(3);
    expect(within(menu).getByRole('menuitemradio', { name: t.appearanceLight })).toHaveAttribute('aria-checked', 'true');
    await user.click(within(menu).getByRole('menuitemradio', { name: t.appearanceSystem }));
    expect(screen.queryByRole('menu')).not.toBeInTheDocument(); expect(trigger).toHaveFocus();
    expect(container.querySelector('.product-storefront')).toHaveAttribute('data-theme', 'dark');
    act(() => { dark = false; listeners.forEach(listener => listener()); });
    expect(container.querySelector('.product-storefront')).toHaveAttribute('data-theme', 'light');
    expect(trigger).toHaveAccessibleName(`${t.appearance}: ${t.appearanceSystem}`);
    await user.click(trigger); await user.click(screen.getByRole('menuitemradio', { name: t.appearanceDark }));
    expect(container.querySelector('.product-storefront')).toHaveAttribute('data-theme', 'dark');
    await user.click(trigger); await user.click(screen.getByRole('menuitemradio', { name: t.appearanceLight }));
    expect(trigger).toHaveFocus();
    expect(container.querySelector('.product-storefront')).toHaveAttribute('data-theme', 'light');
    expect(store.getState().authentication).toEqual(beforeAuth); expect(store.getState().ui).toEqual(beforeUi);
    expect(document.documentElement).not.toHaveAttribute('data-theme'); expect(Object.keys(localStorage)).toEqual(['creastrix.product.loop.preferences']);
  });
  it.each([['ArrowDown', 'Dark'], ['ArrowUp', 'System'], ['Home', 'System'], ['End', 'Dark']])('navigates icon popup with %s and commits %s by Enter', async (key, label) => {
    const { user, t } = show();
    const trigger = screen.getByRole('button', { name: `${t.appearance}: ${t.appearanceLight}` });
    await user.click(trigger); await user.keyboard('{' + key + '}');
    expect(screen.getByRole('menuitemradio', { name: label })).toHaveFocus();
    await user.keyboard('{Enter}'); expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus(); expect(trigger).toHaveAccessibleName(t.appearance + ': ' + label);
  });
  it('supports arrow opening, Space selection, Escape restoration, Tab exit and outside dismissal without a focus trap', async () => {
    const { user, t } = show();
    const trigger = screen.getByRole('button', { name: `${t.appearance}: ${t.appearanceLight}` });
    act(() => trigger.focus()); await user.keyboard('{ArrowDown}');
    expect(screen.getByRole('menuitemradio', { name: t.appearanceSystem })).toHaveFocus();
    await user.keyboard('{End} '); expect(trigger).toHaveAccessibleName(`${t.appearance}: ${t.appearanceDark}`);
    await user.keyboard('{ArrowUp}'); expect(screen.getByRole('menuitemradio', { name: t.appearanceDark })).toHaveFocus();
    await user.keyboard('{Escape}'); expect(trigger).toHaveFocus(); expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    await user.click(trigger); await user.tab(); expect(screen.queryByRole('menu')).not.toBeInTheDocument(); expect(trigger).not.toHaveFocus();
    await user.click(trigger); const language = screen.getByRole('combobox', { name: t.language }); await user.click(language);
    expect(screen.queryByRole('menu')).not.toBeInTheDocument(); expect(language).toHaveFocus();
  });
  it('retains a real native details summary, focus and unhandled keyboard activation events', async () => {
    const { user, t } = show();
    const summary = screen.getByText(t.readMore).closest('summary')!, details = summary.parentElement!;
    expect(details.tagName).toBe('DETAILS'); expect(summary).not.toHaveAttribute('role');
    act(() => summary.focus()); expect(summary).toHaveFocus();
    expect(fireEvent.keyDown(summary, { key: 'Enter' })).toBe(true);
    expect(fireEvent.keyDown(summary, { key: ' ' })).toBe(true);
    await user.click(summary); expect(details).toHaveAttribute('open');
    await user.click(summary); expect(details).not.toHaveAttribute('open');
  });
  it.each(['en', 'de'] as const)('renders the selected design and working DE/EN copy in %s', language => {
    const { t } = show('/products/loop', language);
    expect(screen.getByRole('heading', { level: 1, name: 'Loop' })).toBeVisible();
    expect(screen.getByText(t.subtitle)).toBeVisible();
    expect(screen.getByText(t.personalise)).toBeVisible();
    expect(screen.getByText(t.personalFrom)).toBeVisible();
    expect(screen.getByRole('button', { name: t.add })).toBeEnabled();
    expect(screen.getByRole('link', { name: t.noReviews })).toHaveAttribute('href', '/products/loop/reviews?variant=cobalt');
    const help = screen.getAllByRole('link', { name: t.help });
    expect(help).toHaveLength(2);
    help.forEach(link => expect(link).toHaveAttribute('href', '/preview/info/help'));
    expect(screen.queryByText(/notifications|messages|Benachrichtigungen|Nachrichten/i)).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'support@creastrix.com' })).toHaveAttribute('href', 'mailto:support@creastrix.com');
    expect(screen.getByRole('link', { name: t.contact })).toHaveAttribute('href', 'mailto:hello@creastrix.com');
  });
  it('accepts manual quantities above nine up to stock and prevents cart overfill', async () => {
    const { user, store, t } = show();
    const input = screen.getByRole('textbox', { name: t.quantity });
    await user.clear(input); await user.type(input, '24');
    expect(input).toHaveValue('24');
    expect(screen.getByText('€576.00')).toBeVisible();
    await user.click(screen.getByRole('button', { name: t.add }));
    expect(store.getState().productDemo.cart).toEqual([{ variant: 'cobalt', quantity: 24 }]);
    expect(screen.getByRole('button', { name: t.add })).toBeDisabled();
    expect(input).toBeDisabled();
    expect(screen.getByRole('button', { name: t.decrease })).toBeDisabled();
    expect(screen.getByRole('button', { name: t.increase })).toBeDisabled();
    expect(screen.getByText(t.allInCart)).toBeVisible();
  });
  it.each([1, 2, 3, 4, 5])('previews only the first %i star outlines on mouse hover, clears on leave and never records a rating', count => {
    vi.stubGlobal('matchMedia', vi.fn(query => ({ matches: query === '(hover: hover) and (pointer: fine)' })));
    const { t, store } = show();
    const link = screen.getByRole('link', { name: t.noReviews });
    const group = screen.getByRole('group', { name: t.reviewRating });
    const stars = Array.from(group.children);
    const before = store.getState().productDemo;
    expect(stars).toHaveLength(5);
    fireEvent.pointerEnter(stars[count - 1], { pointerType: 'mouse' });
    stars.forEach((star, index) => expect(star).toHaveAttribute('data-highlighted', String(index < count)));
    group.querySelectorAll('svg').forEach(svg => expect(svg).toHaveAttribute('fill', 'none'));
    fireEvent.pointerLeave(group, { pointerType: 'mouse' });
    stars.forEach(star => expect(star).toHaveAttribute('data-highlighted', 'false'));
    fireEvent.pointerEnter(stars[4], { pointerType: 'touch' });
    stars.forEach(star => expect(star).toHaveAttribute('data-highlighted', 'false'));
    fireEvent.pointerEnter(stars[4], { pointerType: 'mouse' }); fireEvent.blur(link);
    stars.forEach(star => expect(star).toHaveAttribute('data-highlighted', 'false'));
    fireEvent.pointerEnter(stars[4], { pointerType: 'mouse' }); fireEvent.pointerDown(link, { pointerType: 'touch' });
    stars.forEach(star => expect(star).toHaveAttribute('data-highlighted', 'false'));
    act(() => link.focus()); expect(link).toHaveFocus();
    expect(link).toHaveAttribute('href', '/products/loop/reviews?variant=cobalt');
    expect(group.closest('a')).toBeNull(); expect(within(group).getAllByRole('button')).toHaveLength(5);
    expect(screen.queryByRole('radio')).not.toBeInTheDocument();
    expect(store.getState().productDemo).toEqual(before);
  });
  it('does not turn emulated mouse or touch events into sticky star hover on a coarse pointer device', () => {
    vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: false })));
    const { t } = show();
    const group = screen.getByRole('group', { name: t.reviewRating });
    for (const pointerType of ['mouse', 'touch']) {
      fireEvent.pointerEnter(group.lastElementChild!, { pointerType });
      group.querySelectorAll('[data-highlighted]').forEach(star => expect(star).toHaveAttribute('data-highlighted', 'false'));
    }
  });
  it('carries a two-tone demo identity and its owned photo through selection, minicart and cart without borrowing the cobalt photo', async () => {
    const { user, store, t } = show();
    const option = screen.getByRole('button', { name: 'Cobalt & chalk' });
    await user.click(option);
    expect(option).toHaveAttribute('aria-pressed', 'true');
    const swatch = option.querySelector('span')!;
    expect(swatch.style.background).toContain('linear-gradient(135deg');
    expect(swatch.style.background).toContain('50%');
    expect(screen.getByAltText(photoDescription(t.photoAlt, 'Cobalt & chalk'))).toHaveAttribute('src', mediaForVariant('cobalt-chalk').photos[0]);
    expect(screen.getByRole('button', { name: t.model })).toBeEnabled();
    expect(document.querySelector('model-viewer')).toBeNull();
    expect(screen.getByRole('button', { name: t.decrease })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: t.increase }));
    await user.click(screen.getByRole('button', { name: t.add }));
    act(() => store.dispatch(cartAdded({ variant: 'cobalt', quantity: 1 })));
    expect(store.getState().productDemo.cart).toEqual([{ variant: 'cobalt-chalk', quantity: 2 }, { variant: 'cobalt', quantity: 1 }]);
    const cartLink = within(screen.getByRole('banner')).getByRole('link', { name: t.cart });
    await user.hover(cartLink);
    const preview = screen.getByRole('region', { name: t.cartPreview });
    const line = within(preview).getByText('Loop · Cobalt & chalk').closest('.product-peek-line')!;
    expect(line.querySelector('img')).toHaveAttribute('src', mediaForVariant('cobalt-chalk').photos[0]);
    expect(within(line as HTMLElement).getByText('€56.00')).toBeVisible();
    await user.click(cartLink);
    const article = screen.getByRole('article', { name: 'Loop · Cobalt & chalk' });
    expect(article.querySelector('img')).toHaveAttribute('src', mediaForVariant('cobalt-chalk').photos[0]);
    const quantity = within(article).getByRole('textbox', { name: t.quantity + ' · Cobalt & chalk' });
    fireEvent.change(quantity, { target: { value: '999' } });
    expect(quantity).toHaveValue('6');
    const increase = within(article).getByRole('button', { name: t.increase });
    expect(increase).toBeDisabled(); await user.click(increase);
    expect(store.getState().productDemo.cart).toEqual([{ variant: 'cobalt-chalk', quantity: 6 }, { variant: 'cobalt', quantity: 1 }]);
  });
  it.each(['-1', '1.2', '1e2', '+2', 'abc', ' 2'])('does not admit non-digit input %s', value => {
    const { t } = show();
    const input = screen.getByRole('textbox', { name: t.quantity });
    fireEvent.change(input, { target: { value } });
    expect(input).toHaveValue('1');
  });
  it('clamps to stock, announces the limit, repairs a blank and supports decrement/increment', async () => {
    const { user, t } = show();
    const input = screen.getByRole('textbox', { name: t.quantity });
    fireEvent.change(input, { target: { value: '999999999999' } });
    expect(input).toHaveValue('24');
    expect(screen.getByText(`${t.limit} 24.`)).toBeVisible();
    expect(screen.getByRole('button', { name: t.increase })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: t.decrease }));
    expect(input).toHaveValue('23');
    await user.clear(input); await user.tab();
    expect(input).toHaveValue('1');
    expect(screen.getByRole('button', { name: t.decrease })).toBeDisabled();
  });
  it('keeps a product-level favorite across owned colour-media changes without substituting the cobalt photo', async () => {
    const { user, store, t } = show();
    await user.click(screen.getByRole('button', { name: t.favorite }));
    await user.click(screen.getByRole('button', { name: 'Coral' }));
    expect(screen.getByRole('button', { name: t.unfavorite })).toHaveAttribute('aria-pressed', 'true');
    expect(store.getState().productDemo.favorite).toBe('cobalt');
    expect(screen.getByAltText(photoDescription(t.photoAlt, 'Coral'))).toHaveAttribute('src', mediaForVariant('coral').photos[0]);
    expect(screen.getByRole('button', { name: t.model })).toBeEnabled();
    const quantity = screen.getByRole('textbox', { name: t.quantity });
    fireEvent.change(quantity, { target: { value: '24' } });
    expect(quantity).toHaveValue('8');
    await user.click(screen.getByRole('button', { name: 'Chalk' }));
    expect(store.getState().productDemo.favorite).toBe('cobalt');
    const chalkQuantity = screen.getByRole('textbox', { name: t.quantity });
    fireEvent.change(chalkQuantity, { target: { value: '24' } });
    expect(chalkQuantity).toHaveValue('15');
  });
  it.each(['cobalt', 'coral', 'chalk', 'cobalt-chalk'] as const)('keeps one saved card for %s and reopens its captured variant, not a later selection', async id => {
    const { user, store, t } = show();
    const chosen = variantById(id);
    await user.click(screen.getByRole('button', { name: chosen.name.en }));
    await user.click(screen.getByRole('button', { name: t.favorite }));
    const changed = id === 'coral' ? 'chalk' : 'coral';
    await user.click(screen.getByRole('button', { name: variantById(changed).name.en }));
    expect(screen.getByRole('button', { name: t.unfavorite })).toHaveAttribute('aria-pressed', 'true');
    expect(store.getState().productDemo.favorite).toBe(id);
    await user.click(within(screen.getByRole('banner')).getByRole('link', { name: t.favorites }));
    const cards = document.querySelectorAll('.product-saved-card'); expect(cards).toHaveLength(1);
    const card = cards[0] as HTMLElement;
    expect(within(card).getByRole('heading', { name: 'Loop · ' + chosen.name.en })).toBeVisible();
    const link = within(card).getByRole('link'); expect(link).toHaveAttribute('href', '/products/loop?variant=' + id);
    expect(within(card).getByAltText(photoDescription(t.photoAlt, chosen.name.en))).toHaveAttribute('src', mediaForVariant(id).photos[0]);
    expect(within(card).getByText(money(chosen.cents, 'en'))).toBeVisible();
    await user.click(link);
    expect(screen.getByRole('button', { name: chosen.name.en })).toHaveAttribute('aria-pressed', 'true');
    expect(store.getState().productDemo.favorite).toBe(id);
    expect(screen.getByRole('button', { name: t.unfavorite })).toHaveAttribute('aria-pressed', 'true');
  });
  it('clears the saved representative on remove and captures a new color only after re-adding', async () => {
    const { user, store, t } = show();
    await user.click(screen.getByRole('button', { name: 'Coral' }));
    await user.click(screen.getByRole('button', { name: t.favorite }));
    await user.click(within(screen.getByRole('banner')).getByRole('link', { name: t.favorites }));
    await user.click(screen.getByRole('button', { name: t.remove }));
    expect(store.getState().productDemo.favorite).toBeNull();
    expect(document.querySelector('.product-saved-card')).toBeNull();
    expect(screen.getByText(t.emptySaved)).toBeVisible();
    await user.click(screen.getByRole('link', { name: t.back }));
    await user.click(screen.getByRole('button', { name: 'Cobalt & chalk' }));
    await user.click(screen.getByRole('button', { name: t.favorite }));
    expect(store.getState().productDemo.favorite).toBe('cobalt-chalk');
    await user.click(within(screen.getByRole('banner')).getByRole('link', { name: t.favorites }));
    expect(screen.getByRole('heading', { name: 'Loop · Cobalt & chalk' })).toBeVisible();
    expect(document.querySelectorAll('.product-saved-card')).toHaveLength(1);
    expect(document.querySelector('.product-saved-card img')).toHaveAttribute('src', mediaForVariant('cobalt-chalk').photos[0]);
  });
  it.each(['unknown', 'constructor', '__proto__', '', 'Cobalt', 'cobalt&variant=chalk'])('uses a safe default for invalid or duplicate variant query %s', query => {
    show('/products/loop?variant=' + query);
    expect(screen.getByRole('button', { name: 'Cobalt blue' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Coral' })).toHaveAttribute('aria-pressed', 'false');
  });
  it('renders the captured two-tone name and updated memory-only note in all seven saved-view locales', async () => {
    const { user, store, t } = show('/preview/saved');
    act(() => store.dispatch(cartAdded({ variant: 'coral', quantity: 1 })));
    await user.click(screen.getByRole('link', { name: t.back }));
    await user.click(screen.getByRole('button', { name: 'Cobalt & chalk' }));
    await user.click(screen.getByRole('button', { name: t.favorite }));
    await user.click(within(screen.getByRole('banner')).getByRole('link', { name: t.favorites }));
    const language = screen.getByRole('combobox', { name: t.language });
    for (const locale of storefrontLocales) {
      await user.selectOptions(language, locale);
      expect(screen.queryByText(productCopy[locale].savedNote)).toBeNull();
      expect(screen.getByRole('heading', { name: 'Loop · ' + variantById('cobalt-chalk').name[locale] })).toBeVisible();
      expect(screen.getByRole('img', { name: photoDescription(productCopy[locale].photoAlt, variantById('cobalt-chalk').name[locale]) })).toHaveAttribute('src', mediaForVariant('cobalt-chalk').photos[0]);
      expect(document.querySelectorAll('.product-saved-card')).toHaveLength(1);
      expect(store.getState().productDemo.favorite).toBe('cobalt-chalk');
    }
  });
  it('edits and removes cart lines after ordinary route navigation', async () => {
    const { user, store, t } = show();
    fireEvent.change(screen.getByRole('textbox', { name: t.quantity }), { target: { value: '12' } });
    await user.click(screen.getByRole('button', { name: t.add }));
    await user.click(within(screen.getByRole('banner')).getByRole('link', { name: t.cart }));
    expect(screen.getByRole('heading', { name: t.cart })).toBeVisible();
    const input = screen.getByRole('textbox', { name: t.quantity + ' · Cobalt blue' });
    fireEvent.change(input, { target: { value: '20' } });
    expect(store.getState().productDemo.cart[0].quantity).toBe(20);
    expect(screen.getAllByText('€480.00').length).toBeGreaterThan(0);
    await user.click(screen.getByRole('button', { name: t.remove + ' · Cobalt blue' }));
    expect(screen.getByText(t.emptyCart)).toBeVisible();
    expect(store.getState().productDemo.cart).toHaveLength(0);
  });
  it('offers an editable/removable hover/focus cart preview and preserves click navigation', async () => {
    const { user, store, t } = show();
    act(() => store.dispatch(cartAdded({ variant: 'cobalt', quantity: 2 })));
    const link = within(screen.getByRole('banner')).getByRole('link', { name: t.cart });
    await user.hover(link);
    const peek = screen.getByRole('region', { name: t.cartPreview });
    expect(peek).toBeVisible();
    const quantity = within(peek).getByRole('textbox', { name: t.quantity + ' · Cobalt blue' });
    fireEvent.change(quantity, { target: { value: '3' } });
    expect(store.getState().productDemo.cart[0].quantity).toBe(3);
    await user.click(within(peek).getByRole('button', { name: t.remove + ' · Cobalt blue' }));
    expect(store.getState().productDemo.cart).toHaveLength(0);
    expect(within(screen.getByRole('region', { name: t.cartPreview })).getByText(t.emptyCart)).toBeVisible();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('region', { name: t.cartPreview })).not.toBeInTheDocument();
    await user.click(link);
    expect(screen.getByRole('heading', { name: t.cart })).toBeVisible();
  });
  it('uses an icon popup and short subtitles in seven storefront locales without changing auth language, storage or public cart/favorite', async () => {
    const { user, store, t, container } = show();
    const originalAuth = store.getState().authentication;
    const originalUi = store.getState().ui, originalLanguageStorage = localStorage.getItem('creastrix.ui.language');
    act(() => { store.dispatch(cartAdded({ variant: 'cobalt', quantity: 2 })); });
    await user.click(screen.getByRole('button', { name: t.favorite }));
    const appearance = screen.getByRole('button', { name: `${t.appearance}: ${t.appearanceLight}` });
    await user.click(appearance); await user.click(screen.getByRole('menuitemradio', { name: t.appearanceDark }));
    expect(container.querySelector('.product-storefront')).toHaveAttribute('data-theme', 'dark');
    expect(document.documentElement).not.toHaveAttribute('data-theme');
    const language = screen.getByRole('combobox', { name: t.language });
    const mobileHelp = container.querySelector<HTMLAnchorElement>('.product-help')!;
    mobileHelp.querySelector('span')!.hidden = true;
    expect(within(language).getAllByRole('option').map(option => option.textContent)).toEqual(['DE', 'EN', 'RU', 'PL', 'ES', 'FR', 'IT']);
    for (const locale of storefrontLocales) {
      await user.selectOptions(language, locale);
      expect(screen.getByRole('button', { name: productCopy[locale].add })).toBeVisible();
      expect(screen.getByText(productCopy[locale].subtitle)).toBeVisible();
      expect(document.documentElement.lang).toBe(locale);
      expect(store.getState().productDemo.locale).toBe(locale);
      expect(mobileHelp).toHaveAccessibleName(productCopy[locale].help);
      expect(screen.getByText(productCopy[locale].personalIntro)).toBeVisible();
      expect(screen.getByText(productCopy[locale].readMore).closest('.product-summary-label')).toHaveAttribute('data-label', productCopy[locale].readMore);
      expect(productCopy[locale].personalIntro.length).toBeLessThan(50);
      expect(screen.queryByText(productCopy[locale].personalSummary)).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: variantById('cobalt-chalk').name[locale] })).toHaveAttribute('data-check-tone', 'dark');
      const current = productCopy[locale];
      expect(screen.getByRole('img', { name: photoDescription(current.photoAlt, variantById('cobalt').name[locale]) })).toHaveAttribute('src', mediaForVariant('cobalt').photos[0]);
      expect(appearance).toHaveAccessibleName(`${current.appearance}: ${current.appearanceDark}`);
      expect(appearance).toHaveAttribute('title', `${current.appearance}: ${current.appearanceDark}`);
      await user.click(appearance);
      for (const label of [current.appearanceSystem, current.appearanceLight, current.appearanceDark]) {
        const option = screen.getByRole('menuitemradio', { name: label }); expect(option).toHaveAttribute('title', label); expect(option.textContent).toBe('');
        expect(option).toHaveAttribute('aria-checked', String(label === current.appearanceDark));
      }
      await user.keyboard('{Escape}');
    }
    expect(store.getState().productDemo.cart).toEqual([{ variant: 'cobalt', quantity: 2 }]);
    expect(store.getState().productDemo.favorite).toBe('cobalt');
    expect(store.getState().authentication).toEqual(originalAuth);
    expect(store.getState().ui).toEqual(originalUi);
    expect(localStorage.getItem('creastrix.ui.language')).toBe(originalLanguageStorage);
    await user.click(appearance); const current = productCopy[store.getState().productDemo.locale ?? 'en'];
    await user.click(screen.getByRole('menuitemradio', { name: current.appearanceSystem })); expect(appearance).toHaveAccessibleName(`${current.appearance}: ${current.appearanceSystem}`);
  });
  it('navigates to separate reviews and honest help/info destinations', async () => {
    const { user, t } = show();
    await user.click(screen.getByRole('link', { name: t.noReviews }));
    expect(screen.getByRole('heading', { level: 1, name: t.reviews })).toBeVisible();
    expect(screen.getByRole('link', { name: t.writeReview })).toHaveAttribute('href', '/products/loop/reviews/new?variant=cobalt');
    expect(screen.getByRole('heading', { level: 2, name: t.noReviews })).toBeVisible();
    expect(screen.queryByText(t.reviewsBody)).toBeNull();
    await user.click(screen.getAllByRole('link', { name: t.help })[0]);
    expect(screen.getByRole('heading', { level: 1, name: t.help })).toBeVisible();
    expect(screen.getByText(t.helpBody)).toBeVisible();
  });
  it('shows local search suggestions without affecting the card and navigates by keyboard', async () => {
    const { user, t } = show();
    const search = screen.getByRole('combobox', { name: t.search });
    await user.type(search, 'Loop Mini');
    expect(search).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('listbox', { name: t.results })).toBeVisible();
    expect(screen.getByRole('textbox', { name: t.quantity })).toHaveValue('1');
    await user.keyboard('{ArrowDown}{Enter}');
    expect(screen.getByRole('heading', { level: 1, name: 'Loop Mini' })).toBeVisible();
    expect(search).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getByText(t.studioBody)).toBeVisible();
    await user.clear(search); await user.type(search, 'nothing-matches'); await user.keyboard('{Enter}');
    expect(screen.getByRole('heading', { name: t.results })).toBeVisible();
    expect(screen.getByText(t.noResults)).toBeVisible();
  });
  it('filters/ranks live, keeps input focus through keyboard selection and clears stale options', async () => {
    const { user, t } = show();
    const input = screen.getByRole('combobox', { name: t.search });
    await user.type(input, 'Loop');
    const options = within(screen.getByRole('listbox', { name: t.results })).getAllByRole('option');
    expect(options).toHaveLength(5);
    expect(options[0]).toHaveTextContent('Loop3D-printed plant pot');
    await user.keyboard('{ArrowUp}');
    expect(options.at(-1)).toHaveAttribute('aria-selected', 'true');
    expect(input).toHaveFocus();
    expect(input).toHaveAttribute('aria-activedescendant', options.at(-1)!.id);
    await user.keyboard('{ArrowDown}');
    expect(options[0]).toHaveAttribute('aria-selected', 'true');
    await user.keyboard(' Mini');
    expect(input).toHaveValue('Loop Mini');
    expect(within(screen.getByRole('listbox', { name: t.results })).getAllByRole('option')).toHaveLength(1);
    expect(within(screen.getByRole('listbox', { name: t.results })).getByRole('option')).toHaveTextContent('Loop Mini');
    expect(input).not.toHaveAttribute('aria-activedescendant');
    await user.clear(input);
    expect(input).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });
  it('updates localized suggestions immediately and uses the same match for ordinary submit', async () => {
    const { user, t, store } = show();
    const input = screen.getByRole('combobox', { name: t.search });
    await user.type(input, 'Untersetzer');
    expect(screen.getByText(t.noResults)).toBeVisible();
    act(() => { store.dispatch(languageSelected('de')); });
    expect(screen.queryByText(t.noResults)).not.toBeInTheDocument();
    expect(within(screen.getByRole('listbox', { name: productCopy.de.results })).getByRole('option')).toHaveTextContent('Loop TrayUntersetzer');
    await user.keyboard('{Enter}');
    expect(document.title).toBe(productCopy.de.results + ' · Untersetzer · Creastrix');
    expect(screen.getByRole('link', { name: /Loop Tray.*Designkonzept/ })).toHaveAttribute('href', '/preview/items/loop-tray');
    expect(input).toHaveValue('Untersetzer');
  });
  it('does not select or submit during IME composition and only publishes the completed string', () => {
    const { t } = show();
    const input = screen.getByRole('combobox', { name: t.search });
    fireEvent.compositionStart(input);
    fireEvent.change(input, { target: { value: 'Loop' } });
    fireEvent.keyDown(input, { key: 'Enter', isComposing: true });
    fireEvent.submit(input.closest('form')!);
    expect(screen.getByRole('heading', { level: 1, name: 'Loop' })).toBeVisible();
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
    fireEvent.compositionEnd(input, { data: 'Loop' });
    expect(within(screen.getByRole('listbox', { name: t.results })).getAllByRole('option')).toHaveLength(5);
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    expect(input).toHaveAttribute('aria-activedescendant', within(screen.getByRole('listbox', { name: t.results })).getAllByRole('option')[0].id);
    fireEvent.keyDown(input, { key: 'Escape' });
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
    expect(input).not.toHaveAttribute('aria-activedescendant');
  });
});

describe('remembered public variant and explicit URL round-trips', () => {
  it.each(['back', 'continue'] as const)('returns the latest two-tone selection through cart %s despite an older explicit entry variant', async action => {
    const { user, t } = show('/products/loop?variant=coral', 'en', false, true);
    await user.click(screen.getByRole('button', { name: 'Cobalt & chalk' }));
    await user.click(screen.getByRole('button', { name: t.add }));
    await user.click(within(screen.getByRole('banner')).getByRole('link', { name: t.cart }));
    const line = screen.getByRole('article', { name: 'Loop · Cobalt & chalk' });
    expect(within(line).getByRole('link', { name: 'Loop' })).toHaveAttribute('href', '/products/loop?variant=cobalt-chalk');
    const link = screen.getByRole('link', { name: action === 'back' ? t.back : t.continue });
    expect(link).toHaveAttribute('href', '/products/loop?variant=cobalt-chalk');
    await user.click(link);
    expect(screen.getByRole('button', { name: 'Cobalt & chalk' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('current-product-route')).toHaveTextContent('/products/loop?variant=cobalt-chalk');
  });
  it.each(['/products/loop?variant=coral', '/products/loop/?variant=coral'])('replaces the selection entry without focus/scroll reset and retains it through Back/Forward while explicit links win (%s)', async path => {
    const scroll = vi.spyOn(window, 'scrollTo');
    try {
      const { user, t, store } = show(path, 'en', false, true);
      const swatch = screen.getByRole('button', { name: 'Cobalt & chalk' }); scroll.mockClear();
      await user.click(swatch);
      expect(swatch).toHaveFocus(); expect(scroll).not.toHaveBeenCalled();
      expect(screen.getByTestId('current-product-route')).toHaveTextContent(path.split('?')[0] + '?variant=cobalt-chalk');
      await user.click(within(screen.getByRole('banner')).getByRole('link', { name: t.cart }));
      await user.click(screen.getByRole('button', { name: 'History back' }));
      expect(screen.getByRole('button', { name: 'Cobalt & chalk' })).toHaveAttribute('aria-pressed', 'true');
      await user.click(screen.getByRole('button', { name: 'History forward' }));
      expect(screen.getByRole('heading', { name: t.cart })).toBeVisible();
      await user.click(screen.getByRole('button', { name: 'History back' }));
      await user.click(screen.getByRole('link', { name: 'Explicit chalk' }));
      expect(screen.getByRole('button', { name: 'Chalk' })).toHaveAttribute('aria-pressed', 'true');
      await user.click(screen.getByRole('button', { name: 'History back' }));
      expect(screen.getByRole('button', { name: 'Cobalt & chalk' })).toHaveAttribute('aria-pressed', 'true');
      await user.click(screen.getByRole('button', { name: 'History forward' }));
      await user.click(screen.getByRole('link', { name: 'Default product' }));
      expect(screen.getByRole('button', { name: 'Chalk' })).toHaveAttribute('aria-pressed', 'true');
      expect(store.getState().productDemo.selectedVariant).toBe('chalk');
    } finally { scroll.mockRestore(); }
  });
  it.each(['Invalid variant', 'Duplicate variant'])('uses safe cobalt for %s rather than treating remembered selection as trusted URL input', async link => {
    const { user } = show('/products/loop?variant=cobalt-chalk', 'en', false, true);
    await user.click(screen.getByRole('link', { name: link }));
    expect(screen.getByRole('button', { name: 'Cobalt blue' })).toHaveAttribute('aria-pressed', 'true');
    await user.click(screen.getByRole('link', { name: 'Explicit chalk' }));
    expect(screen.getByRole('button', { name: 'Chalk' })).toHaveAttribute('aria-pressed', 'true');
  });
  it('opens the exact cart-line colour even when the last browsed variant differs', async () => {
    const { user, t, store } = show('/products/loop?variant=chalk');
    act(() => store.dispatch(cartAdded({ variant: 'coral', quantity: 1 })));
    await user.click(within(screen.getByRole('banner')).getByRole('link', { name: t.cart }));
    await user.click(within(screen.getByRole('article', { name: 'Loop · Coral' })).getByRole('link', { name: 'Loop' }));
    expect(screen.getByRole('button', { name: 'Coral' })).toHaveAttribute('aria-pressed', 'true');
  });
});

describe('purchase draft and exact cart return', () => {
  it.each([
    ['back', 'cobalt-chalk', 2, true], ['continue', 'cobalt-chalk', 3, true], ['history', 'cobalt-chalk', 2, true],
    ['back', 'coral', 3, true], ['continue', 'coral', 3, true], ['history', 'coral', 3, true],
    ['back', 'coral', 4, false], ['history', 'coral', 4, false],
  ] as const)('returns %s for %s with quantity %i, expanded height and exact fractional departure (add=%s)', async (action, variant, quantity, add) => {
    const position = returnScrollFixture();
    try {
      const { user, t, store } = show('/products/loop/?variant=chalk', 'en', false, true);
      await user.click(screen.getByRole('button', { name: variantById(variant).name.en }));
      fireEvent.change(screen.getByRole('textbox', { name: t.quantity }), { target: { value: String(quantity) } });
      await user.click(screen.getByText(t.readMore));
      expect(document.querySelector('.product-description details')).toHaveAttribute('open');
      if (add) await user.click(screen.getByRole('button', { name: t.add }));
      expect(screen.getByRole('textbox', { name: t.quantity })).toHaveValue(String(quantity));
      const before = store.getState().productDemo, cart = structuredClone(before.cart), serial = before.addSerial;
      position.depart(1375.5);
      await user.click(action === 'continue' && add ? within(document.querySelector<HTMLElement>('.product-buy-feedback')!).getByRole('link', { name: t.viewCart }) : within(screen.getByRole('banner')).getByRole('link', { name: t.cart }));
      position.scroll.mockClear();
      await user.click(action === 'history' ? screen.getByRole('button', { name: 'History back' }) : screen.getByRole('link', { name: action === 'back' ? t.back : t.continue }));
      expect(screen.getByRole('button', { name: variantById(variant).name.en })).toHaveAttribute('aria-pressed', 'true');
      expect(screen.getByRole('textbox', { name: t.quantity })).toHaveValue(String(quantity));
      expect(document.querySelector('.product-description details')).toHaveAttribute('open');
      expect(position.top).toBe(1375.5);
      expect(position.scroll).toHaveBeenCalledExactlyOnceWith({ left: 0, top: 1375.5, behavior: 'instant' });
      expect(screen.getByRole('main')).toHaveFocus();
      expect(store.getState().productDemo.cart).toEqual(cart); expect(store.getState().productDemo.addSerial).toBe(serial);
      await user.click(screen.getByRole('button', { name: action === 'history' ? 'History forward' : 'History back' }));
      position.scroll.mockClear();
      await user.click(screen.getByRole('button', { name: action === 'history' ? 'History back' : 'History forward' }));
      expect(position.top).toBe(1375.5); expect(screen.getByRole('textbox', { name: t.quantity })).toHaveValue(String(quantity));
      expect(position.scroll).toHaveBeenCalledExactlyOnceWith({ left: 0, top: 1375.5, behavior: 'instant' });
      expect(store.getState().productDemo.addSerial).toBe(serial);
    } finally { position.restore(); }
  });
  it('keeps independent valid quantities per variant and never stores a blank or invalid draft', async () => {
    const { user, t } = show('/products/loop?variant=coral');
    fireEvent.change(screen.getByRole('textbox', { name: t.quantity }), { target: { value: '3' } });
    await user.click(screen.getByRole('button', { name: 'Cobalt & chalk' }));
    fireEvent.change(screen.getByRole('textbox', { name: t.quantity }), { target: { value: '2' } });
    await user.click(screen.getByRole('button', { name: 'Coral' }));
    expect(screen.getByRole('textbox', { name: t.quantity })).toHaveValue('3');
    fireEvent.change(screen.getByRole('textbox', { name: t.quantity }), { target: { value: '' } });
    fireEvent.click(within(screen.getByRole('banner')).getByRole('link', { name: t.cart }));
    await user.click(screen.getByRole('link', { name: t.back }));
    expect(screen.getByRole('textbox', { name: t.quantity })).toHaveValue('1');
    fireEvent.change(screen.getByRole('textbox', { name: t.quantity }), { target: { value: '-9' } });
    await user.click(screen.getByRole('button', { name: 'Cobalt & chalk' }));
    expect(screen.getByRole('textbox', { name: t.quantity })).toHaveValue('2');
  });
  it('clamps a remembered draft after cart edits, never revives excess after release, and shows disabled zero when exhausted', async () => {
    const { user, t, store } = show('/products/loop?variant=coral');
    act(() => store.dispatch(cartAdded({ variant: 'coral', quantity: 1 })));
    fireEvent.change(screen.getByRole('textbox', { name: t.quantity }), { target: { value: '6' } });
    for (const [cartQuantity, expected] of [[7, '1'], [2, '1'], [8, '0']] as const) {
      await user.click(within(screen.getByRole('banner')).getByRole('link', { name: t.cart }));
      fireEvent.change(screen.getByRole('textbox', { name: t.quantity + ' · Coral' }), { target: { value: String(cartQuantity) } });
      const serial = store.getState().productDemo.addSerial;
      await user.click(screen.getByRole('link', { name: t.back }));
      expect(screen.getByRole('textbox', { name: t.quantity })).toHaveValue(expected);
      expect(store.getState().productDemo.cart).toEqual([{ variant: 'coral', quantity: cartQuantity }]);
      expect(store.getState().productDemo.addSerial).toBe(serial);
    }
    expect(screen.getByRole('textbox', { name: t.quantity })).toBeDisabled(); expect(screen.getByRole('button', { name: t.add })).toBeDisabled();
    await user.click(within(screen.getByRole('banner')).getByRole('link', { name: t.cart }));
    await user.click(screen.getByRole('button', { name: t.remove + ' · Coral' }));
    await user.click(screen.getByRole('link', { name: t.back }));
    expect(screen.getByRole('textbox', { name: t.quantity })).toHaveValue('1');
    expect(screen.getByRole('button', { name: t.add })).toBeEnabled(); expect(store.getState().productDemo.cart).toEqual([]);
  });
  it('opens a direct cart without inventing a product departure or quantity', async () => {
    const position = returnScrollFixture();
    try {
      const { user, t } = show('/preview/cart'); position.depart(180); position.scroll.mockClear();
      await user.click(screen.getByRole('link', { name: t.back }));
      expect(screen.getByRole('textbox', { name: t.quantity })).toHaveValue('1'); expect(position.top).toBe(0);
      expect(position.scroll).toHaveBeenCalledExactlyOnceWith({ left: 0, top: 0, behavior: 'instant' });
      expect(document.querySelector('.product-description details')).not.toHaveAttribute('open');
    } finally { position.restore(); }
  });
  it('keeps a concrete other-variant cart link a new navigation, not an unrelated departure restore', async () => {
    const position = returnScrollFixture();
    try {
      const { user, t, store } = show('/products/loop?variant=chalk');
      act(() => store.dispatch(cartAdded({ variant: 'coral', quantity: 1 })));
      await user.click(screen.getByText(t.readMore)); position.depart(1400);
      await user.click(within(screen.getByRole('banner')).getByRole('link', { name: t.cart })); position.scroll.mockClear();
      await user.click(within(screen.getByRole('article', { name: 'Loop · Coral' })).getByRole('link', { name: 'Loop' }));
      expect(screen.getByRole('button', { name: 'Coral' })).toHaveAttribute('aria-pressed', 'true'); expect(position.top).toBe(0);
      expect(position.scroll).toHaveBeenCalledExactlyOnceWith({ left: 0, top: 0, behavior: 'instant' });
    } finally { position.restore(); }
  });
});

describe('sale totals and local demonstration review form', () => {
  it('uses chalk current integer cents in quantity/subtotal/minicart/cart and saved unit price, then clears sale on normal color', async () => {
    const { user, t, store } = show();
    await user.click(screen.getByRole('button', { name: 'Chalk' }));
    expect(document.querySelector('.product-price')).toHaveTextContent('€19.90');
    expect(document.querySelector('.product-price-offer del')).toHaveTextContent('€24.00');
    expect(document.querySelector('.product-price-badge')).toHaveTextContent('−17%');
    fireEvent.change(screen.getByRole('textbox', { name: t.quantity }), { target: { value: '2' } });
    expect(document.querySelector('.product-subtotal')).toHaveTextContent('€39.80');
    await user.click(screen.getByRole('button', { name: t.favorite })); await user.click(screen.getByRole('button', { name: t.add }));
    expect(store.getState().productDemo.cart).toEqual([{ variant: 'chalk', quantity: 2 }]);
    const cart = within(screen.getByRole('banner')).getByRole('link', { name: t.cart });
    await user.hover(cart); expect(screen.getByRole('region', { name: t.cartPreview })).toHaveTextContent('€39.80');
    await user.click(within(screen.getByRole('banner')).getByRole('link', { name: t.favorites }));
    expect(document.querySelector('.product-saved-card')).toHaveTextContent('€19.90');
    await user.click(within(screen.getByRole('banner')).getByRole('link', { name: t.cart }));
    expect(screen.getByRole('article', { name: 'Loop · Chalk' })).toHaveTextContent('€39.80');
    await user.click(screen.getByRole('link', { name: t.back }));
    expect(screen.getByRole('button', { name: 'Chalk' })).toHaveAttribute('aria-pressed', 'true');
    expect(document.querySelector('.product-price-badge')).toHaveTextContent('−17%');
    await user.click(screen.getByRole('button', { name: 'Cobalt blue' }));
    expect(document.querySelector('.product-price-badge')).toBeNull(); expect(document.querySelector('.product-price-offer del')).toBeNull();
    expect(document.querySelector('.product-price')).toHaveTextContent('€24.00');
  });
  it.each([1, 2, 3, 4, 5])('routes star %i separately to an editable demo prefill and returns to the concrete variant without submission', async rating => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch);
    const { user, t, store } = show('/products/loop?variant=chalk');
    const stars = screen.getByRole('group', { name: t.reviewRating });
    expect(within(stars).getAllByRole('button')).toHaveLength(5); expect(stars.closest('a')).toBeNull();
    await user.click(within(stars).getByRole('button', { name: `${t.reviewRating}: ${rating} ${t.ratingOutOf}` }));
    expect(screen.getByRole('heading', { level: 1, name: t.reviewFormTitle })).toBeVisible();
    expect(screen.getByRole('radio', { name: `${t.reviewRating}: ${rating} ${t.ratingOutOf}` })).toBeChecked();
    await user.type(screen.getByRole('textbox', { name: t.reviewText }), 'A local draft, never published.');
    const next = rating === 5 ? 1 : 5; await user.click(screen.getByRole('radio', { name: `${t.reviewRating}: ${next} ${t.ratingOutOf}` }));
    expect(screen.getByRole('radio', { name: `${t.reviewRating}: ${next} ${t.ratingOutOf}` })).toBeChecked();
    expect(screen.getByText(t.reviewDemoNote)).toBeVisible(); expect(screen.getByRole('button', { name: t.reviewSubmit })).toBeDisabled();
    expect(fireEvent.submit(screen.getByRole('form', { name: t.reviewFormTitle }))).toBe(false);
    expect(fetch).not.toHaveBeenCalled(); expect(store.getState().productDemo.cart).toEqual([]);
    const back = screen.getByRole('link', { name: t.backToProduct }); expect(back).toHaveAttribute('href', '/products/loop?variant=chalk');
    await user.click(back); expect(screen.getByRole('button', { name: 'Chalk' })).toHaveAttribute('aria-pressed', 'true');
  });
  it.each([undefined, '', '0', '6', '01', '1.0', 'NaN', '3&rating=4'])('leaves an invalid/missing/duplicate direct rating unselected (%s)', rating => {
    const { t } = show('/products/loop/reviews/new?variant=__proto__' + (rating === undefined ? '' : '&rating=' + rating));
    expect(screen.getByText(t.reviewChoose)).toBeVisible();
    screen.getAllByRole('radio').forEach(radio => expect(radio).not.toBeChecked());
    expect(screen.getByRole('textbox', { name: t.reviewText })).toHaveValue('');
    expect(screen.getByRole('link', { name: t.backToProduct })).toHaveAttribute('href', '/products/loop?variant=cobalt');
  });
  it('localizes form fields/disclaimer/back and star names across all seven locales without publishing a result', async () => {
    const { user, t } = show('/products/loop/reviews/new?rating=3&variant=coral');
    const language = screen.getByRole('combobox', { name: t.language });
    for (const locale of storefrontLocales) {
      await user.selectOptions(language, locale); const c = productCopy[locale];
      expect(screen.getByRole('heading', { level: 1, name: c.reviewFormTitle })).toBeVisible();
      expect(screen.getByRole('textbox', { name: c.reviewText })).toBeVisible();
      expect(screen.getByText(c.reviewDemoNote)).toBeVisible();
      expect(screen.getByRole('radio', { name: `${c.reviewRating}: 3 ${c.ratingOutOf}` })).toBeChecked();
      expect(screen.getByRole('link', { name: c.backToProduct })).toHaveAttribute('href', '/products/loop?variant=coral');
      expect(screen.getByRole('button', { name: c.reviewSubmit })).toBeDisabled();
    }
  });
});

describe('viewport media modal and honest personalisation boundary', () => {
  it('replaces a direct variant model with its own fresh asset and rejects stale load publication', async () => {
    const t = productCopy.en, user = userEvent.setup();
    const props = { favorite: false, toggleFavorite: vi.fn(), t };
    const { rerender } = render(<MediaGallery {...props} variant="cobalt" />);
    await user.click(screen.getByRole('button', { name: t.model }));
    const viewer = await readyModel(t.model);
    expect(viewer.closest('[hidden]')).toBeNull();
    await user.click(screen.getByRole('button', { name: t.enlarge }));
    expect(screen.getByRole('dialog')).toBeVisible();
    rerender(<MediaGallery {...props} variant="coral" />);
    const current = await screen.findByLabelText(t.model, { selector: 'model-viewer' });
    expect(current).not.toBe(viewer); expect(viewer.isConnected).toBe(false); expect(current).toHaveAttribute('src', mediaForVariant('coral').model!);
    fireEvent(viewer, new Event('load')); expect(screen.getByRole('button', { name: t.resetView })).toBeDisabled();
    await readyModel(t.model); expect(screen.getByRole('button', { name: t.resetView })).toBeEnabled();
  });
  it.each([0, 1, 3])('reserves the same thumbnail strip for %i actual photos without dummy arrows or clickable placeholders', async count => {
    const original = catalog.mediaForVariant, spy = vi.spyOn(catalog, 'mediaForVariant').mockImplementation(id => ({ ...original(id), photos: original(id).photos.slice(0, count) }));
    try {
      const t = productCopy.en, user = userEvent.setup(), { container } = render(<MediaGallery variant="cobalt" favorite={false} toggleFavorite={() => {}} t={t} />);
      const strip = container.querySelector('.product-thumbnails')!; expect(strip).toBeInTheDocument();
      expect(within(strip as HTMLElement).queryAllByRole('button')).toHaveLength(count);
      const css = readFileSync('src/product/product.css', 'utf8'); expect(css).toMatch(/\.product-thumbnails\s*\{[^}]*min-height:\s*56px/s);
      expect(container.querySelector('.product-media')).toBeInTheDocument();
      if (!count) { const placeholder = within(strip as HTMLElement).getByRole('img', { name: t.noPhoto }); expect(placeholder.closest('button')).toBeNull(); expect(placeholder).toHaveTextContent(t.noPhotoShort); }
      else {
        expect(screen.getByRole('button', { name: t.enlarge })).toBeDisabled(); readyPhotos();
        await user.click(screen.getByRole('button', { name: t.enlarge }));
        expect(screen.getByText(`1 / ${count}`)).toBeVisible();
        expect(screen.queryAllByRole('button', { name: t.next })).toHaveLength(count === 1 ? 0 : 1);
      }
    } finally { spy.mockRestore(); }
  });
  it('keeps loading action-local, ignores detached photo events, and presents a noninteractive error thumbnail with explicit retry', async () => {
    const { user, t } = show('/products/loop', 'en', false, false, false);
    const old = screen.getByAltText(photoDescription(t.photoAlt, 'Cobalt blue'));
    expect(screen.getByRole('button', { name: t.enlarge })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Coral' })).toBeEnabled();
    await user.click(screen.getByRole('button', { name: t.favorite }));
    await user.click(screen.getByRole('button', { name: 'Coral' }));
    const current = screen.getByAltText(photoDescription(t.photoAlt, 'Coral'));
    fireEvent.load(old); fireEvent.error(old); expect(screen.getByRole('button', { name: t.enlarge })).toBeDisabled();
    expect(current).toHaveAttribute('src', mediaForVariant('coral').photos[0]);
    fireEvent.error(current);
    const strip = document.querySelector('.product-thumbnails')!; expect(within(strip as HTMLElement).queryAllByRole('button')).toHaveLength(0);
    expect(within(strip as HTMLElement).getByRole('img', { name: t.noPhoto }).closest('button')).toBeNull();
    expect(document.querySelector('.product-spinner')).toBeNull();
    await user.click(screen.getByRole('button', { name: t.retryPhoto })); expect(screen.getByRole('button', { name: t.enlarge })).toBeDisabled();
    readyPhotos(); expect(screen.getByRole('button', { name: t.enlarge })).toBeEnabled();
  });
  it('normalizes a direct three-to-one photo change inside the open modal without stale photos or duplicate navigation', async () => {
    const t = productCopy.en, user = userEvent.setup(), { rerender } = render(<MediaGallery variant="cobalt" favorite={false} toggleFavorite={() => {}} t={t} />);
    readyPhotos(); await user.click(screen.getByRole('button', { name: t.photoNumber + ' 3' })); readyPhotos();
    await user.click(screen.getByRole('button', { name: t.enlarge })); expect(screen.getByText('3 / 3')).toBeVisible();
    rerender(<MediaGallery variant="chalk" favorite={false} toggleFavorite={() => {}} t={t} />);
    expect(screen.getByText('1 / 1')).toBeVisible();
    expect(within(screen.getByRole('dialog')).getByRole('img', { name: t.photoAlt + ' · 1' })).toHaveAttribute('src', mediaForVariant('chalk').photos[0]);
    expect(screen.queryByRole('button', { name: t.next })).not.toBeInTheDocument();
  });
  it('keeps ready main photo one after thumbnail two fails and shows only that asset as unavailable when selected', async () => {
    const t = productCopy.en, user = userEvent.setup(), { container } = render(<MediaGallery variant="cobalt" favorite={false} toggleFavorite={() => {}} t={t} />);
    readyPhotos(); const main = screen.getByRole('img', { name: t.photoAlt });
    const thumbnail = screen.getByRole('button', { name: t.photoNumber + ' 2' }).querySelector('img')!;
    fireEvent.error(thumbnail);
    expect(screen.getByRole('img', { name: t.photoAlt })).toBe(main);
    expect(main).toHaveAttribute('src', '/product/reference-asset-01.webp');
    expect(screen.getByRole('button', { name: t.enlarge })).toBeEnabled();
    expect(container.querySelector('.product-thumbnails .product-thumbnail-placeholder')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: t.enlarge }));
    await user.click(screen.getByRole('button', { name: t.next }));
    expect(screen.getByText('2 / 3')).toBeVisible();
    expect(within(screen.getByRole('dialog')).getByText(t.noPhoto)).toBeVisible();
    expect(within(screen.getByRole('dialog')).queryByRole('img', { name: t.photoAlt + ' · 2' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: t.previous }));
    expect(within(screen.getByRole('dialog')).getByRole('img', { name: t.photoAlt + ' · 1' })).toHaveAttribute('src', '/product/reference-asset-01.webp');
  });
  it('does not let a detached previous-variant thumbnail failure poison the current variant', () => {
    const t = productCopy.en, { rerender } = render(<MediaGallery variant="cobalt" favorite={false} toggleFavorite={() => {}} t={t} />);
    const old = screen.getByRole('button', { name: t.photoNumber + ' 2' }).querySelector('img')!;
    rerender(<MediaGallery variant="coral" favorite={false} toggleFavorite={() => {}} t={t} />);
    readyPhotos(); fireEvent.error(old); fireEvent.load(old);
    expect(screen.getByRole('img', { name: t.photoAlt })).toHaveAttribute('src', mediaForVariant('coral').photos[0]);
    expect(screen.getByRole('button', { name: t.enlarge })).toBeEnabled();
    expect(document.querySelector('.product-thumbnail-placeholder')).not.toBeInTheDocument();
  });
  it('closes during another photo load and restores focus to the available photo tab instead of a disabled opener', async () => {
    const { user, t } = show();
    await user.click(screen.getByRole('button', { name: t.enlarge }));
    await user.click(screen.getByRole('button', { name: t.next }));
    expect(screen.getByRole('dialog')).toBeVisible();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: t.enlarge })).toBeDisabled();
    expect(screen.getByRole('button', { name: t.photo })).toHaveFocus();
  });
  it('portals outside the inert background, traps Tab, navigates photos, closes and restores focus', async () => {
    const { user, t } = show();
    const opener = screen.getByRole('button', { name: t.enlarge });
    await user.click(opener);
    const dialog = screen.getByRole('dialog', { name: t.gallery });
    expect(dialog.closest('[data-storefront-content]')).toBeNull();
    expect(document.querySelector<HTMLElement>('[data-storefront-content]')?.inert).toBe(true);
    const close = within(dialog).getByRole('button', { name: t.close }), next = within(dialog).getByRole('button', { name: t.next });
    expect(close).toHaveFocus();
    await user.keyboard('{ArrowRight}'); expect(within(dialog).getByText('2 / 3')).toBeVisible();
    await user.keyboard('{ArrowLeft}'); expect(within(dialog).getByText('1 / 3')).toBeVisible();
    const toggle = within(dialog).getByRole('button', { name: t.model });
    await user.tab({ shift: true }); expect(toggle).toHaveFocus();
    await user.tab({ shift: true }); expect(within(dialog).getByRole('button', { name: t.photoNumber + ' 3' })).toHaveFocus();
    await user.tab(); expect(toggle).toHaveFocus();
    await user.tab(); expect(close).toHaveFocus();
    await user.click(next); expect(within(dialog).getByText('2 / 3')).toBeVisible();
    await user.keyboard('{ArrowLeft}'); expect(within(dialog).getByText('1 / 3')).toBeVisible();
    readyPhotos();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(opener).toHaveFocus();
    expect(document.querySelector<HTMLElement>('[data-storefront-content]')?.inert).toBe(false);
  });
  it('keeps one modal frame for Photo/3D, allows expanding 3D and retains a working photo fallback', async () => {
    const { user, t } = show();
    await user.click(screen.getByRole('button', { name: t.model }));
    await readyModel(t.model);
    await user.click(screen.getByRole('button', { name: t.enlarge }));
    const dialog = screen.getByRole('dialog', { name: t.gallery }), frame = dialog.querySelector('.product-gallery-image');
    const footer = dialog.querySelector('.product-gallery-footer');
    const toggle = within(dialog).getByRole('button', { name: t.photo });
    expect(toggle).toBeEnabled();
    expect(toggle.nextElementSibling).toBe(within(dialog).getByRole('button', { name: t.close }));
    expect(within(dialog).queryByRole('button', { name: t.previous })).not.toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: t.photo }));
    expect(dialog.querySelector('.product-gallery-image')).toBe(frame);
    expect(dialog.querySelector('.product-gallery-footer')).toBe(footer);
    expect(within(dialog).getByRole('button', { name: t.previous }).parentElement).toBe(frame);
    expect(within(dialog).getByRole('button', { name: t.next }).parentElement).toBe(frame);
    expect(within(dialog).getByRole('group', { name: t.photo })).toHaveClass('product-gallery-thumbnails');
    expect(within(dialog).queryByText(t.galleryNote)).toBeNull();
    expect(within(dialog).getByRole('img', { name: photoDescription(t.photoAlt, 'Cobalt blue') + ' · 1' })).toHaveAttribute('src', '/product/reference-asset-01.webp');
    await user.click(within(dialog).getByRole('button', { name: t.close }));
  });
  it('dismisses only backdrop clicks and gives personalisation a truthful result, not a dead link', async () => {
    const { user, t } = show();
    const button = screen.getByRole('button', { name: t.personalButton });
    await user.click(button);
    const dialog = screen.getByRole('dialog', { name: t.personalise });
    expect(within(dialog).getByText(t.personalBody)).toBeVisible();
    fireEvent.mouseDown(within(dialog).getByText(t.personalBody));
    expect(dialog).toBeVisible();
    fireEvent.mouseDown(dialog.parentElement!);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(button).toHaveFocus();
  });
  it('keeps keyboard wrap usable after a 3D error hides its focusable custom element', async () => {
    const { user, t } = show();
    await user.click(screen.getByRole('button', { name: t.model }));
    await readyModel(t.model);
    await user.click(screen.getByRole('button', { name: t.enlarge }));
    const dialog = screen.getByRole('dialog', { name: t.gallery });
    const model = await within(dialog).findByLabelText(t.model);
    fireEvent(model, new Event('error'));
    const close = within(dialog).getByRole('button', { name: t.close });
    act(() => { close.focus(); });
    await user.tab({ shift: true });
    expect(within(dialog).getByRole('button', { name: t.photo })).toHaveFocus();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});

describe('native horizontal carousel requests', () => {
  it.each([false, true])('keeps the same six DOM items, requests motion appropriately (reduced=%s), and disables edges', async reduced => {
    vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: reduced })));
    render(<MemoryRouter><Carousel title="Related" t={productCopy.en} /></MemoryRouter>);
    const list = screen.getByRole('list');
    const items = within(list).getAllByRole('listitem');
    Object.defineProperties(list, { clientWidth: { configurable: true, value: 300 }, scrollWidth: { configurable: true, value: 1000 }, scrollLeft: { configurable: true, writable: true, value: 0 } });
    const scroll = vi.fn(); Object.defineProperty(list, 'scrollTo', { configurable: true, value: scroll });
    fireEvent.scroll(list);
    const previous = screen.getByRole('button', { name: productCopy.en.previousProducts }), next = screen.getByRole('button', { name: productCopy.en.nextProducts });
    expect(previous).toBeDisabled(); expect(next).toBeEnabled();
    await userEvent.click(next);
    expect(scroll).toHaveBeenCalledWith(expect.objectContaining({ behavior: reduced ? 'instant' : 'smooth' }));
    expect(scroll.mock.calls[0][0].left).toBeGreaterThan(0);
    list.scrollLeft = 700; fireEvent.scroll(list);
    expect(next).toBeDisabled(); expect(previous).toBeEnabled();
    expect(within(list).getAllByRole('listitem')).toEqual(items);
    expect(screen.getByRole('link', { name: 'Related' })).toHaveAttribute('href', '/preview/collection');
  });
});

describe('new route integration preserves existing authentication entry points', () => {
  it.each(['/', '/login', '/account', '/confirm-email'])('keeps %s in the existing pilot shell', path => {
    const { container } = show(path, 'en', true);
    expect(container.querySelector('.shell')).not.toBeNull();
    expect(container.querySelector('.product-storefront')).toBeNull();
  });
  it('opens the lazy product route, then returns to the unchanged account route without inventing identity', async () => {
    const { user, store, t } = show('/products/loop', 'en', true);
    expect(await screen.findByRole('heading', { name: 'Loop', level: 1 })).toBeVisible();
    await user.click(within(screen.getByRole('banner')).getByRole('link', { name: t.account }));
    expect(screen.getByRole('heading', { name: copy.en.accountTitle })).toBeVisible();
    expect(store.getState().authentication.user).toBeNull();
    expect(screen.getByRole('main')).toHaveFocus();
  });
});

describe('bounded storefront navigation, accessibility and nonpublic metadata', () => {
  it.each(['en', 'de'] as const)('updates route-aware titles/descriptions and language in %s without fabricated SEO offers', async language => {
    const { user, t } = show('/products/loop', language, true);
    expect(await screen.findByRole('heading', { name: 'Loop', level: 1 })).toBeVisible();
    expect(document.title).toBe(`Loop · ${t.subtitle} · Creastrix`);
    expect(document.documentElement.lang).toBe(language);
    expect(document.querySelector('meta[name="description"]')).toHaveAttribute('content', t.descriptionIntro);
    await user.click(screen.getByRole('link', { name: t.noReviews }));
    expect(document.title).toBe(t.reviews + ' · Creastrix');
    expect(document.querySelector('meta[name="description"]')).toHaveAttribute('content', t.reviewsBody);
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(document.querySelector('script[type="application/ld+json"],link[rel="canonical"],link[hreflang]')).toBeNull();
    expect(indexSource).toContain('name="robots" content="noindex,nofollow"');
  });
  it.each([
    ['/preview/cart', 'Your cart', productCopy.en.cartBoundary],
    ['/preview/saved', 'Saved products', productCopy.en.savedNote],
    ['/preview/search?q=Mini', productCopy.en.results + ' · Mini', productCopy.en.searchNote],
    ['/preview/collection', productCopy.en.collection, productCopy.en.searchNote],
    ['/preview/studio', 'Loop Studio', productCopy.en.studioBody],
    ['/preview/info/help', productCopy.en.help, productCopy.en.helpBody],
    ['/preview/info/legal', productCopy.en.legal, productCopy.en.legalBody],
    ['/preview/items/loop-mini', 'Loop Mini · Design concept', productCopy.en.conceptNote],
  ])('uses specific metadata for %s', (path, title, description) => {
    show(path);
    expect(document.title).toBe(title + ' · Creastrix');
    expect(document.querySelector('meta[name="description"]')).toHaveAttribute('content', description);
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
  });
  it.each(['constructor', 'toString', '__proto__'])('rejects inherited info key %s as a normal not-found route', async topic => {
    const { t } = show('/preview/info/' + topic, 'en', true);
    expect(await screen.findByRole('heading', { level: 1, name: t.notFound })).toBeVisible();
    expect(document.title).toBe(t.notFound + ' · Creastrix');
    expect(document.querySelector('meta[name="description"]')).toHaveAttribute('content', t.info);
  });
  it.each([
    ['/preview/studio/', 'Loop Studio'],
    ['/preview/info/help/', productCopy.en.help],
    ['/products/loop/reviews/', productCopy.en.reviews],
    ['/preview/search/?q=Mini', productCopy.en.results + ' · Mini'],
  ])('keeps accepted trailing-slash metadata aligned for %s', (path, title) => {
    show(path);
    expect(document.title).toBe(title + ' · Creastrix');
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    if (path.includes('/search/')) expect(screen.getByRole('combobox', { name: productCopy.en.search })).toHaveValue('Mini');
  });
  it('preserves shell DOM, local appearance and public state between storefront routes', async () => {
    const { user, store, t } = show();
    const header = screen.getByRole('banner'), footer = screen.getByRole('contentinfo'), main = screen.getByRole('main');
    await user.click(screen.getByRole('button', { name: `${t.appearance}: ${t.appearanceLight}` })); await user.click(screen.getByRole('menuitemradio', { name: t.appearanceDark }));
    await user.click(screen.getByRole('button', { name: t.favorite }));
    await user.click(screen.getByRole('link', { name: t.noReviews }));
    expect(screen.getByRole('banner')).toBe(header);
    expect(screen.getByRole('contentinfo')).toBe(footer);
    expect(screen.getByRole('main')).toBe(main);
    expect(main).toHaveFocus();
    expect(main.closest('.product-storefront')).toHaveAttribute('data-theme', 'dark');
    expect(store.getState().productDemo.favorite).toBe('cobalt');
  });
  it('restores Back/Forward scroll positions and manages query-only search navigation once', async () => {
    const descriptor = Object.getOwnPropertyDescriptor(window, 'scrollY');
    let top = 0;
    Object.defineProperty(window, 'scrollY', { configurable: true, get: () => top });
    const scroll = vi.spyOn(window, 'scrollTo').mockImplementation((options?: ScrollToOptions | number) => { if (typeof options === 'object') top = options.top ?? top; });
    try {
      const { user, t } = show('/products/loop', 'en', true, true);
      expect(await screen.findByRole('heading', { name: 'Loop', level: 1 })).toBeVisible();
      act(() => { top = 450; window.dispatchEvent(new Event('scroll')); });
      scroll.mockClear();
      await user.click(screen.getByRole('link', { name: t.noReviews }));
      expect(scroll).toHaveBeenCalledExactlyOnceWith({ left: 0, top: 0, behavior: 'instant' });
      await user.click(screen.getByRole('button', { name: 'History back' }));
      expect(top).toBe(450);
      await user.click(screen.getByRole('button', { name: 'History forward' }));
      expect(top).toBe(0);
      const input = screen.getByRole('combobox', { name: t.search });
      await user.type(input, 'Loop{Enter}');
      act(() => { top = 180; window.dispatchEvent(new Event('scroll')); });
      scroll.mockClear();
      await user.clear(input); await user.type(input, 'Mini{Enter}');
      expect(scroll).toHaveBeenCalledExactlyOnceWith({ left: 0, top: 0, behavior: 'instant' });
      expect(document.title).toBe(t.results + ' · Mini · Creastrix');
      expect(screen.getByRole('main')).toHaveFocus();
      await user.click(screen.getByRole('button', { name: 'History back' }));
      expect(top).toBe(180);
      expect(input).toHaveValue('Loop');
      expect(document.title).toBe(t.results + ' · Loop · Creastrix');
    } finally {
      if (descriptor) Object.defineProperty(window, 'scrollY', descriptor);
      scroll.mockRestore();
    }
  });
  it('restores the prior description on leaving the storefront', () => {
    const meta = document.createElement('meta'); meta.name = 'description'; meta.content = 'Pilot baseline'; document.head.append(meta);
    const { unmount } = show('/preview/studio');
    expect(meta.content).toBe(productCopy.en.studioBody);
    unmount(); expect(meta.content).toBe('Pilot baseline'); meta.remove();
  });
  it('preserves the long-page position when the browser clamps scroll during a shorter route commit', async () => {
    const descriptor = Object.getOwnPropertyDescriptor(window, 'scrollY');
    let top = 0;
    Object.defineProperty(window, 'scrollY', { configurable: true, get: () => document.querySelector('.product-collection-grid') ? Math.min(top, 1262.5) : top });
    const scroll = vi.spyOn(window, 'scrollTo').mockImplementation((options?: ScrollToOptions | number) => { if (typeof options === 'object') top = options.top ?? top; });
    try {
      const { user, t } = show('/products/loop', 'en', true, true);
      expect(await screen.findByRole('heading', { name: 'Loop', level: 1 })).toBeVisible();
      act(() => { top = 1490; window.dispatchEvent(new Event('scroll')); });
      await user.click(within(screen.getByRole('contentinfo')).getByRole('link', { name: t.collection }));
      expect(top).toBe(0);
      await user.click(screen.getByRole('button', { name: 'History back' }));
      expect(top).toBe(1490);
    } finally {
      if (descriptor) Object.defineProperty(window, 'scrollY', descriptor);
      scroll.mockRestore();
    }
  });
});
