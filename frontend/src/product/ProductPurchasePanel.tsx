import { Link } from 'react-router';
import { money } from './catalog';
import { productCopy } from './copy';
import { Icon } from './Icon';
import type { StorefrontLocale } from './locale';
import QuantityInput from './QuantityInput';

type Props = {
  locale: StorefrontLocale; stock: number; remaining: number; unitCents: number;
  requested: number; inCart: number; onQuantity: (quantity: number) => void; onAdd: (quantity: number) => void;
  exhaustedLabel: string; returnTo?: string;
};

/** The item page owns its SKU, draft and availability policy; both product types
 * present the same quantity controls, subtotal and confirmation of the exact line. */
export default function ProductPurchasePanel({ locale, stock, remaining, requested, inCart, unitCents, onQuantity, onAdd, exhaustedLabel, returnTo }: Props) {
  const t = productCopy[locale], amount = Math.min(requested, remaining);
  return <>
    <section className="product-purchase" aria-label={t.add}>
      <div className="product-stock"><strong>{t.stock}: {stock}</strong>{remaining < stock && <span>{remaining === 0 ? exhaustedLabel : `${t.available}: ${remaining}`}</span>}</div>
      <div className="product-buy-row"><QuantityInput t={t} value={amount} max={remaining} disabled={remaining === 0} onChange={onQuantity} onEmpty={() => onQuantity(1)} />
        <button type="button" className="product-primary" disabled={remaining === 0} onClick={() => onAdd(amount)}><Icon name="bag" />{t.add}</button></div>
      <div className="product-subtotal"><span>{t.subtotal} · {amount} × {money(unitCents, locale)}</span><strong>{money(unitCents * amount, locale)}</strong></div>
    </section>
    <div className="product-buy-feedback" role="status">{inCart > 0 && <><Icon name="check" /><span>{t.alreadyInCart.replace('{n}', String(inCart))}</span><Link to="/preview/cart" state={returnTo ? { cartReturnTo: returnTo } : undefined}>{t.viewCart}</Link></>}</div>
  </>;
}
