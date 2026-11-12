import { loopPrice } from '../loopPricing';
import { canPersonalize, studioProducts } from '../studio/studioProducts';
import { isVariantId, mediaForVariant, variantById, type VariantId } from './loop';
import { tideOffer } from './tideOffer';
import { connectedSku } from '../catalog/localCatalog';

// The unqualified finish IDs are existing browser cart keys. An editorial
// product with the same colour has its own SKU, not another alias for that finish.
declare const catalogSku: unique symbol;
export type CartSku = VariantId | typeof tideOffer.sku | (string & { readonly [catalogSku]: true });
/** A browser-supplied key is usable only if the catalog actually contains it.
 * The old Loop and Tide IDs keep working in the independent offline build. */
export const isCartSku = (value: unknown): value is CartSku =>
  typeof value === 'string' && (isVariantId(value) || value === tideOffer.sku || !!connectedSku(value));

/** Read the current offer snapshot, never a price or URL stored by the browser. */
export function readCartSku(sku: CartSku) {
  if (sku === tideOffer.sku) {
    return {
      label: { editionId: tideOffer.id }, color: tideOffer.finish, stock: tideOffer.stock, cents: tideOffer.unitCents, previousCents: tideOffer.previousCents,
      productHref: tideOffer.href, search: '', photo: tideOffer.photo, acceptsDesign: false,
      priceWithDesign: undefined,
    } as const;
  }
  if (!isVariantId(sku)) {
    const offer = connectedSku(sku);
    if (!offer) throw new Error('CATALOG_SKU_UNAVAILABLE');
    const { product, variant } = offer;
    return {
      label: { editionId: product.id }, color: variant, stock: variant.stock,
      cents: variant.cents, previousCents: variant.previousCents,
      productHref: product.href, search: product.variants.length > 1 ? '?variant=' + variant.id : '',
      photo: variant.photos[0] ?? '', acceptsDesign: false, priceWithDesign: undefined,
    } as const;
  }
  const variant = variantById(sku);
  return {
    label: { name: studioProducts.loop.name }, color: variant, stock: variant.stock, cents: variant.cents, previousCents: variant.previousCents,
    productHref: '/products/loop', search: '?variant=' + sku, photo: mediaForVariant(sku).photos[0],
    get acceptsDesign() { return canPersonalize(studioProducts.loop); },
    priceWithDesign: () => loopPrice(sku, true),
  } as const;
}
