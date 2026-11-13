import { expect, it, vi } from 'vitest';
import { catalogEndpoint, fetchCatalog } from './localCatalog';
vi.hoisted(() => { vi.stubEnv('MODE','account-cart-local'); });

const finishes = ['chalk','cobalt-chalk','coral','cobalt'].map(id => ({
  id,sku:id,label:id,cents:2400,stock:2,swatch:'#244bcc',photos:['/product/reference-asset-01.webp'],
  model:'/product/loop-shaped-cobalt.glb',secondSwatch:id==='cobalt-chalk'?'#f0eee8':null,
}));
const studio = { id:'loop',label:'Loop',currency:'EUR',templateRevision:'2',studioFormat:'loop-studio-v1',studioFeeCents:500,variants:finishes };

it('selects the versioned studio only for its complete offer and uses server preview references', async () => {
  const data = await fetchCatalog(async () => Response.json([studio]));
  expect(data.loop?.designFeeCents).toBe(500);
  expect(data.loop?.variants.map(v=>v.id)).toEqual(['cobalt','coral','chalk','cobalt-chalk']);
  expect(data.loop?.variants.at(-1)?.colors).toEqual(['#244bcc','#f0eee8']);
  expect(data.entries[0]?.photo).toBe('/product/reference-asset-01.webp');
});
it.each([{templateRevision:'3'},{studioFormat:'unknown'},{variants:finishes.slice(1)},
  {variants:finishes.map(v=>({...v,sku:'old-'+v.sku}))}])('does not invent a studio for incompatible offer data', async patch => {
  const data = await fetchCatalog(async () => Response.json([{...studio,...patch}]));
  expect(data.loop).toBeUndefined();
  expect(data.products[0]).toMatchObject({personalizable:false,designFeeCents:0});
});

it('renders an independent workspace offer without requiring either legacy sample record', async () => {
  const offer = {id:'kiln-cup',label:'Cup',currency:'EUR',templateRevision:'3',variants:[
    {id:'blue',sku:'kiln-blue',label:'Blue',cents:2475,stock:2,swatch:'#123456'},
  ]};
  expect(catalogEndpoint).toBe('/api/account-catalog/products');
  const value = await fetchCatalog(async () => Response.json([offer]));
  expect(value.products).toHaveLength(1);
  expect(value.products[0]).toMatchObject({id:offer.id,href:'/products/kiln-cup',templateRevision:'3',personalizable:false,
    variants:[{sku:'kiln-blue',cents:2475,stock:2}]});
  expect(value.loop).toBeUndefined();
  expect(value.tide).toBeUndefined();
  expect((await fetchCatalog(async () => Response.json([]))).products).toHaveLength(0);
});

it.each([120,121,200,201])('matches the persistent variant label boundary in Unicode characters: %i', async size => {
  const name = '🌿'.repeat(size);
  const payload = [{id:'bowl',label:'Bowl',currency:'EUR',templateRevision:null,variants:[
    {id:'green',sku:'bowl-green',label:'Green',cents:2,stock:3,swatch:'#345633'},
  ]}, {id:'cup',label:'Cup',currency:'EUR',templateRevision:null,variants:[
    {id:'leaf',sku:'cup-leaf',label:name,cents:1,stock:1,swatch:'#abcdef'},
  ]}];
  const result = fetchCatalog(async () => Response.json(payload));
  if (size > 200) await expect(result).rejects.toThrow('LOCAL_CATALOG_INVALID');
  else {
    const snapshot = await result;
    expect(snapshot.products.map(product => product.id)).toEqual(['bowl','cup']);
    expect(snapshot.products[1].variants[0].name.en).toBe(name);
  }
});
