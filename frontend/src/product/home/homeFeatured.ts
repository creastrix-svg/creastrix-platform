import { mediaForVariant, loopVariants } from '../fixtures/loop';
import { editionItems, localizedEntry, type EditionId } from '../fixtures/editionCatalog';
import { connectedEntries, connectedOffer, entryForId } from '../catalog/localCatalog';
import type { StorefrontLocale } from '../locale';
import { productCopy } from '../copy';
import { tideOffer } from '../fixtures/tideOffer';
import { shelfFilters, type ShelfFilter } from './homeShelfCopy';
import { studioProducts } from '../studio/studioProducts';

type EditionEntry = ReturnType<typeof editionItems>[number];

// These are editorial shelf placements, not backend categories or product availability.
export type Department = Exclude<ShelfFilter, 'all'>;
export function shelfCategorySlug(department: Department): 'living' | Exclude<Department, 'home'> {
  return department === 'home' ? 'living' : department;
}
export function departmentForSlug(raw: string | null): Department | undefined {
  return shelfFilters.find((filter): filter is Department => filter !== 'all' && shelfCategorySlug(filter) === raw);
}
export function shelfCatalogHref(filter: ShelfFilter) {
  return filter === 'all' ? '/catalog' : '/catalog?category=' + shelfCategorySlug(filter);
}
export const offerDepartments: readonly Department[] = ['home', 'gifts', 'decor'];
export function homeOfferInSection(filter: ShelfFilter): boolean {
  return filter === 'all' || offerDepartments.includes(filter);
}
const groups: Record<EditionId, readonly Department[]> = {
  halo: ['home', 'desk', 'decor'], tide: ['home', 'kitchen', 'gifts'], fold: ['carry'],
  arc: ['carry', 'gifts'], linea: ['carry'], tempo: ['home', 'desk'],
  folio: ['desk', 'gifts'], drift: ['home', 'desk'], cove: ['carry'],
  pin: ['carry', 'gifts'], kestrel: ['home', 'desk', 'decor'], moss: ['home', 'kitchen'],
  reed: ['home'], lunar: ['carry', 'gifts'], rail: ['home'],
  pebble: ['home', 'gifts'], prism: ['home', 'decor'], rook: ['home', 'kitchen'],
  dune: ['home', 'decor'], bend: ['home'], loom: ['carry', 'desk'],
  iris: ['carry'], wave: ['home', 'kitchen'], hex: ['home', 'desk', 'kitchen'],
  trail: ['carry'], flute: ['home', 'decor'], stripe: ['home', 'kitchen'],
  nook: ['carry', 'desk'], orbit: ['carry', 'gifts'], ridge: ['home', 'decor'],
  coil: ['home', 'decor', 'gifts'], dot: ['desk'],
};
export function editionDepartments(id: EditionEntry['id']): readonly Department[] {
  const entry = entryForId(id);
  return entry ? shelfFilters.filter((value): value is Department => value !== 'all' && entry.categories.includes(shelfCategorySlug(value)))
    : groups[id as EditionId] ?? [];
}
export function filterHomeEdition(items: readonly EditionEntry[], filter: ShelfFilter) {
  // Drift stays in the full catalog and collection.
  // Its homepage slot belongs to the stock offer, not a duplicate design.
  return items.filter(item => item.id !== 'drift' && (filter === 'all' || editionDepartments(item.id).includes(filter)));
}

export function homeOffer() {
  const available = loopVariants().filter(item => item.stock > 0);
  if (!studioProducts.loop.readyMade || available.length === 0) return null;
  const prices = available.map(item => item.cents);
  return {
    href: '/products/loop', name: studioProducts.loop.name, finishCount: available.length,
    minPrice: Math.min(...prices), maxPrice: Math.max(...prices), photo: mediaForVariant(available[0].id).photos[0],
  };
}

type ShelfTile = Pick<EditionEntry, 'id' | 'name' | 'href' | 'title' | 'material' | 'photo' | 'thumb'> & {
  min?: number; max?: number; finishCount?: number;
};
export function homeTiles(locale: StorefrontLocale, filter: ShelfFilter): ShelfTile[] {
  const live = connectedEntries();
  if (live) return live.filter(e => e.homeOrder !== null && (filter === 'all' || e.categories.includes(shelfCategorySlug(filter))))
    .sort((a, b) => a.homeOrder! - b.homeOrder!)
    .flatMap((entry): ShelfTile[] => {
      const item = localizedEntry(entry, locale);
      if (entry.kind !== 'offer') return [item];
      const product = connectedOffer(entry.id)!;
      const stocked = product.variants.filter(v => v.stock > 0);
      // Keep the multi-finish shelf consistent with the selectable finishes.
      if (product.variants.length > 1 && !stocked.length) return [];
      const displayed = stocked.length ? stocked : product.variants;
      const prices = displayed.map(v => v.cents);
      return [{...item, min:Math.min(...prices), max:Math.max(...prices),
        finishCount:product.variants.length > 1 ? displayed.length : undefined}];
    });
  const offer = homeOffer();
  const designs = filterHomeEdition(editionItems(locale), filter).map(item => item.id === tideOffer.id
    ? { ...item, min: tideOffer.unitCents, max: tideOffer.unitCents } : item);
  return homeOfferInSection(filter) && offer ? [{ ...offer, id: 'loop', title: productCopy[locale].subtitle, thumb: offer.photo,
    material: '', min: offer.minPrice, max: offer.maxPrice }, ...designs] : designs;
}
