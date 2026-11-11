import { loopPrice } from '../loopPricing';
import { canPersonalize, studioProducts } from '../studio/studioProducts';
import { isVariantId, mediaForVariant, variantById, type VariantId } from './loop';
import { tideOffer } from './tideOffer';

// The unqualified finish IDs are existing browser cart keys. An editorial
// product with the same colour has its own SKU, not another alias for that finish.
export type CartSku = VariantId | typeof tideOffer.sku;
export const isFixtureCartSku = (value: unknown): value is CartSku => isVariantId(value) || value === tideOffer.sku;

/** A read of the local fixtures, not persisted prices or a stock reservation. */
export function readCartSku(sku: CartSku) {
  if (sku === tideOffer.sku) {
    return {
      label: { editionId: tideOffer.id }, color: variantById(tideOffer.colorId), stock: tideOffer.stock, cents: tideOffer.unitCents, previousCents: undefined,
      productHref: tideOffer.href, search: '', photo: tideOffer.photo, acceptsDesign: false,
      priceWithDesign: undefined,
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
