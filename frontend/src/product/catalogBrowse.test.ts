import { describe, expect, it } from 'vitest';
import { concepts, variants } from './catalog';
import { editionItems } from './editionCatalog';
import { editionDepartments, filterHomeEdition, homeOfferInSection, shelfCatalogHref } from './homeFeatured';
import { shelfFilters } from './homeShelfCopy';
import { belongsToCustomOrMade, browseCatalog, clearBrowse, toggleFacet } from './catalogBrowse';
import { storefrontLocales, type StorefrontLocale } from './locale';

const browse = (query = '', locale: StorefrontLocale = 'en') => browseCatalog(new URLSearchParams(query), locale);
const ideas = (locale: StorefrontLocale = 'en') => [...editionItems(locale), ...concepts];
const conceptCount = ideas().length, allCount = 1 + conceptCount;
const ids = (query: string) => browse(query).finishes.map(item => item.id);

describe('local buyer catalog facets', () => {
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
    expect(ids('group=custom-or-made&sale=1')).toEqual(['chalk']);
    expect(browse('group=custom-or-made&format=concept').total).toBe(0);
    expect(browse('group=invalid')).toMatchObject({ group: null, filterCount: 0, total: allCount });
  });

  it('counts one physical product with four finishes plus each individual design concept without implying a file or fabrication offer', () => {
    const result = browse();
    expect(result.total).toBe(allCount);
    expect(result.counts.format).toEqual({ ready: 1, 'made-to-order': 0, file: 0, concept: conceptCount });
    expect(result.counts.custom).toEqual({ yes: 1, no: conceptCount });
    expect(result.counts.technique['3d-print']).toBe(1);
    expect(browse('format=file').total).toBe(0);
    expect(browse('format=made-to-order').total).toBe(0);
    expect(browse('format=concept&custom=yes').total).toBe(0);
    expect(browse('custom=no').concepts.map(i => i.name)).toEqual(ideas().map(i => i.name));
    expect(browse('format=concept&technique=3d-print').total).toBe(0);
  });

  it('uses OR within a group, AND across groups, and leaves other options reachable', () => {
    const result = browse('format=ready&custom=yes&color=coral&color=chalk&color=chalk');
    expect(result.finishes.map(item => item.id)).toEqual(['coral', 'chalk']);
    expect(result.filterCount).toBe(4);
    expect(result.counts.color).toEqual({ cobalt: 1, coral: 1, chalk: 1, 'cobalt-chalk': 1 });
    expect(result.counts.custom).toEqual({ yes: 1, no: 0 });
    expect(result.counts.format.concept).toBe(0);
    expect(browse('format=ready&format=concept').total).toBe(allCount);
    expect(ids('color=chalk')).toEqual(['chalk']); // A two-tone finish is its own option.
    expect(browse('format=concept&format=file').total).toBe(conceptCount);
  });

  it('retains category, sale and price-sort behavior while ignoring unknown values without inventing matches', () => {
    expect(browse('category=living').total).toBe(1 + concepts.length + editionItems('en').filter(i => editionDepartments(i.id).includes('home')).length);
    expect(browse('category=gifts&format=ready').total).toBe(1);
    expect(browse('category=gifts&format=concept').concepts.map(item => item.name)).toContain('Folio');
    expect(browse('category=personal&custom=no').total).toBe(0);
    expect(ids('category=living&sale=1&sort=price-desc')).toEqual(['chalk']);
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
    expect(visible.filter(href => href.startsWith('/collections/objects/') && !href.endsWith('/drift'))).toEqual(shelf);
    expect(result.finishes.length > 0).toBe(homeOfferInSection(section));
  });

  it('never reorders the finishes of a single listing just because the catalog sort changes', () => {
    for (const sort of ['stock-desc','price-asc','price-desc','name-asc']) {
      expect(ids('sort=' + sort)).toEqual(variants.map(v => v.id));
      expect(browse('sort=' + sort).total).toBe(allCount);
    }
    expect(browse('color=coral&sale=1').total).toBe(0);
  });

  it.each(storefrontLocales)('sorts visible names in buyer locale %s within their section', locale => {
    const result = browse('sort=name-asc', locale);
    const collator = new Intl.Collator(locale, { sensitivity: 'base', numeric: true });
    expect(result.finishes).toEqual(variants);
    expect(result.concepts.map(item => item.name)).toEqual(ideas(locale).map(item => item.name).sort(collator.compare));
  });

  it('updates without mutating the source and clears only owned parameters', () => {
    const original = new URLSearchParams('ref=spring&color=unknown&color=chalk&sale=1&sort=price-asc&group=custom-or-made');
    const combined = toggleFacet(original, 'color', 'coral');
    expect(combined.getAll('color')).toEqual(['coral', 'chalk']);
    expect(toggleFacet(combined, 'color', 'chalk').getAll('color')).toEqual(['coral']);
    expect(original.getAll('color')).toEqual(['unknown', 'chalk']);
    expect(toggleFacet(original, 'color', 'unknown').toString()).toBe(original.toString());
    expect(clearBrowse(combined).toString()).toBe('ref=spring');
    expect(combined.get('sale')).toBe('1');
  });
});
