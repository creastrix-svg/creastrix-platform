import { expect, it } from 'vitest';
import { searchCatalog } from './search';
import { storefrontLocales } from './locale';
import { concepts, mediaForVariant, variants } from './fixtures/loop';
import { editionItems } from './fixtures/editionCatalog';

it('ranks exact/prefix names first and returns at most five deterministic suggestions', () => {
  expect(searchCatalog('  LOOP MINI ', 'en')[0].id).toBe('loop-mini');
  const results = searchCatalog('Loop', 'en', 5);
  expect(results).toHaveLength(5);
  expect(results[0].id).toBe('loop');
  expect(searchCatalog('Loop S', 'en')[0].id).toBe('loop-stand');
});
it('matches the current DE/EN descriptions, including diacritics, not only product names', () => {
  expect(searchCatalog('plant pot', 'en').map(item => item.id)).toContain('loop');
  expect(searchCatalog('Pflanztopf', 'de').map(item => item.id)).toContain('loop');
  expect(searchCatalog('pflanzenstander', 'de')[0].id).toBe('loop-stand');
  expect(searchCatalog('Untersetzer', 'de')[0].id).toBe('loop-tray');
  expect(searchCatalog('Untersetzer', 'en')).toHaveLength(0);
});
it('returns only available matches and honours an explicit zero limit', () => {
  expect(searchCatalog('nothing-matches', 'en')).toHaveLength(0);
  expect(searchCatalog('', 'en', 1000)).toHaveLength(1 + concepts.length + editionItems('en').length);
  expect(searchCatalog('Loop', 'en', 0)).toHaveLength(0);
});

it.each(storefrontLocales)('finds each localized colour alone or with its product name in %s', language => {
  for (const variant of variants) {
    for (const query of [variant.name[language], 'Loop ' + variant.name[language]]) {
      expect(searchCatalog(query, language)[0]).toMatchObject({
        kind: 'variant', href: '/products/loop?variant=' + variant.id, photo: mediaForVariant(variant.id).photos[0], cents: variant.cents,
      });
    }
  }
});

it('offers matching finishes only for colour intent and never assigns a price to a design concept', () => {
  expect(searchCatalog('c', 'en').map(item => item.href)).toContain('/products/loop?variant=coral');
  const coralOrCork = searchCatalog('cor', 'en');
  expect(coralOrCork.filter(item => item.kind === 'variant').map(item => item.href)).toEqual(['/products/loop?variant=coral']);
  expect(coralOrCork.find(item => item.href === '/collections/objects/hex')).toMatchObject({kind: 'edition', description: expect.stringContaining('cork')});
  expect(searchCatalog('Loop chalk', 'en').map(item => item.href)).toEqual(['/products/loop?variant=chalk', '/products/loop?variant=cobalt-chalk']);
  expect(searchCatalog('Loo', 'en', 7).filter(item => item.kind === 'variant')).toHaveLength(0);
  expect(searchCatalog('plant pot', 'en').filter(item => item.kind === 'variant')).toHaveLength(0);
  expect(searchCatalog('loop mini', 'en')[0]).toMatchObject({ kind: 'concept', href: '/preview/items/loop-mini' });
  expect(searchCatalog('loop mini', 'en')[0]).not.toHaveProperty('cents');
  expect(searchCatalog('@@', 'en')).toEqual([]);
});
