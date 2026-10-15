import type { StorefrontLocale } from './locale';

export type VariantId = 'cobalt' | 'coral' | 'chalk' | 'cobalt-chalk';
export type SwatchColors = readonly [string] | readonly [string, string];
export const variants: { id: VariantId; colors: SwatchColors; checkTone: 'light' | 'dark'; cents: number; previousCents?: number; stock: number; name: Record<StorefrontLocale, string> }[] = [
  { id: 'cobalt', colors: ['#244bcc'], checkTone: 'light', cents: 2400, stock: 24, name: { en: 'Cobalt blue', de: 'Kobaltblau', ru: 'Кобальтовый синий', pl: 'Kobaltowy', es: 'Azul cobalto', fr: 'Bleu cobalt', it: 'Blu cobalto' } },
  { id: 'coral', colors: ['#e17763'], checkTone: 'light', cents: 2600, stock: 8, name: { en: 'Coral', de: 'Koralle', ru: 'Коралловый', pl: 'Koralowy', es: 'Coral', fr: 'Corail', it: 'Corallo' } },
  { id: 'chalk', colors: ['#f0eee8'], checkTone: 'dark', cents: 1990, previousCents: 2400, stock: 15, name: { en: 'Chalk', de: 'Kreide', ru: 'Меловой', pl: 'Kredowy', es: 'Tiza', fr: 'Craie', it: 'Gesso' } },
  { id: 'cobalt-chalk', colors: ['#244bcc', '#f0eee8'], checkTone: 'dark', cents: 2800, stock: 6, name: { en: 'Cobalt & chalk', de: 'Kobaltblau & Kreide', ru: 'Кобальтовый и меловой', pl: 'Kobaltowy i kredowy', es: 'Cobalto y tiza', fr: 'Cobalt et craie', it: 'Cobalto e gesso' } },
];
export const variantById = (id: VariantId) => variants.find(variant => variant.id === id)!;
export const isVariantId = (value: unknown): value is VariantId => typeof value === 'string' && variants.some(variant => variant.id === value);
export function sampleDiscount(cents: number, previousCents?: number): number | null {
  if (!Number.isSafeInteger(cents) || cents < 0 || !Number.isSafeInteger(previousCents) || previousCents! <= cents) return null;
  const percent = Math.round((previousCents! - cents) / previousCents! * 100);
  return percent > 0 && percent <= 100 ? percent : null;
}
export function swatchBackground(colors: SwatchColors) {
  return colors.length === 1 ? colors[0] : `linear-gradient(135deg, ${colors[0]} 0 50%, ${colors[1]} 50% 100%)`;
}
export const photos = ['01', '03', '04'].map(id => `/product/reference-asset-${id}.webp`);
type ProductMedia = { photos: readonly string[]; model: string | null };
const media: Record<VariantId, ProductMedia> = {
  cobalt: { photos, model: '/product/loop-shaped-cobalt.glb' },
  coral: { photos: ['/product/loop-coral-01.webp'], model: '/product/loop-shaped-coral.glb' },
  chalk: { photos: ['/product/loop-chalk-01.webp'], model: '/product/loop-shaped-chalk.glb' },
  'cobalt-chalk': { photos: ['/product/loop-cobalt-chalk-01.webp'], model: '/product/loop-shaped-cobalt-chalk.glb' },
};
export const mediaForVariant = (id: VariantId): ProductMedia => Object.hasOwn(media, id) ? media[id] : { photos: [], model: null };
export const photoDescription = (base: string, variantName: string) => `${base} · ${variantName}`;
export const concepts = [
  { id: 'loop-mini', name: 'Loop Mini', image: '05' },
  { id: 'loop-wide', name: 'Loop Wide', image: '06' },
  { id: 'loop-tray', name: 'Loop Tray', image: '07' },
  { id: 'loop-tall', name: 'Loop Tall', image: '08' },
  { id: 'loop-duo', name: 'Loop Duo', image: '09' },
  { id: 'loop-stand', name: 'Loop Stand', image: '10' },
];
export function money(cents: number, language: StorefrontLocale) {
  const locales = { de: 'de-DE', en: 'en-IE', ru: 'ru-RU', pl: 'pl-PL', es: 'es-ES', fr: 'fr-FR', it: 'it-IT' };
  return new Intl.NumberFormat(locales[language], { style: 'currency', currency: 'EUR' }).format(cents / 100);
}
