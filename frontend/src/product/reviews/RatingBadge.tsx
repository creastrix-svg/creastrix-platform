import { ratingForPath } from '../catalog/localCatalog';
import type { StorefrontLocale } from '../locale';
import { ratingText } from './ratingText';

/** Ratings belong to the catalog item, not to a finish or a particular tile. */
export function RatingBadge({href, locale}: {href: string; locale: StorefrontLocale}) {
  const rating = ratingForPath(href);
  if (rating.average === null || !rating.count) return null;
  return <small className="catalog-rating-badge">
    <span aria-hidden="true">★ {rating.average.toLocaleString(locale, { maximumFractionDigits: 1 })} ({rating.count.toLocaleString(locale)})</span>
    <span className="product-sr-only">{ratingText(rating, locale)}</span>
  </small>;
}
