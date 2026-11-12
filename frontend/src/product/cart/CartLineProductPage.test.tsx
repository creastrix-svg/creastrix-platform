import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { Link, MemoryRouter, useLocation, useNavigate } from 'react-router';
import { beforeEach, expect, it, vi } from 'vitest';
import { createAppStore } from '../../store';
import ProductExperience from '../ProductExperience';
import { cartAdded, cartLineRemoved, cartTotal } from '../demoStore';
import { defaultLettering } from '../studio/design/personalization';
import { saveDesign } from '../studio/session/designSnapshot';
import * as studioProducts from '../studio/studioProducts';
import * as pricing from '../loopPricing';

const cartPath = '/preview/cart';
const catalogPath = '/products/loop?variant=cobalt';
function lettering(text: string) {
  return saveDesign({ selectedId: null, objects: [{ id: 'studio-object-1', kind: 'text', value: { ...defaultLettering('cobalt'), text } }] })!;
}
function TestNavigation() {
  const location = useLocation(), navigate = useNavigate();
  return <nav aria-label="Test shortcuts">
    <Link to={cartPath}>Fixture cart</Link><Link to={catalogPath}>Fixture catalog product</Link>
    <button onClick={() => navigate(-1)}>Fixture browser back</button>
    <button onClick={() => navigate(1)}>Fixture browser forward</button>
    <span data-testid="fixture-url">{location.pathname + location.search}</span>
  </nav>;
}
function show(path = cartPath) {
  const store = createAppStore('en');
  store.dispatch(cartAdded({ variant: 'cobalt', quantity: 1 }));
  store.dispatch(cartAdded({ variant: 'cobalt', quantity: 2, design: lettering('ALPHA') }));
  store.dispatch(cartAdded({ variant: 'cobalt', quantity: 1, design: lettering('BETA') }));
  render(<Provider store={store}><MemoryRouter initialEntries={[path]}>
    <TestNavigation />
    <ProductExperience />
  </MemoryRouter></Provider>);
  return { store, user:userEvent.setup() };
}
const summary = () => screen.getByRole('complementary', { name: 'Summary' });
const exactRow = (text: string) => screen.getByRole('article', {name: new RegExp('Loop · Cobalt blue · Your design .*' + text)});
const plainRow = () => screen.getByRole('article', {name: 'Loop · Cobalt blue'});
const sketch = () => document.querySelector('.product-gallery [data-studio-composition]');
const unitPrice = () => document.querySelector('.product-information .product-price');

it('keeps a free design visibly personalised in the cart', () => {
  vi.spyOn(pricing, 'loopPrice').mockReturnValue({baseCents:2400, feeCents:0, unitCents:2400});
  show();
  expect(exactRow('ALPHA')).toHaveTextContent('€24.00');
  expect(exactRow('ALPHA')).not.toHaveTextContent('Without personalisation');
  expect(plainRow()).toHaveTextContent('Without personalisation');
});

it.each(['list', 'list then form', 'stars'])('returns from %s to the exact cart design, including its surcharge', async path => {
  const {user} = show();
  await user.click(within(exactRow('ALPHA')).getByRole('link', {name:'Loop'}));
  if (path === 'stars') {
    await user.click(await screen.findByRole('button', {name:'Rating: 4 out of 5'}));
    expect(screen.getByRole('radio', {name:'Rating: 4 out of 5'})).toBeChecked();
  } else {
    await user.click(await screen.findByRole('link', {name:'No reviews yet'}));
    if (path === 'list then form') await user.click(screen.getByRole('link', {name:'Write a review'}));
  }
  await user.click(screen.getByRole('link', {name:'Back to product'}));
  await waitFor(() => expect(sketch()).toHaveTextContent('ALPHA'));
  expect(unitPrice()).toHaveTextContent('€29.00');
  expect(screen.getByRole('link', {name:'Edit personalisation'})).toHaveAttribute('href','/products/loop/personalize?variant=cobalt&edit=design-1');
});

it('returns to an unavailable cart item when erased while reading reviews, never a different same-colour design', async () => {
  const {user, store} = show();
  await user.click(within(exactRow('ALPHA')).getByRole('link', {name:'Loop'}));
  await user.click(await screen.findByRole('link', {name:'No reviews yet'}));
  act(() => { store.dispatch(cartLineRemoved('design-1')); });
  await user.click(screen.getByRole('link', {name:'Back to product'}));
  expect(await screen.findByRole('alert')).toHaveTextContent('no longer available');
  expect(unitPrice()).toBeNull();
});

