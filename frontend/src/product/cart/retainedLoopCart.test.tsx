import { render, screen, within } from '@testing-library/react';
import { Provider } from 'react-redux';
import { MemoryRouter } from 'react-router';
import { beforeAll, expect, it, vi } from 'vitest';
import { createAppStore } from '../../store';
import { connectedProduct, loadLocalCatalog } from '../catalog/localCatalog';
import { cartAdded, cartDesignChanged, cartHydrated, cartLinePrice } from '../demoStore';
import { readCartSku } from '../fixtures/cartSkus';
import { restoreStudio,serializeStudio } from '../studio/session/studioStorage';
import { createStudioFontLibrary } from '../studio/design/studioFonts';
import { defaultLettering } from '../studio/design/personalization';
import { designKey } from '../studio/session/designSnapshot';
import CartView from './CartView';
import { money } from '../productDisplay';
import { studioCartCopy } from './studioCartCopy';

vi.hoisted(()=>{vi.stubEnv('MODE','account-cart-local');});
const variant=(id:string,sku=id)=>({id,sku,label:id,cents:3020,stock:6,swatch:'#174f6e'});
beforeAll(async()=>{
  const offer={id:'loop',label:'Loop',currency:'EUR',templateRevision:'3',studioFormat:null,studioFeeCents:375,
    variants:[{...variant('cobalt'),retainedStudioRevision:'2'},variant('coral'),{...variant('different','cobalt-chalk'),retainedStudioRevision:'2'}]};
  const fetcher=vi.spyOn(window,'fetch').mockResolvedValueOnce(Response.json([offer,
    {...offer,id:'another',label:'Another',variants:[{...variant('chalk'),retainedStudioRevision:'2'}]}]));
  try { await loadLocalCatalog(); } finally { fetcher.mockRestore(); }
});
const design={objects:[{id:'studio-object-1',kind:'text' as const,value:{...defaultLettering('cobalt'),font:'rounded' as const,text:'Old design'}}]};
const snapshot=(sku:string,revision='2')=>JSON.stringify({version:1,serial:2,drafts:{},applied:{},creativeDrafts:{},creativeApplied:{},
  fonts:[],assets:[],lines:[{variant:sku,templateRevision:revision,quantity:2,id:'design-1',design}]});
it('hydrates, displays and resaves the pinned v2 after a cold v3 catalog read; never reopens the obsolete editor',async()=>{
  expect(connectedProduct('loop')).toBeUndefined();
  expect(readCartSku('cobalt').acceptsDesign).toBe(false);
  const fonts=createStudioFontLibrary();
  vi.spyOn(fonts,'loadBuiltin').mockResolvedValue({id:'rounded',family:'Nunito',label:'Nunito'});
  const restored=await restoreStudio(snapshot('cobalt'),fonts);
  const store=createAppStore('en');
  store.dispatch(cartHydrated(restored));
  const before=store.getState().productDemo;
  expect(before.cart).toEqual([{id:'design-1',variant:'cobalt',templateRevision:'2',quantity:2,design}]);
  expect(cartLinePrice(before.cart[0])).toEqual({baseCents:3020,feeCents:375,unitCents:3395});
  const saved=JSON.parse(serializeStudio(restored.composition,restored,before));
  expect(saved.lines).toEqual(JSON.parse(snapshot('cobalt')).lines);
  store.dispatch(cartAdded({variant:'cobalt',quantity:1,design}));
  store.dispatch(cartDesignChanged({id:'design-1',expected:designKey(design),
    design:{objects:[{...design.objects[0],value:{...design.objects[0].value,text:'Not authorized'}}]}}));
  expect(store.getState().productDemo).toBe(before);
  render(<Provider store={store}><MemoryRouter><CartView/></MemoryRouter></Provider>);
  const row=within(await screen.findByRole('article'));
  expect(row.getByRole('img',{name:/Old design/}).querySelector('path[fill="#174f6e"]')).not.toBeNull();
  expect(row.getByText(money(3395,'en'))).toBeVisible();
  expect(row.queryByRole('link',{name: new RegExp(studioCartCopy.en.edit)})).not.toBeInTheDocument();
});
it.each([['coral','2'],['missing','2'],['cobalt-chalk','2'],['chalk','2'],['cobalt','3']])(
  'does not infer archived authority from a handle, wrong revision, foreign offer or mismatched SKU: %s @ %s',async(sku,rev)=>{
    await expect(restoreStudio(snapshot(sku,rev),createStudioFontLibrary())).rejects.toThrow();
  });
