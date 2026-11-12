import { storefrontLocales, type StorefrontLocale } from '../locale';
import { publishedPhotoId } from './photoRenditions';

export type CatalogRating = Readonly<{ count: number; average: number | null }>;
type Localized = Readonly<Record<StorefrontLocale, string>>;
export type CatalogEntry = Readonly<{
  id: string; kind: 'offer' | 'edition' | 'concept'; name: string; href: string;
  title: Localized; description: Localized; material: Localized;
  photo: string | null; thumb: string | null; categories: readonly string[];
  collectionId: string | null; collectionName: string | null;
  editionOrder: number | null; homeOrder: number | null; rating: CatalogRating;
}>;
export type Rail = 'studio' | 'related' | 'section';
export const unrated: CatalogRating = Object.freeze({ count: 0, average: null });

const invalid = (): never => { throw new Error('LOCAL_CATALOG_INVALID'); };
const object = (v: unknown) => v !== null && typeof v === 'object' && !Array.isArray(v) ? v as Record<string, unknown> : invalid();
const slug = (v: unknown) => typeof v === 'string' && /^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(v) ? v : invalid();
const position = (v: unknown) => v === null || (typeof v === 'number' && Number.isSafeInteger(v) && v >= 0) ? v : invalid();
const photo = (v: unknown) => v === null || (typeof v === 'string' && /^\/(?:product|shop-edition)\/[a-z0-9_-]+\.webp$/.test(v)) ? v : invalid();
function entryPhotos(e: Record<string, unknown>) {
  if (import.meta.env.MODE === 'local-catalog' && typeof e.photo === 'string' && typeof e.thumb === 'string') {
    const id = publishedPhotoId(e.photo, 'detail');
    if (id && publishedPhotoId(e.thumb, 'thumb') === id) return { photo: e.photo, thumb: e.thumb };
  }
  return { photo: photo(e.photo), thumb: photo(e.thumb) };
}
function localized(v: unknown, required: boolean): Localized {
  const labels = object(v);
  return Object.freeze(Object.fromEntries(storefrontLocales.map(lang => {
    const text = labels[lang];
    return [lang, typeof text === 'string' && (!required || text.trim()) ? text : invalid()];
  })) as Record<StorefrontLocale, string>);
}
function rating(v: unknown): CatalogRating {
  const { count, average } = object(v);
  if (typeof count !== 'number' || !Number.isSafeInteger(count) || count < 0) return invalid();
  if (!count && average === null) return unrated;
  return count && typeof average === 'number' && Number.isFinite(average) && average >= 1 && average <= 5
    ? Object.freeze({ count, average }) : invalid();
}
function entry(raw: unknown): CatalogEntry {
  const e = object(raw), kind = e.kind, id = slug(e.id);
  if (kind !== 'offer' && kind !== 'edition' && kind !== 'concept') return invalid();
  const href = e.href;
  if (typeof href !== 'string' || !/^\/[a-z0-9][a-z0-9/-]*$/.test(href) ||
    (kind === 'edition' && href !== '/collections/objects/' + id) ||
    (kind === 'concept' && href !== '/preview/items/' + id) ||
    typeof e.name !== 'string' || !e.name.trim() || !Array.isArray(e.categories)) return invalid();
  const collectionId = e.collectionId === null ? null : slug(e.collectionId);
  const collectionName = e.collectionName;
  if (!(collectionName === null || (typeof collectionName === 'string' && collectionName.trim().length > 0))
    || (collectionId === null) !== (collectionName === null)) return invalid();
  return Object.freeze({ id, kind, href, name: e.name, collectionId, collectionName, title: localized(e.title, true),
    material: localized(e.material, false), description: localized(e.description, false),
    categories: Object.freeze(e.categories.map(slug)), ...entryPhotos(e),
    editionOrder: position(e.editionOrder), homeOrder: position(e.homeOrder), rating: rating(e.rating) });
}

/** Read one snapshot. A missing recommendation is an empty rail, never a reason
 * to show examples. Every supplied membership must resolve in this snapshot. */
export function parseCatalogEntries(raw: unknown, offers: readonly { id: string; href: string }[]) {
  const body = object(raw);
  if (!Array.isArray(body.entries) || !Array.isArray(body.recommendations)) return invalid();
  const entries = Object.freeze(body.entries.map(entry));
  const byId = new Map(entries.map(item => [item.id, item]));
  const byPath = new Map(entries.map(item => [item.href, item]));
  if (byId.size !== entries.length || byPath.size !== entries.length) return invalid();
  for (const offer of offers) {
    const record = byId.get(offer.id);
    if (record?.kind !== 'offer' || record.href !== offer.href) return invalid();
  }
  if (entries.filter(e => e.kind === 'offer').length !== offers.length) return invalid();
  const rails = new Map<string, readonly CatalogEntry[]>();
  for (const relationship of body.recommendations) {
    const r = object(relationship), owner = slug(r.productId), section = r.section;
    if (!byId.has(owner) || (section !== 'studio' && section !== 'related' && section !== 'section') || !Array.isArray(r.itemIds)) return invalid();
    const key = owner + '/' + section, ids = r.itemIds.map(slug);
    if (rails.has(key) || new Set(ids).size !== ids.length) return invalid();
    rails.set(key, Object.freeze(ids.map(id => byId.get(id) ?? invalid())));
  }
  return { entries, byPath, byId, rails };
}