beforeEach(() => {
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ measureText: (value: string) => ({ width: value.length * 12 }) } as unknown as CanvasRenderingContext2D);
});

it('opens the saved design from its cart photo and clears its fee for exactly those units', async () => {
  const {store, user} = show();
  const other = store.getState().productDemo.cart.find(line => line.id === 'design-2');
  expect(summary()).toHaveTextContent('€111.00');
  await user.click(within(exactRow('ALPHA')).getByRole('link', {name:/Loop · Cobalt blue · Your design 1 · ALPHA/}));
  await waitFor(() => expect(sketch()).toHaveTextContent('ALPHA'));
  expect(unitPrice()).toHaveTextContent('€29.00');
  const gallery = document.querySelector('.product-gallery');
  const editLink = screen.getByRole('link', {name:'Edit personalisation'});
  const scroll = vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
  await user.click(screen.getByRole('button', {name: 'Remove personalisation'}));
  expect(screen.getByRole('heading', {level:1, name:'Loop'})).toBeVisible();
  expect(screen.getByTestId('fixture-url').textContent).toBe('/products/loop?variant=cobalt&cartLine=cobalt');
  expect(unitPrice()).toHaveTextContent('€24.00');
  expect(sketch()).toBeNull();
  expect(screen.queryByRole('button',{name:'Remove personalisation'})).toBeNull();
  expect(document.querySelector('.product-subtotal')).toHaveTextContent('3 × €24.00€72.00');
  expect(document.querySelector('.product-gallery')).toBe(gallery);
  expect(screen.getByRole('link', {name:'Personalise a new item'})).toBe(editLink);
  expect(editLink).toHaveFocus();
  expect(scroll).not.toHaveBeenCalled();
  await user.click(within(screen.getByRole('main')).getByRole('link',{name:'View cart'}));
  expect(await screen.findByRole('complementary', {name:'Summary'})).toHaveTextContent('€101.00');
  expect(plainRow().querySelector('.product-cart-line-total')).toHaveTextContent('€72.00');
  expect(exactRow('BETA').querySelector('.product-cart-line-total')).toHaveTextContent('€29.00');
  expect(store.getState().productDemo.cart).toEqual([{ variant:'cobalt', quantity:3 }, other]);
  expect(cartTotal(store.getState().productDemo)).toBe(10100);
  await user.hover(screen.getByRole('link', {name:'Your cart'}));
  const peek = screen.getByRole('region', {name:'Cart preview'});
  expect(peek).not.toHaveTextContent('ALPHA');
  expect(peek).toHaveTextContent('BETA');
  expect(peek).toHaveTextContent('€101.00');
  await user.click(screen.getByRole('link', {name:'Checkout'}));
  const checkout = within(screen.getByRole('main'));
  expect(checkout.getByRole('article', {name:'Loop · Cobalt blue'})).toHaveTextContent('€72.00');
  expect(checkout.getByRole('article', {name:/BETA/})).toHaveTextContent('€29.00');
  expect(checkout.getByRole('complementary')).toHaveTextContent('€101.00');
});

it('uses the clicked row instead of a catalog preview, including a plain row after clearing another design', async () => {
  const {user} = show('/products/loop/personalize?variant=cobalt');
  fireEvent.change(screen.getByRole('textbox', {name:'Your text'}), {target:{value:'CATALOG'}});
  await user.click(screen.getByRole('button', {name:'Apply design'}));
  await user.click(screen.getByRole('link', {name:'Fixture cart'}));
  await user.click(within(exactRow('BETA')).getByRole('link', {name:'Loop'}));
  await waitFor(() => expect(sketch()).toHaveTextContent('BETA'));
  expect(sketch()).not.toHaveTextContent('CATALOG');
  await user.click(screen.getByRole('button', {name:'Remove personalisation'}));
  await waitFor(() => expect(unitPrice()).toHaveTextContent('€24.00'));
  expect(sketch()).toBeNull();
  await user.click(screen.getByRole('button', {name:'Fixture browser back'}));
  expect(screen.getByTestId('fixture-url').textContent).toBe(cartPath);
  await user.click(screen.getByRole('button', {name:'Fixture browser forward'}));
  expect(screen.getByTestId('fixture-url').textContent).toBe('/products/loop?variant=cobalt&cartLine=cobalt');
  expect(unitPrice()).toHaveTextContent('€24.00');
  expect(sketch()).toBeNull();
  await user.click(within(screen.getByRole('main')).getByRole('link', {name:'View cart'}));
  await user.click(within(plainRow()).getByRole('link', {name:'Loop'}));
  expect(sketch()).toBeNull();
  expect(unitPrice()).toHaveTextContent('€24.00');
  await user.click(screen.getByRole('link', {name:'Fixture catalog product'}));
  await waitFor(() => expect(sketch()).toHaveTextContent('CATALOG'));
  expect(unitPrice()).toHaveTextContent('€29.00');
});

