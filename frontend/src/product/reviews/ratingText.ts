import type { CatalogRating } from '../catalog/catalogEntries';
import { productCopy } from '../copy';
import type { StorefrontLocale } from '../locale';

const countLabel: Record<StorefrontLocale, string> = {
  en: 'Ratings', de: 'Bewertungen', ru: 'Оценок', pl: 'Liczba ocen',
  es: 'Valoraciones', fr: 'Nombre de notes', it: 'Valutazioni',
};

export function ratingText(rating: CatalogRating, locale: StorefrontLocale) {
  if (rating.average === null || !rating.count) return productCopy[locale].noReviews;
  const average = new Intl.NumberFormat(locale, { maximumFractionDigits: 1 }).format(rating.average);
  return average + ' / 5 · ' + countLabel[locale] + ': ' + rating.count.toLocaleString(locale);
}
