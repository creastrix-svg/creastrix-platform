import type { StorefrontLocale } from './locale';
import { mediaForVariant, loopName, loopVariants } from './fixtures/loop';
import { conceptItems } from './fixtures/conceptItems';
import { connectedEntries, connectedOffer, entryForId } from './catalog/localCatalog';
import { thumbnailFor } from './catalog/photoRenditions';
import { money } from './productDisplay';
import { productCopy } from './copy';
import { editionCopy, editionItems } from './fixtures/editionCatalog';
import { tideOffer } from './fixtures/tideOffer';


type SearchResult = {
  kind: 'product' | 'concept' | 'variant' | 'edition';
  id: string;
  name: string;
  description: string;
  href: string;
  photo: string;
  cents?: number;
};

/** A suggestion and its full result should describe the same item and offer status. */
export function searchResultSummary(item: SearchResult, locale: StorefrontLocale) {
  return [item.description, item.kind === 'concept' ? productCopy[locale].concept : item.kind === 'edition' ? editionCopy[locale].status : null,
    item.cents !== undefined ? money(item.cents, locale) : null].filter(Boolean).join(' · ');
}

const normal = (value: string, language: StorefrontLocale) => value.normalize('NFKD').replace(/\p{M}/gu, '').toLocaleLowerCase(language).replace(/[^\p{L}\p{N}]+/gu, ' ').trim();

function rank(term: string, words: string[], name: string, description: string) {
  if (name === term) return 0;
  if (name.startsWith(term)) return 1;
  if (words.every(word => name.split(' ').some(part => part.startsWith(word)))) return 2;
  if (name.includes(term)) return 3;
  return words.every(word => (name + ' ' + description).includes(word)) ? 4 : Infinity;
}

export function searchCatalog(query: string, language: StorefrontLocale, limit?: number) {
  const trimmed = query.slice(0, 100).trim(), term = normal(trimmed, language);
  if (trimmed && !term) return [];
  const words = term.split(' ').filter(Boolean), subtitle = entryForId('loop')?.title[language] ?? productCopy[language].subtitle;
  const live = connectedEntries();
  const catalog: SearchResult[] = live ? live.map(e => ({
    id: e.kind === 'edition' ? 'edition-' + e.id : e.id, name:e.name, href:e.href, photo:e.thumb ?? '',
    kind:e.kind === 'offer' ? 'product' : e.kind, description:[e.title[language], e.material[language]].filter(Boolean).join(' · '),
    ...(e.kind === 'offer' && {cents:Math.min(...connectedOffer(e.id)!.variants.map(v => v.cents))}),
  })) : [
    { kind: 'product', id: 'loop', name: loopName(), href: '/products/loop', photo: mediaForVariant('cobalt').photos[0] ?? '', description: subtitle },
    ...conceptItems(language).map(item => ({
      kind: 'concept' as const, id:item.id, name:item.name, href:item.href, photo:item.thumb, description:item.title,
    })),
    ...editionItems(language).map((item): SearchResult => ({
      ...(item.id === tideOffer.id ? { kind: 'product', id: tideOffer.id, cents: tideOffer.unitCents } : { kind: 'edition', id: 'edition-' + item.id }),
      name: item.name, href: item.href,
      photo: item.thumb, description: item.title + ' · ' + item.material,
    })),
  ];
  const scored = catalog.map(item => ({ item, score: rank(term, words, normal(item.name, language), normal(item.description, language)) }));

  // Generic searches show one product, not four duplicates. A colour-specific search opens the matching finish directly.
  for (const variant of loopVariants()) {
    const label = variant.name[language], colour = normal(label, language);
    const colourWords = colour.split(' ').filter(word => word.length > 2);
    if (!words.some(word => colourWords.some(part => part.startsWith(word)))) continue;
    const item: SearchResult = {
      kind: 'variant', id: 'loop-' + variant.id, name: loopName() + ' · ' + label, description: subtitle,
      href: '/products/loop?variant=' + variant.id, photo: thumbnailFor(mediaForVariant(variant.id).photos[0] ?? ''), cents: variant.cents,
    };
    const description = normal(subtitle, language);
    scored.push({ item, score: Math.min(rank(term, words, normal(loopName(), language) + ' ' + colour, description), rank(term, words, colour, description)) });
  }
  return scored.filter(({ score }) => Number.isFinite(score)).sort((a, b) => a.score - b.score)
    .slice(0, limit === undefined ? undefined : Math.max(0, limit)).map(({ item }) => item);
}
