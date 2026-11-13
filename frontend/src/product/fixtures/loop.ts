import type { StorefrontLocale } from '../locale';
import type { SwatchColors } from '../productDisplay';
import { connectedProduct, connectedSku, type LoopFinish } from '../catalog/localCatalog';
import { permanentCatalogMode, sharedCatalogMode } from '../catalog/permanentCatalog';

// These IDs are the Loop fixture's finishes, not an exhaustive list of storefront variants.
export type VariantId = LoopFinish;
export const variants: { id: VariantId; colors: SwatchColors; checkTone: 'light' | 'dark'; cents: number; previousCents?: number; stock: number; name: Record<StorefrontLocale, string> }[] = [
  { id: 'cobalt', colors: ['#244bcc'], checkTone: 'light', cents: 2400, stock: 24, name: { en: 'Cobalt blue', de: 'Kobaltblau', ru: 'Кобальтовый синий', pl: 'Kobaltowy', es: 'Azul cobalto', fr: 'Bleu cobalt', it: 'Blu cobalto' } },
  { id: 'coral', colors: ['#e17763'], checkTone: 'light', cents: 2600, stock: 8, name: { en: 'Coral', de: 'Koralle', ru: 'Коралловый', pl: 'Koralowy', es: 'Coral', fr: 'Corail', it: 'Corallo' } },
  { id: 'chalk', colors: ['#f0eee8'], checkTone: 'dark', cents: 1990, previousCents: 2400, stock: 15, name: { en: 'Chalk', de: 'Kreide', ru: 'Меловой', pl: 'Kredowy', es: 'Tiza', fr: 'Craie', it: 'Gesso' } },
  { id: 'cobalt-chalk', colors: ['#244bcc', '#f0eee8'], checkTone: 'dark', cents: 2800, stock: 6, name: { en: 'Cobalt & chalk', de: 'Kobaltblau & Kreide', ru: 'Кобальтовый и меловой', pl: 'Kobaltowy i kredowy', es: 'Cobalto y tiza', fr: 'Cobalt et craie', it: 'Cobalto e gesso' } },
];
export const loopVariants = () => connectedProduct('loop')?.variants ?? variants;
export const loopName = () => connectedProduct('loop')?.name ?? 'Loop';
export const variantById = (id: VariantId) => loopVariants().find(variant => variant.id === id)!;
export const isVariantId = (value: unknown): value is VariantId => {
  if(typeof value!=='string' || !variants.some(v=>v.id===value)) return false;
  if(!permanentCatalogMode) return true;
  const match=connectedSku(value);
  if(sharedCatalogMode) return !!match && match.product.id==='loop' && match.variant.id===value && !!connectedProduct('loop');
  return match?.product.id==='loop' && match.product.personalizable && match.product.templateRevision==='2' && match.variant.id===value;
};
/** A retained applied line can use the historical reader without enabling the
 * current editor. Its SKU, variant, offer and pinned template must still resolve. */
export function isAppliedLoopVariant(value: unknown, revision:unknown):value is VariantId {
  if(!permanentCatalogMode) return isVariantId(value);
  if(revision!=='2' || typeof value!=='string' || !variants.some(v=>v.id===value)) return false;
  const match=connectedSku(value);
  return match?.product.id==='loop' && match.variant.id===value && match.variant.sku===value
    && (match.variant.retainedLoopV2FeeCents!==undefined || (sharedCatalogMode ? match.variant.studioFormat==='surface-print-v1' : isVariantId(value)));
}
export const photos = ['01', '03', '04'].map(id => `/product/reference-asset-${id}.webp`);
type ProductMedia = { photos: readonly string[]; model: string | null };
const media: Record<VariantId, ProductMedia> = {
  cobalt: { photos, model: '/product/loop-shaped-cobalt.glb' },
  coral: { photos: ['/product/loop-coral-01.webp'], model: '/product/loop-shaped-coral.glb' },
  chalk: { photos: ['/product/loop-chalk-01.webp'], model: '/product/loop-shaped-chalk.glb' },
  'cobalt-chalk': { photos: ['/product/loop-cobalt-chalk-01.webp'], model: '/product/loop-shaped-cobalt-chalk.glb' },
};
export const mediaForVariant = (id: VariantId): ProductMedia => {
  const catalog = connectedProduct('loop');
  return catalog ? catalog.variants.find(v => v.id === id) ?? { photos: [], model: null }
    : Object.hasOwn(media, id) ? media[id] : { photos: [], model: null };
};
export const concepts = [
  { id: 'loop-mini', name: 'Loop Mini', image: '05' },
  { id: 'loop-wide', name: 'Loop Wide', image: '06' },
  { id: 'loop-tray', name: 'Loop Tray', image: '07' },
  { id: 'loop-tall', name: 'Loop Tall', image: '08' },
  { id: 'loop-duo', name: 'Loop Duo', image: '09' },
  { id: 'loop-stand', name: 'Loop Stand', image: '10' },
];
