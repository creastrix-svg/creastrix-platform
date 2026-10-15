import { createSlice } from '@reduxjs/toolkit';
import type { PayloadAction } from '@reduxjs/toolkit';
import { isVariantId, variantById } from './catalog';
import type { VariantId } from './catalog';
import { isStorefrontLocale } from './locale';
import type { StorefrontLocale } from './locale';
import { readProductPreferences } from './preferences';
import type { MediaMode, ProductPreferences } from './preferences';

type Line = { variant: VariantId; quantity: number };
export type ProductDemoState = ProductPreferences & { favorite: VariantId | null; cart: Line[]; locale: StorefrontLocale | null; addSerial: number };
const initialState = (): ProductDemoState => ({ favorite: null, cart: [], locale: null, addSerial: 0, ...readProductPreferences() });

function reconcilePurchase(state: ProductDemoState, variant: VariantId) {
  const remaining = variantById(variant).stock - (state.cart.find(line => line.variant === variant)?.quantity ?? 0);
  // Zero means unavailable, not a purchase. Released capacity never revives an old excess draft.
  state.purchaseQuantities[variant] = remaining === 0 ? 0 : Math.max(1, Math.min(state.purchaseQuantities[variant], remaining));
}

// Cart/favorite are still memory-only. Only the separate public preference allowlist is restored.
const slice = createSlice({
  name: 'productDemo', initialState,
  reducers: {
    purchaseQuantityChanged(state, { payload }: PayloadAction<Line>) {
      if (!isVariantId(payload.variant) || !Number.isSafeInteger(payload.quantity) || payload.quantity < 1) return;
      state.purchaseQuantities[payload.variant] = payload.quantity;
      reconcilePurchase(state, payload.variant);
    },
    productDescriptionChanged(state, { payload }: PayloadAction<boolean>) { if (typeof payload === 'boolean') state.descriptionOpen = payload; },
    productMediaModeSelected(state, { payload }: PayloadAction<MediaMode>) { if (payload === 'photo' || payload === 'model') state.mediaMode = payload; },
    productVariantSelected(state, { payload }: PayloadAction<VariantId>) { if (isVariantId(payload)) state.selectedVariant = payload; },
    productLocaleSelected(state, { payload }: PayloadAction<StorefrontLocale>) { if (isStorefrontLocale(payload)) state.locale = payload; },
    favoriteToggled(state, { payload }: PayloadAction<VariantId>) {
      if (isVariantId(payload)) state.favorite = state.favorite === null ? payload : null;
    },
    favoriteRemoved(state) { state.favorite = null; },
    cartAdded(state, { payload }: PayloadAction<Line>) {
      if (!Number.isSafeInteger(payload.quantity) || payload.quantity < 1) return;
      const variant = variantById(payload.variant);
      if (!variant) return;
      const line = state.cart.find(item => item.variant === payload.variant);
      if (line?.quantity === variant.stock) return;
      if (line) line.quantity = Math.min(variant.stock, line.quantity + payload.quantity);
      else state.cart.push({ variant: variant.id, quantity: Math.min(variant.stock, payload.quantity) });
      reconcilePurchase(state, variant.id);
      state.addSerial = (state.addSerial + 1) % 1_000_000;
    },
    cartQuantityChanged(state, { payload }: PayloadAction<Line>) {
      if (!Number.isSafeInteger(payload.quantity) || payload.quantity < 1) return;
      const line = state.cart.find(item => item.variant === payload.variant);
      if (line) { line.quantity = Math.min(variantById(line.variant).stock, payload.quantity); reconcilePurchase(state, line.variant); }
    },
    cartLineRemoved(state, { payload }: PayloadAction<VariantId>) {
      state.cart = state.cart.filter(item => item.variant !== payload);
      if (isVariantId(payload)) reconcilePurchase(state, payload);
    },
  },
});
export const { favoriteToggled, favoriteRemoved, cartAdded, cartQuantityChanged, cartLineRemoved, productLocaleSelected, productVariantSelected, purchaseQuantityChanged, productDescriptionChanged, productMediaModeSelected } = slice.actions;
export default slice.reducer;
export const cartCount = (state: ProductDemoState) => state.cart.reduce((sum, line) => sum + line.quantity, 0);
export const cartTotal = (state: ProductDemoState) => state.cart.reduce((sum, line) => sum + variantById(line.variant).cents * line.quantity, 0);
