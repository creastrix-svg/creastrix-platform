import { mediaForVariant, photoDescription, swatchBackground, variantById } from './catalog';
import type { CartSku } from './cartSku';
import { productCopy } from './copy';
import { editionItems } from './editionCatalog';
import type { StorefrontLocale } from './locale';
import { tideOffer } from './tideOffer';

// Cart, checkout and the header preview must describe a SKU in exactly the
// same way. Product fixtures, not persisted browser data, own its media and price.
export function cartOfferView(sku: CartSku, locale: StorefrontLocale) {
  if (sku === tideOffer.sku) {
    const item = editionItems(locale).find(entry => entry.id === tideOffer.id)!;
    const finish = variantById('cobalt');
    return { name: item.name, detail: item.title, finish: finish.name[locale], href: tideOffer.href, photo: tideOffer.photo,
      alt: item.name + ' · ' + item.title, stock: tideOffer.stock, previousCents: undefined, swatch: swatchBackground(finish.colors) };
  }
  const variant = variantById(sku), detail = variant.name[locale];
  return { name: 'Loop', detail, finish: detail, href: '/products/loop?variant=' + sku, photo: mediaForVariant(sku).photos[0],
    alt: photoDescription(productCopy[locale].photoAlt, detail), stock: variant.stock,
    previousCents: variant.previousCents, swatch: swatchBackground(variant.colors) };
}
