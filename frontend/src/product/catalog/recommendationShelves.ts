import type { CatalogEntry } from './catalogEntries';

/** Keep the two ranking sources disjoint across the whole scrollable list,
 * including off-screen cards. A section match cannot displace a text match. */
export function lowerShelves(
  source: string, opening: readonly CatalogEntry[], related: readonly CatalogEntry[], section: readonly CatalogEntry[],
) {
  const seen = new Set([source, ...opening.map(item => item.id)]);
  const take = (candidates: readonly CatalogEntry[]) => {
    const accepted: CatalogEntry[] = [];
    for (const item of candidates) {
      if (item.kind !== 'offer' || seen.has(item.id)) continue;
      seen.add(item.id);
      accepted.push(item);
    }
    return accepted;
  };
  const similar = take(related);
  return { related: similar, section: take(section) };
}

/** The local feed contains one seller's assortment. The server ranks the opening
 * page; expand only the product being viewed instead of shipping the full seller
 * assortment once for every product. A multi-seller feed must scope entries by seller. */
export function expandSellerShelf(
  source: CatalogEntry, opening: readonly CatalogEntry[], reserved: readonly CatalogEntry[], assortment: readonly CatalogEntry[],
): readonly CatalogEntry[] {
  if (!opening.length || source.kind !== 'offer') return [];
  const seen = new Set([source.id, ...reserved.map(item => item.id)]);
  const result: CatalogEntry[] = [], sameCollection: CatalogEntry[] = [], rest: CatalogEntry[] = [];
  function take(item: CatalogEntry, into: CatalogEntry[]) {
    if (item.kind !== 'offer' || seen.has(item.id)) return;
    seen.add(item.id);
    into.push(item);
  }
  for (const item of opening) take(item, result);
  for (const item of assortment) {
    take(item, source.collectionId !== null && item.collectionId === source.collectionId ? sameCollection : rest);
  }
  return [...result, ...sameCollection, ...rest];
}
