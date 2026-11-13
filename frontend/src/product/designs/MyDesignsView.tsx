import {useEffect,useRef,useState,type ReactNode} from 'react';
import {Link} from 'react-router';
import type {AccountCartAdmission} from '../cart/accountCartAdmission';
import {CartSignInAgain} from '../cart/CartAccessAction';
import {useAccountCartAccess} from '../cart/useAccountCartAccess';
import {loadLocalCatalog} from '../catalog/localCatalog';
import {thumbnailFor} from '../catalog/photoRenditions';
import Modal from '../Modal';
import LibraryPreview from './LibraryPreview';
import {personalizationCopy} from '../studio/personalizationCopy';
import {useProductLocale} from '../useProductLocale';
import {Icon} from '../Icon';
import {designLibraryCopy} from './designLibraryCopy';
import {LibraryConflict,libraryCommands,listDesigns,type LibraryChange,type LibraryItem} from './designLibraryApi';
import './designLibrary.css';

type Pending={intent:string;body:LibraryChange};
function libraryEditorPath(item:LibraryItem,catalog:Awaited<ReturnType<typeof loadLocalCatalog>>|undefined){
  const found=catalog?.skus.get(item.sku);
  if(!found||found.product.id!==item.offerId||found.variant.id!==item.variantId||!found.product.personalizable
    ||found.variant.studioFormat!=='surface-print-v1'||found.variant.templateRevision!=='2')return;
  const params=new URLSearchParams({variant:item.variantId,savedDesign:item.id});
  if(found.product.href==='/products/loop'&&item.offerId==='loop')return found.product.href+'/personalize?'+params;
  params.set('personalize','surface');return found.product.href+'?'+params;
}
export default function MyDesignsView({admission,extra}:{admission:AccountCartAdmission;extra?:ReactNode}){
  const lang=useProductLocale(),t=designLibraryCopy[lang],{state,retry,retryRead}=useAccountCartAccess(admission.access);
  const permitted=state.phase==='ready'&&!!state.binding&&state.binding.ownerId===admission.userId;
  const [items,setItems]=useState<LibraryItem[]|null>(null),[catalog,setCatalog]=useState<Awaited<ReturnType<typeof loadLocalCatalog>>>();
  const [phase,setPhase]=useState<'idle'|'busy'|'unknown'|'loadError'|'conflict'|'refused'>('idle'),[epoch,reload]=useState(0);
  const [confirm,setConfirm]=useState<LibraryItem>(),pending=useRef<Pending|null>(null),flight=useRef<AbortController|null>(null);
  const [preview,setPreview]=useState<LibraryItem>();
  useEffect(()=>()=>{flight.current?.abort();pending.current=null;},[admission]);
  useEffect(()=>{
    if(!permitted)return;
    const op=new AbortController(),signal=AbortSignal.any([op.signal,AbortSignal.timeout(15000)]);
    void listDesigns(admission,signal).then(next=>{if(!signal.aborted&&admission.access.ownsDisplay())setItems(next);})
      .catch(()=>{if(!op.signal.aborted)setPhase('loadError');});
    void loadLocalCatalog().then(next=>{if(!op.signal.aborted)setCatalog(next);}).catch(()=>{});
    return ()=>op.abort();
  },[admission,permitted,epoch]);
  const refresh=()=>{setPhase('idle');setItems(null);reload(i=>i+1);};
  async function mutate(target:Pending){
    if(state.phase!=='ready'||!state.binding||flight.current||!admission.access.ownsDisplay())return;
    const op=new AbortController();flight.current=op;pending.current=target;setPhase('busy');setConfirm(undefined);
    try{
      await libraryCommands(admission).change(state.binding,target.intent,target.body,op.signal);
      if(!op.signal.aborted&&admission.access.ownsDisplay()){pending.current=null;refresh();}
    }catch(e){
      if(op.signal.aborted||!admission.access.ownsDisplay())return;
      if(e instanceof LibraryConflict){pending.current=null;setPhase('conflict');}
      else if(e instanceof Error&&e.message==='DESIGN_LIBRARY_REFUSED'){pending.current=null;setPhase('refused');}
      else setPhase('unknown');
    }finally{if(flight.current===op)flight.current=null;}
  }
  const action=(item:LibraryItem,copy:boolean)=>void mutate({intent:crypto.randomUUID(),body:copy?
    {action:'copy',id:item.id,expectedVersion:item.version,copyId:crypto.randomUUID()}:{action:'delete',id:item.id,expectedVersion:item.version}});
  const busy=phase!=='idle'||!permitted;
  return <section className="product-preview-page design-library" data-private-account>
    <Link className="product-back" to="/account"><span className="product-flip"><Icon name="arrow"/></span>{t.back}</Link>
    <h1>{t.title}</h1><p className="design-library-intro">{t.intro}</p>{extra}
    {state.phase==='reauth-required'?<CartSignInAgain admission={admission}/>:!permitted ?
      <p role="status">{state.phase==='opening'||state.phase==='recovering'?t.loading:t.failed}
        {['unavailable','read-unavailable'].includes(state.phase)&&<button type="button" onClick={state.phase==='unavailable'?retry:retryRead}>{t.retry}</button>}</p>:
      <>{phase==='unknown'&&<p role="alert">{t.failed} <button type="button" className="product-text-button" onClick={()=>{if(pending.current)void mutate(pending.current);}}>{t.retry}</button></p>}
        {['loadError','conflict','refused'].includes(phase)&&<p role="alert">{phase==='conflict'?t.conflict:t.failed} <button type="button" className="product-text-button" onClick={refresh}>{t.retry}</button></p>}
        {!items&&phase!=='loadError'?<p role="status">{t.loading}</p>:items?.length===0?<p className="product-cart-empty">{t.empty}</p>:
        <ul className="design-library-grid">{items?.map(item=>{
          const candidate=catalog?.skus.get(item.sku),found=candidate?.product.id===item.offerId&&candidate.variant.id===item.variantId?candidate:undefined;
          const open=libraryEditorPath(item,catalog),photo=found?.variant.photos[0];
          return <li key={item.id} className="design-library-card"><div className="design-library-photo">
            {photo?<img src={thumbnailFor(photo)} width="116" height="116" alt="" loading="lazy"/>:<Icon name="image"/>}</div>
            <div><h2>{item.caption||item.offerLabel}</h2><p>{item.offerLabel} · {found?.variant.name[lang]??item.variantLabel}</p>
              <div className="design-library-tools">{open?<Link className="product-secondary" to={open}>{t.open}</Link>:<small>{t.unavailable}</small>}
              <button type="button" className="product-text-button" disabled={busy} onClick={()=>setPreview(item)}>{personalizationCopy[lang].preview}</button>
              <button type="button" className="product-text-button" disabled={busy} onClick={()=>action(item,true)}>{t.copy}</button>
              <button type="button" className="product-text-button" disabled={busy} onClick={()=>setConfirm(item)}>{t.remove}</button></div>
            </div></li>;
        })}</ul>}</>}
    {permitted&&confirm&&<Modal title={t.confirm} closeLabel={t.cancel} onClose={()=>setConfirm(undefined)}><p>{t.confirmText}</p>
      <button type="button" className="product-secondary" onClick={()=>action(confirm,false)}>{t.confirm}</button></Modal>}
    {permitted&&preview&&<LibraryPreview admission={admission} item={preview} colors={catalog?.skus.get(preview.sku)?.variant.colors} close={()=>setPreview(undefined)}/>}
  </section>;
}
