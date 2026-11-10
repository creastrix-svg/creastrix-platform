import { expect, it } from 'vitest';
import { matchCatalog, toggleFacet, type CatalogData } from './catalogBrowse';

it('matches unrelated offers by their own formats, capabilities and finishes, not their names or routes', () => {
  const sand = { color: { id: 'sand', label: 'Sand', swatch: '#cca' }, cents: 3000, discount: null, stock: 2, photo: '/tiles/sand', search: '?finish=s' };
  const fern = { color: { id: 'fern', label: 'Fern', swatch: '#686' }, cents: 2000, previousCents: 2500, discount: 20,
    stock: 1, photo: '/tiles/fern', search: '?finish=f' };
  const catalog: CatalogData = {
    categories: [{ id: 'textiles', label: 'Textiles' }], techniques: [{ id: 'woven', label: 'Woven' }],
    offers: [
      { name: 'Throw', href: '/textiles/throw', details: 'Wool', format: 'ready', personalizable: true,
        technique: 'woven', categories: ['textiles'], finishes: [sand, fern] },
      { name: 'Runner', href: '/textiles/runner', details: 'Linen', format: 'made-to-order', personalizable: false,
        technique: 'woven', categories: ['textiles'], finishes: [sand] },
    ],
    concepts: [{ name: 'Sketch', href: '/ideas/sketch', caption: 'A study', photo: '/sketch', categories: ['textiles'] }],
  };
  const browse = (query: string) => matchCatalog(new URLSearchParams(query), 'en', catalog);
  const creative = browse('category=textiles&group=custom-or-made');
  expect(creative.offers.map(o => o.name)).toEqual(['Throw', 'Runner']);
  expect(creative.total).toBe(2);
  expect(creative.concepts).toEqual([]);
  expect(creative.counts.color).toEqual({ sand: 2, fern: 1 });
  const plain = browse('format=made-to-order&custom=no');
  expect(plain.offers.map(o => o.name)).toEqual(['Runner']);
  expect(browse('format=made-to-order&custom=yes').total).toBe(0);
  expect(browse('sale=1&color=sand').total).toBe(0);
  const sale = browse('format=ready&custom=yes&technique=woven&color=fern&sale=1');
  expect(sale.offers).toMatchObject([{ href: '/textiles/throw', minCents: 2000, stock: 1, finishes: [fern] }]);
  expect(toggleFacet(new URLSearchParams(), 'color', 'fern', sale.options).toString()).toBe('color=fern');
});
