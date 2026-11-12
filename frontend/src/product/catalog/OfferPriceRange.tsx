import { connectedOffer, entryForPath } from './localCatalog';
import { money } from '../productDisplay';
import type { StorefrontLocale } from '../locale';

/** Collection tiles and recommendations quote the same underlying variants. */
export function OfferPriceRange({href, locale}: {href:string; locale:StorefrontLocale}) {
  const entry = entryForPath(href), offer = entry && connectedOffer(entry.id);
  if (!offer?.variants.length) return null;
  const prices = offer.variants.map(v => v.cents);
  const min = Math.min(...prices), max = Math.max(...prices);
  return <small className="product-rail-price">{money(min, locale)}{max !== min && ' – ' + money(max, locale)}</small>;
}
