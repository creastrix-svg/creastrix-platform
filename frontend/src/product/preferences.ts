import { isVariantId, variants } from './catalog';
import type { VariantId } from './catalog';

export type MediaMode = 'photo' | 'model';
export type ProductPreferences = { selectedVariant: VariantId; purchaseQuantities: Record<VariantId, number>; mediaMode: MediaMode; descriptionOpen: boolean };
export const PRODUCT_PREFERENCES_KEY = 'creastrix.product.loop.preferences';
const maximumLength = 1024;
const keys = ['version', 'selectedVariant', 'purchaseQuantities', 'mediaMode', 'descriptionOpen'];
const record = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);
const exactKeys = (value: Record<string, unknown>, expected: string[]) => Object.keys(value).length === expected.length && Object.keys(value).every(key => expected.includes(key));
const quantity = (value: unknown, stock: number) => typeof value === 'number' && Number.isSafeInteger(value) ? Math.max(1, Math.min(value, stock)) : 1;

export function defaultProductPreferences(): ProductPreferences {
  return { selectedVariant: 'cobalt', purchaseQuantities: { cobalt: 1, coral: 1, chalk: 1, 'cobalt-chalk': 1 }, mediaMode: 'photo', descriptionOpen: false };
}

export function readProductPreferences(): ProductPreferences {
  // This single bounded public record is never an account, cart or authorization source.
  try {
    const raw = window.localStorage.getItem(PRODUCT_PREFERENCES_KEY);
    if (!raw || raw.length > maximumLength) return defaultProductPreferences();
    const value: unknown = JSON.parse(raw);
    if (!record(value) || !exactKeys(value, keys) || value.version !== 1 || !isVariantId(value.selectedVariant)
      || !record(value.purchaseQuantities) || !exactKeys(value.purchaseQuantities, variants.map(variant => variant.id))
      || !['photo', 'model'].includes(value.mediaMode as string) || typeof value.descriptionOpen !== 'boolean') return defaultProductPreferences();
    const result = defaultProductPreferences();
    for (const variant of variants) result.purchaseQuantities[variant.id] = quantity(value.purchaseQuantities[variant.id], variant.stock);
    return { ...result, selectedVariant: value.selectedVariant, mediaMode: value.mediaMode as MediaMode, descriptionOpen: value.descriptionOpen };
  } catch {
    return defaultProductPreferences();
  }
}

export function serializeProductPreferences(value: ProductPreferences): string {
  const purchaseQuantities = defaultProductPreferences().purchaseQuantities;
  // A cart-exhausted zero is not a durable purchase choice; a fresh empty cart starts at one.
  for (const variant of variants) purchaseQuantities[variant.id] = quantity(value.purchaseQuantities[variant.id], variant.stock);
  return JSON.stringify({ version: 1, selectedVariant: value.selectedVariant, purchaseQuantities, mediaMode: value.mediaMode, descriptionOpen: value.descriptionOpen });
}

export function rememberProductPreferences(serialized: string): void {
  try { window.localStorage.setItem(PRODUCT_PREFERENCES_KEY, serialized); } catch { /* Public preferences remain usable in memory when storage is denied or full. */ }
}
