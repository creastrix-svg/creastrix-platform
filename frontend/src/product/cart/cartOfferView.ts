import { photoDescription, swatchBackground } from '../productDisplay';
import { readCartSku, type CartSku } from '../fixtures/cartSkus';
import { editionItem } from '../fixtures/editionCatalog';
import { connectedOffer, entryForPath } from '../catalog/localCatalog';
import { thumbnailFor } from '../catalog/photoRenditions';
import { productCopy } from '../copy';
import type { StorefrontLocale } from '../locale';

// Cart, checkout and the header preview must describe a SKU in exactly the
// same way. The current catalog, not persisted browser data, owns its media and price.
export function cartOfferView(sku: CartSku, locale: StorefrontLocale) {
  const { label, color, productHref, search, photo, stock, previousCents } = readCartSku(sku);
  const finish = color.name[locale];
  const item = label.name !== undefined ? { name: label.name, title: finish } : editionItem(label.editionId, locale)!;
  return { name: item.name, detail: item.title, finish, href: productHref + search, photo: photo ? thumbnailFor(photo) : photo, stock, previousCents,
    alt: photoDescription(label.editionId ? item.name : productCopy[locale].photoAlt, item.title), swatch: swatchBackground(color.colors) };
}

/** Only a known, local offer can become the return target. Never navigate to a
 * raw URL supplied through browser history state. */
export function cartReturnOfferPath(path: unknown) {
  if (typeof path !== 'string' || !path.startsWith('/') || path.startsWith('//')) return undefined;
  const entry = entryForPath(path);
  if (entry?.kind !== 'offer') return undefined;
  // Retain only the known finish, not arbitrary browser history parameters.
  const query = path.split('#', 1)[0].split('?')[1];
  const variants = new URLSearchParams(query).getAll('variant'), offer = connectedOffer(entry.id);
  const finish = variants.length === 1 && offer?.variants.find(v => v.id === variants[0]);
  return finish && offer && offer.variants.length > 1 ? entry.href + '?variant=' + encodeURIComponent(finish.id) : entry.href;
}
