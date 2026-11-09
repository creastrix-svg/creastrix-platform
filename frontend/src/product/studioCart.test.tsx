import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { Link, MemoryRouter } from 'react-router';
import { beforeEach, expect, it, vi } from 'vitest';
import { createAppStore } from '../store';
import { cartAdded, cartCount, cartDesignChanged, cartHydrated, cartLineId, cartLineRemoved, cartLineRestored, cartQuantityChanged, cartTotal, lineCapacity, variantCartQuantity } from './demoStore';
import { defaultLettering } from './personalization';
import { designKey, saveDesign } from './designSnapshot';
import ProductExperience from './ProductExperience';
import { variantById } from './catalog';
import { productCopy } from './copy';

vi.mock('./ModelPreview', async () => {
  const { createPortal } = await import('react-dom');
  return { default: ({ target, decoration }: { target: HTMLElement | null; decoration?: { objects?: unknown } }) => target ? createPortal(<output data-testid="cart-model">{JSON.stringify(decoration?.objects)}</output>, target) : null };
});
const design = (text: string) => saveDesign({ selectedId: 'studio-object-1', objects: [{ id: 'studio-object-1', kind: 'text', value: { ...defaultLettering('cobalt'), text } }] })!;
beforeEach(() => {
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ measureText: (text: string) => ({ width: text.length * 12 }) } as unknown as CanvasRenderingContext2D);
});
it('keeps plain and different designs separate; repeated equal snapshots add to their own stable line', () => {
  const store = createAppStore('en'), a = design('A'), b = design('B');
  store.dispatch(cartAdded({ variant: 'cobalt', quantity: 1 }));
  store.dispatch(cartAdded({ variant: 'cobalt', quantity: 2, design: a }));
  store.dispatch(cartAdded({ variant: 'cobalt', quantity: 1, design: b }));
  store.dispatch(cartAdded({ variant: 'cobalt', quantity: 1, design: a }));
  const state = store.getState().productDemo;
  expect(state.cart.map(cartLineId)).toEqual(['cobalt', 'design-1', 'design-2']);
  expect(state.cart.map(line => line.quantity)).toEqual([1, 3, 1]);
  expect(state.cart[1].design).toEqual(a); expect(state.cart[1].design).not.toBe(a);
  expect(cartCount(state)).toBe(5); expect(cartTotal(state)).toBe(2400 + 4 * 2900);
});
it('isolates exact-line edits and ignores stale or deleted design owners', () => {
  const store = createAppStore('en'), a = design('A'), b = design('B');
  for (const snapshot of [a, b]) store.dispatch(cartAdded({ variant: 'cobalt', quantity: 1, design: snapshot }));
  const old = store.getState().productDemo.cart;
  store.dispatch(cartDesignChanged({ id: 'design-1', expected: designKey(a), design: design('C') }));
  expect(store.getState().productDemo.cart[1]).toEqual(old[1]);
  expect(old[0].design).toEqual(a);
  const current = store.getState().productDemo.cart;
  store.dispatch(cartDesignChanged({ id: 'design-1', expected: designKey(a), design: b }));
  expect(store.getState().productDemo.cart).toEqual(current);
  store.dispatch(cartLineRemoved('design-1'));
  store.dispatch(cartDesignChanged({ id: 'design-1', expected: designKey(design('C')), design: b }));
  expect(store.getState().productDemo.cart).toHaveLength(1);
});
it('enforces stock across every design, preserving exact tombstones and refusing partial restoration', () => {
  const store = createAppStore('en'), stock = variantById('coral').stock;
  store.dispatch(cartAdded({ variant: 'coral', quantity: 3, design: design('A') }));
  store.dispatch(cartAdded({ variant: 'coral', quantity: stock, design: design('B') }));
  expect(variantCartQuantity(store.getState().productDemo, 'coral')).toBe(stock);
  expect(lineCapacity(store.getState().productDemo, store.getState().productDemo.cart[0])).toBe(3);
  store.dispatch(cartQuantityChanged({ variant: 'coral', id: 'design-1', quantity: 999 }));
  expect(store.getState().productDemo.cart[0].quantity).toBe(3);
  store.dispatch(cartLineRemoved('design-1'));
  const revision = store.getState().productDemo.cartRemoved['design-1']!.revision;
  store.dispatch(cartAdded({ variant: 'coral', quantity: 2 }));
  store.dispatch(cartLineRestored({ variant: 'coral', id: 'design-1', revision }));
  expect(store.getState().productDemo.cart.some(line => line.id === 'design-1')).toBe(false);
  store.dispatch(cartLineRemoved('coral'));
  store.dispatch(cartLineRestored({ variant: 'coral', id: 'design-1', revision }));
  expect(store.getState().productDemo.cart.map(cartLineId)).toEqual(['design-1', 'design-2']);
  expect(store.getState().productDemo.cart[0].design).toEqual(design('A'));
});
it('preserves legacy plain live lines and does not let hydration overwrite them', () => {
  const store = createAppStore('en');
  store.dispatch(cartHydrated({ lines: [{ variant: 'chalk', quantity: 2 }], serial: 1 }));
  expect(store.getState().productDemo.cart).toEqual([{ variant: 'chalk', quantity: 2 }]);
  store.dispatch(cartHydrated({ lines: [], serial: 1 }));
  expect(store.getState().productDemo.cart).toHaveLength(1);
});
function show(path: string, store = createAppStore('en')) {
  render(<Provider store={store}><MemoryRouter initialEntries={[path]}><nav aria-label="Test routes"><Link to="/preview/cart">Fixture cart</Link><Link to="/products/loop/personalize?variant=cobalt">Fixture studio</Link></nav><ProductExperience /></MemoryRouter></Provider>);
  return { store, user: userEvent.setup() };
}
it('explains when the original quantity cannot be restored even with some stock left', () => {
  const store = createAppStore('en'), stock = variantById('coral').stock;
  store.dispatch(cartAdded({ variant: 'coral', quantity: 3, design: design('A') }));
  store.dispatch(cartLineRemoved('design-1'));
  store.dispatch(cartAdded({ variant: 'coral', quantity: stock - 1 }));
  show('/preview/cart', store);
  const removed = screen.getByRole('article', { name: /Your design 1/ });
  expect(within(removed).getByRole('button', { name: /^Restore/ })).toBeDisabled();
  expect(within(removed).getByText(productCopy.en.restoreLimited)).toHaveAttribute('role', 'status');
  expect(removed).not.toHaveTextContent(productCopy.en.allInCart);
});
it('shows the applied composition on the product sketch and model, then adds the exact snapshot to cart', async () => {
  const { user, store } = show('/products/loop/personalize?variant=cobalt');
  fireEvent.change(screen.getByRole('textbox', { name: 'Your text' }), { target: { value: 'ALPHA' } });
  await user.click(screen.getByRole('button', { name: 'Apply design' }));
  await user.click(screen.getByRole('link', { name: 'Back to product' }));
  expect(document.querySelector('.product-gallery [data-studio-composition]')).toHaveTextContent('ALPHA');
  await user.click(screen.getByRole('button', { name: '3D model' }));
  expect(await screen.findByTestId('cart-model')).toHaveTextContent('ALPHA');
  await user.click(screen.getByRole('button', { name: 'Add to cart' }));
  expect(store.getState().productDemo.cart[0].design).toEqual(design('ALPHA'));
  await user.click(screen.getByRole('link', { name: 'View cart' }));
  expect(document.querySelector('.product-cart-photo')).toHaveTextContent('ALPHA');
});
it('counts only active lines for the current colour and applied design, and adds one more after the first submission', async () => {
  const store = createAppStore('en'), alpha = design('ALPHA'), beta = design('BETA');
  store.dispatch(cartAdded({ variant: 'cobalt', quantity: 3 }));
  store.dispatch(cartAdded({ variant: 'coral', quantity: 2, design: alpha }));
  store.dispatch(cartAdded({ variant: 'cobalt', quantity: 2, design: beta }));
  const { user } = show('/products/loop/personalize?variant=cobalt', store);
  fireEvent.change(screen.getByRole('textbox', { name: 'Your text' }), { target: { value: 'ALPHA' } });
  await user.click(screen.getByRole('button', { name: 'Apply design' }));
  await user.click(screen.getByRole('link', { name: 'Back to product' }));
  expect(screen.queryByText(/^Already in cart:/)).toBeNull();
  const input = screen.getByRole('textbox', { name: 'Quantity' }), add = screen.getByRole('button', { name: 'Add to cart' });
  fireEvent.change(input, { target: { value: '4' } });
  await user.click(add);
  expect(input).toHaveValue('1');
  expect(screen.getByText('Already in cart: 4')).toBeVisible();
  await user.click(add);
  expect(screen.getByText('Already in cart: 5')).toBeVisible();

  // Independently edited lines can converge to the same design. Both count,
  // but neither an unrelated colour nor a removed line may contribute.
  const betaId = cartLineId(store.getState().productDemo.cart.find(line => designKey(line.design) === designKey(beta))!);
  act(() => { store.dispatch(cartDesignChanged({ id: betaId, expected: designKey(beta), design: alpha })); });
  expect(screen.getByText('Already in cart: 7')).toBeVisible();
  act(() => { store.dispatch(cartLineRemoved(betaId)); });
  expect(screen.getByText('Already in cart: 5')).toBeVisible();
  await user.click(screen.getByRole('button', { name: 'Coral' }));
  expect(screen.queryByText(/^Already in cart:/)).toBeNull();
});
it('cancels edits without changing cart or product draft, then saves only the exact line across cart/minicart/checkout', async () => {
  const store = createAppStore('en');
  for (const text of ['ALPHA', 'BETA']) store.dispatch(cartAdded({ variant: 'cobalt', quantity: 1, design: design(text) }));
  const { user } = show('/preview/cart', store);
  const initial = store.getState().productDemo.cart;
  const first = () => document.querySelector<HTMLElement>('[data-cart-line-id="design-1"]')!;
  await user.click(within(first()).getByRole('link', { name: /^Edit personalisation/ }));
  fireEvent.change(screen.getByRole('textbox', { name: 'Your text' }), { target: { value: 'DISCARD' } });
  await user.click(screen.getByRole('link', { name: 'Back to cart — discard edits' }));
  expect(store.getState().productDemo.cart).toEqual(initial);
  await user.click(within(first()).getByRole('link', { name: /^Edit personalisation/ }));
  expect(screen.getByRole('textbox', { name: 'Your text' })).toHaveValue('ALPHA');
  fireEvent.change(screen.getByRole('textbox', { name: 'Your text' }), { target: { value: 'GAMMA' } });
  await user.click(screen.getByRole('button', { name: 'Save this cart design' }));
  expect(store.getState().productDemo.cart[0].design).toEqual(design('GAMMA'));
  expect(store.getState().productDemo.cart[1]).toEqual(initial[1]);
  await user.hover(screen.getByRole('link', { name: 'Your cart' }));
  const peek = screen.getByRole('region', { name: 'Cart preview' });
  expect(peek).toHaveTextContent('GAMMA'); expect(peek).toHaveTextContent('BETA');
  await user.click(screen.getByRole('link', { name: 'Checkout' }));
  expect(document.querySelectorAll('.checkout-line')).toHaveLength(2);
  expect(document.querySelector('.checkout-lines')).toHaveTextContent('GAMMA');
  expect(document.querySelector('.checkout-lines')).toHaveTextContent('BETA');
});
it('fails closed when an edited line is removed while its isolated editor is open', async () => {
  const store = createAppStore('en'); store.dispatch(cartAdded({ variant: 'cobalt', quantity: 1, design: design('A') }));
  show('/products/loop/personalize?edit=design-1', store);
  await screen.findByRole('textbox', { name: 'Your text' });
  act(() => { store.dispatch(cartLineRemoved('design-1')); });
  expect(screen.getByRole('alert')).toHaveTextContent('no longer available');
  expect(screen.queryByRole('button', { name: 'Save this cart design' })).toBeNull();
});
it.each(['cancel', 'save'])('keeps the product draft and AI brief unchanged after cart %s', async outcome => {
  const store = createAppStore('en'); store.dispatch(cartAdded({ variant: 'cobalt', quantity: 1, design: design('CART') }));
  const { user } = show('/products/loop/personalize?variant=cobalt', store);
  fireEvent.change(screen.getByRole('textbox', { name: 'Your text' }), { target: { value: 'PRODUCT' } });
  await user.click(screen.getByRole('button', { name: 'AI' }));
  fireEvent.change(screen.getByRole('textbox', { name: 'Describe your idea' }), { target: { value: 'Original product idea' } });
  await user.click(screen.getByRole('link', { name: 'Fixture cart' }));
  await user.click(within(document.querySelector<HTMLElement>('.product-cart-line')!).getByRole('link', { name: /^Edit personalisation/ }));
  expect(screen.queryByRole('button', { name: 'AI' })).toBeNull();
  expect(screen.queryByRole('textbox', { name: 'Describe your idea' })).toBeNull();
  fireEvent.change(screen.getByRole('textbox', { name: 'Your text' }), { target: { value: 'EDIT' } });
  await user.click(outcome === 'save' ? screen.getByRole('button', { name: 'Save this cart design' }) : screen.getByRole('link', { name: 'Back to cart — discard edits' }));
  expect(store.getState().productDemo.cart[0].design).toEqual(design(outcome === 'save' ? 'EDIT' : 'CART'));
  await user.click(screen.getByRole('link', { name: 'Fixture studio' }));
  expect(screen.getByRole('textbox', { name: 'Your text' })).toHaveValue('PRODUCT');
  await user.click(screen.getByRole('button', { name: 'AI' }));
  expect(screen.getByRole('textbox', { name: 'Describe your idea' })).toHaveValue('Original product idea');
});
it('distinguishes same-colour design rows, actions, quantity controls and thumbnails by stable labels', () => {
  const store = createAppStore('en');
  store.dispatch(cartAdded({ variant: 'cobalt', quantity: 1 }));
  store.dispatch(cartAdded({ variant: 'cobalt', quantity: 1, design: design('ALPHA') }));
  store.dispatch(cartAdded({ variant: 'cobalt', quantity: 1, design: design('BETA') }));
  show('/preview/cart', store);
  const names = screen.getAllByRole('article').map(row => row.getAttribute('aria-label'));
  expect(new Set(names).size).toBe(3);
  for (const label of ['Your design 1 · ALPHA', 'Your design 2 · BETA']) {
    const row = screen.getByRole('article', { name: 'Loop · Cobalt blue · ' + label });
    expect(within(row).getByRole('link', { name: 'Edit personalisation · Cobalt blue · ' + label })).toBeVisible();
    expect(within(row).getByRole('button', { name: 'Remove · Cobalt blue · ' + label })).toBeVisible();
    expect(within(row).getByRole('textbox', { name: 'Quantity · Cobalt blue · ' + label })).toHaveValue('1');
    expect(within(row).getByRole('img', { name: label })).toBeVisible();
    expect(row.querySelector('.product-cart-design-meta')).toHaveTextContent(label);
  }
});

