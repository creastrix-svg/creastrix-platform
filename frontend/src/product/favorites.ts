import { variants } from './catalog';
import { isCartSku } from './cartSku';
import type { CartSku } from './cartSku';
import { tideOffer } from './tideOffer';

// One bounded key per known colour: saving Coral cannot replace another tab's
// Chalk. Removal deletes only that key; no startup or bulk snapshot writes.
const prefix = 'creastrix.product.loop.favorites.v1.';
export const favoriteSkus: readonly CartSku[] = [...variants.map(({id}) => id), tideOffer.sku];
export const favoriteKey = (id: CartSku) => id === tideOffer.sku ? 'creastrix.product.tide.favorites.v1.cobalt' : prefix + id;
const unsaved = new WeakMap<object, Map<CartSku, boolean>>();
export function readFavoriteIds(owner?: object): CartSku[] | null {
  try { return favoriteSkus.filter(id => (owner && unsaved.get(owner)?.get(id)) ?? (localStorage.getItem(favoriteKey(id)) === '1')); }
  catch { return null; }
}
export function writeFavorite(owner: object, id: CartSku, saved: boolean) {
  if (!isCartSku(id)) return undefined;
  try {
    if (saved) localStorage.setItem(favoriteKey(id), '1');
    else localStorage.removeItem(favoriteKey(id));
    unsaved.get(owner)?.delete(id);
  } catch {
    // A later peer refresh/remount must not silently discard this tab's failed
    // local edit. The overlay dies with its store and never writes itself back.
    const pending = unsaved.get(owner) ?? new Map<CartSku, boolean>();
    pending.set(id, saved); unsaved.set(owner, pending);
  }
  return !unsaved.get(owner)?.size;
}
export function isFavoriteEvent(event: StorageEvent) {
  try { return event.storageArea === localStorage && (event.key === null || favoriteSkus.some(id => event.key === favoriteKey(id))); }
  catch { return false; }
}
