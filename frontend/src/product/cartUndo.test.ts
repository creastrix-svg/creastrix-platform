import { afterEach, expect, it, vi } from 'vitest';
import { createAppStore } from '../store';
import reducer, { cartAdded, cartCount, cartDisplayLines, cartLineRemoved, cartLineRestored, cartQuantityChanged, cartRemovedLineDismissed, cartTotal, favoriteToggled, purchaseQuantityChanged } from './demoStore';
import type { ProductDemoState } from './demoStore';
import type { VariantId } from './catalog';
import * as catalog from './catalog';
import { serializeProductPreferences } from './preferences';

afterEach(() => { vi.restoreAllMocks(); });
const originalLines = [{ variant: 'cobalt' as const, quantity: 2 }, { variant: 'coral' as const, quantity: 3 }, { variant: 'chalk' as const, quantity: 1 }];
function threeLines() {
  const store = createAppStore('en', vi.fn()); originalLines.forEach(line => store.dispatch(cartAdded(line))); return store;
}
function removedMiddle() {
  const store = threeLines(); store.dispatch(cartLineRemoved('coral')); return store;
}
const restore = (variant: VariantId = 'coral', revision = 4) => cartLineRestored({ variant, revision });

it('restores independent deleted rows after another active row changes, in their original order and quantities', () => {
  const store = removedMiddle(); store.dispatch(cartLineRemoved('cobalt')); store.dispatch(cartQuantityChanged({ variant: 'chalk', quantity: 4 }));
  expect(store.getState().productDemo.cartRemoved).toEqual({ coral: { quantity: 3, revision: 4 }, cobalt: { quantity: 2, revision: 5 } });
  expect(cartDisplayLines(store.getState().productDemo).map(line => [line.variant, line.quantity])).toEqual([['cobalt', 0], ['coral', 0], ['chalk', 4]]);
  store.dispatch(restore()); store.dispatch(restore('cobalt', 5));
  expect(store.getState().productDemo.cart).toEqual([...originalLines.slice(0, 2), { variant: 'chalk', quantity: 4 }]);
  expect(cartCount(store.getState().productDemo)).toBe(9); expect(cartTotal(store.getState().productDemo)).toBe(20_560);
  expect(store.getState().productDemo.cartRemoved).toEqual({}); expect(store.getState().productDemo.addSerial).toBe(3);
});

it.each([
  ['cobalt', 'coral', 'chalk'], ['cobalt', 'chalk', 'coral'], ['coral', 'cobalt', 'chalk'],
  ['coral', 'chalk', 'cobalt'], ['chalk', 'cobalt', 'coral'], ['chalk', 'coral', 'cobalt'],
] as VariantId[][])('retains all zero rows and restores original order for %s/%s/%s', (...order) => {
  const store = threeLines(); order.forEach(id => store.dispatch(cartLineRemoved(id)));
  const removed = store.getState().productDemo;
  expect(removed.cart).toEqual([]); expect(cartCount(removed)).toBe(0); expect(cartTotal(removed)).toBe(0);
  expect(cartDisplayLines(removed).map(line => [line.variant, line.quantity])).toEqual([['cobalt', 0], ['coral', 0], ['chalk', 0]]);
  // Restoration order need not match removal order; the visible slots do not move.
  [...order].reverse().forEach(variant => store.dispatch(restore(variant, removed.cartRemoved[variant]!.revision)));
  expect(store.getState().productDemo.cart).toEqual(originalLines);
  expect(cartCount(store.getState().productDemo)).toBe(6); expect(cartTotal(store.getState().productDemo)).toBe(14_590);
});

it('consumes only the exact row token once and rejects a stale replay after another deletion', () => {
  const store = removedMiddle(); store.dispatch(restore()); const restored = store.getState().productDemo;
  store.dispatch(restore()); expect(store.getState().productDemo).toBe(restored);
  store.dispatch(cartLineRemoved('coral')); const later = store.getState().productDemo;
  store.dispatch(restore()); expect(store.getState().productDemo).toBe(later);
  store.dispatch(restore('coral', 6)); expect(store.getState().productDemo.cart).toEqual(originalLines);
});

