import { fireEvent, render, screen, within } from '@testing-library/react';
import { StrictMode } from 'react';
import { Provider } from 'react-redux';
import { Link, MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { createAppStore } from '../../store';
import App from '../../App';
import { browseCatalog } from '../browse/catalogFixtures';
import { cartLinePrice } from '../demoStore';
import { cartSkuStock } from '../cartSku';
import { mediaForVariant } from '../fixtures/loop';
import { catalogEndpoint, connectedMode, fetchCatalog, parseLocalCatalog, ratingForPath, storefrontCatalog } from './localCatalog';
import { parseCatalogEntries } from './catalogEntries';
import { expandSellerShelf, lowerShelves } from './recommendationShelves';
import { editionItems } from '../fixtures/editionCatalog';
import { homeTiles } from '../home/homeFeatured';
import { railItems } from '../detail/carouselContent';
import { searchCatalog } from '../search';
import { isStorefrontPath } from '../routes';
import { cartReturnOfferPath } from '../cart/cartOfferView';

vi.hoisted(() => { vi.stubEnv('MODE', 'local-catalog'); });

const fixture = () => {
  const color = (id: string, sku: string) => ({ id, sku, cents: 3400, previousCents: null as number | null, stock: 7,
    colors: ['#ffeedd'], checkTone: 'light', name: {en:'Finish',de:'Farbe',ru:'Цвет',pl:'Kolor',fr:'Couleur',es:'Color',it:'Colore'},
    photos: ['/product/changed.webp'], model: null as string | null });
  const loop = { id: 'loop', name: 'Database pot', href: '/products/loop', personalizable: true, designFeeCents: 750,
    currency: 'EUR', variants: ['cobalt', 'coral', 'chalk', 'cobalt-chalk'].map(id => color(id, id)) };
  loop.variants[2] = { ...loop.variants[2], cents: 1275, previousCents: 1600, stock: 2 };
  const tide = { id:'tide', name:'Database cup', href:'/collections/objects/tide', personalizable:false,
    currency:'EUR', designFeeCents:0, variants:[{ ...color('cobalt','tide-cobalt'), cents:835, stock:6 }] };
  const labels = (value: string) => ({ en:value, de:value, es:value, fr:value, it:value, pl:value, ru:value });
  const presentation = (id: string, kind: string, name: string, href: string, order: number | null) => ({
    id, kind, name, href, title: labels('Local title ' + id), material: labels('Local stone'), description: labels('Catalog description ' + id),
    photo:'/shop-edition/halo.webp', thumb:'/shop-edition/halo-small.webp', categories:['desk'], homeOrder:order, editionOrder:order,
    collectionId: id === 'loop' || id === 'nebula' ? 'maker-series' : null,
    collectionName: id === 'loop' || id === 'nebula' ? 'Maker Series' : null,
    rating:{count:id === 'loop' ? 2 : 0,average:id === 'loop' ? 4.5 : null},
  });
  const entries = [presentation('loop','offer',loop.name,loop.href,null),presentation('tide','offer',tide.name,tide.href,2),
    presentation('nebula','offer','Nebula','/collections/objects/nebula',1),
    presentation('ring','concept','Ring study','/preview/items/ring',null)];
  entries[1].editionOrder = null;
  const nebula = {...tide,id:'nebula',name:'Nebula',href:entries[2].href,variants:[{...color('standard','nebula-standard'),cents:7800}]};
  return { products:[loop, tide, nebula], entries, recommendations:[
    {productId:'loop',section:'related',itemIds:['tide']},
    {productId:'loop',section:'studio',itemIds:['nebula']},
    {productId:'tide',section:'studio',itemIds:['loop']},
  ] };
};
const ok = (raw: unknown) => new Response(JSON.stringify(raw), { headers: {'content-type': 'application/json; charset=utf-8'} });

describe('local catalog contract', () => {
  it.each(['/products/section-pick', '/products/reviews', '/products/reviews/reviews',
    '/products/reviews/reviews/new/'])('selects the storefront shell for %s', path => {
    expect(isStorefrontPath(path)).toBe(true);
  });
  it('reserves every ranked recommendation, even past the first viewport, when expanding the seller rail', () => {
    const raw = fixture(), { entries } = parseCatalogEntries(raw, raw.products);
    const [source, cup, sibling, concept] = entries;
    const opening = ['a','b','c','d'].map(id => ({...sibling,id}));
    const related = ['r1','r2','r3','r4','r5','r6'].map(id => ({...cup,id}));
    const assortment = [source, cup, concept, ...opening, ...related, sibling];
    const lower = lowerShelves(source.id, opening, related, [source, concept, ...opening, ...related, cup]);
    expect(lower.related.map(e => e.id)).toEqual(['r1','r2','r3','r4','r5','r6']);
    expect(lower.section.map(e => e.id)).toEqual([cup.id]);
    const ids = expandSellerShelf(source, opening, [...lower.related, ...lower.section], assortment).map(e => e.id);
    expect(ids).toEqual(['a','b','c','d',sibling.id]);
    expect(expandSellerShelf(source, [], [], assortment)).toEqual([]);
  });
  it('keeps all remaining section items after text matches without repeating either source', () => {
    const data = fixture(), [source, other, sibling, concept] = parseCatalogEntries(data, data.products).entries;
    const opening = [{...sibling, id:'opening'}], reserve = ['c1','c2','c3','c4','c5'].map(id => ({...other,id}));
    const groups = lowerShelves(source.id, opening, [source, ...opening, sibling, concept, sibling],
      [...opening, source, sibling, ...reserve]);
    expect(groups.related.map(e => e.id)).toEqual([sibling.id]);
    expect(groups.section.map(e => e.id)).toEqual(['c1','c2','c3','c4','c5']);
    expect(expandSellerShelf(source, opening, [...groups.related, ...groups.section], [...opening, ...reserve, other, sibling, source, concept])
      .map(e => e.id)).toEqual(['opening',other.id]);
  });
  it('parses additional products without manufacturing a legacy card or combining equal finish IDs', () => {
    const data = fixture(), cup = data.products[1];
    data.products.push({...cup, id:'new-cup', href:'/products/cup', variants:[{...cup.variants[0], sku:'other-cobalt'}]});
    const rows = parseLocalCatalog(data), result = storefrontCatalog(rows);
    expect(rows.map(p => p.id)).toEqual(['loop','tide','nebula','new-cup']);
    expect(result.loop.variants[0].sku).toBe('cobalt');
    expect(result.tide.variants[0].sku).toBe('tide-cobalt');
  });
  it.each([
    ['duplicate SKU', (d: ReturnType<typeof fixture>) => { d.products[1].variants[0].sku = 'cobalt'; }],
    ['SKU reserved for a saved design', (d: ReturnType<typeof fixture>) => { d.products[2].variants[0].sku = 'design-1'; }],
    ['unsafe media URL', (d: ReturnType<typeof fixture>) => { d.products[0].variants[0].photos = ['//example.invalid/photo.webp']; }],
    ['invalid price', (d: ReturnType<typeof fixture>) => { d.products[0].variants[0].cents = -1; }],
    ['unsafe quantity', (d: ReturnType<typeof fixture>) => { d.products[0].variants[0].stock = 1_000_001; }],
    ['non-product route', (d: ReturnType<typeof fixture>) => { d.products[2].href = '/account'; }],
    ['missing variants', (d: ReturnType<typeof fixture>) => { d.products[2].variants = []; }],
  ] as const)('rejects %s before publishing a snapshot', (_label, corrupt) => {
    const data = fixture(); corrupt(data); expect(() => parseLocalCatalog(data)).toThrow('LOCAL_CATALOG_INVALID');
  });
  it('rejects non-JSON (e.g. a missing proxy returning the SPA)', async () => {
    await expect(fetchCatalog(async () => new Response('<html>', {headers: {'content-type':'text/html'}}))).rejects.toThrow('LOCAL_CATALOG_UNAVAILABLE');
  });
  it.each([0,1])('never lists a finish that the fixed detail adapter silently replaces (%s)', index => {
    const data = fixture(), product = data.products[index];
    product.variants.push({...product.variants[0], id:'extra', sku:product.id + '-extra'});
    expect(() => storefrontCatalog(parseLocalCatalog(data))).toThrow('LOCAL_CATALOG_INVALID');
  });
  it('rejects incomplete entries, ambiguous ratings and dangling recommendation targets', () => {
    const data = fixture(), parsed = parseCatalogEntries(data, data.products);
    expect(parsed.entries).toHaveLength(4);
    expect(() => parseCatalogEntries({products:data.products},data.products)).toThrow();
    data.recommendations[0].itemIds.push('unknown');
    expect(() => parseCatalogEntries(data,data.products)).toThrow();
    data.recommendations[0].itemIds.pop();
    data.entries[0].rating.average = null;
    expect(() => parseCatalogEntries(data,data.products)).toThrow();
  });
  it('requires a collection link to have both a safe catalog key and a display name', () => {
    const data = fixture();
    expect(parseCatalogEntries(data, data.products).byId.get('tide')).toMatchObject({ collectionId:null, collectionName:null });
    for (const pair of [
      {collectionId:'loop', collectionName:null}, {collectionId:null, collectionName:'Loop'},
      {collectionId:'bad&format=ready', collectionName:'Loop'}, {collectionId:'loop', collectionName:'  '},
    ]) {
      Object.assign(data.entries[0], pair);
      expect(() => parseCatalogEntries(data, data.products)).toThrow('LOCAL_CATALOG_INVALID');
    }
  });
});

it('gates both existing cards on one real read; failure stays explicit, retry installs different server facts', async () => {
  expect(connectedMode).toBe(true);
  const data = fixture();
  const extra = {...data.entries[2], id:'section-pick', name:'Section pick', href:'/products/reviews',
    collectionId:null, collectionName:null, editionOrder:null, homeOrder:null};
  data.entries.push(extra);
  data.products.push({...data.products[2],id:extra.id,name:extra.name,href:extra.href,
    variants:[{...data.products[2].variants[0], sku:'section-pick-standard'},
      {...data.products[2].variants[0], id:'red', sku:'section-pick-red'}]});
  data.recommendations.push({productId:'loop',section:'section',itemIds:[extra.id]});
  const request = vi.spyOn(window, 'fetch').mockResolvedValueOnce(new Response(null, {status: 503})).mockResolvedValueOnce(ok(data));
  const store = createAppStore('en');
  render(<StrictMode><Provider store={store}><MemoryRouter initialEntries={['/catalog']}>
    <Link to="/products/loop?variant=chalk">Open test pot</Link><Link to="/collections/objects/tide">Open test cup</Link>
    <Link to="/collections/objects">Open test collection</Link>
    <App /></MemoryRouter></Provider></StrictMode>);
  expect(await screen.findByRole('alert')).toHaveTextContent('could not be loaded');
  expect(screen.queryByText('Loop', {exact:true})).toBeNull();
  fireEvent.click(screen.getByRole('button', {name:'Try again'}));
  expect(await screen.findByText('Database pot', {exact:true})).toBeVisible();
  expect(homeTiles('en','desk').map(e => e.name)).toEqual(['Nebula','Database cup']);
  // Removing the cup from the editorial selection must not remove the offer.
  expect(editionItems('ru').map(e => e.title)).toEqual(['Local title nebula']);
  expect(searchCatalog('Nebula', 'en')[0]).toMatchObject({href:'/collections/objects/nebula',kind:'product',cents:7800});
  expect(searchCatalog('Halo', 'en')).toHaveLength(0);
  expect(browseCatalog(new URLSearchParams('format=concept'),'en').concepts.map(e => e.name)).toEqual(['Ring study']);
  expect(browseCatalog(new URLSearchParams('format=ready'),'en').offers.find(e => e.name === 'Nebula')).toMatchObject({minCents:7800,stock:7});
  expect(railItems('nebula','studio','en')).toEqual([]);
  expect(railItems('loop','related','en')?.map(e => e.note)).toEqual(['Local title tide']);
  const collection = browseCatalog(new URLSearchParams('collection=maker-series'), 'en');
  expect(collection.offers.map(e => e.name).sort()).toEqual(['Database pot','Nebula']);
  expect(collection.concepts).toHaveLength(0);
  expect(browseCatalog(new URLSearchParams('collection=loop'), 'en').total).toBe(0);
  expect(ratingForPath('/products/loop/?variant=chalk#section')).toEqual({count:2,average:4.5});
  const offer = browseCatalog(new URLSearchParams('sale=1'), 'en').offers[0];
  expect(offer.minCents).toBe(1275);
  expect(offer.finishes[0].stock).toBe(2);
  fireEvent.click(screen.getByRole('link', {name:'Nebula'}));
  expect(await screen.findByRole('heading', {name:'Nebula'})).toBeVisible();
  expect(screen.queryByText('Design preview',{exact:true})).toBeNull();
  const add = screen.getByRole('button',{name:'Add to cart'});
  fireEvent.click(add);
  expect(store.getState().productDemo.cart).toContainEqual(expect.objectContaining({variant:'nebula-standard',quantity:1}));
  fireEvent.click(screen.getByText('Open test pot'));
  expect(await screen.findByRole('heading', {name:'Database pot'})).toBeVisible();
  const targets = (section: string) => within(within(screen.getByRole('region', {name:section})).getByRole('list'))
    .getAllByRole('link').map(link => link.getAttribute('href'));
  expect(targets('More from this seller')).toEqual(['/collections/objects/nebula']);
  expect(targets('You may also like')).toEqual(['/collections/objects/tide', extra.href]);
  expect(screen.queryByRole('region', {name:'More from this section'})).toBeNull();
  const sellerLink = screen.getByRole('link', {name:'More from this seller'});
  expect(sellerLink).toHaveTextContent('Maker Series');
  expect(sellerLink).toHaveAttribute('href','/catalog?collection=maker-series');
  expect(screen.getAllByText('€12.75').length).toBeGreaterThan(0);
  expect(mediaForVariant('chalk')).toMatchObject({model:null,photos:['/product/changed.webp']});
  expect(document.title).toContain('Database pot');
  expect(cartLinePrice({variant:'chalk'}).unitCents).toBe(1275);
  expect(cartSkuStock('chalk')).toBe(2);
  expect(isStorefrontPath(extra.href)).toBe(true);
  expect(isStorefrontPath(extra.href + '/reviews')).toBe(true);
  expect(cartReturnOfferPath(extra.href + '?variant=red&next=/account')).toBe(extra.href + '?variant=red');
  expect(cartReturnOfferPath(extra.href + '?variant=retired')).toBe(extra.href);
  const continuation = within(screen.getByRole('region', {name:'You may also like'}))
    .getAllByRole('link').find(link => link.getAttribute('href') === extra.href);
  expect(continuation).toBeDefined();
  fireEvent.click(continuation!);
  expect(await screen.findByRole('heading', {name:extra.name})).toBeVisible();
  const review = within(screen.getByRole('main')).getAllByRole('link')
    .find(link => link.getAttribute('href')?.split('?')[0] === extra.href + '/reviews');
  expect(review).toBeDefined();
  fireEvent.click(review!);
  expect(await screen.findByRole('heading', {level:1, name:/Section pick/i})).toBeVisible();
  const back = within(screen.getByRole('main')).getAllByRole('link').find(link => link.getAttribute('href')?.split('?')[0] === extra.href);
  expect(back).toBeDefined(); fireEvent.click(back!);
  expect(await screen.findByRole('heading', {name:extra.name})).toBeVisible();
  fireEvent.click(screen.getByText('Open test cup'));
  expect(await screen.findByRole('heading', {name:'Database cup'})).toBeVisible();
  expect(targets('More from this seller')).toEqual(['/products/loop','/collections/objects/nebula',extra.href]);
  expect(within(screen.getByRole('main')).getAllByText('€8.35').length).toBeGreaterThan(0);
  expect(cartSkuStock('tide-cobalt')).toBe(6);
  fireEvent.click(screen.getByText('Open test collection'));
  expect(await screen.findByRole('link', {name:/Nebula.*€78\.00/})).toBeVisible();
  expect(request).toHaveBeenCalledTimes(2);
  expect(request).toHaveBeenCalledWith(catalogEndpoint,expect.objectContaining({cache:'no-store',credentials:'omit',redirect:'error'}));
});
