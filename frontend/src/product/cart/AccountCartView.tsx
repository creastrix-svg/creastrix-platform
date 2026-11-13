import { useAppSelector } from '../../hooks';
import { sameOwner } from '../../authBoundary';
import { productCopy } from '../copy';
import { useProductLocale } from '../useProductLocale';
import { useAccountCartAccess } from './useAccountCartAccess';
import type { AccountCartAdmission } from './accountCartAdmission';
import { Link } from 'react-router';
import CartSourceImage from './CartSourceImage';
import { accountProductLabels } from './accountProductLabels';
import { isLoopFinish } from './appliedLoopDesign';
import CartAppliedDesign from './CartAppliedDesign';
import { permanentCatalogMode, sharedCatalogMode } from '../catalog/permanentCatalog';
import { PendingDirectAdditions } from './PendingDirectAdditions';

// Pending or denied access is not an empty cart. Only a confirmed read can display lines.
const messages = {
  en: ['Opening your cart…', 'Your cart is temporarily unavailable.', 'Retry', 'Retry loading cart'],
  de: ['Warenkorb wird geöffnet…', 'Dein Warenkorb ist vorübergehend nicht verfügbar.', 'Erneut versuchen', 'Warenkorb erneut laden'],
  ru: ['Открываем корзину…', 'Корзина временно недоступна.', 'Повторить', 'Повторить загрузку корзины'],
  pl: ['Otwieranie koszyka…', 'Twój koszyk jest chwilowo niedostępny.', 'Spróbuj ponownie', 'Załaduj koszyk ponownie'],
  es: ['Abriendo tu cesta…', 'Tu cesta no está disponible temporalmente.', 'Reintentar', 'Volver a cargar la cesta'],
  fr: ['Ouverture du panier…', 'Votre panier est temporairement indisponible.', 'Réessayer', 'Recharger le panier'],
  it: ['Apertura del carrello…', 'Il tuo carrello è temporaneamente non disponibile.', 'Riprova', 'Ricarica il carrello'],
};

export default function AccountCartView({ admission }: { admission: AccountCartAdmission }) {
  const auth = useAppSelector(state => state.authentication);
  const locale = useProductLocale(), t = productCopy[locale];
  const [opening, unavailable, retryLabel, retryReadLabel] = messages[locale];
  const matches = auth.phase === 'AUTHENTICATED' && auth.user?.id === admission.userId
    && sameOwner(auth.owner, admission.owner);
  const { state, retry, retryRead } = useAccountCartAccess(matches ? admission.access : null);
  return <section className="product-preview-page cart-view">
    <h1>{t.cart}</h1>
    {state.phase === 'ready' ? <div data-private-account>
      {permanentCatalogMode && admission.directAdditions && state.binding &&
        <PendingDirectAdditions admission={admission} writer={admission.directAdditions} binding={state.binding}/>}
      {state.cart.length === 0 ? <p>{t.emptyCart}</p> : <ul>{state.cart.map(line =>
        <li key={line.lineId} data-account-cart-line={line.lineId}>{line.product?.listed && (!sharedCatalogMode || line.product.href)
          ? <Link to={(line.product.href??('/products/' + line.product.offerId)) + '?variant=' + line.product.variantId
            + (sharedCatalogMode && line.designLabel!==null ? '&accountLine='+line.lineId : '')}>{line.offerLabel}</Link>
          : <strong>{line.offerLabel}</strong>} · {line.variantLabel}
          {line.designLabel !== null && <> · {line.designLabel}</>} × {line.quantity}
          {line.product && !line.product.listed && <> · {accountProductLabels[locale][2]}</>}
          {line.product?.templateRevision && <> · {accountProductLabels[locale][0]}: {line.product.templateRevision}
            {permanentCatalogMode && line.product.templateRevision==='2' && (sharedCatalogMode
              ? line.product.studioFormat==='surface-print-v1' && !!line.product.sku : line.product.offerId==='loop' && isLoopFinish(line.product.variantId))
              ? <CartAppliedDesign key={line.lineId} admission={admission} lineId={line.lineId} sku={line.product.sku??line.product.variantId}
                  variant={isLoopFinish(line.product.variantId)?line.product.variantId:'cobalt'}
                  flat={sharedCatalogMode && line.product.offerId!=='loop'}
                  swatch={line.product.swatch} secondSwatch={line.product.secondSwatch}/>
              : <CartSourceImage key={line.lineId + ':' + line.product.templateRevision} admission={admission} lineId={line.lineId}/>}</>}
        </li>)}</ul>}
    </div> : <>
      <p role="status">{state.phase === 'opening' || state.phase === 'recovering' ? opening : unavailable}</p>
      {state.phase === 'unavailable' && <button className="product-secondary" type="button" onClick={retry}>{retryLabel}</button>}
      {state.phase === 'read-unavailable' && <button className="product-secondary" type="button" onClick={retryRead}>{retryReadLabel}</button>}
    </>}
  </section>;
}
