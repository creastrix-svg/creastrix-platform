import { fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { Provider } from 'react-redux';
import { MemoryRouter, Routes, Route } from 'react-router';
import { createAppStore } from '../../store';
import { cartAdded, cartHydrated, cartQuantityChanged, type CartLine } from '../demoStore';
import * as loop from '../fixtures/loop';
import * as edition from '../fixtures/editionCatalog';
import { initialStudioComposition } from '../studio/design/studioComposition';
import { defaultLettering } from '../studio/design/personalization';
import { createStudioFontLibrary } from '../studio/design/studioFonts';
import { restoreStudio, serializeStudio } from '../studio/session/studioStorage';
import { saveDesign } from '../studio/session/designSnapshot';
import CartView from './CartView';
import CheckoutView from '../checkout/CheckoutView';
import { checkoutCopy } from '../checkout/checkoutCopy';
import { stockShortageNote } from './studioCartCopy';

vi.mock('../catalog/localCatalog', async original => ({ ...await original(), connectedMode: true }));
afterEach(() => vi.restoreAllMocks());

it.each([0, 2])('preserves the mixed snapshot after available stock drops to %i; checkout requires buyer reconciliation', async stock => {
  const doc = { selectedId: 'studio-object-8', objects: [{ id: 'studio-object-8', kind: 'text' as const,
    value: { ...defaultLettering('chalk'), text: 'Keep me' } }] };
  const composition = initialStudioComposition(), design = saveDesign(doc)!;
  const snapshot = { ...composition, applied: { chalk: doc } };
  const original: CartLine[] = [{variant: 'chalk', quantity: 2}, {variant: 'chalk', id: 'design-1', quantity: 3, design},
    {variant: 'tide-cobalt', quantity: 1}];
  const raw = serializeStudio(snapshot, {drafts: {}, applied: {}, customFonts: []}, {cart: original, cartSerial: 2});
  const lookup = loop.variantById;
  vi.spyOn(loop, 'variantById').mockImplementation(id => id === 'chalk' ? {...lookup(id), stock} : lookup(id));
  // This storage-focused test opts into variable stock with no HTTP snapshot.
  // Give the cup a presentation record independently of its shelf placement.
  vi.spyOn(edition, 'editionItem').mockImplementation((id, locale) => edition.editionItems(locale).find(row => row.id === id));
  const fonts = createStudioFontLibrary();
  vi.spyOn(fonts, 'loadBuiltin').mockImplementation(async id => ({id, label: id, family: id}));
  const restored = await restoreStudio(raw, fonts);
  expect(restored.lines).toEqual(original);
  expect(restored.composition.applied.chalk).toEqual(doc);
  const store = createAppStore('en', vi.fn());
  store.dispatch(cartHydrated(restored));
  expect(store.getState().productDemo.purchaseQuantities.chalk).toBe(0);
  store.dispatch(cartAdded({variant: 'chalk', quantity: 1}));
  store.dispatch(cartQuantityChanged({variant: 'chalk', quantity: 999}));
  expect(store.getState().productDemo.cart).toEqual(original);
  render(<Provider store={store}><MemoryRouter initialEntries={['/preview/checkout']}><Routes>
    <Route path="/preview/checkout" element={<CheckoutView />} /><Route path="/preview/cart" element={<CartView />} />
  </Routes></MemoryRouter></Provider>);
  expect(screen.queryByRole('form')).toBeNull();
  expect(screen.getByText(stockShortageNote.en)).toBeVisible();
  fireEvent.click(screen.getByRole('link', {name: checkoutCopy.en.backToCart}));
  expect(screen.getByRole('button', {name: checkoutCopy.en.proceed})).toBeDisabled();
  const rows = screen.getAllByRole('article');
  expect(within(rows[2]).queryByText(stockShortageNote.en)).toBeNull();
  fireEvent.click(within(rows[0]).getByRole('button', {name: /decrease/i}));
  expect(store.getState().productDemo.cart[0].quantity).toBe(1);
  fireEvent.click(within(rows[0]).getByRole('button', {name: /remove/i}));
  if (stock) fireEvent.click(within(rows[1]).getByRole('button', {name: /decrease/i}));
  else fireEvent.click(within(rows[1]).getByRole('button', {name: /remove/i}));
  expect(screen.getByRole('link', {name: checkoutCopy.en.proceed})).toBeVisible();
  expect(store.getState().productDemo.cart.at(-1)).toEqual(original[2]);
  if (stock) expect(store.getState().productDemo.cart[0]).toEqual({...original[1], quantity: 2});
  fonts.dispose();
});

it('still rejects quantities beyond the connected catalog storage envelope', async () => {
  const raw = serializeStudio(initialStudioComposition(), {drafts: {}, applied: {}, customFonts: []},
    {cart: [{variant: 'tide-cobalt', quantity: 1_000_001}], cartSerial: 1});
  const fonts = createStudioFontLibrary();
  await expect(restoreStudio(raw, fonts)).rejects.toThrow('STUDIO_STORAGE_STOCK');
  fonts.dispose();
});
