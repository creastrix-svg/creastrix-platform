import { useLayoutEffect, useRef } from 'react';
import { Link } from 'react-router';
import { useAppDispatch, useAppSelector } from '../hooks';
import { mediaForVariant, money, photoDescription, swatchBackground, variantById } from './catalog';
import type { VariantId } from './catalog';
import { cartCount, cartDisplayLines, cartLineRemoved, cartLineRestored, cartQuantityChanged, cartRemovedLineDismissed, cartTotal } from './demoStore';
import { productCopy } from './copy';
import { checkoutCopy } from './checkoutCopy';
import { Icon } from './Icon';
import ProductPrice from './ProductPrice';
import QuantityInput from './QuantityInput';
import { useProductLocale } from './useProductLocale';

export default function CartView() {
  const language = useProductLocale(), t = productCopy[language], checkout = checkoutCopy[language];
  const demo = useAppSelector(state => state.productDemo), dispatch = useAppDispatch();
  const lines = cartDisplayLines(demo);
  const change = demo.cartChange;
  const rowActions = useRef<Partial<Record<VariantId, HTMLButtonElement | null>>>({}), continueLink = useRef<HTMLAnchorElement>(null);
  const pendingDismiss = useRef<{ revision: number; variant: VariantId; removedRevision: number; index: number } | null>(null);
  useLayoutEffect(() => {
    const request = pendingDismiss.current; pendingDismiss.current = null;
    // Only this exact successful local X may move focus; shared/programmatic changes cannot.
    if (!request || change.kind !== 'dismissed' || change.revision !== request.revision || change.variant !== request.variant || change.removedRevision !== request.removedRevision) return;
    const next = demo.cartOrder[Math.min(request.index, demo.cartOrder.length - 1)];
    (next ? rowActions.current[next] : continueLink.current)?.focus({ preventScroll: true });
  }, [change, demo.cartOrder]);
  const changedVariant = change.kind === 'other' ? null : change.variant;
  const notice = changedVariant === null ? '' : `${change.kind === 'restored' ? t.cartRestored : t.cartRemoved}: Loop · ${variantById(changedVariant).name[language]}`;
  const product = '/products/loop?variant=' + demo.selectedVariant;
  return <div className="product-preview-page cart-view">
    <Link ref={continueLink} to={product} state={{ productReturn: true }} className="product-back"><span className="product-flip"><Icon name="arrow" /></span>{t.continue}</Link>
    <h1>{t.cart}</h1>
    <p className="product-sr-only" role="status">{notice}</p>
    {lines.length === 0 ? <section className="product-cart-empty" aria-label={t.emptyCart}>
      <span className="product-cart-empty-icon"><Icon name="bag" /></span><h2>{t.emptyCart}</h2><p>{t.cartEmptyBody}</p>
    </section> : <div className="product-cart-layout"><div className="product-cart-items">
      {lines.map((line, index) => {
        const variant = variantById(line.variant), photo = mediaForVariant(line.variant).photos[0], removed = line.removedRevision !== null;
        return <article className={'product-cart-line' + (removed ? ' product-cart-line-removed' : '')} key={line.variant} aria-label={'Loop · ' + variant.name[language]}>
          <Link className="product-cart-photo" to={'/products/loop?variant=' + line.variant} aria-label={'Loop · ' + variant.name[language]}>
            {photo ? <img src={photo} alt={photoDescription(t.photoAlt, variant.name[language])} width="160" height="160" /> : <span className="product-cart-swatch" style={{ background: swatchBackground(variant.colors) }} aria-label={t.noPhoto} />}
          </Link>
          <div className="product-cart-line-copy"><h2><Link to={'/products/loop?variant=' + line.variant}>Loop</Link></h2><span>{variant.name[language]}</span>
            <p className="product-cart-stock">{t.stock}: <strong>{variant.stock}</strong> {t.pieces}</p>
          </div>
          <div className="product-cart-controls">
            <div className="product-cart-unit"><ProductPrice cents={variant.cents} previousCents={variant.previousCents} language={language} t={t} /></div>
            <QuantityInput key={removed ? 'removed' : 'active'} context={variant.name[language]} value={line.quantity} max={variant.stock} disabled={removed} onChange={quantity => dispatch(cartQuantityChanged({ variant: line.variant, quantity }))} t={t} />
            <dl className="product-cart-line-total"><dt>{t.cartLineSubtotal}</dt><dd>{money(variant.cents * line.quantity, language)}</dd></dl>
            <button type="button" ref={element => { rowActions.current[line.variant] = element; }} className={'product-text-button product-cart-action' + (removed ? ' product-cart-restore' : '')} onClick={event => {
              // Keep the same keyed action mounted and focused across remove/restore, without scrolling.
              event.currentTarget.focus({ preventScroll: true });
              dispatch(removed ? cartLineRestored({ variant: line.variant, revision: line.removedRevision! }) : cartLineRemoved(line.variant));
            }} aria-label={`${removed ? t.cartRestore : t.remove} · ${variant.name[language]}`}>{!removed && <Icon name="trash" />}{removed ? t.cartRestore : t.remove}</button>
          </div>
          {removed && <button type="button" className="product-cart-dismiss" title={t.cartDismiss} aria-label={`${t.cartDismiss} · ${variant.name[language]}`} onClick={() => {
            pendingDismiss.current = { revision: change.revision + 1, variant: line.variant, removedRevision: line.removedRevision!, index };
            dispatch(cartRemovedLineDismissed({ variant: line.variant, revision: line.removedRevision! }));
          }}><Icon name="close" /></button>}
        </article>;
      })}
    </div><aside className="product-cart-summary" aria-label={t.cartSummary}>
      <h2>{t.cartSummary}</h2><dl className="product-cart-counts"><div><dt>{t.cartLineCount}</dt><dd>{demo.cart.length}</dd></div><div><dt>{t.cartUnitCount}</dt><dd>{cartCount(demo)}</dd></div></dl>
      <div className="product-cart-subtotal"><span>{t.cartItemsSubtotal}</span><strong>{money(cartTotal(demo), language)}</strong></div>
      {demo.cart.length > 0 ? <Link className="product-primary product-cart-checkout" to="/preview/checkout">{checkout.proceed}<Icon name="arrow" /></Link>
        : <button type="button" className="product-primary product-cart-checkout" disabled>{checkout.proceed}<Icon name="arrow" /></button>}
    </aside></div>}
  </div>;
}
