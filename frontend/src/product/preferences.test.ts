import { afterEach, describe, expect, it, vi } from 'vitest';
import { createAppStore } from '../store';
import { cartAdded, favoriteToggled, productMediaModeSelected } from './demoStore';
import { defaultProductPreferences, PRODUCT_PREFERENCES_KEY, readProductPreferences, rememberProductPreferences, serializeProductPreferences } from './preferences';
import type { MediaMode } from './preferences';

const preference = { version: 1, selectedVariant: 'coral', purchaseQuantities: { cobalt: 2, coral: 3, chalk: 4, 'cobalt-chalk': 5 }, mediaMode: 'model', descriptionOpen: true };
afterEach(() => { vi.restoreAllMocks(); });

describe('versioned public Loop preference allowlist', () => {
  it('reads only its own key and leaves auth/language/account records untouched', () => {
    localStorage.setItem('creastrix.auth.boundary', 'private sentinel'); localStorage.setItem('creastrix.ui.language', 'de');
    localStorage.setItem(PRODUCT_PREFERENCES_KEY, JSON.stringify(preference));
    const read = vi.spyOn(Storage.prototype, 'getItem');
    expect(readProductPreferences()).toEqual({ selectedVariant: 'coral', purchaseQuantities: preference.purchaseQuantities, mediaMode: 'model', descriptionOpen: true });
    expect(read).toHaveBeenCalledExactlyOnceWith(PRODUCT_PREFERENCES_KEY);
    read.mockRestore();
    expect(localStorage.getItem('creastrix.auth.boundary')).toBe('private sentinel'); expect(localStorage.getItem('creastrix.ui.language')).toBe('de');
  });
  it.each([null, '', '{', 'null', '[]', '42', JSON.stringify({ ...preference, version: 2 }), JSON.stringify({ ...preference, accountId: 'never trust' }), JSON.stringify({ ...preference, selectedVariant: '__proto__' }), JSON.stringify({ ...preference, mediaMode: 'constructor' }), JSON.stringify({ ...preference, descriptionOpen: 'false' }), JSON.stringify({ ...preference, purchaseQuantities: { ...preference.purchaseQuantities, unknown: 1 } }), ' '.repeat(1025)])('falls back safely for absent, malformed, unknown or oversized data: %s', raw => {
    if (raw !== null) localStorage.setItem(PRODUCT_PREFERENCES_KEY, raw);
    expect(readProductPreferences()).toEqual(defaultProductPreferences());
  });
  it.each([0, -1, 1.5, null, '3', Number.MAX_SAFE_INTEGER + 1])('normalizes unsafe quantity %s without inventing a cart', quantity => {
    localStorage.setItem(PRODUCT_PREFERENCES_KEY, JSON.stringify({ ...preference, purchaseQuantities: { ...preference.purchaseQuantities, cobalt: quantity } }));
    expect(readProductPreferences().purchaseQuantities.cobalt).toBe(1);
    const state = createAppStore('en', vi.fn()).getState();
    expect(state.productDemo.cart).toEqual([]); expect(state.productDemo.favorite).toBeNull(); expect(state.authentication.user).toBeNull();
  });
  it('clamps safe integers to each own stock and maps cart-exhausted zero to fresh quantity one', () => {
    localStorage.setItem(PRODUCT_PREFERENCES_KEY, JSON.stringify({ ...preference, purchaseQuantities: { cobalt: 900, coral: 900, chalk: 900, 'cobalt-chalk': 900 } }));
    expect(readProductPreferences().purchaseQuantities).toEqual({ cobalt: 24, coral: 8, chalk: 15, 'cobalt-chalk': 6 });
    const store = createAppStore('en', vi.fn()); store.dispatch(cartAdded({ variant: 'coral', quantity: 8 })); store.dispatch(favoriteToggled('coral'));
    const payload = JSON.parse(serializeProductPreferences(store.getState().productDemo));
    expect(payload.purchaseQuantities.coral).toBe(1); expect(Object.keys(payload)).toEqual(['version', 'selectedVariant', 'purchaseQuantities', 'mediaMode', 'descriptionOpen']);
    expect(payload).not.toHaveProperty('cart'); expect(payload).not.toHaveProperty('favorite'); expect(payload).not.toHaveProperty('authentication');
  });
  it('handles denied reads and writes without affecting the in-memory store or propagating an error', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new DOMException('denied', 'SecurityError'); });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('full', 'QuotaExceededError'); });
    const store = createAppStore('en', vi.fn());
    expect(store.getState().productDemo).toMatchObject(defaultProductPreferences());
    store.dispatch(productMediaModeSelected('model')); store.dispatch(productMediaModeSelected('unknown' as MediaMode));
    expect(store.getState().productDemo.mediaMode).toBe('model');
    expect(() => rememberProductPreferences(serializeProductPreferences(store.getState().productDemo))).not.toThrow();
  });
  it('projects only approved fields even when a caller passes an extended state object', () => {
    const source = { ...defaultProductPreferences(), cart: [{ variant: 'coral', quantity: 2 }], favorite: 'coral', accountId: 'do not persist', csrf: 'do not persist', Q: 'do not persist' };
    rememberProductPreferences(serializeProductPreferences(source));
    expect(JSON.parse(localStorage.getItem(PRODUCT_PREFERENCES_KEY)!)).toEqual({ version: 1, ...defaultProductPreferences() });
    expect(localStorage.length).toBe(1);
  });
});
