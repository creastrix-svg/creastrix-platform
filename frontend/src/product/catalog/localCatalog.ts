import { storefrontLocales, type StorefrontLocale } from '../locale';
import type { SwatchColors } from '../productDisplay';
import { isCatalogOfferHref, tideOfferPath } from '../routes';
import { parseCatalogEntries, unrated, type CatalogRating, type Rail } from './catalogEntries';
import { expandSellerShelf, lowerShelves } from './recommendationShelves';
export type { CatalogRating } from './catalogEntries';

export const catalogEndpoint = '/api/local-catalog/products';
export const connectedMode = import.meta.env.MODE === 'local-catalog';
export type CatalogProductId = 'loop' | 'tide';
export type LoopFinish = 'cobalt' | 'coral' | 'chalk' | 'cobalt-chalk';
type Variant<Id extends string = string> = Readonly<{
  id: Id; sku: string; cents: number; previousCents?: number; stock: number;
  name: Readonly<Record<StorefrontLocale, string>>; colors: SwatchColors; checkTone: 'dark' | 'light';
  photos: readonly string[]; model: string | null;
}>;
type Product<Id extends string = string> = Readonly<{
  id: string; name: string; href: string; personalizable: boolean; designFeeCents: number; variants: readonly Variant<Id>[];
}>;
export type CatalogProduct = Product;
export type CatalogVariant = Variant;
type Cards = Readonly<{loop: Product<LoopFinish>; tide: Product<'cobalt'>}>;
type Catalog = Cards & ReturnType<typeof parseCatalogEntries> & {
  products: readonly Product[]; offersById: ReadonlyMap<string, Product>;
  skus: ReadonlyMap<string, {product: Product; variant: Variant}>;
};

const loopIds = ['cobalt', 'coral', 'chalk', 'cobalt-chalk'] as const;
const invalid = (): never => { throw new Error('LOCAL_CATALOG_INVALID'); };
function record(raw: unknown): Record<string, unknown> {
  return raw && typeof raw === 'object' && !Array.isArray(raw) ? raw as Record<string, unknown> : invalid();
}
function label(raw: unknown, max: number) {
  return typeof raw === 'string' && raw.trim().length && raw.trim().length <= max ? raw.trim() : invalid();
}
export const isCatalogKey = (raw: unknown): raw is string =>
  typeof raw === 'string' && /^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(raw);
const key = (raw: unknown) => isCatalogKey(raw) ? raw : invalid();
// Plain cart rows use their SKU as identity. A saved design has its own serial.
export const isReservedCartLineId = (value: string) => /^design-[0-9]+$/.test(value);
function integer(raw: unknown) {
  return typeof raw === 'number' && Number.isSafeInteger(raw) && raw >= 0 && raw <= 1_000_000 ? raw : invalid();
}
function localAsset(raw: unknown, extension: 'webp' | 'glb') {
  return typeof raw === 'string' && /^\/(product|shop-edition)\/[a-z0-9_-]+\.(webp|glb)$/.test(raw) && raw.endsWith('.' + extension) ? raw : invalid();
}

