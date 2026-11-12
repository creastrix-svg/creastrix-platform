import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { MemoryRouter, Route, Routes } from 'react-router';
import { expect, it, vi } from 'vitest';
import { createAppStore } from '../../store';
import { loadLocalCatalog } from '../catalog/localCatalog';
import { isCartSku } from '../cartSku';
import { cartAdded, cartHydrated, cartLineRemoved, cartLineRestored, cartTotal, favoriteToggled } from '../demoStore';
import { favoriteKey, readFavoriteIds } from '../favorites';
import { initialStudioComposition } from '../studio/design/studioComposition';
import { createStudioFontLibrary } from '../studio/design/studioFonts';
import { restoreStudio, serializeStudio } from '../studio/session/studioStorage';
import CheckoutView from '../checkout/CheckoutView';
import { cartOfferView } from './cartOfferView';
import CartView from './CartView';

vi.hoisted(() => { vi.stubEnv('MODE', 'local-catalog'); });

const labels = (s: string) => ({de:s,en:s,ru:s,pl:s,fr:s,es:s,it:s});
const finish = (id: string, sku: string) => ({id, sku, cents: 4670, previousCents: null,
  stock: 3, name: labels('Natural'), colors: ['#bb9988'], checkTone: 'dark',
  photos: ['/shop-edition/lamp.webp'], model: null});
const offer = (id: string, href: string, variants: ReturnType<typeof finish>[]) => ({
  id, href, name: id.toUpperCase(), currency: 'EUR', personalizable: false, designFeeCents: 0, variants,
});

it('retains database merchandise through favorites, cart recovery, undo, return navigation and checkout', async () => {
  const products = [
    offer('loop','/products/loop',['cobalt','coral','chalk','cobalt-chalk'].map(id => finish(id,id))),
    offer('tide','/collections/objects/tide',[finish('cobalt','tide-cobalt')]),
    offer('halo','/collections/objects/halo',[finish('standard','halo-standard')]),
    offer('loop-mini','/preview/items/loop-mini',[finish('standard','loop-mini-standard')]),
  ];
  const entries = products.map(({id, href, name}) => ({id, href, name, kind:'offer', title:labels(id+' title'),
    description:labels(''), material:labels(''), photo:null, thumb:null, categories:['living'],
    collectionId:null, collectionName:null, editionOrder:null, homeOrder:null, rating:{count:0,average:null}}));
  vi.spyOn(window,'fetch').mockResolvedValue(new Response(JSON.stringify({ products, entries, recommendations:[] }),
    {headers:{'content-type':'application/json'}}));
  const beforeLoad = createAppStore('en');
  expect(beforeLoad.getState().productDemo.favorites).toEqual([]);
  await loadLocalCatalog();
  const lamp = 'halo-standard', pot = 'loop-mini-standard';
  if (!isCartSku(lamp) || !isCartSku(pot)) throw new Error('Server variants were not recognised.');
  expect(isCartSku('unknown-standard')).toBe(false);
  const store = createAppStore('en');
  store.dispatch(favoriteToggled(lamp)); store.dispatch(favoriteToggled(pot));
  expect(localStorage.getItem(favoriteKey(lamp))).toBe('1');
  store.dispatch(cartAdded({variant:lamp,quantity:1,design:{objects:[]}}));
  expect(store.getState().productDemo.cart).toEqual([]);
  store.dispatch(cartAdded({variant:lamp,quantity:5})); store.dispatch(cartAdded({variant:pot,quantity:2}));
  expect(cartTotal(store.getState().productDemo)).toBe(5 * 4670);
  const snapshot = serializeStudio(initialStudioComposition(),{drafts:{},applied:{},customFonts:[]}, store.getState().productDemo);
  const fonts = createStudioFontLibrary();
  vi.spyOn(fonts,'loadBuiltin').mockImplementation(async id => ({id,label:id,family:id}));
  const restored = await restoreStudio(snapshot, fonts);
  expect(restored.lines).toEqual([{variant:lamp,quantity:3},{variant:pot,quantity:2}]);
  const forged = JSON.parse(snapshot); forged.lines[0].design = {objects:[]};
  await expect(restoreStudio(JSON.stringify(forged), fonts)).rejects.toThrow('STUDIO_STORAGE_CART');
  fonts.dispose();
  const fresh = createAppStore('en');
  fresh.dispatch(cartHydrated(restored));
  expect(readFavoriteIds()).toEqual([lamp,pot]);
  expect(fresh.getState().productDemo.favorites).toEqual([lamp,pot]);
  expect(cartOfferView(lamp,'ru')).toMatchObject({name:'HALO',detail:'halo title',href:products[2].href,photo:'/shop-edition/lamp.webp'});
  fresh.dispatch(cartLineRemoved(pot));
  fresh.dispatch(cartLineRestored({variant:pot,revision:fresh.getState().productDemo.cartChange.revision}));
  expect(cartTotal(fresh.getState().productDemo)).toBe(5 * 4670);
  const user = userEvent.setup();
  render(<Provider store={fresh}><MemoryRouter initialEntries={[{pathname:'/preview/cart',state:{cartReturnTo:products[2].href}}]}>
    <Routes><Route path="/preview/cart" element={<CartView/>}/><Route path="/preview/checkout" element={<CheckoutView/>}/></Routes>
  </MemoryRouter></Provider>);
  expect(screen.getByRole('link',{name:'Continue shopping'})).toHaveAttribute('href',products[2].href);
  expect(within(screen.getAllByRole('article')[0]).getByRole('heading',{name:'HALO'})).toBeVisible();
  await user.click(screen.getByRole('link',{name:'Checkout'}));
  expect(screen.getByRole('heading',{name:'Checkout'})).toBeVisible();
  expect(screen.getByText('€233.50')).toBeVisible();
});
