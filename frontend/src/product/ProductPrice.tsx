import type { StorefrontLocale } from './locale';
import { money, sampleDiscount } from './catalog';
import type { ProductCopy } from './copy';

export default function ProductPrice({ cents, language, t, previousCents }: { cents: number; language: StorefrontLocale; t: ProductCopy; previousCents?: number }) {
  const valid = Number.isSafeInteger(cents) && cents >= 0, percent = sampleDiscount(cents, previousCents);
  return <div className="product-price-row"><strong className={'product-price' + (percent !== null ? ' product-price-sale' : '') + (!valid ? ' product-price-unavailable' : '')}>{valid ? <><span className="product-sr-only">{t.currentPrice}: </span>{money(cents, language)}</> : t.priceUnavailable}</strong><div><span>{t.price}</span><small>{t.without}</small></div><div className="product-price-offer">
    {percent !== null && <span className="product-price-comparison"><del><span className="product-sr-only">{t.previousPrice}: </span>{money(previousCents!, language)}</del><span className="product-price-badge"><span className="product-sr-only">{t.sampleDiscount}: </span>−{percent}%</span></span>}
  </div></div>;
}
