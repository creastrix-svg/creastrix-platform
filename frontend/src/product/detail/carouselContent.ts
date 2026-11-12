import { concepts } from '../fixtures/loop';
import { conceptDescription } from '../fixtures/conceptItems';
import { editionItem } from '../fixtures/editionCatalog';
import { tideOffer } from '../fixtures/tideOffer';
import type { StorefrontLocale } from '../locale';
import { connectedMode, connectedRecommendations, entryForId } from '../catalog/localCatalog';
import type { Rail } from '../catalog/catalogEntries';

export type CarouselItem = { name: string; note: string; href: string; photo: string };
export type CarouselContent = { items: readonly CarouselItem[]; href: string; collectionName?: string };

/** Creastrix is the single seller in this pilot. Narrow its destination only
 * for an explicitly named collection, never from a URL prefix or a category. */
export function sellerDestination(source: string): Pick<CarouselContent, 'href' | 'collectionName'> {
  const entry = entryForId(source);
  const offlineLoop = !connectedMode && source === 'loop';
  const id = entry?.collectionId ?? (offlineLoop ? 'loop' : null);
  const name = entry?.collectionName ?? (offlineLoop ? 'Loop' : null);
  return id && name ? { href:'/catalog?collection=' + encodeURIComponent(id), collectionName:name } : {href:'/catalog'};
}

export function railItems(source: string, section: Rail, locale: StorefrontLocale) {
  return connectedRecommendations(source, section)?.map(item => ({
    name:item.name, note:item.title[locale], href:item.href, photo:item.thumb ?? '',
  }));
}

export function loopCarouselContent(locale: StorefrontLocale, section: Rail): CarouselContent {
  const live = railItems('loop', section, locale);
  const destination = section === 'studio' ? sellerDestination('loop') : { href:'/catalog' };
  if (live !== undefined) return { ...destination, items:live };
  // The standalone reference has no separately curated section neighbours.
  if (section === 'section') return { ...destination, items:[] };
  // The offline seller shelf leads with two accessories. Reserve the other
  // planters for the similarity shelf rather than filling it with homeware.
  const ids = section === 'studio'
    ? ['loop-tray', 'loop-stand', 'loop-mini', 'loop-wide']
    : ['loop-tall', 'loop-duo'];
  const items = ids.map(id => {
    const item = concepts.find(record => record.id === id)!;
    return {name:item.name, note:conceptDescription(id, locale),
      href:'/preview/items/' + id, photo:`/product/reference-asset-${item.image}.webp`};
  });
  // Tide is the only other purchasable offline offer. Do not extend the
  // seller's assortment by treating unrelated editorial photos as offers.
  if (section === 'studio') {
    const tide = editionItem(tideOffer.id, locale)!;
    items.push({ name:tide.name, note:tide.title, href:tide.href, photo:tide.thumb });
  }
  return { ...destination, items };
}
