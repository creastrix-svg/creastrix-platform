import type { StorefrontLocale } from './locale';
import { money, discountPercent } from './productDisplay';
import type { ProductCopy } from './copy';
import { loopPricingCopy } from './loopPricingCopy';

export default function ProductPrice({ cents, language, t, previousCents, designFeeCents = 0, hasDesign = designFeeCents > 0, baseNote = t.without }: { cents: number; language: StorefrontLocale; t: ProductCopy; previousCents?: number; designFeeCents?: number; hasDesign?: boolean; baseNote?: string }) {
  const valid = Number.isSafeInteger(cents) && cents >= 0 && Number.isSafeInteger(designFeeCents) && designFeeCents >= 0 && designFeeCents <= cents;
  const baseCents = cents - designFeeCents, percent = valid ? discountPercent(baseCents, previousCents) : null;
  const personalized = valid && hasDesign, copy = loopPricingCopy[language];
  const comparison = percent !== null && <span className="product-price-comparison"><del><span className="product-sr-only">{t.previousPrice}: </span>{money(previousCents!, language)}</del><span className="product-price-badge"><span className="product-sr-only">{t.sampleDiscount}: </span>−{percent}%</span></span>;
  return <div className="product-price-row"><strong className={'product-price' + (percent !== null && !personalized ? ' product-price-sale' : '') + (!valid ? ' product-price-unavailable' : '')}>{valid ? <><span className="product-sr-only">{t.currentPrice}: </span>{money(cents, language)}</> : t.priceUnavailable}</strong><div><span>{t.price}</span>{!personalized && <small>{baseNote}</small>}</div><div className="product-price-offer">
    {personalized ? <div className="product-price-breakdown"><span>{copy.base}: {money(baseCents, language)} {comparison}</span><span>{copy.fee(money(designFeeCents, language))}</span></div> : comparison}
  </div></div>;
}
