import { createSlice } from '@reduxjs/toolkit';
import type { PayloadAction } from '@reduxjs/toolkit';
import { isVariantId, variantById } from './catalog';
import type { VariantId } from './catalog';
import { isStorefrontLocale } from './locale';
import type { StorefrontLocale } from './locale';
import { readProductPreferences } from './preferences';
import type { MediaMode, ProductPreferences } from './preferences';

type Line = { variant: VariantId; quantity: number };
type CartChange = { revision: number } & ({ kind: 'other' } | { kind: 'removed' | 'restored'; variant: VariantId } | { kind: 'dismissed'; variant: VariantId; removedRevision: number });
type RemovedLine = { quantity: number; revision: number };
export type ProductDemoState = ProductPreferences & { favorite: VariantId | null; cart: Line[]; cartOrder: VariantId[]; cartRemoved: Partial<Record<VariantId, RemovedLine>>; locale: StorefrontLocale | null; addSerial: number; cartChange: CartChange };
const initialState = (): ProductDemoState => ({ favorite: null, cart: [], cartOrder: [], cartRemoved: {}, locale: null, addSerial: 0, cartChange: { revision: 0, kind: 'other' }, ...readProductPreferences() });

function nextCartRevision(state: ProductDemoState) {
  // Never reuse a removal token: an old click cannot restore a later deletion.
  const revision = state.cartChange.revision;
  return Number.isSafeInteger(revision) && revision >= 0 && revision < Number.MAX_SAFE_INTEGER ? revision + 1 : null;
}

function insertCartLine(state: ProductDemoState, line: Line) {
  // Stable presentation slots survive deletion; only active lines enter totals/minicart.
  state.cart.push(line);
  state.cart.sort((left, right) => state.cartOrder.indexOf(left.variant) - state.cartOrder.indexOf(right.variant));
}

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
      const revision = nextCartRevision(state); if (revision === null) return;
      if (line) line.quantity = Math.min(variant.stock, line.quantity + payload.quantity);
      else {
        if (!state.cartOrder.includes(variant.id)) state.cartOrder.push(variant.id);
        insertCartLine(state, { variant: variant.id, quantity: Math.min(variant.stock, payload.quantity) });
      }
      // An explicit new addition replaces this variant's old restore, not other rows'.
      delete state.cartRemoved[variant.id];
      reconcilePurchase(state, variant.id);
      state.addSerial = (state.addSerial + 1) % 1_000_000;
      state.cartChange = { revision, kind: 'other' };
    },
    cartQuantityChanged(state, { payload }: PayloadAction<Line>) {
      if (!Number.isSafeInteger(payload.quantity) || payload.quantity < 1) return;
      const line = state.cart.find(item => item.variant === payload.variant);
      if (!line) return;
      const next = Math.min(variantById(line.variant).stock, payload.quantity);
      if (next === line.quantity) return;
      const revision = nextCartRevision(state); if (revision === null) return;
      line.quantity = next; reconcilePurchase(state, line.variant);
      state.cartChange = { revision, kind: 'other' };
    },
    cartLineRemoved(state, { payload }: PayloadAction<VariantId>) {
      const index = state.cart.findIndex(item => item.variant === payload); if (index < 0) return;
      const revision = nextCartRevision(state); if (revision === null) return;
      const line = state.cart[index];
      state.cartRemoved[line.variant] = { quantity: line.quantity, revision };
      state.cart.splice(index, 1); reconcilePurchase(state, line.variant);
      state.cartChange = { revision, kind: 'removed', variant: line.variant };
    },
    cartLineRestored(state, { payload }: PayloadAction<{ variant: VariantId; revision: number }>) {
      if (!isVariantId(payload.variant) || !Number.isSafeInteger(payload.revision) || payload.revision < 1) return;
      const removed = state.cartRemoved[payload.variant], variant = variantById(payload.variant), revision = nextCartRevision(state);
      // Each row owns its token. Other rows may change; this exact snapshot must still exist.
      if (!removed || removed.revision !== payload.revision || revision === null || state.cartOrder.filter(id => id === payload.variant).length !== 1 || !Number.isSafeInteger(removed.quantity) || removed.quantity < 1 || removed.quantity > variant.stock || state.cart.some(line => line.variant === payload.variant)) return;
      insertCartLine(state, { variant: payload.variant, quantity: removed.quantity });
      delete state.cartRemoved[payload.variant];
      reconcilePurchase(state, payload.variant);
      state.cartChange = { revision, kind: 'restored', variant: payload.variant };
    },
    cartRemovedLineDismissed(state, { payload }: PayloadAction<{ variant: VariantId; revision: number }>) {
      if (!isVariantId(payload.variant) || !Number.isSafeInteger(payload.revision) || payload.revision < 1) return;
      const removed = state.cartRemoved[payload.variant], revision = nextCartRevision(state);
      // Closing a presentation slot must never remove an active or later re-added line.
      if (!removed || removed.revision !== payload.revision || revision === null || state.cart.some(line => line.variant === payload.variant) || state.cartOrder.filter(id => id === payload.variant).length !== 1) return;
      delete state.cartRemoved[payload.variant];
      state.cartOrder = state.cartOrder.filter(id => id !== payload.variant);
      state.cartChange = { revision, kind: 'dismissed', variant: payload.variant, removedRevision: payload.revision };
    },
  },
});
export const { favoriteToggled, favoriteRemoved, cartAdded, cartQuantityChanged, cartLineRemoved, cartLineRestored, cartRemovedLineDismissed, productLocaleSelected, productVariantSelected, purchaseQuantityChanged, productDescriptionChanged, productMediaModeSelected } = slice.actions;
export default slice.reducer;
export const cartCount = (state: ProductDemoState) => state.cart.reduce((sum, line) => sum + line.quantity, 0);
export const cartTotal = (state: ProductDemoState) => state.cart.reduce((sum, line) => sum + variantById(line.variant).cents * line.quantity, 0);
export const cartDisplayLines = (state: ProductDemoState) => state.cartOrder.flatMap(variant => {
  const active = state.cart.find(line => line.variant === variant), removed = state.cartRemoved[variant];
  return active ? [{ ...active, removedRevision: null as number | null }] : removed ? [{ variant, quantity: 0, removedRevision: removed.revision }] : [];
});