it('prices restored personalised units independently of the plain sale variant across cart, mini-cart and checkout', async () => {
  const store = createAppStore('en');
  store.dispatch(cartHydrated({ lines: [
    { variant: 'chalk', quantity: 1 },
    { id: 'design-1', variant: 'chalk', quantity: 2, design: design('ETTA') },
  ], serial: 2 }));
  const { user } = show('/preview/cart', store);
  const plain = screen.getByRole('article', { name: 'Loop · Chalk' });
  const configured = screen.getByRole('article', { name: /Loop · Chalk · Your design 1/ });
  const summary = screen.getByRole('complementary', { name: 'Summary' });
  expect(plain.querySelector('.product-cart-unit')).toHaveTextContent('€19.90');
  expect(plain.querySelector('del')).toHaveTextContent('€24.00');
  expect(plain).not.toHaveTextContent('€5.00');
  expect(configured.querySelector('.product-cart-unit')).toHaveTextContent('€24.90');
  expect(configured.querySelector('.product-cart-unit del')).toBeNull();
  expect(configured.querySelector('.product-cart-design-meta')).toHaveTextContent('€19.90');
  expect(configured.querySelector('.product-cart-design-meta')).toHaveTextContent('€5.00');
  expect(configured.querySelector('.product-cart-line-total')).toHaveTextContent('€49.80');
  expect(summary).toHaveTextContent('€69.70');

  fireEvent.change(within(configured).getByRole('textbox'), { target: { value: '3' } });
  expect(configured.querySelector('.product-cart-line-total')).toHaveTextContent('€74.70');
  expect(summary).toHaveTextContent('€94.60');
  await user.click(within(configured).getByRole('button', { name: /^Remove ·/ }));
  expect(configured.querySelector('.product-cart-line-total')).toHaveTextContent('€0.00');
  expect(summary).toHaveTextContent('€19.90');
  await user.click(within(configured).getByRole('button', { name: /^Restore ·/ }));
  expect(configured.querySelector('.product-cart-line-total')).toHaveTextContent('€74.70');
  expect(summary).toHaveTextContent('€94.60');

  await user.hover(screen.getByRole('link', { name: 'Your cart' }));
  const peek = screen.getByRole('region', { name: 'Cart preview' });
  const peekLine = peek.querySelector('[data-cart-line-id="design-1"]')!;
  expect(peekLine).toHaveTextContent('3 × €24.90 = €74.70');
  expect(peekLine).toHaveTextContent('€5.00');
  expect(peek.querySelector('.product-peek-total')).toHaveTextContent('€94.60');
  await user.click(screen.getByRole('link', { name: 'Checkout' }));
  const review = screen.getByRole('article', { name: /Loop · Chalk · Your design 1/ });
  expect(within(review).getByText('Price per item').nextElementSibling).toHaveTextContent('€24.90');
  expect(within(review).getByText('Line subtotal').nextElementSibling).toHaveTextContent('€74.70');
  expect(review.querySelector('.product-cart-design-meta')).toHaveTextContent('€5.00');
  expect(document.querySelector('.checkout-subtotal')).toHaveTextContent('€94.60');
});
