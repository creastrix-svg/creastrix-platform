/** Public preview vocabulary, not production Listing or fulfilment types. A
 * concept is not a production file, even if its illustration came from CAD. */
const offerFormats = ['ready', 'made-to-order', 'file'] as const;
export const facetKeys = ['format', 'custom', 'technique', 'color'] as const;
export type FacetKey = typeof facetKeys[number];
type FacetOptions = Record<FacetKey, readonly string[]>;
export const catalogQueryKeys: readonly string[] = [...facetKeys, 'category', 'group', 'sale', 'sort'];
export const customOrMadeGroup = 'custom-or-made';
export const browseSorts = ['featured', 'price-asc', 'price-desc', 'stock-desc', 'name-asc'] as const;
export type BrowseSort = typeof browseSorts[number];
type Facets = Partial<Record<FacetKey, string>>;
type Color = { id: string; label: string; swatch: string };
type Finish = {
  color: Color; cents: number; previousCents?: number; discount: number | null; stock: number; photo: string; search: string;
};
type Offer = {
  href: string; reviewsHref?: string; name: string; details: string;
  format: typeof offerFormats[number]; personalizable: boolean; technique?: string;
  categories: readonly string[]; finishes: readonly Finish[];
};
export type CatalogOffer = Offer & { minCents: number; maxCents: number; stock: number };
type Concept = { href: string; name: string; caption: string; photo: string; categories: readonly string[] };
export type CatalogData = {
  offers: readonly Offer[]; concepts: readonly Concept[];
  categories: readonly { id: string; label: string }[]; techniques: readonly { id: string; label: string }[];
};
type BrowseItem = ({ kind: 'finish'; offer: Offer; data: Finish } | { kind: 'concept'; data: Concept }) & {
  facets: Facets;
  sale: boolean;
  categories: readonly string[];
};

/** The creative collection is a union, not three simultaneous filters. A
 * view-only concept does not become a production file by appearing in a design collection. */
export function belongsToCustomOrMade({ custom, format }: BrowseItem['facets']): boolean {
  return custom === 'yes' || format === 'made-to-order' || format === 'file';
}

export function toggleFacet(params: URLSearchParams, key: FacetKey, value: string, options: FacetOptions): URLSearchParams {
  const next = new URLSearchParams(params);
  if (!options[key].includes(value)) return next;
  const selection = new Set(params.getAll(key));
  if (selection.has(value)) selection.delete(value); else selection.add(value);
  next.delete(key);
  // Consistent share links regardless of selection order; discard non-facet values.
  for (const allowed of options[key]) if (selection.has(allowed)) next.append(key, allowed);
  return next;
}

export function clearBrowse(params: URLSearchParams): URLSearchParams {
  const next = new URLSearchParams(params);
  for (const key of catalogQueryKeys) next.delete(key);
  return next;
}

/** Match at finish level, count and sort at product level. The supplied
 * storefront data owns the merchandised items, labels and destinations. */
