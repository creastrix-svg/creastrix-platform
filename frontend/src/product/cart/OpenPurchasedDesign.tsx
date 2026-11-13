import {useEffect,useEffectEvent,useState,type ReactNode} from 'react';
import {Link} from 'react-router';
import {productCopy} from '../copy';
import {useProductLocale} from '../useProductLocale';
import type {VariantId} from '../fixtures/loop';
import {usePersonalizationSession} from '../studio/session/personalizationSession';
import {useStudioCreative} from '../studio/session/studioCreativeContext';
import type {LoadedStudioFont} from '../studio/design/studioFonts';
import {studioCartCopy} from './studioCartCopy';
import {useAccountCartAccess} from './useAccountCartAccess';
import type {AccountCartAdmission} from './accountCartAdmission';
import {readPrivateCartDesign} from './readPrivateCartDesign';

/** A cart link explicitly opens this buyer's saved configuration in their
 * private editor. Failure never opens the plain card with a cheaper subtotal. */
export function OpenPurchasedDesign({admission,variant,variantId=variant,sku,lineId,href,children}:{
  admission:AccountCartAdmission;variant:VariantId;variantId?:string;sku:string;lineId:string;href:string;children:ReactNode;
}){
  const {state}=useAccountCartAccess(admission.access);
  const row=state.phase==='ready'?state.cart.find(line=>line.lineId===lineId):undefined;
  const allowed=!!row && row.product?.sku===sku && row.product.variantId===variantId && row.product.href===href
    && row.product.listed && row.product.studioFormat==='surface-print-v1' && row.product.templateRevision==='2'
    && !!row.product.designId && !!row.product.designRevision;
  const lang=useProductLocale(),labels=studioCartCopy[lang];
  const {dispatch}=usePersonalizationSession(),creative=useStudioCreative();
  const [status,setStatus]=useState<'loading'|'failed'|'ready'>('loading');
  const library=creative.fonts();
  const install=useEffectEvent((objects:Awaited<ReturnType<typeof readPrivateCartDesign>>,fonts:LoadedStudioFont[])=>{
    for(const font of fonts)creative.rememberFont(font);
    dispatch({type:'openPurchased',variant,objects});
    setStatus('ready');
  });
  useEffect(()=>{
    if(!allowed)return;
    const op=new AbortController(),fonts:LoadedStudioFont[]=[];
    const signal=AbortSignal.any([op.signal,AbortSignal.timeout(30000)]);
    void readPrivateCartDesign(admission,lineId,sku,library,signal,fonts).then(objects=>{
      if(!signal.aborted && admission.access.ownsDisplay())install(objects,fonts);
    }).catch(()=>{if(!op.signal.aborted)setStatus('failed');});
    return ()=>op.abort();
  },[allowed,admission,lineId,sku,library]);
  if(status==='ready' && allowed && admission.access.ownsDisplay())return <>{children}</>;
  return <section data-private-account><p role="status">{status==='failed' || (state.phase!=='opening' && !allowed)
    ? labels.restoreFailed : labels.loading}</p><Link className="product-back" to="/preview/cart">{productCopy[lang].viewCart}</Link></section>;
}
