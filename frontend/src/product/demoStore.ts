import { favoriteSkus, readFavoriteIds } from './favorites';
import { createSlice, original } from '@reduxjs/toolkit';
import type { PayloadAction } from '@reduxjs/toolkit';
import { isVariantId, variants } from './fixtures/loop';
import { permanentCatalogMode } from './catalog/permanentCatalog';
import type { VariantId } from './fixtures/loop';
import { isStorefrontLocale } from './locale';
import type { StorefrontLocale } from './locale';
import { readProductPreferences } from './preferences';
import type { MediaMode, ProductPreferences } from './preferences';
import { copyDesign, designKey, rebindDesignFonts, visibleDesign, supportsAppliedFonts } from './studio/session/designSnapshot';
import type { SavedDesign } from './studio/session/designSnapshot';
import type { StudioFontId } from './studio/design/studioFonts';
import { cartSkuStock, isCartSku } from './cartSku';
import type { CartSku } from './cartSku';
import { readCartSku } from './fixtures/cartSkus';
import { connectedSku } from './catalog/localCatalog';

export type CartLine<S extends CartSku = CartSku> = { variant: S; quantity: number; id?: string; design?: SavedDesign; templateRevision?:string };
/** A plain line uses its SKU; a design line keeps its own identity through edits and undo. */
export const cartLineId = (line: Pick<CartLine, 'id' | 'variant'>) => line.id ?? line.variant;
type Line = CartLine;
type CartChange = { revision: number } & (
  | { kind: 'other' }
  | { kind: 'removed' | 'restored'; variant: CartSku; id?: string }
  | { kind: 'dismissed'; variant: CartSku; id?: string; removedRevision: number }
);
type RemovedLine = { quantity: number; revision: number; line?: CartLine };
export type ProductDemoState = ProductPreferences & {
  favorites: CartSku[];
  favoritesMemoryOnly?: true;
  cart: Line[];
  // Presentation order also includes removed lines until the buyer dismisses or restores them.
  cartOrder: string[];
  cartRemoved: Partial<Record<string, RemovedLine>>;
  locale: StorefrontLocale | null;
  addSerial: number;
  cartSerial: number;
  cartChange: CartChange;
};
const initialState = (): ProductDemoState => ({ favorites: readFavoriteIds() ?? [], cart: [], cartOrder: [], cartRemoved: {}, locale: null, addSerial: 0, cartSerial: 1, cartChange: { revision: 0, kind: 'other' }, ...readProductPreferences() });
export const variantCartQuantity = (state: Pick<ProductDemoState, 'cart'>, variant: CartSku) => state.cart.reduce((sum, line) => sum + (line.variant === variant ? line.quantity : 0), 0);
export const cartStockShortages = (state: Pick<ProductDemoState, 'cart'>) =>
  [...new Set(state.cart.map(line => line.variant))].filter(sku => variantCartQuantity(state, sku) > cartSkuStock(sku));
/** A restored line can exceed current stock. It may shrink incrementally
 * without forcing other designs off the cart, but cannot grow until stock permits. */
export const lineCapacity = (state: Pick<ProductDemoState, 'cart'>, line: CartLine) =>
  line.quantity + Math.max(0, cartSkuStock(line.variant) - variantCartQuantity(state, line.variant));

function matchesConfiguration(variant: CartSku, design?: SavedDesign) {
  const key = designKey(design);
  return (line: CartLine) => line.variant === variant && (design ? !!line.design && designKey(line.design) === key : !line.design);
}

export function configurationCartQuantity(cart: CartLine[], variant: VariantId, design?: SavedDesign) {
  const matches = matchesConfiguration(variant, design);
  // Independently edited lines may now have the same design. Both count for the buyer.
  return cart.reduce((total, line) => matches(line) ? total + line.quantity : total, 0);
}

function nextCartRevision(state: ProductDemoState) {
  // Never reuse a removal token: an old click cannot restore a later deletion.
  const revision = state.cartChange.revision;
  return Number.isSafeInteger(revision) && revision >= 0 && revision < Number.MAX_SAFE_INTEGER ? revision + 1 : null;
}

