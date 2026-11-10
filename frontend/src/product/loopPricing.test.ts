import { describe, expect, it } from 'vitest';
import { createAppStore } from '../store';
import { variants } from './fixtures/loop';
import { cartAdded, cartHydrated, cartLinePrice, cartTotal } from './demoStore';
import type { SavedDesign, SavedObject } from './studio/session/designSnapshot';
import { loopPrice } from './loopPricing';
import { defaultLettering } from './studio/design/personalization';

const text = (value: string): SavedObject => ({ id: 'studio-object-1', kind: 'text', value: { ...defaultLettering('cobalt'), text: value } });
const picture: SavedObject = { id: 'studio-object-2', kind: 'image', fit: 'contain',
  value: { image: { src: 'test-image', aspect: 1 }, size: 80, x: 0, y: 0 } };

describe('Loop preview price in integer cents', () => {
  it.each(variants)('uses the current $id base price, never the crossed-out price', variant => {
    expect(loopPrice(variant.id, false)).toEqual({ baseCents: variant.cents, feeCents: 0, unitCents: variant.cents });
    expect(loopPrice(variant.id, true)).toEqual({ baseCents: variant.cents, feeCents: 500, unitCents: variant.cents + 500 });
  });

  it.each<[string, SavedDesign | undefined, number]>([
    ['no design', undefined, 2400],
    ['empty design', { objects: [] }, 2400],
    ['blank lettering', { objects: [text('   ')] }, 2400],
    ['lettering', { objects: [text('Loop')] }, 2900],
    ['picture alone', { objects: [picture] }, 2900],
    ['multiple layers', { objects: [text('Loop'), picture] }, 2900],
  ])('charges once per item for visible content: %s', (_label, design, unitCents) => {
    expect(cartLinePrice({ variant: 'cobalt', design }).unitCents).toBe(unitCents);
  });

  it('derives the same mixed subtotal after restoring saved designs; cart data needs no price fields', () => {
    const store = createAppStore('en');
    store.dispatch(cartAdded({ variant: 'cobalt', quantity: 2, design: { objects: [text('Loop'), picture] } }));
    store.dispatch(cartAdded({ variant: 'cobalt', quantity: 1 }));
    store.dispatch(cartAdded({ variant: 'chalk', quantity: 2, design: { objects: [picture] } }));
    const state = store.getState().productDemo;
    expect(cartTotal(state)).toBe(5800 + 2400 + 4980);
    const fresh = createAppStore('en');
    fresh.dispatch(cartHydrated({ lines: structuredClone(state.cart), serial: state.cartSerial }));
    expect(cartTotal(fresh.getState().productDemo)).toBe(13180);
  });
});
