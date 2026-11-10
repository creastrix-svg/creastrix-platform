import { expect, it, vi } from 'vitest';
import { createAppStore } from '../../store';
import { cartOfferView } from '../cart/cartOfferView';
import { productCopy } from '../copy';
import { cartAdded, cartLinePrice } from '../demoStore';
import { storefrontLocales } from '../locale';
import type { SavedDesign } from '../studio/session/designSnapshot';
import * as studio from '../studio/studioProducts';
import { editionItems } from './editionCatalog';
import { variants } from './loop';
import { tideOffer } from './tideOffer';

it.each(storefrontLocales)('preserves the original photo descriptions for every cart SKU (%s)', locale => {
  for (const variant of variants) {
    expect(cartOfferView(variant.id, locale).alt).toBe(productCopy[locale].photoAlt + ' · ' + variant.name[locale]);
  }
  const cup = editionItems(locale).find(i => i.id === tideOffer.id)!;
  expect(cartOfferView(tideOffer.sku, locale).alt).toBe(cup.name + ' · ' + cup.title);
});

it('stops new design additions when configuration is unavailable without repricing an existing snapshot', () => {
  const design: SavedDesign = { objects: [{ id: 'studio-object-1', kind: 'image', fit: 'contain',
    value: { image: { src: 'test-image', aspect: 1 }, size: 80, x: 0, y: 0 } }] };
  const store = createAppStore('en');
  store.dispatch(cartAdded({ variant: 'chalk', quantity: 1, design }));
  const unavailable = vi.spyOn(studio, 'canPersonalize').mockReturnValue(false);
  try {
    store.dispatch(cartAdded({ variant: 'chalk', quantity: 1, design }));
    store.dispatch(cartAdded({ variant: 'chalk', quantity: 1 }));
    const lines = store.getState().productDemo.cart;
    expect(lines.map(line => line.quantity)).toEqual([1, 1]);
    expect(lines.map(line => cartLinePrice(line).unitCents)).toEqual([2490, 1990]);
  } finally { unavailable.mockRestore(); }
});