export function matchCatalog(params: URLSearchParams, locale: string, catalog: CatalogData) {
  const colors = new Map(catalog.offers.flatMap(offer => offer.finishes.map(({ color }) => [color.id, color.label] as const)));
  const options: FacetOptions = {
    format: [...offerFormats, 'concept'], custom: ['yes', 'no'],
    technique: catalog.techniques.map(t => t.id), color: [...colors.keys()],
  };
  const read = (key: FacetKey) => {
    const requested = new Set(params.getAll(key));
    return options[key].filter(value => requested.has(value));
  };
  const selected: Record<FacetKey, readonly string[]> = {
    format: read('format'), custom: read('custom'), technique: read('technique'), color: read('color'),
  };
  const sort = browseSorts.find(value => value === params.get('sort')) ?? 'featured';
  const sale = params.get('sale') === '1';
  const group = params.get('group') === customOrMadeGroup ? customOrMadeGroup : null;
  const requestedCategory = params.get('category'), department = catalog.categories.find(c => c.id === requestedCategory);
  const category = department?.id ?? (requestedCategory === 'personal' ? 'personal' : null);
  const items: BrowseItem[] = [
    ...catalog.offers.flatMap(offer => offer.finishes.map((data): BrowseItem => ({
      kind: 'finish', offer, data, categories: offer.categories, sale: data.discount !== null,
      facets: { format: offer.format, custom: offer.personalizable ? 'yes' : 'no', technique: offer.technique, color: data.color.id },
    }))),
    ...catalog.concepts.map((data): BrowseItem => ({
      kind: 'concept', data, categories: data.categories, sale: false,
      // There is no buyer editor for these concepts. No manufacturing technique is attested.
      facets: { format: 'concept', custom: 'no' },
    })),
  ];
  const scope = items.filter(item => (!sale || item.sale)
    && (!group || belongsToCustomOrMade(item.facets))
    && (category !== 'personal' || item.facets.custom === 'yes')
    && (!department || item.categories.includes(department.id)));
  const matches = (item: BrowseItem, except?: FacetKey) => facetKeys.every(key => {
    const values = selected[key], actual = item.facets[key];
    return key === except || !values.length || (actual !== undefined && values.includes(actual));
  });
  const visible = scope.filter(item => matches(item));
  // Disjunctive counts keep alternatives discoverable: the other groups apply,
  // but a checked colour alone must not erase the counts of the other colours.
  // A finish is the unit for matching; the product is the unit for display and counts.
  // A sale on one finish cannot satisfy a simultaneous filter for another finish.
  const countProducts = (rows: readonly BrowseItem[]) => new Set(rows.map(item => item.kind === 'finish' ? item.offer.href : item.data.href)).size;
  const countFor = (key: FacetKey): Record<string, number> => Object.fromEntries(options[key].map(value => [
    value, countProducts(scope.filter(item => item.facets[key] === value && matches(item, key))),
  ]));
  const counts = { format: countFor('format'), custom: countFor('custom'), technique: countFor('technique'), color: countFor('color') };
  const finishesByOffer = new Map<Offer, Finish[]>();
  for (const row of visible) {
    if (row.kind !== 'finish') continue;
    const group = finishesByOffer.get(row.offer);
    if (group) group.push(row.data); else finishesByOffer.set(row.offer, [row.data]);
  }
  const offers = catalog.offers.flatMap((offer): CatalogOffer[] => {
    const finishes = finishesByOffer.get(offer);
    if (!finishes) return [];
    const prices = finishes.map(f => f.cents);
    return [{ ...offer, finishes, minCents: Math.min(...prices), maxCents: Math.max(...prices), stock: finishes.reduce((n, f) => n + f.stock, 0) }];
  });
  const visibleConcepts = visible.flatMap(item => item.kind === 'concept' ? [item.data] : []);
  if (sort === 'price-asc') offers.sort((a, b) => a.minCents - b.minCents);
  if (sort === 'price-desc') offers.sort((a, b) => b.minCents - a.minCents);
  if (sort === 'stock-desc') offers.sort((a, b) => b.stock - a.stock);
  if (sort === 'name-asc') {
    const collator = new Intl.Collator(locale, { numeric: true, sensitivity: 'base' });
    offers.sort((a, b) => collator.compare(a.name, b.name));
    visibleConcepts.sort((a, b) => collator.compare(a.name, b.name));
  }
  const filterCount = Number(sale) + Number(category !== null) + Number(group !== null)
    + facetKeys.reduce((count, key) => count + selected[key].length, 0);
  return { offers, concepts: visibleConcepts, sale, sort, category, categoryLabel: department?.label, group, selected, counts, options,
    colorLabels: Object.fromEntries(colors), techniqueLabels: Object.fromEntries(catalog.techniques.map(t => [t.id, t.label])),
    filterCount, total: offers.length + visibleConcepts.length };
}
