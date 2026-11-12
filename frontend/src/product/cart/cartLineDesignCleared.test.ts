import { expect, it, vi } from 'vitest';
import { createAppStore } from '../../store';
import { cartAdded, cartCount, cartDesignChanged, cartHydrated, cartLineDesignCleared, cartLinePrice, cartLineRemoved, cartLineRestored, cartStockShortages, cartTotal } from '../demoStore';
import { defaultLettering } from '../studio/design/personalization';
import { initialStudioComposition } from '../studio/design/studioComposition';
import { createStudioFontLibrary } from '../studio/design/studioFonts';
import { designKey, saveDesign } from '../studio/session/designSnapshot';
import { restoreStudio, serializeStudio } from '../studio/session/studioStorage';

const design = (text: string) => saveDesign({ selectedId: null, objects: [
  { id: 'studio-object-1', kind: 'text', value: { ...defaultLettering('cobalt'), text } },
] })!;
const a = design('A'), b = design('B');
const clearA = () => cartLineDesignCleared({ id:'design-1', variant:'cobalt', expected:designKey(a) });

it('turns only the selected design into a plain line, at base price, and keeps that identity after reload', async () => {
  const store = createAppStore('en'), state = () => store.getState().productDemo;
  store.dispatch(cartAdded({ variant:'cobalt', quantity:2, design:a }));
  store.dispatch(cartAdded({ variant:'cobalt', quantity:1, design:b }));
  const before = state(), beforeTotal = cartTotal(before);
  store.dispatch(clearA());
  expect(state().cartOrder).toEqual(['cobalt','design-2']);
  expect(state().cart).toEqual([{ variant:'cobalt', quantity:2 }, before.cart[1]]);
  expect(cartLinePrice(state().cart[0])).toMatchObject({feeCents:0, unitCents:2400});
  expect(cartLinePrice(state().cart[1])).toMatchObject({feeCents:500, unitCents:2900});
  expect(cartTotal(state())).toBe(beforeTotal - 2 * 500);
  expect(cartCount(state())).toBe(cartCount(before));
  const raw = serializeStudio(initialStudioComposition(), {drafts:{}, applied:{}, customFonts:[]}, state());
  const fonts = createStudioFontLibrary();
  vi.spyOn(fonts, 'loadBuiltin').mockImplementation(async id => ({id, label:id, family:id}));
  try {
    const fresh = createAppStore('en');
    fresh.dispatch(cartHydrated(await restoreStudio(raw, fonts)));
    expect(fresh.getState().productDemo.cart).toEqual(state().cart);
    expect(fresh.getState().productDemo.cartOrder).toEqual(state().cartOrder);
    expect(cartTotal(fresh.getState().productDemo)).toBe(cartTotal(state()));
    fresh.dispatch(cartAdded({ variant:'cobalt', quantity:1, design:a }));
    expect(fresh.getState().productDemo.cart.at(-1)?.id).toBe('design-3');
  } finally { fonts.dispose(); }
});

it('merges into the existing plain SKU without losing units even after available stock fell', () => {
  const store = createAppStore('en'), state = () => store.getState().productDemo;
  store.dispatch(cartHydrated({ serial:3, lines:[
    {id:'design-1', variant:'cobalt', quantity:2, design:a},
    {id:'design-2', variant:'cobalt', quantity:1, design:b},
    {variant:'cobalt', quantity:24},
  ] }));
  expect(cartStockShortages(state())).toContain('cobalt');
  store.dispatch(clearA());
  expect(state().cartOrder).toEqual(['design-2','cobalt']);
  expect(state().cart).toEqual([{id:'design-2', variant:'cobalt', quantity:1, design:b}, {variant:'cobalt', quantity:26}]);
  expect(cartCount(state())).toBe(27);
  expect(cartStockShortages(state())).toContain('cobalt');
  const once = state();
  store.dispatch(clearA());
  expect(state()).toBe(once);
});

it('supersedes only the removed plain SKU, retaining the edited position and unrelated undo tokens', () => {
  const store = createAppStore('en'), state = () => store.getState().productDemo;
  store.dispatch(cartAdded({ variant:'cobalt', quantity:3 }));
  store.dispatch(cartAdded({ variant:'coral', quantity:1 }));
  store.dispatch(cartAdded({ variant:'cobalt', quantity:2, design:a }));
  store.dispatch(cartLineRemoved('cobalt'));
  store.dispatch(cartLineRemoved('coral'));
  const cobalt = state().cartRemoved.cobalt!, coral = state().cartRemoved.coral!;
  store.dispatch(clearA());
  expect(state().cartOrder).toEqual(['coral','cobalt']);
  expect(state().cartRemoved).toEqual({coral});
  store.dispatch(cartLineRestored({variant:'cobalt',revision:cobalt.revision}));
  expect(state().cart).toEqual([{ variant:'cobalt', quantity:2 }]);
  store.dispatch(cartLineRestored({variant:'coral',revision:coral.revision}));
  expect(state().cart).toEqual([{variant:'coral', quantity:1}, {variant:'cobalt', quantity:2}]);
});

it('ignores stale, deleted, wrong-variant and plain targets', () => {
  const store = createAppStore('en'), state = () => store.getState().productDemo;
  store.dispatch(cartAdded({variant:'cobalt', quantity:1, design:a}));
  store.dispatch(cartAdded({variant:'cobalt', quantity:1}));
  store.dispatch(cartDesignChanged({id:'design-1', expected:designKey(a), design:b}));
  const changed = state();
  store.dispatch(clearA());
  store.dispatch(cartLineDesignCleared({id:'design-1', variant:'coral', expected:designKey(b)}));
  store.dispatch(cartLineDesignCleared({id:'cobalt', variant:'cobalt', expected:''}));
  expect(state()).toBe(changed);
  store.dispatch(cartLineRemoved('design-1'));
  const removed = state();
  store.dispatch(cartLineDesignCleared({id:'design-1', variant:'cobalt', expected:designKey(b)}));
  expect(state()).toBe(removed);
});
