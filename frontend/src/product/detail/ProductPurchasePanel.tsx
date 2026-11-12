import { useContext, useId } from 'react';
import { Link } from 'react-router';
import { money } from '../productDisplay';
import { productCopy } from '../copy';
import { Icon } from '../Icon';
import type { StorefrontLocale } from '../locale';
import QuantityInput from '../QuantityInput';
import { GuestCartActionsEnabled } from '../cart/guestCartActions';
import { useProductLocale } from '../useProductLocale';

type Props = {
  locale: StorefrontLocale; stock: number; remaining: number; unitCents: number;
  requested: number; inCart: number; onQuantity: (quantity: number) => void; onAdd: (quantity: number) => void;
  exhaustedLabel: string; returnTo?: string;
};

const readOnlyMessage: Record<StorefrontLocale, string> = {
  en: 'Your account cart is read-only. Items cannot be added or changed.',
  de: 'Dein Konto-Warenkorb ist schreibgeschützt. Artikel lassen sich nicht hinzufügen oder ändern.',
  ru: 'Корзина аккаунта доступна только для просмотра. Добавлять или изменять товары пока нельзя.',
  pl: 'Koszyk na Twoim koncie jest tylko do odczytu. Nie można dodawać ani zmieniać produktów.',
  es: 'La cesta de tu cuenta es de solo lectura. No puedes añadir ni modificar artículos.',
  fr: 'Le panier de votre compte est en lecture seule. Aucun article ne peut être ajouté ou modifié.',
  it: 'Il carrello del tuo account è di sola lettura. Non puoi aggiungere o modificare articoli.',
};

export function ReadOnlyCartNotice({ purchase = false }: { purchase?: boolean }) {
  const locale = useProductLocale(), t = productCopy[locale], id = useId();
  return <section className="product-purchase" aria-label={purchase ? t.add : t.cart}>
    <p id={id}>{readOnlyMessage[locale]}</p>
    {purchase && <button className="product-primary" type="button" disabled aria-describedby={id}><Icon name="bag" />{t.add}</button>}
    <Link className="product-text-button" to="/preview/cart">{t.viewCart}</Link>
  </section>;
}

/** The item page owns its SKU, draft and availability policy; both product types
 * present the same quantity controls, subtotal and confirmation of the exact line. */
export default function ProductPurchasePanel({ locale, stock, remaining, requested, inCart, unitCents, onQuantity, onAdd, exhaustedLabel, returnTo }: Props) {
  const guestActions = useContext(GuestCartActionsEnabled);
  if (!guestActions) return <ReadOnlyCartNotice purchase />;
  remaining = Math.max(0, remaining);
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
