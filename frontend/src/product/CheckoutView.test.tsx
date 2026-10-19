import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { readFileSync } from 'node:fs';
import { Provider } from 'react-redux';
import { Link, MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { afterEach, expect, it, vi } from 'vitest';
import { createAppStore } from '../store';
import CheckoutView from './CheckoutView';
import ProductExperience from './ProductExperience';
import type { VariantId } from './catalog';
import { money, variantById } from './catalog';
import { checkoutCopy } from './checkoutCopy';
import { productCopy } from './copy';
import { cartAdded, cartLineRemoved, cartLineRestored, cartQuantityChanged, productLocaleSelected } from './demoStore';
import { storefrontLocales } from './locale';
import type { StorefrontLocale } from './locale';

vi.mock('@google/model-viewer', () => ({}));
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

const sampleDetails = { name: 'Sample Person', email: 'sample@example.test', street: 'Example Street 4', postalCode: '12345', city: 'Sample City', country: 'Example Country' };
type Field = keyof typeof sampleDetails;
const fields = Object.keys(sampleDetails) as Field[];
function LocationProbe() { const location = useLocation(); return <output data-testid="checkout-location">{JSON.stringify(location)}</output>; }
function fixture(lines: { variant: VariantId; quantity: number }[] = [{ variant: 'chalk', quantity: 2 }], locale: StorefrontLocale = 'en') {
  const store = createAppStore('en', vi.fn());
  lines.forEach(line => store.dispatch(cartAdded(line)));
  store.dispatch(productLocaleSelected(locale));
  return store;
}
function show(store = fixture()) {
  const view = render(<Provider store={store}><MemoryRouter initialEntries={['/preview/checkout']}><Routes>
    <Route path="/preview/checkout" element={<CheckoutView />} />
    <Route path="/preview/cart" element={<><h1>Cart route</h1><Link to="/preview/checkout">Open checkout</Link></>} />
  </Routes><LocationProbe /></MemoryRouter></Provider>);
  return { ...view, store, user: userEvent.setup(), t: checkoutCopy.en };
}
function fill(values = sampleDetails) { fields.forEach(key => fireEvent.change(screen.getByRole('textbox', { name: checkoutCopy.en[key] }), { target: { value: values[key] } })); }

it('renders the checkout route in the existing storefront and returns to its cart without mutation', async () => {
  const store = fixture(), before = store.getState().productDemo.cart, user = userEvent.setup();
  render(<Provider store={store}><MemoryRouter initialEntries={['/preview/checkout']}><ProductExperience /></MemoryRouter></Provider>);
  expect(within(screen.getByRole('main')).getByRole('heading', { level: 1, name: checkoutCopy.en.title })).toBeVisible();
  await user.click(screen.getByRole('link', { name: checkoutCopy.en.backToCart }));
  expect(within(screen.getByRole('main')).getByRole('heading', { level: 1, name: productCopy.en.cart })).toBeVisible();
  expect(store.getState().productDemo.cart).toBe(before);
});

it('summarizes only active lines with their own photos, current prices, quantities and goods subtotal', () => {
  const store = fixture([{ variant: 'chalk', quantity: 2 }, { variant: 'coral', quantity: 3 }, { variant: 'cobalt-chalk', quantity: 1 }]);
  store.dispatch(cartLineRemoved('coral'));
  const { t } = show(store), p = productCopy.en;
  const summary = screen.getByRole('complementary', { name: t.summaryTitle });
  expect(within(summary).getAllByRole('article')).toHaveLength(2);
  expect(within(summary).queryByRole('article', { name: 'Loop · Coral' })).toBeNull();
  const chalk = within(summary).getByRole('article', { name: 'Loop · Chalk' });
  expect(within(chalk).getByRole('img')).toHaveAttribute('src', '/product/loop-chalk-01.webp');
  expect(within(chalk).getByText(p.price).nextElementSibling).toHaveTextContent('€19.90');
  expect(within(chalk).getByText(p.quantity).nextElementSibling).toHaveTextContent('2');
  expect(within(chalk).getByText(p.cartLineSubtotal).nextElementSibling).toHaveTextContent('€39.80');
  expect(chalk.querySelector('del')).toBeNull();
  expect(within(summary).getByRole('article', { name: 'Loop · Cobalt & chalk' }).querySelector('img')).toHaveAttribute('src', '/product/loop-cobalt-chalk-01.webp');
  expect(within(summary).getByText(p.cartLineCount).nextElementSibling).toHaveTextContent('2');
  expect(within(summary).getByText(p.cartUnitCount).nextElementSibling).toHaveTextContent('3');
  expect(within(summary).getByText(p.cartItemsSubtotal).nextElementSibling).toHaveTextContent('€67.80');
  expect(within(summary).getByText(t.subtotalNote)).toBeVisible();
  expect(within(summary).getByText(t.servicesUnavailable)).toBeVisible();
  act(() => { store.dispatch(cartQuantityChanged({ variant: 'chalk', quantity: 3 })); });
  expect(within(summary).getByText(p.cartItemsSubtotal).nextElementSibling).toHaveTextContent('€87.70');
  expect(within(chalk).getByText(p.cartLineSubtotal).nextElementSibling).toHaveTextContent('€59.70');
});

it.each(storefrontLocales)('localizes the complete prototype and current summary in %s', locale => {
  const store = fixture([{ variant: 'cobalt', quantity: 2 }], locale);
  show(store);
  const t = checkoutCopy[locale], p = productCopy[locale];
  expect(screen.getByRole('heading', { level: 1, name: t.title })).toBeVisible();
  expect(screen.getByRole('link', { name: t.backToCart })).toHaveAttribute('href', '/preview/cart');
  expect(screen.getByText(t.prototypeLabel).parentElement).toHaveTextContent(t.prototypeNote);
  expect(screen.getByRole('form', { name: t.detailsTitle })).toHaveAccessibleDescription(`${t.requiredNote} ${t.prototypeLabel} · ${t.prototypeNote}`);
  for (const field of fields) expect(screen.getByRole('textbox', { name: t[field] })).toBeRequired();
  expect(screen.getByRole('button', { name: t.previewDetails })).toBeVisible();
  const summary = screen.getByRole('complementary', { name: t.summaryTitle });
  expect(within(summary).getByRole('article', { name: 'Loop · ' + variantById('cobalt').name[locale] })).toBeVisible();
  expect(within(summary).getByText(p.cartItemsSubtotal).nextElementSibling?.textContent).toBe(money(4800, locale));
  expect(Object.keys(t).sort()).toEqual(Object.keys(checkoutCopy.en).sort());
  expect(Object.values(t).every(value => value.trim().length > 0)).toBe(true);
});

it.each([false, true])('guards an empty active cart, including retained removed rows: %s', removed => {
  const store = fixture(removed ? [{ variant: 'coral', quantity: 1 }] : []);
  if (removed) store.dispatch(cartLineRemoved('coral'));
  const { t } = show(store);
  expect(screen.getByRole('heading', { name: t.emptyTitle })).toBeVisible();
  expect(screen.getByText(t.emptyBody)).toBeVisible();
  expect(screen.getByRole('link', { name: t.backToCart })).toHaveAttribute('href', '/preview/cart');
  expect(screen.queryByRole('form')).toBeNull(); expect(screen.queryByRole('textbox')).toBeNull();
  expect(screen.queryByRole('button')).toBeNull(); expect(screen.queryByRole('complementary')).toBeNull();
});

it('uses bounded labelled fields, native required/email validity, and keyboard-accessible local preview', async () => {
  const { t, user } = show();
  const form = screen.getByRole('form', { name: t.detailsTitle }) as HTMLFormElement;
  expect(form).toHaveAttribute('autocomplete', 'off'); expect(form).not.toHaveAttribute('novalidate');
  const limits = { name: 120, email: 254, street: 200, postalCode: 32, city: 100, country: 100 };
  for (const field of fields) {
    const input = screen.getByRole('textbox', { name: t[field] });
    expect(input).toHaveAttribute('maxlength', String(limits[field]));
    expect(input).toHaveAttribute('autocomplete', 'off');
    expect(input).toHaveAttribute('type', field === 'email' ? 'email' : 'text');
    expect(input).toHaveValue('');
  }
  await user.tab(); expect(screen.getByRole('link', { name: t.backToCart })).toHaveFocus();
  for (const field of fields) { await user.tab(); expect(screen.getByRole('textbox', { name: t[field] })).toHaveFocus(); }
  await user.tab(); expect(screen.getByRole('button', { name: t.previewDetails })).toHaveFocus();
  await user.keyboard('{Enter}'); expect(form.checkValidity()).toBe(false);
  expect(screen.queryByRole('heading', { name: t.previewTitle })).toBeNull();
  fill({ ...sampleDetails, email: 'not-an-email' });
  await user.click(screen.getByRole('button', { name: t.previewDetails }));
  expect(form.checkValidity()).toBe(false); expect(screen.queryByRole('heading', { name: t.previewTitle })).toBeNull();
  fireEvent.change(screen.getByRole('textbox', { name: t.email }), { target: { value: sampleDetails.email } });
  expect(form.checkValidity()).toBe(true);
  const focus = vi.spyOn(HTMLElement.prototype, 'focus');
  await user.click(screen.getByRole('button', { name: t.previewDetails }));
  const previewHeading = screen.getByRole('heading', { name: t.previewTitle });
  expect(previewHeading).toHaveFocus();
  // Explicit preview submission must allow the browser to reveal the appended result.
  expect(focus.mock.calls.filter((_call, index) => focus.mock.contexts[index] === previewHeading)).toEqual([[]]);
  expect(screen.getByText(t.previewNote)).toBeVisible();
  const preview = screen.getByRole('region', { name: t.previewTitle });
  for (const field of fields) expect(within(preview).getByText(t[field]).nextElementSibling).toHaveTextContent(sampleDetails[field]);
});

it('renders entered text safely and hides an obsolete preview as soon as a field is edited', async () => {
  const { user, t } = show();
  const untrusted = '<img src=x onerror=alert(1)>';
  fill({ ...sampleDetails, name: untrusted });
  await user.click(screen.getByRole('button', { name: t.previewDetails }));
  const preview = screen.getByRole('region', { name: t.previewTitle });
  expect(within(preview).getByText(untrusted)).toBeVisible(); expect(preview.querySelector('img')).toBeNull();
  fireEvent.change(screen.getByRole('textbox', { name: t.city }), { target: { value: 'Different City' } });
  expect(screen.queryByRole('region', { name: t.previewTitle })).toBeNull();
});

it('discards details on route departure and never clears or submits the cart', async () => {
  const { user, t, store } = show(), before = store.getState();
  fill(); await user.click(screen.getByRole('button', { name: t.previewDetails }));
  expect(store.getState()).toBe(before);
  await user.click(screen.getByRole('link', { name: t.backToCart }));
  expect(screen.getByRole('heading', { name: 'Cart route' })).toBeVisible();
  expect(screen.queryByText(sampleDetails.name)).toBeNull();
  await user.click(screen.getByRole('link', { name: 'Open checkout' }));
  for (const field of fields) expect(screen.getByRole('textbox', { name: t[field] })).toHaveValue('');
  expect(screen.queryByRole('region', { name: t.previewTitle })).toBeNull();
  expect(store.getState()).toBe(before);
});

it('discards form details when the final active line disappears, even if it is later restored', async () => {
  const { user, store, t } = show();
  fill(); await user.click(screen.getByRole('button', { name: t.previewDetails }));
  act(() => { store.dispatch(cartLineRemoved('chalk')); });
  expect(screen.queryByRole('form')).toBeNull(); expect(screen.queryByText(sampleDetails.email)).toBeNull();
  const revision = store.getState().productDemo.cartRemoved.chalk!.revision;
  act(() => { store.dispatch(cartLineRestored({ variant: 'chalk', revision })); });
  expect(screen.getByRole('textbox', { name: t.email })).toHaveValue('');
  expect(screen.queryByRole('region', { name: t.previewTitle })).toBeNull();
});

it('keeps draft data out of network, storage, logs, Redux and route state', async () => {
  const store = fixture(), before = store.getState();
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch);
  const xhr = vi.spyOn(XMLHttpRequest.prototype, 'send');
  const storageReads = vi.spyOn(Storage.prototype, 'getItem'), storageWrites = vi.spyOn(Storage.prototype, 'setItem');
  const logs = [vi.spyOn(console, 'log'), vi.spyOn(console, 'info'), vi.spyOn(console, 'warn'), vi.spyOn(console, 'error')];
  const { user, t } = show(store);
  fill(); await user.click(screen.getByRole('button', { name: t.previewDetails }));
  expect(fetch).not.toHaveBeenCalled(); expect(xhr).not.toHaveBeenCalled();
  expect(storageReads).not.toHaveBeenCalled(); expect(storageWrites).not.toHaveBeenCalled();
  logs.forEach(log => expect(log).not.toHaveBeenCalled());
  expect(store.getState()).toBe(before);
  const route = screen.getByTestId('checkout-location').textContent;
  expect(route).toContain('/preview/checkout');
  Object.values(sampleDetails).forEach(value => expect(route).not.toContain(value));
  const source = readFileSync('src/product/CheckoutView.tsx', 'utf8');
  expect(source).not.toMatch(/\b(fetch|XMLHttpRequest|WebSocket|sendBeacon|localStorage|sessionStorage|indexedDB|useAppDispatch|console)\b/);
  expect(source).not.toMatch(/type=["'](?:password|hidden)["']|cc-number|cc-csc|\/api\/|\/auth\//);
});