it('treats explicit re-add as a new quantity in the same slot and retires the old restore without duplication', () => {
  const store = removedMiddle(); store.dispatch(cartAdded({ variant: 'coral', quantity: 1 }));
  const added = store.getState().productDemo;
  expect(added.cart).toEqual([originalLines[0], { variant: 'coral', quantity: 1 }, originalLines[2]]);
  expect(added.cartRemoved.coral).toBeUndefined(); expect(cartDisplayLines(added)).toHaveLength(3);
  store.dispatch(restore()); expect(store.getState().productDemo).toBe(added);
  store.dispatch(cartLineRemoved('coral')); expect(store.getState().productDemo.cartRemoved.coral).toEqual({ quantity: 1, revision: 6 });
  store.dispatch(restore()); expect(store.getState().productDemo.cart).toHaveLength(2);
  store.dispatch(restore('coral', 6)); expect(store.getState().productDemo.cart[1].quantity).toBe(1);
  expect(store.getState().productDemo.addSerial).toBe(4);
});

it.each([
  cartAdded({ variant: 'cobalt', quantity: 1 }), cartAdded({ variant: 'cobalt-chalk', quantity: 2 }),
  cartQuantityChanged({ variant: 'cobalt', quantity: 3 }), cartLineRemoved('chalk'),
  favoriteToggled('chalk'), purchaseQuantityChanged({ variant: 'chalk', quantity: 3 }),
])('preserves this row snapshot across an unrelated action $type with $payload', action => {
  const store = removedMiddle(), snapshot = store.getState().productDemo.cartRemoved.coral;
  store.dispatch(action); expect(store.getState().productDemo.cartRemoved.coral).toBe(snapshot);
  store.dispatch(restore()); expect(store.getState().productDemo.cart.find(line => line.variant === 'coral')?.quantity).toBe(3);
});

it.each([
  cartAdded({ variant: 'cobalt', quantity: 0 }), cartAdded({ variant: 'coral', quantity: NaN }),
  cartQuantityChanged({ variant: 'cobalt', quantity: 2 }), cartQuantityChanged({ variant: 'coral', quantity: 1 }),
  cartLineRemoved('coral'), restore('coral', 3), restore('coral', NaN),
  restore('constructor' as VariantId), restore('coral', Number.MAX_SAFE_INTEGER + 1),
])('does not mutate the cart for rejected/no-op action $type with $payload', action => {
  const store = removedMiddle(), before = store.getState().productDemo;
  store.dispatch(action); expect(store.getState().productDemo).toBe(before);
  store.dispatch(restore()); expect(store.getState().productDemo.cart[1]).toEqual(originalLines[1]);
});

