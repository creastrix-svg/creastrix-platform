import { readCartSku, type CartSku } from './fixtures/cartSkus';

export type { CartSku };
export { isFixtureCartSku as isCartSku } from './fixtures/cartSkus';
export const cartSkuStock = (sku: CartSku) => readCartSku(sku).stock;
