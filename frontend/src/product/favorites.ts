import { isVariantId, variants } from './catalog';
import type { VariantId } from './catalog';

// One bounded key per known colour: saving Coral cannot replace another tab's
// Chalk. Removal deletes only that key; no startup or bulk snapshot writes.
const prefix = 'creastrix.product.loop.favorites.v1.';
export const favoriteKey = (id: VariantId) => prefix + id;
const unsaved = new WeakMap<object, Map<VariantId, boolean>>();
export function readFavoriteIds(owner?: object): VariantId[] | null {
  try { return variants.filter(({ id }) => (owner && unsaved.get(owner)?.get(id)) ?? (localStorage.getItem(favoriteKey(id)) === '1')).map(({ id }) => id); }
  catch { return null; }
}
export function writeFavorite(owner: object, id: VariantId, saved: boolean) {
  if (!isVariantId(id)) return undefined;
  try {
    if (saved) localStorage.setItem(favoriteKey(id), '1');
    else localStorage.removeItem(favoriteKey(id));
    unsaved.get(owner)?.delete(id);
  } catch {
    // A later peer refresh/remount must not silently discard this tab's failed
    // local edit. The overlay dies with its store and never writes itself back.
    const pending = unsaved.get(owner) ?? new Map<VariantId, boolean>();
    pending.set(id, saved); unsaved.set(owner, pending);
  }
  return !unsaved.get(owner)?.size;
}
export function isFavoriteEvent(event: StorageEvent) {
  try { return event.storageArea === localStorage && (event.key === null || variants.some(({ id }) => event.key === favoriteKey(id))); }
  catch { return false; }
}
