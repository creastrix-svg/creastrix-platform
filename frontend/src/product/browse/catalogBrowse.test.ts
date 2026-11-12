import { describe, expect, it } from 'vitest';
import { concepts, variants } from '../fixtures/loop';
import { editionItems } from '../fixtures/editionCatalog';
import { editionDepartments, filterHomeEdition, homeOfferInSection, shelfCatalogHref } from '../home/homeFeatured';
import { shelfFilters } from '../home/homeShelfCopy';
import { belongsToCustomOrMade, clearBrowse, toggleFacet } from './catalogBrowse';
import { browseCatalog } from './catalogFixtures';
import { storefrontLocales, type StorefrontLocale } from '../locale';
import { tideOffer } from '../fixtures/tideOffer';

const browse = (query = '', locale: StorefrontLocale = 'en') => browseCatalog(new URLSearchParams(query), locale);
const ideas = (locale: StorefrontLocale = 'en') => [...editionItems(locale).filter(item => item.id !== tideOffer.id), ...concepts];
const conceptCount = ideas().length, allCount = 2 + conceptCount;
const loopFinishes = (query: string) => browse(query).offers.find(o => o.name === 'Loop')?.finishes.map(f => f.color.id) ?? [];
const offers = (query: string) => browse(query).offers.map(o => o.name);

