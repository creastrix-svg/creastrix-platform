import { useLayoutEffect, useRef } from 'react';
import { Link, useLocation } from 'react-router';
import { useAppDispatch, useAppSelector } from '../../hooks';
import { isVariantId } from '../fixtures/loop';
import { money } from '../productDisplay';
import { cartOfferView, cartReturnOfferPath } from './cartOfferView';
import { tideOffer } from '../fixtures/tideOffer';
import { cartCount, cartDisplayLines, cartLinePrice, cartLineRemoved, cartLineRestored, cartQuantityChanged, cartRemovedLineDismissed, cartTotal } from '../demoStore';
import { productCopy } from '../copy';
import { checkoutCopy } from '../checkout/checkoutCopy';
import { Icon } from '../Icon';
import ProductPrice from '../ProductPrice';
import QuantityInput from '../QuantityInput';
import { useProductLocale } from '../useProductLocale';
import { cartLineId, cartStockShortages, lineCapacity, variantCartQuantity } from '../demoStore';
import DesignThumbnail from '../studio/preview/DesignThumbnail';
import { cartDesignLabel, stockShortageNote, studioCartCopy } from './studioCartCopy';
import CartDesignPriceDetails from './CartDesignPriceDetails';

export default function CartView() {
  const language = useProductLocale(), t = productCopy[language], checkout = checkoutCopy[language];
  const demo = useAppSelector(state => state.productDemo), dispatch = useAppDispatch();
  const lines = cartDisplayLines(demo);
  const shortages = cartStockShortages(demo);
  const c = studioCartCopy[language];
  const change = demo.cartChange;
  const rowActions = useRef<Partial<Record<string, HTMLButtonElement | null>>>({}), continueLink = useRef<HTMLAnchorElement>(null);
  const pendingDismiss = useRef<{ revision: number; id: string; removedRevision: number; index: number } | null>(null);
  useLayoutEffect(() => {
    const request = pendingDismiss.current; pendingDismiss.current = null;
    // Only this exact successful local X may move focus; shared/programmatic changes cannot.
    if (!request || change.kind !== 'dismissed' || change.revision !== request.revision || cartLineId(change) !== request.id || change.removedRevision !== request.removedRevision) return;
    const next = demo.cartOrder[Math.min(request.index, demo.cartOrder.length - 1)];
    (next ? rowActions.current[next] : continueLink.current)?.focus({ preventScroll: true });
  }, [change, demo.cartOrder]);
  const changedVariant = change.kind === 'other' ? null : change.variant;
  const changed = changedVariant === null ? null : cartOfferView(changedVariant, language);
  const notice = !changed ? '' : `${change.kind === 'restored' ? t.cartRestored : t.cartRemoved}: ${changed.name} · ${changed.detail}`;
  const { state } = useLocation();
  const sameSku = !state?.cartReturnTo && lines.length > 0 && lines.every(line => line.variant === lines[0].variant);
  const source = state?.cartReturnTo === tideOffer.href ? tideOffer.href : cartReturnOfferPath(state?.cartReturnTo)
    ?? (sameSku && !isVariantId(lines[0].variant) ? cartOfferView(lines[0].variant, language).href : undefined);
  const product = source && source !== '/products/loop' ? source : '/products/loop?variant=' + demo.selectedVariant;
  return <div className="product-preview-page cart-view">
    <Link ref={continueLink} to={product} state={{ productReturn: true }} className="product-back"><span className="product-flip"><Icon name="arrow" /></span>{t.continue}</Link>
    <h1>{t.cart}</h1>
    <p className="product-sr-only" role="status">{notice}</p>
    {lines.length === 0 ? <section className="product-cart-empty" aria-label={t.emptyCart}>
      <span className="product-cart-empty-icon"><Icon name="bag" /></span><h2>{t.emptyCart}</h2><p>{t.cartEmptyBody}</p>
    </section> : <div className="product-cart-layout"><div className="product-cart-items">
      {lines.map((line, index) => {
        const offer = cartOfferView(line.variant, language), removed = line.removedRevision !== null;
        const { baseCents, feeCents, unitCents } = cartLinePrice(line);
        const id = cartLineId(line), restoreUnavailable = removed && (demo.cartRemoved[id]?.quantity ?? 0) > offer.stock - variantCartQuantity(demo, line.variant);
        const designLabel = cartDesignLabel(line, language), context = offer.detail + (designLabel ? ' · ' + designLabel : '');
        // A finish alone cannot distinguish two saved designs or a plain cart
        // line from an unrelated catalogue preview of the same colour.
        const itemHref = !removed && isVariantId(line.variant)
          ? '/products/loop?' + new URLSearchParams({ variant: line.variant, cartLine: id })
          : offer.href;
        const image = line.design && isVariantId(line.variant)
          ? <DesignThumbnail design={line.design} variant={line.variant} label={designLabel} />
          : offer.photo ? <img src={offer.photo} alt={offer.alt} width="160" height="160" />
          : <span className="product-cart-swatch" style={{ background: offer.swatch }} aria-label={t.noPhoto} />;
        const inactiveDesign = removed && !!line.design;
        return <article className={'product-cart-line' + (removed ? ' product-cart-line-removed' : '')} key={id} data-cart-line-id={id} aria-label={offer.name + ' · ' + context}>
          {inactiveDesign ? <div className="product-cart-photo">{image}</div>
            : <Link className="product-cart-photo" to={itemHref} aria-label={offer.name + ' · ' + context}>{image}</Link>}
          <div className="product-cart-line-copy"><h2>{inactiveDesign ? offer.name : <Link to={itemHref}>{offer.name}</Link>}</h2><span>{offer.detail}</span>
            <p className="product-cart-stock">{t.stock}: <strong>{offer.stock}</strong></p>
            {!removed && shortages.includes(line.variant) && <p role="status">{stockShortageNote[language]}</p>}
            {line.design && <div className="product-cart-design-meta"><span>{designLabel}</span><CartDesignPriceDetails baseCents={baseCents} feeCents={feeCents} language={language} />{!removed && <Link className="product-text-button" aria-label={`${c.edit} · ${context}`} to={`/products/loop/personalize?variant=${line.variant}&edit=${encodeURIComponent(id)}`}>{c.edit}</Link>}</div>}
            {restoreUnavailable && <p role="status">{t.restoreLimited}</p>}
          </div>
          <div className="product-cart-controls">
            <div className="product-cart-unit">{line.design
              ? <div className="product-price-row"><div>{t.price}</div><strong className="product-price">{money(unitCents, language)}</strong></div>
              : <ProductPrice cents={unitCents} previousCents={offer.previousCents} language={language} t={t} />}</div>
            <QuantityInput key={removed ? 'removed' : 'active'} context={context} value={line.quantity} max={lineCapacity(demo, line)} disabled={removed} onChange={quantity => dispatch(cartQuantityChanged({ variant: line.variant, id: line.id, quantity }))} t={t} />
            <dl className="product-cart-line-total"><dt>{t.cartLineSubtotal}</dt><dd>{money(unitCents * line.quantity, language)}</dd></dl>
            <button type="button" disabled={restoreUnavailable} ref={element => { rowActions.current[id] = element; }} className={'product-text-button product-cart-action' + (removed ? ' product-cart-restore' : '')} onClick={event => {
              // Keep the same keyed action mounted and focused across remove/restore, without scrolling.
              event.currentTarget.focus({ preventScroll: true });
              dispatch(removed ? cartLineRestored({ variant: line.variant, id: line.id, revision: line.removedRevision! }) : cartLineRemoved(id));
            }} aria-label={`${removed ? t.cartRestore : t.remove} · ${context}`}>{!removed && <Icon name="trash" />}{removed ? t.cartRestore : t.remove}</button>
          </div>
          {removed && <button type="button" className="product-cart-dismiss" title={t.cartDismiss} aria-label={`${t.cartDismiss} · ${context}`} onClick={() => {
            pendingDismiss.current = { revision: change.revision + 1, id, removedRevision: line.removedRevision!, index };
            dispatch(cartRemovedLineDismissed({ variant: line.variant, id: line.id, revision: line.removedRevision! }));
          }}><Icon name="close" /></button>}
        </article>;
      })}
    </div><aside className="product-cart-summary" aria-label={t.cartSummary}>
      <h2>{t.cartSummary}</h2><dl className="product-cart-counts"><div><dt>{t.cartLineCount}</dt><dd>{demo.cart.length}</dd></div><div><dt>{t.cartUnitCount}</dt><dd>{cartCount(demo)}</dd></div></dl>
      <div className="product-cart-subtotal"><span>{t.cartItemsSubtotal}</span><strong>{money(cartTotal(demo), language)}</strong></div>
      {demo.cart.length > 0 && shortages.length === 0 ? <Link className="product-primary product-cart-checkout" to="/preview/checkout" state={{cartReturnTo: product}}>{checkout.proceed}<Icon name="arrow" /></Link>
        : <button type="button" className="product-primary product-cart-checkout" disabled>{checkout.proceed}<Icon name="arrow" /></button>}
    </aside></div>}
  </div>;
}
