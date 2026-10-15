import { describe, expect, it, vi } from 'vitest';
import { createAppStore, authenticationStarted, authenticationFinished } from '../store';
import { swatchBackground, variants } from './catalog';
import type { VariantId } from './catalog';
import { cartAdded, cartCount, cartLineRemoved, cartQuantityChanged, cartTotal, favoriteRemoved, favoriteToggled, productVariantSelected, purchaseQuantityChanged, productDescriptionChanged } from './demoStore';

const defaultPurchase = { cobalt: 1, coral: 1, chalk: 1, 'cobalt-chalk': 1 };

describe('public memory-only product state', () => {
  it('keeps purchase drafts separate from cart lines and permanently narrows them when available capacity falls', () => {
    const store = createAppStore('en', vi.fn());
    store.dispatch(purchaseQuantityChanged({ variant: 'coral', quantity: 6 }));
    store.dispatch(purchaseQuantityChanged({ variant: 'cobalt-chalk', quantity: 3 }));
    expect(store.getState().productDemo.cart).toEqual([]);
    store.dispatch(cartAdded({ variant: 'cobalt-chalk', quantity: 3 }));
    expect(store.getState().productDemo.purchaseQuantities['cobalt-chalk']).toBe(3);
    store.dispatch(cartAdded({ variant: 'coral', quantity: 3 }));
    expect(store.getState().productDemo.purchaseQuantities.coral).toBe(5);
    store.dispatch(cartQuantityChanged({ variant: 'coral', quantity: 7 }));
    store.dispatch(cartQuantityChanged({ variant: 'coral', quantity: 2 }));
    expect(store.getState().productDemo.purchaseQuantities.coral).toBe(1);
    store.dispatch(cartQuantityChanged({ variant: 'coral', quantity: 8 }));
    expect(store.getState().productDemo.purchaseQuantities.coral).toBe(0);
    store.dispatch(cartLineRemoved('coral'));
    expect(store.getState().productDemo.purchaseQuantities.coral).toBe(1);
    expect(store.getState().productDemo.purchaseQuantities['cobalt-chalk']).toBe(3);
    expect(store.getState().productDemo.addSerial).toBe(2);
    expect(createAppStore('en', vi.fn()).getState().productDemo.purchaseQuantities).toEqual(defaultPurchase);
  });
  it.each([0, -1, 1.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1])('does not retain invalid purchase quantity %s', quantity => {
    const store = createAppStore('en', vi.fn()), before = store.getState().productDemo;
    store.dispatch(purchaseQuantityChanged({ variant: 'coral', quantity }));
    store.dispatch(purchaseQuantityChanged({ variant: 'constructor' as VariantId, quantity: 3 }));
    expect(store.getState().productDemo).toBe(before);
  });
  it('keeps disclosure height state public, boolean-only and document-memory-only', () => {
    const persist = vi.fn(), store = createAppStore('en', persist), auth = store.getState().authentication;
    store.dispatch(productDescriptionChanged(true)); store.dispatch(productDescriptionChanged('no' as unknown as boolean));
    expect(store.getState().productDemo.descriptionOpen).toBe(true);
    expect(store.getState().authentication).toBe(auth); expect(store.getState().productDemo.addSerial).toBe(0);
    expect(persist).not.toHaveBeenCalled(); expect(localStorage.length).toBe(0);
    expect(createAppStore('en', persist).getState().productDemo.descriptionOpen).toBe(false);
  });
  it('remembers only validated public variants without coupling account, cart, favorite or persistence', () => {
    const persist = vi.fn(), store = createAppStore('en', persist), before = store.getState();
    store.dispatch(productVariantSelected('cobalt-chalk'));
    expect(store.getState().productDemo.selectedVariant).toBe('cobalt-chalk');
    for (const invalid of ['unknown', 'constructor', '__proto__', '', 'Cobalt']) store.dispatch(productVariantSelected(invalid as VariantId));
    expect(store.getState().productDemo.selectedVariant).toBe('cobalt-chalk');
    expect(store.getState().productDemo.cart).toEqual(before.productDemo.cart);
    expect(store.getState().productDemo.favorite).toEqual(before.productDemo.favorite);
    expect(store.getState().authentication).toEqual(before.authentication);
    expect(persist).not.toHaveBeenCalled(); expect(localStorage.length).toBe(0);
    expect(createAppStore('en', persist).getState().productDemo.selectedVariant).toBe('cobalt');
  });
  it('stores one representative favorite variant, clears it on removal and captures the current color on re-add', () => {
    const persist = vi.fn(), store = createAppStore('en', persist);
    store.dispatch(favoriteToggled('coral'));
    expect(store.getState().productDemo.favorite).toBe('coral');
    store.dispatch(cartAdded({ variant: 'chalk', quantity: 1 }));
    expect(store.getState().productDemo.favorite).toBe('coral');
    store.dispatch(favoriteToggled('cobalt-chalk'));
    expect(store.getState().productDemo.favorite).toBeNull();
    store.dispatch(favoriteToggled('cobalt-chalk'));
    expect(store.getState().productDemo.favorite).toBe('cobalt-chalk');
    store.dispatch(favoriteRemoved()); store.dispatch(favoriteRemoved());
    expect(store.getState().productDemo.favorite).toBeNull();
    expect(store.getState().productDemo.cart).toEqual([{ variant: 'chalk', quantity: 1 }]);
    expect(persist).not.toHaveBeenCalled(); expect(localStorage.length).toBe(0);
    expect(createAppStore('en', persist).getState().productDemo.favorite).toBeNull();
  });
  it('supports one/two local sample tones and renders an exact diagonal half split with explicit check contrast', () => {
    expect(swatchBackground(['#244bcc'])).toBe('#244bcc');
    expect(swatchBackground(['#244bcc', '#f0eee8'])).toBe('linear-gradient(135deg, #244bcc 0 50%, #f0eee8 50% 100%)');
    expect(variants.every(variant => variant.colors.length === 1 || variant.colors.length === 2)).toBe(true);
    expect(variants.find(variant => variant.id === 'chalk')?.checkTone).toBe('dark');
    const combination = variants.find(variant => variant.id === 'cobalt-chalk')!;
    expect(combination.colors).toEqual(['#244bcc', '#f0eee8']);
    expect(combination.stock).toBe(6); expect(combination.cents).toBe(2800);
    expect(Object.values(combination.name)).toHaveLength(7);
    expect(Object.values(combination.name).every(name => name.trim().length > 0)).toBe(true);
  });
  it.each(variants)('caps cumulative $id cart units at its own demo stock, not nine', variant => {
    const store = createAppStore('en', vi.fn());
    store.dispatch(cartAdded({ variant: variant.id, quantity: variant.stock - 1 }));
    store.dispatch(cartAdded({ variant: variant.id, quantity: 2 }));
    expect(store.getState().productDemo.cart).toEqual([{ variant: variant.id, quantity: variant.stock }]);
    expect(cartTotal(store.getState().productDemo)).toBe(variant.stock * variant.cents);
  });
  it.each([0, -1, 1.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1])('rejects invalid quantity %s without changing cart', quantity => {
    const store = createAppStore('en', vi.fn());
    store.dispatch(cartAdded({ variant: 'cobalt', quantity: 2 }));
    const before = store.getState().productDemo;
    store.dispatch(cartAdded({ variant: 'cobalt', quantity }));
    store.dispatch(cartQuantityChanged({ variant: 'cobalt', quantity }));
    expect(store.getState().productDemo).toEqual(before);
  });
  it('edits/removes one colour without changing another or product-level favorite', () => {
    const store = createAppStore('de', vi.fn());
    store.dispatch(favoriteToggled('cobalt'));
    store.dispatch(cartAdded({ variant: 'cobalt', quantity: 12 }));
    store.dispatch(cartAdded({ variant: 'coral', quantity: 3 }));
    store.dispatch(cartQuantityChanged({ variant: 'cobalt', quantity: 20 }));
    expect(cartCount(store.getState().productDemo)).toBe(23);
    expect(cartTotal(store.getState().productDemo)).toBe(55_800);
    store.dispatch(cartLineRemoved('cobalt'));
    expect(store.getState().productDemo).toEqual({ favorite: 'cobalt', cart: [{ variant: 'coral', quantity: 3 }], locale: null, addSerial: 2, selectedVariant: 'cobalt', purchaseQuantities: defaultPurchase, descriptionOpen: false, mediaMode: 'photo' });
  });
  it('has no account/persistence coupling and remount starts empty', () => {
    const persist = vi.fn(), store = createAppStore('en', persist);
    const authBefore = store.getState().authentication;
    store.dispatch(cartAdded({ variant: 'cobalt', quantity: 12 }));
    store.dispatch(favoriteToggled('cobalt'));
    expect(store.getState().authentication).toEqual(authBefore);
    expect(persist).not.toHaveBeenCalled();
    expect(localStorage.length).toBe(0);
    const owner = { document: 'one', epoch: 1, request: 1, revision: 'test' };
    store.dispatch(authenticationStarted({ owner, phase: 'CHECKING', notice: 'NONE' }));
    store.dispatch(authenticationFinished({ owner, result: { phase: 'AUTHENTICATED', notice: 'NONE', user: { id: '11111111-1111-4111-8111-111111111111', status: 'ACTIVE' } } }));
    store.dispatch(authenticationStarted({ owner: { ...owner, epoch: 2 }, phase: 'TRANSITION', notice: 'NONE' }));
    expect(store.getState().productDemo).toEqual({ favorite: 'cobalt', cart: [{ variant: 'cobalt', quantity: 12 }], locale: null, addSerial: 1, selectedVariant: 'cobalt', purchaseQuantities: defaultPurchase, descriptionOpen: false, mediaMode: 'photo' });
    expect(createAppStore('en', persist).getState().productDemo).toEqual({ favorite: null, cart: [], locale: null, addSerial: 0, selectedVariant: 'cobalt', purchaseQuantities: defaultPurchase, descriptionOpen: false, mediaMode: 'photo' });
  });
});
