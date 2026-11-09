import { Link } from 'react-router';
import type { VariantId } from './catalog';
import { money } from './catalog';
import { LOOP_DESIGN_FEE_CENTS } from './loopPricing';
import { loopPricingCopy } from './loopPricingCopy';
import { productCopy } from './copy';
import { Icon } from './Icon';
import { AppliedPersonalization } from './PersonalizationView';
import { canPersonalize } from './studioProducts';
import type { StudioProduct } from './studioProducts';
import { useProductLocale } from './useProductLocale';

/** Seller-configured fixture capabilities, not a buyer-controlled switch.
 * The same gate is also checked by the studio on direct route entry. */
export default function PersonalizationEntry({ profile, variant, feeCents = 0 }: { profile: StudioProduct; variant: VariantId; feeCents?: number }) {
  const locale = useProductLocale(), t = productCopy[locale];
  if (!canPersonalize(profile)) return null;
  return <section className="product-personalisation" aria-label={t.personalise}>
    <div><strong>{t.personalise}</strong><span>{t.personalIntro}</span>{profile.id === 'loop' && <small>{feeCents ? loopPricingCopy[locale].fee(money(feeCents, locale)) : loopPricingCopy[locale].from(money(LOOP_DESIGN_FEE_CENTS, locale))}</small>}</div>
    <Link to={'/products/loop/personalize?variant=' + variant + (profile.id === 'lamp' ? '&product=lamp' : '')}>
      <Icon name="spark" /><span>{t.personalButton}</span><Icon name="arrow" />
    </Link>
    {profile.id === 'loop' && <AppliedPersonalization variant={variant} />}
  </section>;
}
