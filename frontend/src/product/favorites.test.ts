import { expect, it, vi } from 'vitest';
import { createAppStore } from '../store';
import { favoriteRemoved, favoriteToggled } from './demoStore';
import { favoriteKey, readFavoriteIds } from './favorites';
import type { VariantId } from './catalog';
import { tideOffer } from './tideOffer';

it('keeps the Tide cup and cobalt Loop as separate favourites across a fresh store', () => {
  const store = createAppStore('en');
  store.dispatch(favoriteToggled('cobalt'));
  store.dispatch(favoriteToggled(tideOffer.sku));
  const fresh = createAppStore('en');
  expect(fresh.getState().productDemo.favorites).toEqual(['cobalt', tideOffer.sku]);
  fresh.dispatch(favoriteRemoved(tideOffer.sku));
  expect(readFavoriteIds()).toEqual(['cobalt']);
});

it('independent tabs write only their exact variant; removal and the empty result survive reload', () => {
  const a = createAppStore('en'), b = createAppStore('en');
  a.dispatch(favoriteToggled('chalk')); b.dispatch(favoriteToggled('cobalt'));
  expect(readFavoriteIds()).toEqual(['cobalt', 'chalk']);
  a.dispatch(favoriteRemoved('chalk'));
  expect(createAppStore('en').getState().productDemo.favorites).toEqual(['cobalt']);
  b.dispatch(favoriteRemoved('cobalt'));
  expect(createAppStore('en').getState().productDemo.favorites).toEqual([]);
  expect(localStorage.length).toBe(0);
});

it('never restores malformed markers or unknown versions/variants and does not rewrite unrelated keys', () => {
  localStorage.setItem(favoriteKey('coral'), 'true');
  localStorage.setItem(favoriteKey('chalk').replace('.v1.', '.v2.'), '1');
  localStorage.setItem(favoriteKey('chalk').replace('chalk', 'unknown'), '1');
  const store = createAppStore('en'); store.dispatch(favoriteToggled('constructor' as VariantId));
  expect(store.getState().productDemo.favorites).toEqual([]);
  expect(localStorage.length).toBe(3);
});

it('keeps denied writes in the originating store, signals memory-only status and recovers on successful editing', () => {
  const store = createAppStore('en');
  const write = vi.fn(() => { throw new Error('quota'); });
  vi.stubGlobal('localStorage', { setItem: write, getItem: () => null, removeItem: () => {}, clear: () => {} });
  try {
    store.dispatch(favoriteToggled('coral'));
    expect(write).toHaveBeenCalledWith(favoriteKey('coral'), '1');
    expect(store.getState().productDemo.favoritesMemoryOnly).toBe(true);
    expect(readFavoriteIds(store.getState)).toEqual(['coral']); expect(readFavoriteIds()).toEqual([]);
    store.dispatch(favoriteRemoved('coral'));
    expect(store.getState().productDemo.favoritesMemoryOnly).toBeUndefined();
    expect(readFavoriteIds(store.getState)).toEqual([]);
    vi.stubGlobal('localStorage', { getItem: () => { throw new Error('denied'); } });
    expect(readFavoriteIds()).toBeNull(); expect(createAppStore('en').getState().productDemo.favorites).toEqual([]);
  } finally { vi.unstubAllGlobals(); }
});
