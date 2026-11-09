import { concepts, sampleDiscount, variants } from './catalog';
import { editionItems } from './editionCatalog';
import { departmentForSlug, editionDepartments, offerDepartments, shelfCategorySlug, type Department } from './homeFeatured';
import { conceptDescription } from './search';
import type { StorefrontLocale } from './locale';
import { canPersonalize, studioProducts } from './studioProducts';

/** Public preview vocabulary, not production Listing or fulfilment types. A
 * concept is not a production file, even if its illustration came from CAD. */
export const facetOptions = {
  format: ['ready', 'made-to-order', 'file', 'concept'],
  custom: ['yes', 'no'],
  technique: ['3d-print'],
  color: variants.map(variant => variant.id),
} as const;
export type FacetKey = keyof typeof facetOptions;
const facetKeys = Object.keys(facetOptions) as FacetKey[];
export const catalogQueryKeys: readonly string[] = [...facetKeys, 'category', 'group', 'sale', 'sort'];
export const customOrMadeGroup = 'custom-or-made';
export const browseSorts = ['featured', 'price-asc', 'price-desc', 'stock-desc', 'name-asc'] as const;
export type BrowseSort = typeof browseSorts[number];
type Variant = typeof variants[number];
type Concept = { href: string; name: string; caption: string; photo: string };
type BrowseItem = ({ kind: 'finish'; data: Variant } | { kind: 'concept'; data: Concept }) & {
  facets: Partial<Record<FacetKey, string>>;
  sale: boolean;
  departments: readonly Department[];
};

function options(key: FacetKey): readonly string[] { return facetOptions[key]; }

/** The creative collection is a union, not three simultaneous filters. A
 * view-only concept does not become a production file by appearing in a design collection. */
export function belongsToCustomOrMade({ custom, format }: BrowseItem['facets']): boolean {
  return custom === 'yes' || format === 'made-to-order' || format === 'file';
}

export function toggleFacet(params: URLSearchParams, key: FacetKey, value: string): URLSearchParams {
  const next = new URLSearchParams(params);
  if (!options(key).includes(value)) return next;
  const selection = new Set(params.getAll(key));
  if (selection.has(value)) selection.delete(value); else selection.add(value);
  next.delete(key);
  // Consistent share links regardless of selection order; discard non-facet values.
  for (const allowed of options(key)) if (selection.has(allowed)) next.append(key, allowed);
  return next;
}

export function clearBrowse(params: URLSearchParams): URLSearchParams {
  const next = new URLSearchParams(params);
  for (const key of catalogQueryKeys) next.delete(key);
  return next;
}

export function browseCatalog(params: URLSearchParams, locale: StorefrontLocale) {
  const read = (key: FacetKey) => {
    const requested = new Set(params.getAll(key));
    return options(key).filter(value => requested.has(value));
  };
  const selected: Record<FacetKey, readonly string[]> = {
    format: read('format'), custom: read('custom'), technique: read('technique'), color: read('color'),
  };
  const sort = browseSorts.find(value => value === params.get('sort')) ?? 'featured';
  const sale = params.get('sale') === '1';
  const group = params.get('group') === customOrMadeGroup ? customOrMadeGroup : null;
  const requestedCategory = params.get('category'), department = departmentForSlug(requestedCategory);
  const category: ReturnType<typeof shelfCategorySlug> | 'personal' | null =
    department ? shelfCategorySlug(department) : requestedCategory === 'personal' ? 'personal' : null;
  const profile = studioProducts.loop;
  const personal = canPersonalize(profile);
  const editorial = editionItems(locale);
  const ideas = [
    ...editorial.map(item => ({ href: item.href, name: item.name, caption: item.title, photo: item.thumb, departments: editionDepartments(item.id) })),
    ...concepts.map(item => ({ href: '/preview/items/' + item.id, name: item.name, caption: conceptDescription(item.id, locale),
      photo: '/product/reference-asset-' + item.image + '.webp', departments: ['home'] as const })),
  ];
  const items: BrowseItem[] = [
    ...variants.map((data): BrowseItem => ({
      kind: 'finish', data, departments: offerDepartments, sale: sampleDiscount(data.cents, data.previousCents) !== null,
      facets: { format: profile.readyMade ? 'ready' : 'made-to-order', custom: personal ? 'yes' : 'no', technique: '3d-print', color: data.id },
    })),
    ...ideas.map((data): BrowseItem => ({
      kind: 'concept', data, departments: data.departments, sale: false,
      // There is no buyer editor for these concepts. No manufacturing technique is attested.
      facets: { format: 'concept', custom: 'no' },
    })),
  ];
  const scope = items.filter(item => (!sale || item.sale)
    && (!group || belongsToCustomOrMade(item.facets))
    && (category !== 'personal' || item.facets.custom === 'yes')
    && (!department || item.departments.includes(department)));
  const matches = (item: BrowseItem, except?: FacetKey) => facetKeys.every(key => {
    const values = selected[key], actual = item.facets[key];
    return key === except || !values.length || (actual !== undefined && values.includes(actual));
  });
  const visible = scope.filter(item => matches(item));
  // Disjunctive counts keep alternatives discoverable: the other groups apply,
  // but a checked colour alone must not erase the counts of the other colours.
  // A finish is the unit for matching; the product is the unit for display and counts.
  // In particular a sale on chalk cannot satisfy a simultaneous coral-only request.
  const countProducts = (rows: readonly BrowseItem[]) => new Set(rows.map(item => item.kind === 'finish' ? '/products/loop' : item.data.href)).size;
  const countFor = (key: FacetKey): Record<string, number> => Object.fromEntries(options(key).map(value => [
    value, countProducts(scope.filter(item => item.facets[key] === value && matches(item, key))),
  ]));
  const counts = { format: countFor('format'), custom: countFor('custom'), technique: countFor('technique'), color: countFor('color') };
  const finishes = visible.flatMap(item => item.kind === 'finish' ? [item.data] : []);
  const visibleConcepts = visible.flatMap(item => item.kind === 'concept' ? [item.data] : []);
  if (sort === 'name-asc') {
    const collator = new Intl.Collator(locale, { numeric: true, sensitivity: 'base' });
    visibleConcepts.sort((a, b) => collator.compare(a.name, b.name));
  }
  const filterCount = Number(sale) + Number(category !== null) + Number(group !== null)
    + facetKeys.reduce((count, key) => count + selected[key].length, 0);
  return { finishes, concepts: visibleConcepts, sale, sort, category, group, selected, counts, filterCount, total: countProducts(visible) };
}
