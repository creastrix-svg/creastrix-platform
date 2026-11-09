import { isVariantId, variantById } from './catalog';
import type { VariantId } from './catalog';
import { tideOffer } from './tideOffer';

// The existing four unqualified SKU strings belong to Loop. Keep their IDs
// stable in saved carts; another product's blue finish is not the same SKU.
export type CartSku = VariantId | typeof tideOffer.sku;
export const isCartSku = (value: unknown): value is CartSku => isVariantId(value) || value === tideOffer.sku;
export const cartSkuStock = (sku: CartSku) => sku === tideOffer.sku ? tideOffer.stock : variantById(sku).stock;
