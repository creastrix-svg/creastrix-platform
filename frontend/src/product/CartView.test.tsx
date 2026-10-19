import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { MemoryRouter } from 'react-router';
import { afterEach, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { createAppStore } from '../store';
import ProductExperience from './ProductExperience';
import { cartAdded, cartLineRestored, cartRemovedLineDismissed, productDescriptionChanged, productMediaModeSelected, productVariantSelected, purchaseQuantityChanged } from './demoStore';
import { productCopy } from './copy';
import { checkoutCopy } from './checkoutCopy';
import type { VariantId } from './catalog';
import { money, variantById } from './catalog';
import { storefrontLocales } from './locale';

vi.mock('@google/model-viewer', () => ({}));
afterEach(() => { vi.restoreAllMocks(); });
let fixtureKey = 0;
function show(lines: { variant: VariantId; quantity: number }[] = [], path = '/preview/cart') {
  const store = createAppStore('en', vi.fn()); lines.forEach(line => store.dispatch(cartAdded(line)));
  const url = new URL(path, 'https://example.test');
  const view = render(<Provider store={store}><MemoryRouter initialEntries={[{ pathname: url.pathname, search: url.search, key: 'cart-ui-case-' + ++fixtureKey }]}><ProductExperience /></MemoryRouter></Provider>);
  return { ...view, store, user: userEvent.setup(), t: productCopy.en, main: screen.getByRole('main') };
}

it('renders variant-owned media, current unit prices, line subtotals and separate line/unit counts', () => {
  const { main, t } = show([{ variant: 'chalk', quantity: 2 }, { variant: 'coral', quantity: 3 }, { variant: 'cobalt', quantity: 1 }]);
  const chalk = within(main).getByRole('article', { name: 'Loop · Chalk' });
  expect(within(chalk).getByRole('img')).toHaveAttribute('src', '/product/loop-chalk-01.webp');
  expect(within(chalk).getByText(t.price)).toBeVisible(); expect(within(chalk).getByText('€19.90')).toBeVisible();
  expect(within(chalk).getByText('€24.00').closest('del')).toBeInTheDocument(); expect(within(chalk).getByText('−17%')).toBeVisible();
  expect(within(chalk).getByText('€39.80')).toBeVisible();
  const summary = within(main).getByRole('complementary', { name: 'Summary' });
  expect(within(summary).getByText('Product lines').nextElementSibling).toHaveTextContent('3');
  expect(within(summary).getByText('Units').nextElementSibling).toHaveTextContent('6');
  expect(within(summary).getByText('€141.80')).toBeVisible();
});

it('keeps numeric stock and quantity notices truthful, clamps at own stock and recomputes current-price totals', () => {
  const { main, store, t } = show([{ variant: 'chalk', quantity: 2 }]);
  const line = within(main).getByRole('article', { name: 'Loop · Chalk' });
  expect(line.querySelector('.product-cart-stock')).toHaveTextContent(t.stock + ': 15 ' + t.pieces);
  const input = within(line).getByRole('textbox', { name: t.quantity + ' · Chalk' });
  fireEvent.change(input, { target: { value: '999' } }); expect(input).toHaveValue('15');
  expect(within(line).getByText(t.limit + ' 15.')).toBeVisible(); expect(store.getState().productDemo.cart[0].quantity).toBe(15);
  expect(within(line).getByText('€298.50')).toBeVisible();
  expect(within(main).getByRole('complementary', { name: t.cartSummary })).toHaveTextContent('€298.50');
  expect(within(line).getByRole('button', { name: t.increase + ' · Chalk' })).toBeDisabled();
  fireEvent.change(input, { target: { value: '1.5' } }); expect(input).toHaveValue('15');
  fireEvent.change(input, { target: { value: '0' } }); expect(input).toHaveValue('1');
  expect(within(line).getByRole('button', { name: t.decrease + ' · Chalk' })).toBeDisabled();
});

it('keeps unit price, quantity and subtotal in the same DOM row for regular and discounted variants', () => {
  const { main } = show([{ variant: 'chalk', quantity: 2 }, { variant: 'cobalt-chalk', quantity: 3 }]);
  for (const row of within(main).getAllByRole('article')) {
    const controls = row.querySelector<HTMLDivElement>('.product-cart-controls')!;
    expect(Array.from(controls.children).slice(0, 3).map(child => child.className)).toEqual(['product-cart-unit', 'product-quantity-field', 'product-cart-line-total']);
    expect(row.querySelector('.product-cart-line-copy')).not.toContainElement(controls);
    expect(controls.querySelector('.product-cart-action')).toBeInTheDocument();
  }
});

it.each(storefrontLocales)('names minicart quantity actions by their own variant in %s', async locale => {
  const { user } = show([{ variant: 'chalk', quantity: 2 }, { variant: 'cobalt-chalk', quantity: 3 }]);
  await user.selectOptions(screen.getByRole('combobox', { name: productCopy.en.language }), locale);
  const t = productCopy[locale];
  await user.hover(within(screen.getByRole('banner')).getByRole('link', { name: t.cart }));
  const popup = screen.getByRole('region', { name: t.cartPreview });
  for (const variant of ['chalk', 'cobalt-chalk'] as const) {
    const context = variantById(variant).name[locale];
    expect(within(popup).getByRole('button', { name: `${t.increase} · ${context}` })).toBeEnabled();
    expect(within(popup).getByRole('button', { name: `${t.decrease} · ${context}` })).toBeEnabled();
    expect(within(popup).getByRole('textbox', { name: `${t.quantity} · ${context}` })).toBeEnabled();
  }
});

it('keeps the same row/action and keyboard focus through zero-disabled removal and exact restoration without scrolling', async () => {
  const { main, user, store, t } = show([{ variant: 'cobalt', quantity: 2 }, { variant: 'coral', quantity: 3 }, { variant: 'chalk', quantity: 1 }]);
  const scroll = vi.spyOn(window, 'scrollTo'); scroll.mockClear();
  const row = within(main).getByRole('article', { name: 'Loop · Coral' }), action = within(row).getByRole('button', { name: t.remove + ' · Coral' });
  expect(action.querySelector('svg path')).toHaveAttribute('d', 'M3 6h18 M9 6V3h6v3 M5 6l1 15h12l1-15 M10 10v7 M14 10v7');
  action.focus(); const focus = vi.spyOn(action, 'focus');
  await user.keyboard('{Enter}');
  expect(within(main).getByRole('article', { name: 'Loop · Coral' })).toBe(row);
  expect(within(row).getByRole('button', { name: t.cartRestore + ' · Coral' })).toBe(action); expect(action).toHaveFocus();
  expect(row).toHaveClass('product-cart-line-removed'); expect(within(row).getByRole('textbox')).toHaveValue('0');
  expect(within(row).getByRole('textbox')).toBeDisabled(); expect(within(row).getByRole('button', { name: t.decrease + ' · Coral' })).toBeDisabled(); expect(within(row).getByRole('button', { name: t.increase + ' · Coral' })).toBeDisabled();
  expect(row.querySelector('.product-cart-line-total dd')).toHaveTextContent('€0.00');
  expect(main.querySelector('p.product-sr-only[role="status"]')).toHaveTextContent(t.cartRemoved + ': Loop · Coral');
  await user.keyboard(' ');
  expect(store.getState().productDemo.cart.map(line => line.variant)).toEqual(['cobalt', 'coral', 'chalk']);
  expect(within(row).getByRole('button', { name: t.remove + ' · Coral' })).toBe(action); expect(action).toHaveFocus();
  expect(row).not.toHaveClass('product-cart-line-removed'); expect(within(row).getByRole('textbox')).toHaveValue('3'); expect(within(row).getByRole('textbox')).toBeEnabled();
  expect(main.querySelector('p.product-sr-only[role="status"]')).toHaveTextContent(t.cartRestored + ': Loop · Coral');
  expect(focus).toHaveBeenLastCalledWith({ preventScroll: true });
  expect(scroll).not.toHaveBeenCalled();
});

it('announces removals without describing another row action with the last changed variant', async () => {
  const { main, user, t } = show([{ variant: 'cobalt', quantity: 2 }, { variant: 'coral', quantity: 3 }]);
  const cobalt = within(main).getByRole('button', { name: t.remove + ' · Cobalt blue' });
  const coral = within(main).getByRole('button', { name: t.remove + ' · Coral' });
  await user.click(coral);
  const status = main.querySelector('p.product-sr-only[role="status"]');
  expect(status).toHaveTextContent(t.cartRemoved + ': Loop · Coral');
  expect(cobalt).toHaveAccessibleDescription(''); expect(cobalt).not.toHaveAttribute('aria-describedby');
  expect(coral).toHaveAccessibleName(t.cartRestore + ' · Coral'); expect(coral).toHaveFocus();
  expect(coral).toHaveAccessibleDescription('');
  await user.click(coral);
  expect(status).toHaveTextContent(t.cartRestored + ': Loop · Coral');
  expect(cobalt).toHaveAccessibleDescription(''); expect(coral).toHaveAccessibleDescription('');
  expect(coral).toHaveAccessibleName(t.remove + ' · Coral'); expect(coral).toHaveFocus();
});

it.each(['first', 'middle', 'last'] as const)('dismisses the %s zero row and focuses its nearest surviving row action without scrolling', async position => {
  const { main, user, store, t } = show([{ variant: 'cobalt', quantity: 2 }, { variant: 'coral', quantity: 3 }, { variant: 'chalk', quantity: 1 }]);
  expect(main.querySelector('.product-cart-dismiss')).toBeNull();
  for (const name of ['Cobalt blue', 'Coral', 'Chalk']) await user.click(within(main).getByRole('button', { name: t.remove + ' · ' + name }));
  const index = { first: 0, middle: 1, last: 2 }[position], names = ['Cobalt blue', 'Coral', 'Chalk'], targetName = names[index === 2 ? 1 : index + 1];
  const target = within(main).getByRole('button', { name: t.cartRestore + ' · ' + targetName }), focus = vi.spyOn(target, 'focus');
  const scroll = vi.spyOn(window, 'scrollTo'); scroll.mockClear();
  await user.click(within(main).getByRole('button', { name: t.cartDismiss + ' · ' + names[index] }));
  expect(within(main).queryByRole('article', { name: 'Loop · ' + names[index] })).toBeNull();
  expect(within(main).getAllByRole('article')).toHaveLength(2); expect(target).toHaveFocus();
  expect(focus).toHaveBeenLastCalledWith({ preventScroll: true }); expect(scroll).not.toHaveBeenCalled();
  expect(store.getState().productDemo.cart).toEqual([]); expect(within(main).getByRole('complementary')).toHaveTextContent('€0.00');
  await user.click(target); expect(store.getState().productDemo.cart).toHaveLength(1);
});

it('does not steal input focus for a programmatic dismissal without an owned X click', async () => {
  const { main, user, store, t } = show([{ variant: 'cobalt', quantity: 2 }, { variant: 'coral', quantity: 3 }]);
  await user.click(within(main).getByRole('button', { name: t.remove + ' · Coral' }));
  const revision = store.getState().productDemo.cartRemoved.coral!.revision, input = within(main).getByRole('textbox', { name: t.quantity + ' · Cobalt blue' });
  await user.click(input); expect(input).toHaveFocus();
  act(() => { store.dispatch(cartRemovedLineDismissed({ variant: 'coral', revision })); });
  expect(input).toHaveFocus(); expect(within(main).queryByRole('article', { name: 'Loop · Coral' })).toBeNull();
});

it('focuses Continue shopping after the last X, rejects old Restore and accepts a new ordinary card add', async () => {
  const { main, user, store, t } = show([{ variant: 'coral', quantity: 3 }]);
  await user.click(within(main).getByRole('button', { name: t.remove + ' · Coral' }));
  const revision = store.getState().productDemo.cartRemoved.coral!.revision;
  const dismiss = within(main).getByRole('button', { name: t.cartDismiss + ' · Coral' }); dismiss.focus();
  const scroll = vi.spyOn(window, 'scrollTo'); scroll.mockClear(); await user.keyboard('{Enter}');
  expect(within(main).queryByRole('article')).toBeNull(); expect(within(main).getByText(t.emptyCart)).toBeVisible();
  const continueLink = within(main).getByRole('link', { name: t.continue }); expect(continueLink).toHaveFocus(); expect(scroll).not.toHaveBeenCalled();
  const dismissed = store.getState().productDemo;
  act(() => { store.dispatch(cartLineRestored({ variant: 'coral', revision })); }); expect(store.getState().productDemo).toBe(dismissed);
  await user.click(continueLink); await user.click(screen.getByRole('button', { name: 'Coral' }));
  fireEvent.change(screen.getByRole('textbox', { name: t.quantity }), { target: { value: '2' } }); await user.click(screen.getByRole('button', { name: t.add }));
  await user.click(within(screen.getByRole('banner')).getByRole('link', { name: t.cart }));
  expect(within(screen.getByRole('main')).getAllByRole('article')).toHaveLength(1);
  expect(within(screen.getByRole('main')).getByRole('textbox')).toHaveValue('2'); expect(screen.getByRole('main').querySelector('.product-cart-dismiss')).toBeNull();
});

it('shares active-only minicart mutations without invalidating another row or stealing its input focus', async () => {
  const { main, user, store, t } = show([{ variant: 'cobalt', quantity: 2 }, { variant: 'coral', quantity: 3 }]);
  await user.click(within(main).getByRole('button', { name: t.remove + ' · Coral' })); const old = store.getState().productDemo.cartChange.revision;
  expect(within(main).getByRole('button', { name: t.cartRestore + ' · Coral' })).toBeVisible();
  await user.hover(within(screen.getByRole('banner')).getByRole('link', { name: t.cart }));
  const popup = screen.getByRole('region', { name: t.cartPreview }), input = within(popup).getByRole('textbox', { name: t.quantity + ' · Cobalt blue' });
  expect(within(popup).queryByRole('textbox', { name: t.quantity + ' · Coral' })).toBeNull();
  await user.click(input); fireEvent.change(input, { target: { value: '4' } }); expect(input).toHaveFocus();
  expect(within(main).getByRole('button', { name: t.cartRestore + ' · Coral' })).toBeVisible(); expect(store.getState().productDemo.cartRemoved.coral?.revision).toBe(old);
  await user.click(within(popup).getByRole('button', { name: t.remove + ' · Cobalt blue' }));
  expect(within(screen.getByRole('banner')).getByRole('link', { name: t.cart })).toHaveFocus();
  expect(within(main).getAllByRole('article')).toHaveLength(2); expect(main.querySelectorAll('.product-cart-line-removed')).toHaveLength(2);
  expect(within(main).queryByText(t.emptyCart)).toBeNull(); expect(within(main).getByRole('complementary')).toHaveTextContent('€0.00');
  expect(screen.getByRole('banner').querySelector('.product-count')).toBeNull();
  await user.hover(within(screen.getByRole('banner')).getByRole('link', { name: t.cart }));
  expect(within(screen.getByRole('region', { name: t.cartPreview })).getByText(t.emptyCart)).toBeVisible();
  expect(within(screen.getByRole('region', { name: t.cartPreview })).queryByRole('textbox')).toBeNull();
  await user.click(within(main).getByRole('button', { name: t.cartRestore + ' · Coral' }));
  await user.click(within(main).getByRole('button', { name: t.cartRestore + ' · Cobalt blue' }));
  expect(store.getState().productDemo.cart).toEqual([{ variant: 'cobalt', quantity: 4 }, { variant: 'coral', quantity: 3 }]);
});

it('keeps A/B/C DOM slots through remove B/A, minicart C edit, and independent B/A restores', async () => {
  const { main, user, store, t } = show([{ variant: 'cobalt', quantity: 2 }, { variant: 'coral', quantity: 3 }, { variant: 'chalk', quantity: 1 }]);
  const rows = within(main).getAllByRole('article');
  await user.click(within(main).getByRole('button', { name: t.remove + ' · Coral' }));
  await user.click(within(main).getByRole('button', { name: t.remove + ' · Cobalt blue' }));
  await user.hover(within(screen.getByRole('banner')).getByRole('link', { name: t.cart }));
  const popup = screen.getByRole('region', { name: t.cartPreview });
  expect(within(popup).getAllByRole('textbox')).toHaveLength(1);
  fireEvent.change(within(popup).getByRole('textbox'), { target: { value: '4' } });
  await user.click(within(main).getByRole('button', { name: t.cartRestore + ' · Coral' }));
  await user.click(within(main).getByRole('button', { name: t.cartRestore + ' · Cobalt blue' }));
  expect(within(main).getAllByRole('article')).toEqual(rows);
  expect(within(main).getAllByRole('textbox').map(input => (input as HTMLInputElement).value)).toEqual(['2', '3', '4']);
  const summary = within(main).getByRole('complementary');
  expect(within(summary).getByText(t.cartLineCount).nextElementSibling).toHaveTextContent('3');
  expect(within(summary).getByText(t.cartUnitCount).nextElementSibling).toHaveTextContent('9'); expect(summary).toHaveTextContent('€205.60');
  expect(store.getState().productDemo.addSerial).toBe(3);
});

it('uses an explicit card re-add quantity and retires the old Restore before a new remove/restore cycle', async () => {
  const { main, user, store, t } = show([{ variant: 'coral', quantity: 3 }]);
  await user.click(within(main).getByRole('button', { name: t.remove + ' · Coral' }));
  const revision = store.getState().productDemo.cartRemoved.coral!.revision;
  const row = within(main).getByRole('article');
  await user.click(within(row).getByRole('link', { name: 'Loop · Coral' }));
  fireEvent.change(screen.getByRole('textbox', { name: t.quantity }), { target: { value: '2' } });
  await user.click(screen.getByRole('button', { name: t.add }));
  await user.click(within(screen.getByRole('banner')).getByRole('link', { name: t.cart }));
  const cart = screen.getByRole('main'), added = store.getState().productDemo;
  expect(within(cart).getAllByRole('article')).toHaveLength(1); expect(within(cart).getByRole('textbox')).toHaveValue('2');
  expect(within(cart).queryByRole('button', { name: t.cartRestore + ' · Coral' })).toBeNull();
  act(() => { store.dispatch(cartLineRestored({ variant: 'coral', revision })); }); expect(store.getState().productDemo).toBe(added);
  await user.click(within(cart).getByRole('button', { name: t.remove + ' · Coral' }));
  await user.click(within(cart).getByRole('button', { name: t.cartRestore + ' · Coral' }));
  expect(within(cart).getByRole('textbox')).toHaveValue('2'); expect(store.getState().productDemo.cart).toEqual([{ variant: 'coral', quantity: 2 }]);
});

it.each(storefrontLocales)('renders localized Continue shopping and disables checkout for zero active rows in %s', async locale => {
  const { main, user, store } = show([{ variant: 'coral', quantity: 3 }]);
  await user.selectOptions(screen.getByRole('combobox', { name: productCopy.en.language }), locale);
  const t = productCopy[locale], name = variantById('coral').name[locale];
  const summary = within(main).getByRole('complementary', { name: t.cartSummary });
  expect(within(main).getByRole('button', { name: `${t.increase} · ${name}` })).toBeEnabled();
  expect(within(main).getByRole('button', { name: `${t.decrease} · ${name}` })).toBeEnabled();
  expect(within(main).getByRole('textbox', { name: `${t.quantity} · ${name}` })).toHaveValue('3');
  expect(within(summary).getByText(t.cartLineCount).nextElementSibling).toHaveTextContent('1');
  expect(within(summary).getByText(t.cartUnitCount).nextElementSibling).toHaveTextContent('3');
  expect(within(summary).getByText(t.cartItemsSubtotal)).toBeVisible();
  expect(within(summary).getByRole('link', { name: checkoutCopy[locale].proceed })).toHaveAttribute('href', '/preview/checkout');
  expect(within(summary).queryByRole('link', { name: t.continue })).toBeNull();
  expect(summary.querySelector('.product-cart-subtotal > strong')?.textContent).toBe(money(7800, locale));
  await user.click(within(main).getByRole('button', { name: t.remove + ' · ' + name }));
  expect(within(main).queryByText(t.cartEmptyBody)).toBeNull();
  expect(within(main).getByRole('link', { name: t.continue })).toHaveAttribute('href', '/products/loop?variant=cobalt');
  expect(within(main).getByRole('link', { name: t.continue })).toHaveClass('product-back');
  expect(within(summary).getByText(t.cartLineCount).nextElementSibling).toHaveTextContent('0'); expect(within(summary).getByText(t.cartUnitCount).nextElementSibling).toHaveTextContent('0');
  expect(summary.querySelector('.product-cart-subtotal > strong')?.textContent).toBe(money(0, locale));
  expect(within(main).getByRole('textbox')).toHaveValue('0'); expect(within(main).getByRole('textbox')).toBeDisabled();
  const dismissText = { de: 'Zeile aus dem Warenkorb entfernen', en: 'Remove row from cart', ru: 'Убрать строку из корзины', pl: 'Usuń wiersz z koszyka', es: 'Quitar fila del carrito', fr: 'Retirer la ligne du panier', it: 'Rimuovi riga dal carrello' };
  expect(within(main).getByRole('button', { name: dismissText[locale] + ' · ' + name })).toHaveAttribute('title', dismissText[locale]);
  expect(within(summary).getByRole('button', { name: checkoutCopy[locale].proceed })).toBeDisabled();
  expect(within(summary).queryByRole('link', { name: checkoutCopy[locale].proceed })).toBeNull();
  await user.click(within(main).getByRole('button', { name: t.cartRestore + ' · ' + name }));
  expect(store.getState().productDemo.cart).toEqual([{ variant: 'coral', quantity: 3 }]);
  expect(main.querySelector('.product-cart-dismiss')).toBeNull();
  expect(within(summary).getByRole('link', { name: checkoutCopy[locale].proceed })).toBeVisible();
});

it.each(['cart', 'checkout'] as const)('preserves productReturn colour, purchase draft, Photo/3D and disclosure via %s', async action => {
  const { user, store, t } = show([], '/products/loop?variant=cobalt-chalk');
  act(() => {
    store.dispatch(productVariantSelected('cobalt-chalk')); store.dispatch(purchaseQuantityChanged({ variant: 'cobalt-chalk', quantity: 3 }));
    store.dispatch(productMediaModeSelected('model')); store.dispatch(productDescriptionChanged(true)); store.dispatch(cartAdded({ variant: 'cobalt-chalk', quantity: 2 }));
  });
  // Use the media mode control as well: the mounted gallery owns its current mode.
  await user.click(screen.getByRole('button', { name: t.model }));
  await user.click(within(screen.getByRole('banner')).getByRole('link', { name: t.cart }));
  if (action === 'checkout') {
    await user.click(within(screen.getByRole('main')).getByRole('link', { name: checkoutCopy.en.proceed }));
    expect(within(screen.getByRole('main')).getByRole('heading', { level: 1, name: checkoutCopy.en.title })).toBeVisible();
    await user.click(screen.getByRole('link', { name: checkoutCopy.en.backToCart }));
  }
  await user.click(within(screen.getByRole('main')).getByRole('link', { name: t.continue }));
  expect(screen.getByRole('button', { name: 'Cobalt & chalk' })).toHaveAttribute('aria-pressed', 'true');
  expect(screen.getByRole('textbox', { name: t.quantity })).toHaveValue('3'); expect(screen.getByRole('button', { name: t.model })).toHaveAttribute('aria-pressed', 'true');
  expect(document.querySelector('.product-description details')).toHaveAttribute('open');
  expect(store.getState().productDemo.cart).toEqual([{ variant: 'cobalt-chalk', quantity: 2 }]);
});

it('keeps fresh-empty navigation, hidden status, fixed counters and removed/Restore styles scoped without timers', () => {
  const { main, t } = show();
  expect(within(main).getByText(t.emptyCart)).toBeVisible(); expect(within(main).getByRole('link', { name: t.continue })).toBeVisible();
  expect(main.querySelector('.product-cart-status')).toBeNull(); expect(main.querySelector('p.product-sr-only[role="status"]')).toBeEmptyDOMElement();
  const css = readFileSync('src/product/product.css', 'utf8'), component = readFileSync('src/product/CartView.tsx', 'utf8');
  expect(css).toMatch(/\.cart-view \.product-cart-layout\s*\{[^}]*grid-template-columns:\s*minmax\(0, 1fr\) minmax\(260px, 320px\)/s);
  expect(css).not.toContain('.product-cart-status');
  expect(css).toMatch(/\.cart-view \.product-quantity, \.product-cart-peek \.product-quantity\s*\{[^}]*box-sizing:\s*border-box;[^}]*flex:\s*none;[^}]*width:\s*108px;[^}]*min-width:\s*108px;[^}]*max-width:\s*108px;/s);
  expect(css).toMatch(/\.cart-view \.product-cart-controls\s*\{[^}]*grid-template-columns:\s*minmax\(0, 1fr\) 108px minmax\(0, 1fr\)/s);
  expect(css).toMatch(/\.cart-view \.product-quantity-notice\s*\{[^}]*left:\s*50%;[^}]*transform:\s*translateX\(-50%\)/s);
  expect(css).toMatch(/\.cart-view \.product-cart-restore\s*\{[^}]*color:\s*var\(--product-sale\)/s);
  expect(css).toMatch(/\.cart-view \.product-cart-dismiss\s*\{[^}]*grid-column:\s*4;[^}]*width:\s*44px;[^}]*height:\s*44px;[^}]*min-width:\s*44px;[^}]*min-height:\s*44px;[^}]*color:\s*var\(--product-text\)/s);
  expect(css).toContain('.cart-view .product-cart-dismiss:is(:hover, :focus-visible)');
  expect(css).toMatch(/\.cart-view \.product-cart-line-removed \.product-cart-photo\s*\{[^}]*opacity:\s*\.5/s);
  expect(css).not.toMatch(/\.cart-view \.product-cart-line-removed\s*\{[^}]*opacity/s);
  expect(css).toMatch(/\.cart-view \.product-cart-unit \.product-price-row\s*\{[^}]*grid-template-rows:\s*32px 52px auto;[^}]*min-height:\s*0;/s);
  expect(css).toMatch(/\.cart-view \.product-cart-counts > div\s*\{[^}]*border-color:\s*var\(--product-border\)/s);
  expect(css).toMatch(/\.cart-view \.product-cart-counts dt\s*\{[^}]*color:\s*var\(--product-muted\)/s);
  expect(css.slice(css.indexOf('@media (max-width: 1200px)'))).toMatch(/\.cart-view \.product-cart-layout\s*\{[^}]*grid-template-columns:\s*1fr/s);
  expect(css.slice(css.indexOf('@media (max-width: 1200px)'))).toMatch(/\.cart-view \.product-cart-summary\s*\{[^}]*width:\s*min\(100%, 360px\);[^}]*justify-self:\s*end;/s);
  expect(component).not.toMatch(/setTimeout|setInterval|Spinner|localStorage|fetch\(/);
});

it('keeps the last removed row and zero summary instead of substituting an empty screen', async () => {
  const { main, user, store, t } = show([{ variant: 'coral', quantity: 3 }]);
  const scroll = vi.spyOn(window, 'scrollTo'); scroll.mockClear();
  await user.click(within(main).getByRole('button', { name: t.remove + ' · Coral' }));
  expect(within(main).queryByText(t.emptyCart)).toBeNull(); expect(within(main).getByRole('complementary')).toHaveTextContent('€0.00');
  const restore = within(main).getByRole('button', { name: 'Restore · Coral' }); expect(restore).toHaveFocus();
  await user.click(restore);
  expect(store.getState().productDemo.cart).toEqual([{ variant: 'coral', quantity: 3 }]);
  expect(within(main).queryByRole('button', { name: 'Restore · Coral' })).toBeNull();
  expect(within(main).getByRole('button', { name: t.remove + ' · Coral' })).toHaveFocus();
  expect(scroll).not.toHaveBeenCalled(); scroll.mockRestore();
});
