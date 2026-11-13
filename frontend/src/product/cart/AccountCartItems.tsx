import {useEffect,useLayoutEffect,useRef,useState} from 'react';
import type {ReactNode} from 'react';
import {Link,useLocation} from 'react-router';
import {catalogReady,fetchCatalog,loadLocalCatalog} from '../catalog/localCatalog';
import {thumbnailFor} from '../catalog/photoRenditions';
import {checkoutCopy} from '../checkout/checkoutCopy';
import {productCopy} from '../copy';
import {Icon} from '../Icon';
import {money,swatchBackground} from '../productDisplay';
import ProductPrice from '../ProductPrice';
import QuantityInput from '../QuantityInput';
import {useProductLocale} from '../useProductLocale';
import {CartLayout,CartRow} from './CartLayout';
import {cartReturnOfferPath} from './cartOfferView';
import {accountProductLabels} from './accountProductLabels';
import {isLoopFinish} from './appliedLoopDesign';
import {stockShortageNote} from './studioCartCopy';
import type {AccountCartAdmission,AccountCartLines} from './accountCartAdmission';
import type {Binding} from './localCartCommands';
import {useAccountLineEdit} from './useAccountLineEdit';
import CartAppliedDesign from './CartAppliedDesign';
import CartDesignPriceDetails from './CartDesignPriceDetails';
import CartSourceImage from './CartSourceImage';
import {useCartUndoVisit} from './cartUndoVisit';

type Catalog=Awaited<ReturnType<typeof fetchCatalog>>;
const safeNumber=(v:bigint)=>v<=BigInt(Number.MAX_SAFE_INTEGER)?Number(v):undefined;
const feedback={
  en:['The cart changed or this item is no longer available. Check the updated cart.','We could not confirm your change.','Try again','Catalog details are unavailable.'],
  de:['Der Warenkorb hat sich geändert oder der Artikel ist nicht mehr verfügbar. Prüfe den aktuellen Warenkorb.','Die Änderung konnte nicht bestätigt werden.','Erneut versuchen','Katalogdetails sind nicht verfügbar.'],
  ru:['Корзина изменилась или товар больше недоступен. Проверьте обновлённую корзину.','Не удалось подтвердить изменение.','Повторить','Не удалось загрузить данные товаров.'],
  pl:['Koszyk się zmienił lub produkt jest niedostępny. Sprawdź aktualny koszyk.','Nie udało się potwierdzić zmiany.','Spróbuj ponownie','Dane katalogu są niedostępne.'],
  fr:['Le panier a changé ou cet article est indisponible. Vérifiez le panier actualisé.','La modification n’a pas pu être confirmée.','Réessayer','Les informations du catalogue sont indisponibles.'],
  es:['El carrito ha cambiado o el artículo ya no está disponible. Revisa el carrito actualizado.','No se pudo confirmar el cambio.','Reintentar','Los datos del catálogo no están disponibles.'],
  it:['Il carrello è cambiato o l’articolo non è più disponibile. Controlla il carrello aggiornato.','Non è stato possibile confermare la modifica.','Riprova','I dati del catalogo non sono disponibili.'],
};

/** Private lines must still be visible when an offer is withdrawn. This
 * enrichment is public, never transmits buyer data, and never falls back to
 * local fixtures or treats an unknown price as a free item. */
function useCartCatalog(version:string|undefined,needed:boolean,epoch:number){
  const [last,setLast]=useState<{key:string;data?:Catalog}>(),[retry,setRetry]=useState(0);
  const key=[version??'',epoch,retry].join(':');
  useEffect(()=>{
    if(!needed)return;
    let active=true;
    void (catalogReady()?fetchCatalog(window.fetch.bind(window)):loadLocalCatalog()).then(
      data=>{if(active)setLast({key,data});},()=>{if(active)setLast({key});});
    return ()=>{active=false;};
  },[key,needed]);
  const current=last?.key===key?last:undefined;
  return {data:current?.data,loading:needed && !current,failed:needed && !!current && !current.data,retry:()=>setRetry(n=>n+1)};
}