it('cannot overwrite or merge an active line even if malformed state retains an old snapshot', () => {
  const removed = removedMiddle().getState().productDemo;
  const malformed: ProductDemoState = { ...removed, cart: [...removed.cart, { variant: 'coral', quantity: 1 }] };
  expect(reducer(malformed, restore())).toBe(malformed);
});
it('dismisses only one removed slot/snapshot, preserves active totals and other restores, and retires its token', () => {
  const store = removedMiddle(); store.dispatch(cartLineRemoved('chalk'));
  const before = store.getState().productDemo;
  store.dispatch(cartRemovedLineDismissed({ variant: 'coral', revision: 4 }));
  const dismissed = store.getState().productDemo;
  expect(dismissed.cartOrder).toEqual(['cobalt', 'chalk']); expect(dismissed.cartRemoved.coral).toBeUndefined();
  expect(dismissed.cartRemoved.chalk).toBe(before.cartRemoved.chalk); expect(dismissed.cart).toBe(before.cart);
  expect(dismissed.purchaseQuantities).toBe(before.purchaseQuantities); expect(dismissed.addSerial).toBe(before.addSerial);
  expect(cartCount(dismissed)).toBe(2); expect(cartTotal(dismissed)).toBe(4800);
  store.dispatch(restore()); store.dispatch(cartRemovedLineDismissed({ variant: 'coral', revision: 4 }));
  expect(store.getState().productDemo).toBe(dismissed);
  store.dispatch(restore('chalk', 5)); expect(store.getState().productDemo.cart).toEqual([originalLines[0], originalLines[2]]);
});
it('re-adds a dismissed variant as a new last row and rejects its old Restore/X across later cycles', () => {
  const store = removedMiddle(); store.dispatch(cartRemovedLineDismissed({ variant: 'coral', revision: 4 }));
  store.dispatch(cartAdded({ variant: 'coral', quantity: 1 }));
  const added = store.getState().productDemo;
  expect(added.cart).toEqual([originalLines[0], originalLines[2], { variant: 'coral', quantity: 1 }]);
  expect(added.cartOrder).toEqual(['cobalt', 'chalk', 'coral']); expect(cartDisplayLines(added)).toHaveLength(3);
  store.dispatch(restore()); store.dispatch(cartRemovedLineDismissed({ variant: 'coral', revision: 4 })); expect(store.getState().productDemo).toBe(added);
  store.dispatch(cartLineRemoved('coral')); const removedAgain = store.getState().productDemo;
  store.dispatch(cartRemovedLineDismissed({ variant: 'coral', revision: 4 })); expect(store.getState().productDemo).toBe(removedAgain);
  store.dispatch(restore('coral', 7)); expect(store.getState().productDemo.cart[2].quantity).toBe(1);
});
it('never dismisses an active line even if malformed state retains a matching removed snapshot', () => {
  const removed = removedMiddle().getState().productDemo;
  const malformed: ProductDemoState = { ...removed, cart: [...removed.cart, { variant: 'coral', quantity: 1 }] };
  expect(reducer(malformed, cartRemovedLineDismissed({ variant: 'coral', revision: 4 }))).toBe(malformed);
});
it.each([
  { variant: 'coral', revision: 3 }, { variant: 'coral', revision: 0 }, { variant: 'coral', revision: NaN },
  { variant: 'constructor', revision: 4 }, { variant: 'cobalt', revision: 4 },
] as { variant: VariantId; revision: number }[])('rejects stale/invalid/active dismissal $variant/$revision without mutation', payload => {
  const store = removedMiddle(), before = store.getState().productDemo;
  store.dispatch(cartRemovedLineDismissed(payload)); expect(store.getState().productDemo).toBe(before);
});
it('checks current own stock and rejects oversized restoration without a partial clamp', () => {
  const store = removedMiddle(), before = store.getState().productDemo, original = catalog.variantById;
  vi.spyOn(catalog, 'variantById').mockImplementation(id => id === 'coral' ? { ...original(id), stock: 2 } : original(id));
  store.dispatch(restore()); expect(store.getState().productDemo).toBe(before);
  expect(cartDisplayLines(before)[1].quantity).toBe(0);
});
it.each([0, -1, 1.5, 9, Number.MAX_SAFE_INTEGER + 1])('rejects invalid snapshot quantity %s atomically', quantity => {
  const removed = removedMiddle().getState().productDemo;
  const malformed: ProductDemoState = { ...removed, cartRemoved: { coral: { quantity, revision: 4 } } };
  expect(reducer(malformed, restore())).toBe(malformed);
});
it.each([['cobalt', 'chalk'], ['cobalt', 'coral', 'coral', 'chalk']] as VariantId[][])('rejects a missing/duplicate presentation slot: %s', (...cartOrder) => {
  const removed = removedMiddle().getState().productDemo, malformed: ProductDemoState = { ...removed, cartOrder };
  expect(reducer(malformed, restore())).toBe(malformed);
});
it('never reuses tokens and keeps presentation/removal metadata out of persisted preferences', () => {
  const removed = removedMiddle().getState().productDemo;
  const exhausted: ProductDemoState = { ...removed, cartChange: { revision: Number.MAX_SAFE_INTEGER, kind: 'other' } };
  for (const action of [restore(), cartRemovedLineDismissed({ variant: 'coral', revision: 4 }), cartAdded({ variant: 'coral', quantity: 1 }), cartQuantityChanged({ variant: 'cobalt', quantity: 3 }), cartLineRemoved('chalk')]) expect(reducer(exhausted, action)).toBe(exhausted);
  expect(Object.keys(JSON.parse(serializeProductPreferences(removed))).sort()).toEqual(['descriptionOpen', 'mediaMode', 'purchaseQuantities', 'selectedVariant', 'version']);
  const fresh = createAppStore('en', vi.fn()).getState().productDemo;
  expect(fresh.cart).toEqual([]); expect(fresh.cartOrder).toEqual([]); expect(fresh.cartRemoved).toEqual({});
  expect(fresh.cartChange).toEqual({ revision: 0, kind: 'other' }); expect(localStorage.length).toBe(0);
});
