import {expect,it,vi} from 'vitest';
import {catalogEndpoint,fetchCatalog} from './localCatalog';
import {mediaNamespace} from './photoRenditions';
vi.hoisted(()=>vi.stubEnv('MODE','shared-cart-local'));

const labels=(s:string)=>({en:s,ru:s,fr:s,de:s,es:s,it:s,pl:s});
const key='904e4c92-11db-4000-b4d5-4bb23ba0d58c',photo=mediaNamespace+key+'/detail.jpg',thumb=mediaNamespace+key+'/thumb.jpg';
const href='/collections/objects/new-piece',id='different-from-route';
const variant={id:'pearl',sku:'purchase-pearl',name:labels('Pearl'),cents:1200,previousCents:null,stock:7,colors:['#ababab'],
  checkTone:'dark',photos:[photo],model:null};
const product={id,name:'New piece',href,personalizable:true,designFeeCents:175,currency:'EUR',variants:[variant]};
const entry={id,href,name:product.name,kind:'offer',title:labels('New piece from DB'),description:labels('Description'),material:labels('Clay'),
  photo,thumb,categories:['living'],collectionId:null,collectionName:null,editionOrder:1,homeOrder:2,rating:{count:3,average:4}};
it('uses the full storefront snapshot and canonical media; a new SKU buys without Loop, Tide or a per-SKU capability fixture',async()=>{
  const fetcher=vi.fn(async()=>Response.json({products:[product],entries:[entry],recommendations:[]}));
  const data=await fetchCatalog(fetcher);
  expect(fetcher.mock.calls[0]).toEqual([catalogEndpoint,expect.objectContaining({credentials:'omit',method:'GET'})]);
  expect(catalogEndpoint).toBe('/api/local-catalog/products');
  expect(data.byPath.get(href)).toMatchObject({id,photo,thumb,rating:entry.rating});
  expect(data.skus.get(variant.sku)?.variant).toMatchObject({id:'pearl',sku:'purchase-pearl',directAddSupported:true,photos:[photo]});
  expect(data.skus.get(variant.sku)?.variant.studioFormat).toBeUndefined();
  expect(data.loop).toBeUndefined();expect(data.tide).toBeUndefined();
  const configured={...product,variants:[{...variant,studioFormat:'surface-print-v1',templateRevision:'2'}]};
  const result=await fetchCatalog(async()=>Response.json({products:[configured],entries:[entry],recommendations:[]}));
  expect(result.skus.get(variant.sku)?.variant).toMatchObject({studioFormat:'surface-print-v1',templateRevision:'2'});
  expect((await fetchCatalog(async()=>Response.json({products:[],entries:[],recommendations:[]}))).products).toHaveLength(0);
});