describe('local buyer catalog facets', () => {
  it('limits a shared Loop collection to its product and the six studio designs, including in facet counts', () => {
    const family = browse('collection=loop');
    expect(family.offers.map(offer => offer.href)).toEqual(['/products/loop']);
    expect(family.concepts.map(item => item.href)).toEqual([
      'loop-mini', 'loop-wide', 'loop-tray', 'loop-tall', 'loop-duo', 'loop-stand',
    ].map(id => '/preview/items/' + id));
    expect(family).toMatchObject({ hasCollection: true, filterCount: 1, total: 7, counts: { format: { ready: 1, concept: 6 } } });
    expect(browse('collection=loop&category=kitchen').total).toBe(0);
    expect(loopFinishes('collection=loop&group=custom-or-made&sale=1')).toEqual(['chalk']);
  });

  it.each(['collection=tide', 'collection=', 'collection=loop&collection=other'])('does not broaden an unresolved %s', query => {
    expect(browse(query)).toMatchObject({ hasCollection: true, total: 0, filterCount: 1, offers: [], concepts: [],
      counts: { format: { ready: 0, concept: 0 } } });
  });

  it('includes each creative path independently while excluding ordinary stock and view-only concepts', () => {
    for (const facets of [{ format: 'ready', custom: 'yes' }, { format: 'made-to-order', custom: 'no' }, { format: 'file' }]) {
      expect(belongsToCustomOrMade(facets)).toBe(true);
    }
    for (const facets of [{ format: 'ready', custom: 'no' }, { format: 'concept', custom: 'no' }, {}]) {
      expect(belongsToCustomOrMade(facets)).toBe(false);
    }
    const result = browse('group=custom-or-made');
    expect(result).toMatchObject({ group: 'custom-or-made', filterCount: 1, total: 1, concepts: [] });
    expect(result.counts.format).toEqual({ ready: 1, 'made-to-order': 0, file: 0, concept: 0 });
    expect(offers('group=custom-or-made')).toEqual(['Loop']);
    expect(loopFinishes('group=custom-or-made&sale=1')).toEqual(['chalk']);
    expect(browse('group=custom-or-made&format=concept').total).toBe(0);
    expect(browse('group=invalid')).toMatchObject({ group: null, filterCount: 0, total: allCount });
  });

  it('counts two physical products and keeps the remaining concepts out of commercial facets', () => {
    const result = browse();
    expect(result.total).toBe(allCount);
    expect(result.counts.format).toEqual({ ready: 2, 'made-to-order': 0, file: 0, concept: conceptCount });
    expect(result.counts.custom).toEqual({ yes: 1, no: conceptCount + 1 });
    expect(result.counts.technique['3d-print']).toBe(1);
    expect(result.counts.color).toEqual({ cobalt: 2, coral: 1, chalk: 1, 'cobalt-chalk': 1 });
    expect(offers('format=ready')).toEqual(['Loop', 'Tide']);
    expect(offers('format=ready&custom=yes')).toEqual(['Loop']);
    expect(offers('format=ready&custom=no')).toEqual(['Tide']);
    expect(offers('format=ready&custom=yes&custom=no')).toEqual(['Loop', 'Tide']);
    expect(offers('format=ready&technique=3d-print')).toEqual(['Loop']);
    expect(browse('format=file').total).toBe(0);
    expect(browse('format=made-to-order').total).toBe(0);
    expect(browse('format=concept&custom=yes').total).toBe(0);
    expect(browse('custom=no').concepts.map(i => i.name)).toEqual(ideas().map(i => i.name));
    expect(browse('format=concept&technique=3d-print').total).toBe(0);
  });

  it('uses OR within a group, AND across groups, and leaves other options reachable', () => {
    const result = browse('format=ready&custom=yes&color=coral&color=chalk&color=chalk');
    expect(loopFinishes('format=ready&custom=yes&color=coral&color=chalk&color=chalk')).toEqual(['coral', 'chalk']);
    expect(result.total).toBe(1);
    expect(result.filterCount).toBe(4);
    expect(result.counts.color).toEqual({ cobalt: 1, coral: 1, chalk: 1, 'cobalt-chalk': 1 });
    expect(result.counts.custom).toEqual({ yes: 1, no: 0 });
    expect(result.counts.format.concept).toBe(0);
    expect(browse('format=ready&format=concept').total).toBe(allCount);
    expect(loopFinishes('color=chalk')).toEqual(['chalk']); // A two-tone finish is its own option.
    expect(browse('format=concept&format=file').total).toBe(conceptCount);
  });

  it('retains category, sale and price-sort behavior while ignoring unknown values without inventing matches', () => {
    expect(browse('category=living').total).toBe(1 + concepts.length + editionItems('en').filter(i => editionDepartments(i.id).includes('home')).length);
    expect(offers('category=gifts&format=ready')).toEqual(['Loop', 'Tide']);
    expect(offers('category=kitchen&format=ready')).toEqual(['Tide']);
    expect(browse('category=gifts&format=concept').concepts.map(item => item.name)).toContain('Folio');
    expect(browse('category=personal&custom=no').total).toBe(0);
    expect(loopFinishes('category=living&sale=1&sort=price-desc')).toEqual(['chalk']);
    const garbage = browse('format=constructor&category=__proto__&custom=toString&sort=popular');
    expect(garbage).toMatchObject({ total: allCount, sort: 'featured', category: null, filterCount: 0 });
    expect(browse('color=bogus&color=coral').filterCount).toBe(1);
  });

  it.each(shelfFilters)('carries the %s shelf selection through to real catalog results', section => {
    const link = shelfCatalogHref(section);
    const result = browse(link.split('?')[1]);
    const shelf = filterHomeEdition(editionItems('en'), section).map(item => item.href);
    expect(shelf.length).toBeGreaterThan(0);
    expect(result.filterCount).toBe(section === 'all' ? 0 : 1);
    const visible = result.concepts.map(item => item.href);
    expect(visible.filter(href => href.startsWith('/collections/objects/') && !href.endsWith('/drift'))).toEqual(shelf.filter(href => href !== tideOffer.href));
    expect(result.offers.some(offer => offer.href === tideOffer.href)).toBe(shelf.includes(tideOffer.href));
    expect(result.offers.some(offer => offer.name === 'Loop')).toBe(homeOfferInSection(section));
  });

  it('sorts the offers by displayed starting price or stock without reordering their finishes', () => {
    expect(offers('sort=price-asc')).toEqual(['Tide', 'Loop']);
    expect(offers('sort=price-desc')).toEqual(['Loop', 'Tide']);
    expect(offers('sort=stock-desc')).toEqual(['Loop', 'Tide']);
    expect(offers('sort=name-asc')).toEqual(['Loop', 'Tide']);
    for (const sort of ['stock-desc','price-asc','price-desc','name-asc']) {
      expect(loopFinishes('sort=' + sort)).toEqual(variants.map(v => v.id));
      expect(browse('sort=' + sort).total).toBe(allCount);
    }
    const cobalt = browse('format=ready&color=cobalt&sort=stock-desc').offers;
    expect(cobalt.map(({ name, stock }) => [name, stock])).toEqual([['Loop', 24], ['Tide', 18]]);
    expect(browse('color=coral').offers[0].stock).toBe(8);
    expect(browse('color=coral&sale=1').total).toBe(0);
  });

  it.each(storefrontLocales)('sorts visible names in buyer locale %s within their section', locale => {
    const result = browse('sort=name-asc', locale);
    const collator = new Intl.Collator(locale, { sensitivity: 'base', numeric: true });
    expect(result.offers.map(o => o.name)).toEqual(['Loop', 'Tide'].sort(collator.compare));
    expect(result.concepts.map(item => item.name)).toEqual(ideas(locale).map(item => item.name).sort(collator.compare));
  });

  it('updates without mutating the source and clears only owned parameters', () => {
    const original = new URLSearchParams('ref=spring&collection=loop&color=unknown&color=chalk&sale=1&sort=price-asc&group=custom-or-made');
    const { options } = browse();
    const combined = toggleFacet(original, 'color', 'coral', options);
    expect(combined.getAll('color')).toEqual(['coral', 'chalk']);
    expect(toggleFacet(combined, 'color', 'chalk', options).getAll('color')).toEqual(['coral']);
    expect(original.getAll('color')).toEqual(['unknown', 'chalk']);
    expect(toggleFacet(original, 'color', 'unknown', options).toString()).toBe(original.toString());
    expect(clearBrowse(combined).toString()).toBe('ref=spring');
    expect(combined.get('sale')).toBe('1');
  });
});