export function AccountCartItems({admission,cart:serverCart,version,binding,extra}:{
  admission:AccountCartAdmission;cart:AccountCartLines;version?:string;binding?:Binding;extra?:ReactNode;
}) {
  const lang=useProductLocale(),t=productCopy[lang],f=feedback[lang],checkout=checkoutCopy[lang];
  const visit=useCartUndoVisit(),undo=visit.account([admission.userId,admission.owner.document,admission.owner.revision,binding?.namespace].join(':'));
  const cart=serverCart.filter(line=>!line.removed||undo.has(line.lineId));
  const editor=useAccountLineEdit(admission,binding,version,undo);
  const {state}=useLocation(),catalog=useCartCatalog(version,cart.some(row=>row.product?.listed),editor.catalogEpoch);
  const section=useRef<HTMLDivElement>(null),[dismiss,setDismiss]=useState<{id:string;index:number}|null>(null),focused=useRef<typeof dismiss>(null);
  useLayoutEffect(()=>{
    const target=dismiss;
    if(!target || focused.current===target || editor.locked || editor.phase!=='idle' || cart.some(line=>line.lineId===target.id))return;
    const buttons=section.current?.querySelectorAll<HTMLButtonElement>('.product-cart-action');
    (buttons?.[Math.min(target.index,cart.length-1)]??section.current?.querySelector<HTMLAnchorElement>('.product-back'))?.focus({preventScroll:true});
    focused.current=target;
  },[cart,dismiss,editor.locked,editor.phase]);
  const active=cart.filter(line=>!line.removed), demand=new Map<string,bigint>();
  for(const line of active){const sku=line.product?.sku;if(sku)demand.set(sku,(demand.get(sku)??0n)+BigInt(line.quantity));}
  const units=active.reduce((n,row)=>n+BigInt(row.quantity),0n);
  const priced=cart.map(line=>{
    const p=line.product,ref=p?.listed&&p.sku?catalog.data?.skus.get(p.sku):undefined;
    const offer=ref?.product.id===p?.offerId && ref?.variant.id===p?.variantId && ref?.product.href===p?.href ?ref:undefined;
    const v=offer?.variant,design=line.designLabel!==null,removed=line.removed===true,q=BigInt(line.quantity);
    const fee=design?offer?.product.designFeeCents??0:0, cents=v?v.cents+fee:undefined;
    const amount=removed?0n:BigInt(cents??0)*q;
    return {line,p,offer,v,design,removed,q,fee,cents,amount};
  });
  const payable=priced.filter(i=>!i.removed && i.p?.listed),totalKnown=payable.every(i=>i.cents!==undefined);
  const total=payable.reduce((n,i)=>n+i.amount,0n);
  const items=priced.map(({line,p,offer,v,design,removed,q,fee,cents,amount},index)=>{
    const waiting=!!p?.listed && catalog.loading;
    const unavailableAmount=<span aria-label={t.priceUnavailable} title={t.priceUnavailable}>—</span>;
    const displayAmount=removed?money(0,lang):waiting?'—':cents===undefined||safeNumber(amount)===undefined?unavailableAmount:money(Number(amount),lang);
    const context=(v?.name[lang]??line.variantLabel)+(design?' · '+line.designLabel:'');
    const href=p?.listed && p.href && !removed?p.href+'?'+new URLSearchParams({variant:p.variantId,...(design?{accountLine:line.lineId}:{})}):undefined;
    const photo=v?.photos[0],fallback=photo?<img src={thumbnailFor(photo)} alt={line.offerLabel+' · '+context} width="160" height="160"/>
      :<span className="product-cart-swatch" style={{background:v?swatchBackground(v.colors):p?.swatch}} role="img" aria-label={t.noPhoto}/>;
    const locked=editor.locked,consumed=p?.sku?demand.get(p.sku)??0n:0n;
    const other=consumed-(removed?0n:q),available=BigInt(v?.stock??0)-other;
    const acceptsIncrease=!!v && (!design || (!!v.studioFormat && p?.studioFormat===v.studioFormat && p?.templateRevision===v.templateRevision));
    // The catalog caps an increase, never an already requested amount. A
    // reduction remains possible on a shortage, withdrawal, or failed lookup.
    const cap=Math.min(1_000_000,Number(!removed && (!acceptsIncrease || q>available)?q:available<0n?0n:available));
    const restoreLimited=removed && !!v && q>available;
    const restoreIneligible=removed && (!v || (design && (!v.studioFormat || p?.studioFormat!==v.studioFormat || p?.templateRevision!==v.templateRevision)));
    const shortage=!removed && !!v && consumed>BigInt(v.stock);
    function render(preview:ReactNode=null,view:ReactNode=null){
      const name=offer?.product.name??line.offerLabel;
      return <CartRow key={line.lineId} account t={t} id={line.lineId} name={name} context={context} removed={removed}
        photo={href?<Link className="product-cart-photo" to={href} aria-label={name+' · '+context}>{preview??fallback}</Link>
          :<div className="product-cart-photo">{preview??fallback}</div>}
        title={href?<Link to={href}>{name}</Link>:name} detail={v?.name[lang]??line.variantLabel}
        stock={<p className="product-cart-stock">{p?.listed ? <>{t.stock}: <strong>{v?.stock??'—'}</strong></>:accountProductLabels[lang][2]}</p>}
        notes={<>{shortage && <p role="status">{stockShortageNote[lang]}</p>}
          {design && <div className="product-cart-design-meta"><span>{line.designLabel}</span>{v && <CartDesignPriceDetails baseCents={v.cents} feeCents={fee} language={lang}/>}
            {!removed && view}</div>}{restoreLimited && <p role="status">{t.restoreLimited}</p>}
            {restoreIneligible && p?.listed && catalog.data && <p role="status">{accountProductLabels[lang][2]}</p>}</>}
        price={cents===undefined?<div className="product-price-row"><strong className="product-price" aria-busy={waiting}>{waiting?'—':unavailableAmount}</strong><div>{t.price}</div></div>
          :design && cents!==undefined?<div className="product-price-row"><div>{t.price}</div><strong className="product-price">{money(cents,lang)}</strong></div>
          :<ProductPrice cents={cents??NaN} previousCents={v?.previousCents} language={lang} t={t}/>}
        quantity={<QuantityInput key={line.quantity+':'+removed} commitOnBlur exactValue={removed?'0':line.quantity} t={t} context={context}
          value={removed?0:safeNumber(q)??Number.MAX_SAFE_INTEGER} max={cap} disabled={locked || removed} decreaseDisabled={q>1_000_001n}
          onChange={n=>{if(n>0 && BigInt(n)!==q)void editor.submit(line.lineId,{action:'quantity',quantity:String(n)});}}/>}
        subtotal={displayAmount} actionDisabled={locked||restoreLimited||restoreIneligible}
        onAction={()=>void editor.submit(line.lineId,{action:removed?'restore':'remove'})}
        dismissDisabled={locked} onDismiss={()=>{setDismiss({id:line.lineId,index});void editor.submit(line.lineId,{action:'dismiss'});}}/>;
    }
    if(!removed && design && p?.templateRevision==='2' && p.studioFormat==='surface-print-v1' && p.sku)
      return <CartAppliedDesign key={line.lineId} admission={admission} lineId={line.lineId} variant={isLoopFinish(p.variantId)?p.variantId:'cobalt'}
        sku={p.sku} flat={p.offerId!=='loop'} swatch={p.swatch} secondSwatch={p.secondSwatch}>{render}</CartAppliedDesign>;
    return render(null,!removed && p?.templateRevision?<CartSourceImage admission={admission} lineId={line.lineId}/>:null);
  });
  const back=catalogReady()?cartReturnOfferPath(state?.cartReturnTo)??'/catalog':'/catalog';
  return <div data-private-account ref={section}><CartLayout t={t} back={back} empty={cart.length===0}
    lines={active.length} units={String(units)} subtotal={totalKnown && safeNumber(total)!==undefined?money(Number(total),lang):catalog.loading?'—':t.priceUnavailable}
    notice={editor.notice==='remove'?t.cartRemoved:editor.notice==='restore'?t.cartRestored:undefined}
    feedback={<>{extra}{editor.phase==='unknown'&&<p role="alert">{f[1]} <button type="button" className="product-text-button" onClick={()=>void editor.retry()}>{f[2]}</button></p>}
      {editor.phase==='refused'&&<p role="status">{f[0]}</p>}{catalog.failed&&<p role="status">{f[3]} <button type="button" className="product-text-button" onClick={catalog.retry}>{f[2]}</button></p>}</>}
    checkout={<button type="button" className="product-primary product-cart-checkout" disabled>{checkout.proceed}<Icon name="arrow"/></button>}>{items}</CartLayout></div>;
}
