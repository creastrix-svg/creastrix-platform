import { useContext, useId } from 'react';
import { Link } from 'react-router';
import { money } from '../productDisplay';
import { productCopy } from '../copy';
import { Icon } from '../Icon';
import type { StorefrontLocale } from '../locale';
import QuantityInput from '../QuantityInput';
import { GuestCartActionsEnabled } from '../cart/guestCartActions';
import { useProductLocale } from '../useProductLocale';
import { AccountPurchaseContext } from '../cart/accountPurchaseContext';

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
  const admission=useContext(AccountPurchaseContext);
  const restricted={en:'This action is unavailable for this item in your account cart.',
    de:'Diese Aktion ist für diesen Artikel in deinem Kontowarenkorb nicht verfügbar.',
    ru:'Для этого товара действие в корзине аккаунта недоступно.',pl:'Ta czynność jest niedostępna dla tego produktu w koszyku konta.',
    es:'Esta acción no está disponible para este artículo en la cesta de tu cuenta.',
    it:'Questa azione non è disponibile per questo articolo nel carrello del tuo account.',
    fr:'Cette action est indisponible pour cet article dans le panier de votre compte.'};
  return <section className="product-purchase" aria-label={purchase ? t.add : t.cart}>
    <p id={id}>{admission?.directAdditions ? restricted[locale] : readOnlyMessage[locale]}</p>
    {purchase && <button className="product-primary" type="button" disabled aria-describedby={id}><Icon name="bag" />{t.add}</button>}
    <Link className="product-text-button" to="/preview/cart">{t.viewCart}</Link>
  </section>;
}

/** The item page owns its SKU, draft and availability policy; both product types
 * present the same quantity controls, subtotal and confirmation of the exact line. */
export default function ProductPurchasePanel(props: Props) {
  const guestActions = useContext(GuestCartActionsEnabled);
  if (!guestActions) return <ReadOnlyCartNotice purchase />;
  return <PurchaseControls {...props}/>;
}

/** Pure presentation. Guest and account callers have separate state and command paths. */
export function PurchaseControls({ locale, stock, remaining, requested, inCart, unitCents, onQuantity, onAdd, exhaustedLabel, returnTo,
  locked=false,frozenQuantity,feedback=null }: Omit<Props,'inCart'> & {inCart:number|string;locked?:boolean;frozenQuantity?:number;feedback?:React.ReactNode}) {
  remaining = Math.max(0, remaining);
  const t = productCopy[locale], amount = frozenQuantity??Math.min(requested, remaining);
  return <>
    <section className="product-purchase" aria-label={t.add}>
      <div className="product-stock"><strong>{t.stock}: {stock}</strong>{remaining < stock && <span>{remaining === 0 ? exhaustedLabel : `${t.available}: ${remaining}`}</span>}</div>
      <div className="product-buy-row"><QuantityInput t={t} value={amount} max={remaining} disabled={locked || remaining === 0} onChange={onQuantity} onEmpty={() => onQuantity(1)} />
        <button type="button" className="product-primary" disabled={locked || remaining === 0} onClick={() => onAdd(amount)}><Icon name="bag" />{t.add}</button></div>
      <div className="product-subtotal"><span>{t.subtotal} · {amount} × {money(unitCents, locale)}</span><strong>{money(unitCents * amount, locale)}</strong></div>
    </section>
    <div className="product-buy-feedback" role="status">{feedback}{String(inCart)!=='0' && <><Icon name="check" /><span>{t.alreadyInCart.replace('{n}', String(inCart))}</span><Link to="/preview/cart" state={returnTo ? { cartReturnTo: returnTo } : undefined}>{t.viewCart}</Link></>}</div>
  </>;
}