function readProduct(raw: unknown): Product {
  const p = record(raw);
  const id = key(p.id), href = p.href;
  if (typeof href !== 'string' || !isCatalogOfferHref(href)
    || p.currency !== 'EUR' || typeof p.personalizable !== 'boolean' || !Array.isArray(p.variants) || !p.variants.length) return invalid();
  const seen = new Set<string>();
  const variants = p.variants.map(rawVariant => {
    const v = record(rawVariant);
    const finish = key(v.id), sku = key(v.sku);
    if (seen.has(finish) || isReservedCartLineId(sku)) return invalid();
    seen.add(finish);
    const names = record(v.name);
    const name = Object.fromEntries(storefrontLocales.map(locale => [locale, label(names[locale], 120)])) as Record<StorefrontLocale, string>;
    if (!Array.isArray(v.colors) || v.colors.length < 1 || v.colors.length > 2
      || !v.colors.every(color => typeof color === 'string' && /^#[\da-f]{6}$/i.test(color))
      || (v.checkTone !== 'light' && v.checkTone !== 'dark') || !Array.isArray(v.photos) || v.photos.length > 10) return invalid();
    const photos = v.photos.map(path => localAsset(path, 'webp'));
    if (new Set(photos).size !== photos.length) return invalid();
    const cents = integer(v.cents), stock = integer(v.stock);
    const previousCents = v.previousCents === null ? undefined : integer(v.previousCents);
    if (previousCents !== undefined && previousCents <= cents) return invalid();
    return Object.freeze({ id: finish, sku, name: Object.freeze(name), cents, previousCents, stock,
      colors: Object.freeze([...v.colors]) as SwatchColors, checkTone: v.checkTone,
      photos: Object.freeze(photos), model: v.model === null ? null : localAsset(v.model, 'glb') });
  });
  const designFeeCents = integer(p.designFeeCents);
  if (designFeeCents && !p.personalizable) return invalid();
  return Object.freeze({ id, name: label(p.name, 200), href, personalizable: p.personalizable, designFeeCents, variants: Object.freeze(variants) });
}

export function parseLocalCatalog(raw: unknown): readonly Product[] {
  const { products } = record(raw);
  if (!Array.isArray(products)) return invalid();
  const rows = products.map(readProduct), skus = rows.flatMap(p => p.variants.map(v => v.sku));
  if (new Set(rows.map(p => p.id)).size !== rows.length || new Set(rows.map(p => p.href)).size !== rows.length
    || new Set(skus).size !== skus.length) return invalid();
  return Object.freeze(rows);
}

/** Legacy detail routes and persisted cart keys predate the backend. Additional
 * database products don't break them or silently acquire a purchasable route. */
function cardAdapter<Id extends string>(rows: readonly Product[], id: CatalogProductId, href: string, ids: readonly Id[],
  sku: (finish: Id) => string): Product<Id> {
  const product = rows.find(row => row.id === id);
  if (!product || product.href !== href) return invalid();
  const variants = product.variants.filter((variant): variant is Variant<Id> => ids.some(key => key === variant.id && sku(key) === variant.sku));
  if (variants.length !== ids.length || product.variants.length !== ids.length) return invalid();
  return Object.freeze({...product, variants: Object.freeze(variants)});
}
export function storefrontCatalog(rows: readonly Product[]): Cards {
  return Object.freeze({ loop: cardAdapter(rows, 'loop', '/products/loop', loopIds, id => id),
    tide: cardAdapter(rows, 'tide', tideOfferPath, ['cobalt'] as const, () => 'tide-cobalt') });
}

// One immutable price/stock snapshot per document. The explicitly selected
// connected mode never switches to fixture data on transport or schema failure.
let snapshot: Catalog | undefined;
let pending: Promise<Catalog> | undefined;
function presentation() {
  if (!connectedMode) return undefined;
  if (!snapshot) throw new Error('LOCAL_CATALOG_NOT_LOADED');
  return snapshot;
}
export const connectedEntries = () => presentation()?.entries;
export const hasDesignConcepts = () => connectedEntries()?.some(e => e.kind !== 'offer') ?? true;
export const connectedOffers = () => presentation()?.products;
export const connectedOffer = (id: string) => presentation()?.offersById.get(id);
// Store construction and browser preference validation can precede the first read.
export const connectedSku = (sku: string) => connectedMode ? snapshot?.skus.get(sku) : undefined;
export const entryForPath = (href: string) => presentation()?.byPath.get(href.split(/[?#]/, 1)[0].replace(/\/+$/, ''));
export const entryForId = (id: string) => presentation()?.byId.get(id);
export const ratingForPath = (href: string): CatalogRating => entryForPath(href)?.rating ?? unrated;
export function connectedRecommendations(source: string, section: Rail) {
  const data = presentation();
  if (!data) return undefined;
  const item = data.byId.get(source);
  if (!item) return [];
  const opening = data.rails.get(source + '/studio') ?? [];
  const lower = lowerShelves(source, opening, data.rails.get(source + '/related') ?? [], data.rails.get(source + '/section') ?? []);
  return section === 'studio'
    ? expandSellerShelf(item, opening, [...lower.related, ...lower.section], data.entries)
    : lower[section];
}
export const catalogReady = () => !connectedMode || !!snapshot;
export function connectedProduct<Id extends CatalogProductId>(id: Id): Catalog[Id] | undefined {
  if (!connectedMode) return undefined;
  if (!snapshot) throw new Error('LOCAL_CATALOG_NOT_LOADED');
  return snapshot[id];
}
export async function fetchCatalog(transport: typeof fetch): Promise<Catalog> {
  const response = await transport(catalogEndpoint, { method: 'GET', credentials: 'omit',
    cache: 'no-store', redirect: 'error', headers: { Accept: 'application/json' }, signal: AbortSignal.timeout(10000) });
  if (!response.ok || !response.headers.get('content-type')?.toLowerCase().startsWith('application/json')) {
    throw new Error('LOCAL_CATALOG_UNAVAILABLE');
  }
  const raw: unknown = await response.json(), products = parseLocalCatalog(raw);
  const offersById = new Map(products.map(p => [p.id, p]));
  const skus = new Map(products.flatMap(product => product.variants.map(variant => [variant.sku, {product,variant}] as const)));
  return { ...storefrontCatalog(products), ...parseCatalogEntries(raw, products), products, offersById, skus };
}
export function loadLocalCatalog(): Promise<Catalog> {
  if (snapshot) return Promise.resolve(snapshot);
  return pending ??= fetchCatalog(window.fetch.bind(window)).then(value => snapshot = value).finally(() => { pending = undefined; });
}
