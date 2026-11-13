import type {ReactNode,Ref} from 'react';
import {Link} from 'react-router';
import {Icon} from '../Icon';
import type {ProductCopy} from '../copy';

/** The same storefront layout is used by both sources. Storage, prices, edit
 * commands and private design reads remain the responsibility of their caller. */
export function CartLayout({t,back,backRef,notice,feedback,empty,children,lines,units,subtotal,checkout}:{
  t:ProductCopy;back:string;backRef?:Ref<HTMLAnchorElement>;notice?:string;feedback?:ReactNode;empty:boolean;
  children:ReactNode;lines:number;units:ReactNode;subtotal:ReactNode;checkout:ReactNode;
}) {
  return <div className="product-preview-page cart-view">
    <Link ref={backRef} to={back} state={{productReturn:true}} className="product-back"><span className="product-flip"><Icon name="arrow"/></span>{t.continue}</Link>
    <h1>{t.cart}</h1><p className="product-sr-only" role="status">{notice}</p>{feedback}
    {empty ? <section className="product-cart-empty" aria-label={t.emptyCart}>
      <span className="product-cart-empty-icon"><Icon name="bag"/></span><h2>{t.emptyCart}</h2><p>{t.cartEmptyBody}</p>
    </section> : <div className="product-cart-layout"><div className="product-cart-items">{children}</div>
      <aside className="product-cart-summary" aria-label={t.cartSummary}><h2>{t.cartSummary}</h2>
        <dl className="product-cart-counts"><div><dt>{t.cartLineCount}</dt><dd>{lines}</dd></div><div><dt>{t.cartUnitCount}</dt><dd>{units}</dd></div></dl>
        <div className="product-cart-subtotal"><span>{t.cartItemsSubtotal}</span><strong>{subtotal}</strong></div>{checkout}
      </aside></div>}
  </div>;
}

export function CartRow({t,id,name,context,removed,photo,title,detail,stock,notes,price,quantity,subtotal,
  onAction,actionRef,actionDisabled,onDismiss,dismissDisabled,account}:{
  t:ProductCopy;id:string;name:string;context:string;removed:boolean;photo:ReactNode;title:ReactNode;detail:string;
  stock:ReactNode;notes?:ReactNode;price:ReactNode;quantity:ReactNode;subtotal:ReactNode;onAction:()=>void;
  actionRef?:Ref<HTMLButtonElement>;actionDisabled?:boolean;onDismiss?:()=>void;dismissDisabled?:boolean;account?:boolean;
}) {
  return <article className={'product-cart-line'+(removed?' product-cart-line-removed':'')} data-cart-line-id={id}
    data-account-cart-line={account?id:undefined} aria-label={name+' · '+context}>
    {photo}<div className="product-cart-line-copy"><h2>{title}</h2><span>{detail}</span>{stock}{notes}</div>
    <div className="product-cart-controls"><div className="product-cart-unit">{price}</div>{quantity}
      <dl className="product-cart-line-total"><dt>{t.cartLineSubtotal}</dt><dd>{subtotal}</dd></dl>
      <button type="button" disabled={actionDisabled} ref={actionRef} className={'product-text-button product-cart-action'+(removed?' product-cart-restore':'')}
        onClick={event=>{event.currentTarget.focus({preventScroll:true});onAction();}}
        aria-label={`${removed?t.cartRestore:t.remove} · ${context}`}>{!removed && <Icon name="trash"/>}{removed?t.cartRestore:t.remove}</button>
    </div>
    {removed && onDismiss && <button type="button" className="product-cart-dismiss" disabled={dismissDisabled} title={t.cartDismiss}
      aria-label={`${t.cartDismiss} · ${context}`} onClick={onDismiss}><Icon name="close"/></button>}
  </article>;
}
