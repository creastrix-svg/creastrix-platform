import {useEffect,useLayoutEffect,useMemo,useState,useSyncExternalStore} from 'react';
import {Link} from 'react-router';
import type {AccountCartAdmission} from './accountCartAdmission';
import type {DirectCartAdditions} from './directCartAdditions';
import {additionScope,purchaseDesignDigest} from './directCartAdditions';
import {useAccountCartAccess} from './useAccountCartAccess';
import {PurchaseControls} from '../detail/ProductPurchasePanel';
import {useProductLocale} from '../useProductLocale';
import {productCopy} from '../copy';
import type {CatalogVariant} from '../catalog/localCatalog';
import type {SavedDesign} from '../studio/session/designSnapshot';
import {projectAccountDesign} from './appliedLoopDesign';
import {accountProductLabels} from './accountProductLabels';

import {directAdditionCopy as labels} from './directAdditionCopy';

export function AccountOfferPurchase({admission,writer,offerId,variant,returnTo,design,unitCents=variant.cents}:{
  admission:AccountCartAdmission;writer:DirectCartAdditions;offerId:string;variant:CatalogVariant;returnTo:string;
  design?:SavedDesign;unitCents?:number;
}) {
  const locale=useProductLocale(), t=productCopy[locale], l=labels[locale];
  const {state}=useAccountCartAccess(admission.access);
  const binding=state.phase==='ready'?state.binding:undefined;
  const [quantity,setQuantity]=useState(1);
  // null means a design exists but cannot be represented. Never substitute
  // an undecorated product, even if the plain variant is available.
  const projection=useMemo(()=>{
    if(!design)return undefined;
    try {return variant.studioFormat==='surface-print-v1'?projectAccountDesign(variant.sku,variant.templateRevision,design):null;}
    catch {return null;}
  },[variant,design]);
  const [hashed,setHashed]=useState<{source:NonNullable<typeof projection>;digest:string}>();
  useEffect(()=>{
    if(!projection)return;
    let active=true;
    void purchaseDesignDigest(projection).then(digest=>{if(active)setHashed({source:projection,digest});}).catch(()=>{});
    return ()=>{active=false;};
  },[projection]);
  const status=useSyncExternalStore(writer.subscribe,writer.getSnapshot,writer.getSnapshot);
  useLayoutEffect(()=>{if(binding)writer.open(binding,variant.sku);},[binding,writer,variant.sku]);
  if(state.phase!=='ready'||!binding||binding.ownerId!==admission.userId) {
    return <PurchaseControls locale={locale} stock={variant.stock} remaining={variant.stock} requested={quantity}
      inCart={0} unitCents={unitCents} locked onQuantity={()=>{}} onAdd={()=>{}} exhaustedLabel={t.allInCart}
      feedback={<><span>{state.phase==='opening'?l[0]:l[1]}</span><Link to="/preview/cart">{t.viewCart}</Link></>}/>;
  }
  const matching=state.cart.filter(line=>line.product?.offerId===offerId && line.product.variantId===variant.id);
  const total=matching.reduce((sum,line)=>sum+BigInt(line.quantity),0n);
  const plain=matching.reduce((sum,line)=>sum+(line.designLabel===null?BigInt(line.quantity):0n),0n);
  const exact=projection ? matching.find(line=>hashed?.source===projection && line.lineId===writer.lineFor(binding,variant.sku,hashed.digest))?.quantity??'0' : String(design?0:plain);
  const remaining=Number(total<BigInt(variant.stock)?BigInt(variant.stock)-total:0n);
  const phase=status.scope===additionScope(binding,variant.sku)?status.phase:'closed';
  const retry=phase==='pending'||phase==='conflict';
  const add=async(amount:number,replay:boolean)=>{
    if((replay || projection!==null) && admission.access.ownsDisplay()
        && await writer.submit(binding,variant.sku,amount,replay,projection??undefined))setQuantity(1);
  };
  return <PurchaseControls locale={locale} stock={variant.stock} remaining={remaining} requested={quantity} onQuantity={setQuantity}
    inCart={exact} unitCents={unitCents} onAdd={amount=>void add(amount,false)} exhaustedLabel={t.allInCart}
    locked={projection===null || (phase!=='ready'&&phase!=='rejected')} frozenQuantity={retry||phase==='sending'?status.quantity:undefined}
    returnTo={returnTo} feedback={<>
      {phase==='sending' && <span>{l[2]}</span>}
      {phase==='rejected' && <span>{l[5]}</span>}
      {phase==='storage' && <span>{l[6]}</span>}
      {retry && <><span>{phase==='conflict'?l[7]:l[3].replace('{n}',String(status.quantity))}</span>
        <button className="product-text-button" type="button" onClick={()=>void add(0,true)}>{l[4]}</button></>}
      {projection===null && <span>{t.personalise} · {accountProductLabels[locale][2]}</span>}
      {(!!design||retry||phase==='storage') && exact==='0' && <Link to="/preview/cart">{t.viewCart}</Link>}
    </>}/>;
}