function insertCartLine(state: ProductDemoState, line: Line) {
  // Stable presentation slots survive deletion; only active lines enter totals/minicart.
  state.cart.push(line);
  state.cart.sort((left, right) => state.cartOrder.indexOf(cartLineId(left)) - state.cartOrder.indexOf(cartLineId(right)));
}

function reconcilePurchase(state: ProductDemoState, variant: CartSku) {
  if (!isVariantId(variant)) return; // Loop-only draft controls.
  const remaining = cartSkuStock(variant) - variantCartQuantity(state, variant);
  // Zero means unavailable, not a purchase. Released capacity never revives an old excess draft.
  state.purchaseQuantities[variant] = remaining <= 0 ? 0 : Math.max(1, Math.min(state.purchaseQuantities[variant], remaining));
}

// Plain lines retain their legacy variant ID. Personalised lines own independent
// monotonic IDs; the image/text snapshot is detached from the editor's history.
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
    favoriteToggled(state, { payload }: PayloadAction<CartSku>) {
      if (!isCartSku(payload)) return;
      if (state.favorites.includes(payload)) state.favorites = state.favorites.filter(id => id !== payload);
      else state.favorites = favoriteSkus().filter(id => id === payload || state.favorites.includes(id));
    },
    favoriteStorageReported(state, { payload }: PayloadAction<boolean>) {
      if (payload) delete state.favoritesMemoryOnly; else state.favoritesMemoryOnly = true;
    },
    favoriteRemoved(state, { payload }: PayloadAction<CartSku>) { state.favorites = state.favorites.filter(id => id !== payload); },
    favoritesRead(state, { payload }: PayloadAction<CartSku[]>) {
      const ids = favoriteSkus().filter(id => payload.includes(id));
      if (ids.length !== state.favorites.length || ids.some((id, i) => id !== state.favorites[i])) state.favorites = ids;
    },
    cartAdded(state, { payload }: PayloadAction<Line>) {
      if (!isCartSku(payload.variant) || !Number.isSafeInteger(payload.quantity) || payload.quantity < 1) return;
      if (payload.design !== undefined && (!readCartSku(payload.variant).acceptsDesign
          || (permanentCatalogMode && !supportsAppliedFonts(payload.design)))) return;
      const sku = payload.variant;
      const design = visibleDesign(payload.design) ? payload.design : undefined;
      const line = state.cart.find(matchesConfiguration(payload.variant, design));
      const capacity = cartSkuStock(sku) - variantCartQuantity(state, sku);
      if (capacity <= 0 || !Number.isSafeInteger(state.cartSerial) || state.cartSerial >= Number.MAX_SAFE_INTEGER) return;
      const revision = nextCartRevision(state); if (revision === null) return;
      if (line) line.quantity += Math.min(capacity, payload.quantity);
      else {
        const next: CartLine = { variant: sku, quantity: Math.min(capacity, payload.quantity), ...(design ? { id: `design-${state.cartSerial++}`, design: copyDesign(design),
          ...(permanentCatalogMode ? {templateRevision:connectedSku(sku)!.variant.templateRevision ?? connectedSku(sku)!.product.templateRevision} : {}) } : {}) };
        const id = cartLineId(next);
        if (!state.cartOrder.includes(id)) state.cartOrder.push(id);
        insertCartLine(state, next);
      }
      if (!design) delete state.cartRemoved[sku];
      // Only a successful addition consumes this colour's pending quantity.
      if (isVariantId(sku)) state.purchaseQuantities[sku] = 1;
      reconcilePurchase(state, sku);
      state.addSerial = (state.addSerial + 1) % 1_000_000;
      state.cartChange = { revision, kind: 'other' };
    },
    cartQuantityChanged(state, { payload }: PayloadAction<Line>) {
      if (!Number.isSafeInteger(payload.quantity) || payload.quantity < 1) return;
      const line = state.cart.find(item => cartLineId(item) === cartLineId(payload));
      if (!line || line.variant !== payload.variant) return;
      const next = Math.min(lineCapacity(state, line), payload.quantity);
      if (next === line.quantity) return;
      const revision = nextCartRevision(state); if (revision === null) return;
      line.quantity = next; reconcilePurchase(state, line.variant);
      state.cartChange = { revision, kind: 'other' };
    },
    cartLineRemoved(state, { payload }: PayloadAction<string>) {
      const index = state.cart.findIndex(item => cartLineId(item) === payload); if (index < 0) return;
      const revision = nextCartRevision(state); if (revision === null) return;
      const line = state.cart[index];
      state.cartRemoved[payload] = { quantity: line.quantity, revision, ...(line.id ? { line: { ...line } } : {}) };
      state.cart.splice(index, 1); reconcilePurchase(state, line.variant);
      state.cartChange = { revision, kind: 'removed', variant: line.variant, ...(line.id ? { id: line.id } : {}) };
    },
    cartUndoExpired(state,{payload:through}:PayloadAction<number>){
      // This is presentation cleanup; active quantities and persistence revisions do not change.
      const expired=Object.keys(state.cartRemoved).filter(id=>(state.cartRemoved[id]?.revision??Infinity)<=through);
      if(!expired.length)return;
      for(const id of expired)delete state.cartRemoved[id];
      const active=new Set(state.cart.map(cartLineId));
      state.cartOrder=state.cartOrder.filter(id=>active.has(id)||!!state.cartRemoved[id]);
    },
    cartLineRestored(state, { payload }: PayloadAction<{ variant: CartSku; revision: number; id?: string }>) {
      if (!isCartSku(payload.variant) || !Number.isSafeInteger(payload.revision) || payload.revision < 1) return;
      const id = cartLineId(payload), removed = state.cartRemoved[id], revision = nextCartRevision(state);
      // Each row owns its token. Other rows may change; this exact snapshot must still exist.
      if (!removed || (removed.line?.variant ?? id) !== payload.variant || removed.revision !== payload.revision || revision === null || state.cartOrder.filter(key => key === id).length !== 1 || !Number.isSafeInteger(removed.quantity) || removed.quantity < 1 || removed.quantity > cartSkuStock(payload.variant) - variantCartQuantity(state, payload.variant) || state.cart.some(line => cartLineId(line) === id)) return;
      insertCartLine(state, removed.line ?? { variant: payload.variant, quantity: removed.quantity });
      delete state.cartRemoved[id];
      reconcilePurchase(state, payload.variant);
      state.cartChange = { revision, kind: 'restored', variant: payload.variant, ...(payload.id ? { id: payload.id } : {}) };
    },
    cartRemovedLineDismissed(state, { payload }: PayloadAction<{ variant: CartSku; revision: number; id?: string }>) {
      if (!isCartSku(payload.variant) || !Number.isSafeInteger(payload.revision) || payload.revision < 1) return;
      const id = cartLineId(payload), removed = state.cartRemoved[id], revision = nextCartRevision(state);
      // Closing a presentation slot must never remove an active or later re-added line.
      if (!removed || (removed.line?.variant ?? id) !== payload.variant || removed.revision !== payload.revision || revision === null || state.cart.some(line => cartLineId(line) === id) || state.cartOrder.filter(key => key === id).length !== 1) return;
      delete state.cartRemoved[id];
      state.cartOrder = state.cartOrder.filter(key => key !== id);
      state.cartChange = { revision, kind: 'dismissed', variant: payload.variant, ...(payload.id ? { id: payload.id } : {}), removedRevision: payload.revision };
    },
    cartDesignChanged(state, { payload }: PayloadAction<{ id: string; expected: string; design: SavedDesign }>) {
      const line = state.cart.find(line => cartLineId(line) === payload.id);
      if (!line?.design || (permanentCatalogMode && !isVariantId(line.variant))
          || designKey(line.design) !== payload.expected || !visibleDesign(payload.design)
          || (permanentCatalogMode && !supportsAppliedFonts(payload.design))) return;
      line.design = copyDesign(payload.design);
    },
    cartLineDesignCleared(state, { payload }: PayloadAction<{ id: string; variant: CartSku; expected: string }>) {
      const { id, variant, expected } = payload;
      const index = state.cart.findIndex(line => line.id === id && line.variant === variant);
      const line = state.cart[index];
      if (!line?.design || designKey(line.design) !== expected) return;
      const revision = nextCartRevision(state); if (revision === null) return;
      // The units were already in the cart: removing lettering must not reserve
      // stock again or clamp quantities after a catalog availability change.
      const plain = state.cart.find(item => item.variant === variant && !item.design);
      if (plain) {
        plain.quantity += line.quantity;
        state.cart.splice(index, 1);
        state.cartOrder = state.cartOrder.filter(key => key !== id);
      } else {
        state.cart[index] = { variant, quantity: line.quantity };
        // Reuse the edited row's position. A deleted plain SKU is superseded,
        // not restored; its former units are not part of this conversion.
        state.cartOrder = state.cartOrder.filter(key => key !== variant).map(key => key === id ? variant : key);
        delete state.cartRemoved[variant];
      }
      state.cartChange = { revision, kind: 'other' };
    },
    cartHydrated(state, { payload }: PayloadAction<{ lines: CartLine[]; serial: number; fontRemap?: Record<string, StudioFontId>; replaceCaptured?: boolean }>) {
      // Existing live additions win over a late restore. The UI is held during normal hydration.
      if (!payload.replaceCaptured && (state.cart.length || state.cartChange.revision)) {
        // Returning from an account/info route retains Redux lines, but their
        // previous storefront's FontFaces were disposed. Only runtime font IDs
        // are rebound; identities, quantities, designs and removal tokens survive.
        const rebind = (line: CartLine) => {
          if (!line.design || !payload.fontRemap) return;
          if (!line.design.objects.some(object => object.kind === 'text' && payload.fontRemap![object.value.font])) return;
          const rebound = rebindDesignFonts(original(line.design) ?? line.design, payload.fontRemap);
          if (!rebound) return;
          const restored = payload.lines.find(item => item.design && designKey(item.design) === designKey(rebound));
          line.design = restored?.design ? copyDesign(restored.design) : rebound;
        };
        state.cart.forEach(rebind);
        Object.values(state.cartRemoved).forEach(removed => { if (removed?.line) rebind(removed.line); });
        return;
      }
      state.cart = payload.lines;
      state.cartOrder = payload.lines.map(cartLineId); state.cartRemoved = {};
      if (payload.replaceCaptured) state.cartChange = { revision: nextCartRevision(state) ?? state.cartChange.revision, kind: 'other' };
      state.cartSerial = payload.serial;
      for (const variant of variants) reconcilePurchase(state, variant.id);
    },
  },
});
export const { favoriteStorageReported, favoritesRead, favoriteToggled, favoriteRemoved, cartAdded, cartQuantityChanged, cartLineRemoved, cartUndoExpired, cartLineRestored, cartRemovedLineDismissed, cartDesignChanged, cartLineDesignCleared, cartHydrated, productLocaleSelected, productVariantSelected, purchaseQuantityChanged, productDescriptionChanged, productMediaModeSelected } = slice.actions;
export default slice.reducer;
export const cartCount = (state: ProductDemoState) => state.cart.reduce((sum, line) => sum + line.quantity, 0);
export const cartLinePrice = (line: Pick<CartLine, 'variant' | 'design'>) => {
  const offer = readCartSku(line.variant);
  // Disabling new designs does not strip a previously applied design's fee.
  return offer.priceWithDesign && visibleDesign(line.design) ? offer.priceWithDesign()
    : { baseCents: offer.cents, feeCents: 0, unitCents: offer.cents };
};
export const cartTotal = (state: ProductDemoState) => state.cart.reduce((sum, line) => sum + cartLinePrice(line).unitCents * line.quantity, 0);
export const cartDisplayLines = (state: ProductDemoState) => state.cartOrder.flatMap(id => {
  const active = state.cart.find(line => cartLineId(line) === id), removed = state.cartRemoved[id];
  return active ? [{ ...active, removedRevision: null as number | null }] : removed && (removed.line || isCartSku(id)) ? [{ ...(removed.line ?? { variant: id as CartSku }), quantity: 0, removedRevision: removed.revision }] : [];
});
