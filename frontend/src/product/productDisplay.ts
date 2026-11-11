import type { StorefrontLocale } from './locale';

export type SwatchColors = readonly [string] | readonly [string, string];

export function swatchBackground(colors: SwatchColors) {
  return colors.length === 1 ? colors[0] : `linear-gradient(135deg, ${colors[0]} 0 50%, ${colors[1]} 50% 100%)`;
}

export const photoDescription = (base: string, variantName: string) => `${base} · ${variantName}`;

/** Display only a meaningful whole-percent saving; never invent one from an invalid comparison price. */
export function discountPercent(cents: number, previousCents?: number): number | null {
  if (!Number.isSafeInteger(cents) || cents < 0 || !Number.isSafeInteger(previousCents) || previousCents! <= cents) return null;
  const percent = Math.round((previousCents! - cents) / previousCents! * 100);
  return percent > 0 && percent <= 100 ? percent : null;
}

export function money(cents: number, language: StorefrontLocale) {
  const locales = { de: 'de-DE', en: 'en-IE', ru: 'ru-RU', pl: 'pl-PL', es: 'es-ES', fr: 'fr-FR', it: 'it-IT' };
  return new Intl.NumberFormat(locales[language], { style: 'currency', currency: 'EUR' }).format(cents / 100);
}
