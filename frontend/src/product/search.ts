import type { StorefrontLocale } from './locale';
import { concepts, mediaForVariant, variants } from './fixtures/loop';
import { money } from './productDisplay';
import { productCopy } from './copy';
import { editionCopy, editionItems } from './fixtures/editionCatalog';
import { tideOffer } from './fixtures/tideOffer';

const descriptions: Record<string, Record<StorefrontLocale, string>> = {
  'loop-mini': { en: 'Compact plant pot', de: 'Kompakter Pflanztopf', ru: 'Компактный горшок', pl: 'Mała doniczka', es: 'Maceta compacta', fr: 'Pot compact', it: 'Vaso compatto' },
  'loop-wide': { en: 'Wide plant pot', de: 'Breiter Pflanztopf', ru: 'Широкий горшок', pl: 'Szeroka doniczka', es: 'Maceta ancha', fr: 'Pot large', it: 'Vaso largo' },
  'loop-tray': { en: 'Plant saucer', de: 'Untersetzer', ru: 'Подставка для горшка', pl: 'Podstawka pod doniczkę', es: 'Plato para maceta', fr: 'Soucoupe', it: 'Sottovaso' },
  'loop-tall': { en: 'Tall plant pot', de: 'Hoher Pflanztopf', ru: 'Высокий горшок', pl: 'Wysoka doniczka', es: 'Maceta alta', fr: 'Pot haut', it: 'Vaso alto' },
  'loop-duo': { en: 'Double plant pot', de: 'Doppelter Pflanztopf', ru: 'Двойной горшок', pl: 'Podwójna doniczka', es: 'Maceta doble', fr: 'Pot double', it: 'Vaso doppio' },
  'loop-stand': { en: 'Plant stand', de: 'Pflanzenständer', ru: 'Подставка для растений', pl: 'Stojak na rośliny', es: 'Soporte para plantas', fr: 'Support pour plantes', it: 'Supporto per piante' },
};
export const conceptDescription = (id: string, locale: StorefrontLocale) => descriptions[id]?.[locale] ?? productCopy[locale].concept;

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
  const words = term.split(' ').filter(Boolean), subtitle = productCopy[language].subtitle;
  const catalog: SearchResult[] = [
    { kind: 'product', id: 'loop', name: 'Loop', href: '/products/loop', photo: mediaForVariant('cobalt').photos[0], description: subtitle },
    ...concepts.map(({ id, name, image }) => ({
      kind: 'concept' as const, id, name, href: '/preview/items/' + id, photo: '/product/reference-asset-' + image + '.webp', description: conceptDescription(id, language),
    })),
    ...editionItems(language).map((item): SearchResult => ({
      ...(item.id === tideOffer.id ? { kind: 'product', id: tideOffer.id, cents: tideOffer.unitCents } : { kind: 'edition', id: 'edition-' + item.id }),
      name: item.name, href: item.href,
      photo: item.thumb, description: item.title + ' · ' + item.material,
    })),
  ];
  const scored = catalog.map(item => ({ item, score: rank(term, words, normal(item.name, language), normal(item.description, language)) }));

  // Generic searches show one product, not four duplicates. A colour-specific search opens the matching finish directly.
  for (const variant of variants) {
    const label = variant.name[language], colour = normal(label, language);
    const colourWords = colour.split(' ').filter(word => word.length > 2);
    if (!words.some(word => colourWords.some(part => part.startsWith(word)))) continue;
    const item: SearchResult = {
      kind: 'variant', id: 'loop-' + variant.id, name: 'Loop · ' + label, description: subtitle,
      href: '/products/loop?variant=' + variant.id, photo: mediaForVariant(variant.id).photos[0], cents: variant.cents,
    };
    const description = normal(subtitle, language);
    scored.push({ item, score: Math.min(rank(term, words, 'loop ' + colour, description), rank(term, words, colour, description)) });
  }
  return scored.filter(({ score }) => Number.isFinite(score)).sort((a, b) => a.score - b.score)
    .slice(0, limit === undefined ? undefined : Math.max(0, limit)).map(({ item }) => item);
}