it('stays on the matching plain configuration when there was no active plain row to merge into', async () => {
  const {store, user} = show('/products/loop?variant=cobalt&cartLine=design-1');
  act(() => { store.dispatch(cartLineRemoved('cobalt')); });
  await user.click(await screen.findByRole('button', {name:'Remove personalisation'}));
  expect(screen.getByTestId('fixture-url').textContent).toBe('/products/loop?variant=cobalt&cartLine=cobalt');
  expect(unitPrice()).toHaveTextContent('€24.00');
  expect(sketch()).toBeNull();
  expect(document.querySelector('.product-subtotal')).toHaveTextContent('2 × €24.00€48.00');
  await user.click(within(screen.getByRole('main')).getByRole('link', {name:'View cart'}));
  expect(summary()).toHaveTextContent('€77.00');
  expect(plainRow().querySelector('.product-cart-line-total')).toHaveTextContent('€48.00');
});

it('focuses the purchase panel cart link after clearing a saved design when new personalisation is unavailable', async () => {
  const {user} = show();
  vi.spyOn(studioProducts, 'canPersonalize').mockReturnValue(false);
  await user.click(within(exactRow('ALPHA')).getByRole('link', {name:'Loop'}));
  const remove = await screen.findByRole('button', {name:'Remove personalisation'});
  expect(screen.queryByRole('link', {name:'Edit personalisation'})).toBeNull();
  await user.click(remove);
  expect(screen.getByTestId('fixture-url').textContent).toBe('/products/loop?variant=cobalt&cartLine=cobalt');
  expect(unitPrice()).toHaveTextContent('€24.00');
  expect(screen.queryByRole('link', {name:'Personalise a new item'})).toBeNull();
  const viewCart = within(screen.getByRole('main')).getByRole('link', {name:'View cart'});
  expect(viewCart).toHaveFocus();
  await user.keyboard('{Enter}');
  expect(summary()).toHaveTextContent('€101.00');
});

it('discards unsaved editor changes when cancelling from a line-bound product card', async () => {
  const {store, user} = show();
  const original = store.getState().productDemo.cart;
  await user.click(within(exactRow('ALPHA')).getByRole('link', {name:'Loop'}));
  await user.click(await screen.findByRole('link', {name:'Edit personalisation'}));
  await user.click(await screen.findByRole('button', {name:'Text 1: ALPHA'}));
  const input = await screen.findByRole('textbox', {name:'Your text'});
  expect(input).toHaveValue('ALPHA');
  fireEvent.change(input, {target: {value:'UNSAVED'}});
  await user.click(screen.getByRole('link', {name:'Back to cart — discard edits'}));
  expect(summary()).toHaveTextContent('€111.00');
  expect(store.getState().productDemo.cart).toEqual(original);
});

it('previews a plain unit after the last layer is deleted but only reprices the cart on explicit confirmation', async () => {
  const {store, user} = show();
  await user.click(within(exactRow('ALPHA')).getByRole('link', {name:/^Edit personalisation/}));
  await user.click(await screen.findByRole('button', {name:'Text 1: ALPHA'}));
  await user.click(screen.getByRole('button', {name:'Delete selected object'}));
  expect(cartTotal(store.getState().productDemo)).toBe(11100);
  const clear = screen.getByRole('button', {name:'Remove personalisation'});
  expect(clear).toBeEnabled();
  await user.click(clear);
  expect(await screen.findByRole('complementary', {name:'Summary'})).toHaveTextContent('€101.00');
  expect(plainRow().querySelector('.product-cart-line-total')).toHaveTextContent('€72.00');
  expect(exactRow('BETA')).toBeVisible();
});

it.each([
  ['cobalt', 'design-404'],
  ['coral', 'design-1'],
])('does not silently open a generic product for an unavailable or mismatched %s/%s cart reference', async (variant, line) => {
  const {store} = show('/products/loop?variant=' + variant + '&cartLine=' + line);
  expect(await screen.findByRole('alert')).toHaveTextContent('no longer available');
  expect(unitPrice()).toBeNull();
  expect(store.getState().productDemo.cart).toHaveLength(3);
});
