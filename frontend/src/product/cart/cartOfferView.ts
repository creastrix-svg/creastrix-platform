import { photoDescription, swatchBackground } from '../productDisplay';
import { readCartSku, type CartSku } from '../fixtures/cartSkus';
import { editionItems } from '../fixtures/editionCatalog';
import type { StorefrontLocale } from '../locale';

// Cart, checkout and the header preview must describe a SKU in exactly the
// same way. Product fixtures, not persisted browser data, own its media and price.
export function cartOfferView(sku: CartSku, locale: StorefrontLocale) {
  const { label, color, productHref, search, photo, stock, previousCents } = readCartSku(sku);
  const finish = color.name[locale];
  const item = 'editionId' in label ? editionItems(locale).find(entry => entry.id === label.editionId)! : { name: label.name, title: finish };
  return { name: item.name, detail: item.title, finish, href: productHref + search, photo, stock, previousCents,
    alt: photoDescription(item.name, item.title), swatch: swatchBackground(color.colors) };
}
