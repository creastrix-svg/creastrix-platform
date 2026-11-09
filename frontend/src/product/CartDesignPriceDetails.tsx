import { money } from './catalog';
import type { StorefrontLocale } from './locale';
import { loopPricingCopy } from './loopPricingCopy';

/** Keep the explanation beside the design, not inside the narrow numeric columns. */
export default function CartDesignPriceDetails({ baseCents, feeCents, language }: {
  baseCents: number; feeCents: number; language: StorefrontLocale;
}) {
  if (feeCents === 0) return null;
  const copy = loopPricingCopy[language];
  return <small>{copy.base}: {money(baseCents, language)} · {copy.fee(money(feeCents, language))}</small>;
}
