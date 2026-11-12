import { Link } from 'react-router';
import type { VariantId } from '../fixtures/loop';
import { money } from '../productDisplay';
import { loopDesignFee } from '../loopPricing';
import { loopPricingCopy } from '../loopPricingCopy';
import { productCopy } from '../copy';
import { Icon } from '../Icon';
import { AppliedDesignSummary, AppliedPersonalization } from './PersonalizationView';
import type { CompositionDocument } from './design/studioComposition';
import { cartItemPersonalization, studioCartCopy } from '../cart/studioCartCopy';
import { canPersonalize } from './studioProducts';
import type { StudioProduct } from './studioProducts';
import { useProductLocale } from '../useProductLocale';

/** Seller-configured fixture capabilities, not a buyer-controlled switch.
 * The same gate is also checked by the studio on direct route entry. */
type CartItemContext = { id: string; applied?: CompositionDocument; onRemove: () => void };
export default function PersonalizationEntry({ profile, variant, feeCents = 0, cartItem, editingEnabled = true }: {
  profile: StudioProduct; variant: VariantId; feeCents?: number; cartItem?: CartItemContext; editingEnabled?: boolean;
}) {
  const locale = useProductLocale(), t = productCopy[locale], copy = cartItemPersonalization[locale];
  const canEdit = editingEnabled && canPersonalize(profile);
  if (!canEdit && !cartItem?.applied) return null;
  const destination = '/products/loop/personalize?variant=' + variant + (cartItem?.applied
    ? '&edit=' + encodeURIComponent(cartItem.id) : profile.id === 'lamp' ? '&product=lamp' : '');
  return <section className="product-personalisation" aria-label={t.personalise}>
    <div><strong>{t.personalise}</strong><span>{cartItem?.applied ? copy.scope : t.personalIntro}</span>{profile.id === 'loop' && <small>{feeCents ? loopPricingCopy[locale].fee(money(feeCents, locale)) : loopPricingCopy[locale].from(money(loopDesignFee(), locale))}</small>}</div>
    {canEdit && <Link to={destination}>
      <Icon name="spark" /><span>{cartItem ? (cartItem.applied ? studioCartCopy[locale].edit : copy.newItem) : t.personalButton}</span><Icon name="arrow" />
    </Link>}
    {cartItem ? cartItem.applied && <AppliedDesignSummary variant={variant} applied={cartItem.applied}
      note={t.personalBody} onRemove={cartItem.onRemove} /> : profile.id === 'loop' && <AppliedPersonalization variant={variant} />}
  </section>;
}
