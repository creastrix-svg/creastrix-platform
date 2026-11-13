import {useContext,useEffect,useEffectEvent,useRef,useState,type ReactNode} from 'react';
import {Link} from 'react-router';
import {Spinner} from '../Icon';
import type {VariantId} from '../fixtures/loop';
import type {AccountCartAdmission} from '../cart/accountCartAdmission';
import {AccountPurchaseContext} from '../cart/accountPurchaseContext';
import type {AppliedLoopDesign} from '../cart/appliedLoopDesign';
import {decodePrivateLayers} from '../cart/readPrivateCartDesign';
import {useAccountCartAccess} from '../cart/useAccountCartAccess';
import {CartSignInAgain} from '../cart/CartAccessAction';
import type {CompositionDocument,StudioObject} from '../studio/design/studioComposition';
import {useStudioCreative} from '../studio/session/studioCreativeContext';
import {usePersonalizationSession} from '../studio/session/personalizationSession';
import type {LoadedStudioFont} from '../studio/design/studioFonts';
import {useProductLocale} from '../useProductLocale';
import {designLibraryCopy} from './designLibraryCopy';
import {editorScope,LibraryConflict,libraryCommands,openDesign,type LibraryChange} from './designLibraryApi';

export function MissingLibraryDesign(){
  const t=designLibraryCopy[useProductLocale()],account=useContext(AccountPurchaseContext);
  return <section className="product-preview-page"><p role="alert">{t.unavailable}</p>
    {account?<Link className="product-back" to="/account/designs">{t.title}</Link>
      :<Link className="product-back" to="/login">{t.signIn}</Link>}</section>;
}
/** A fresh open installs only an editable draft. Applying it to the product
 * (and later buying it) is always a separate explicit action. */
export function LoadLibraryStudio({id,admission,sku,offerId,variantId,slot,children}:{
  id:string;admission:AccountCartAdmission;sku:string;offerId:string;variantId:string;slot:VariantId;
  children:(version:string)=>ReactNode;
}){
  const t=designLibraryCopy[useProductLocale()],creative=useStudioCreative(),{dispatch}=usePersonalizationSession();
  const library=creative.fonts(),[state,setState]=useState<'loading'|'failed'|{version:string;scope:string}>('loading'),[retry,setRetry]=useState(0);
  const scope=JSON.stringify([editorScope(admission),id,sku,offerId,variantId,slot]),installed=useRef<string|null>(null);
  const install=useEffectEvent((objects:StudioObject[],fonts:LoadedStudioFont[],version:string)=>{
    fonts.forEach(f=>creative.rememberFont(f));dispatch({type:'openLibraryDraft',variant:slot,objects});installed.current=scope;setState({version,scope});
  });
  useEffect(()=>{
    if(installed.current===scope)return;
    installed.current=null;
    const op=new AbortController(),signal=AbortSignal.any([op.signal,AbortSignal.timeout(30000)]);
    const current=()=>!signal.aborted && admission.access.ownsDisplay();
    void (async()=>{
      const {item,design}=await openDesign(admission,id,signal);
      if(!current()||item.offerId!==offerId||item.variantId!==variantId||item.sku!==sku)throw Error('DESIGN_MISMATCH');
      const fonts:LoadedStudioFont[]=[],layers=await decodePrivateLayers(design,library,signal,current,fonts);
      if(current())install(layers,fonts,item.version);
    })().catch(()=>{if(!op.signal.aborted)setState('failed');});
    return ()=>op.abort();
  },[admission,id,sku,offerId,variantId,library,retry,scope]);
  if(typeof state==='object'&&state.scope===scope)return <>{children(state.version)}</>;
  return <section data-private-account className="product-preview-page"><p role="status">{state==='loading'?t.loading:t.failed}</p>
    {state==='failed'&&<button type="button" className="product-secondary" onClick={()=>{setState('loading');setRetry(n=>n+1);}}>{t.retry}</button>}
    <Link className="product-back" to="/account/designs">{t.title}</Link></section>;
}

type PendingSave={intent:string;change:Extract<LibraryChange,{action:'save'}>;snapshot:AppliedLoopDesign;mark:CompositionDocument;staged:boolean};
type SaveProps={
  admission:AccountCartAdmission;doc:CompositionDocument;snapshot:()=>AppliedLoopDesign;enabled:boolean;original?:{id:string;version:string};
  onCreated?:(id:string)=>void;
};
export function SaveLibraryDesign(props:SaveProps){
  return <SaveOwnedDesign key={editorScope(props.admission)} {...props}/>;
}
function SaveOwnedDesign({admission,doc,snapshot,enabled,original,onCreated}:SaveProps){
  const t=designLibraryCopy[useProductLocale()],{state,retry,retryRead}=useAccountCartAccess(admission.access);
  const [status,setPhase]=useState<'idle'|'saved'|'retry'|'failed'|'conflict'|{writing:AccountCartAdmission}>('idle');
  const phase=typeof status==='string'?status:status.writing===admission?'busy':'retry';
  const accepted=useRef(original),[last,setLast]=useState<CompositionDocument|null>(original?doc:null),pending=useRef<PendingSave|null>(null);
  const flight=useRef<AbortController|null>(null);
  // A native chooser may refresh this admission without leaving the editor.
  // Retire the previous request, not its immutable intent: the new binding
  // may retry it explicitly. Changing buyer/document unmounts this keyed state.
  useEffect(()=>()=>{flight.current?.abort();flight.current=null;},[admission]);
  const dirty=last!==doc;
  async function save(){
    if(flight.current||!admission.access.ownsDisplay()||state.phase!=='ready'||!state.binding||(!enabled&&!pending.current))return;
    const op=new AbortController();flight.current=op;setPhase({writing:admission});
    const commands=libraryCommands(admission),live=()=>!op.signal.aborted&&admission.access.ownsDisplay();
    try{
      const target=accepted.current;
      // Retain the original intent and its immutable snapshot on an uncertain
      // response; a retry must not stage newly edited text under that intent.
      const item=pending.current??={intent:crypto.randomUUID(),change:{action:'save',id:target?.id??crypto.randomUUID(),
        expectedVersion:target?.version??'0',designId:crypto.randomUUID()},snapshot:snapshot(),mark:doc,staged:false};
      if(!item.staged){
        await commands.stage(state.binding,item.change.designId,item.snapshot,op.signal);
        if(!live())return;item.staged=true;
      }
      const result=await commands.change(state.binding,item.intent,item.change,op.signal);
      if(!live())return;
      setLast(item.mark);accepted.current=result;pending.current=null;setPhase('saved');
      // Replace only on first creation. The new URL can be revisited after
      // reload; do not remount the current editor or lose newer local edits.
      if(!target)onCreated?.(result.id);
    }catch(e){
      if(!live())return;
      if(e instanceof LibraryConflict){pending.current=null;setPhase('conflict');}
      else if(!pending.current||e instanceof Error&&['GUEST_CART_UNSUPPORTED','DESIGN_LIBRARY_REFUSED'].includes(e.message)){
        pending.current=null;setPhase('failed');
      }else setPhase('retry');
    }finally{if(flight.current===op)flight.current=null;}
  }
  return <div className="studio-library-actions"><button type="button" className="product-secondary" aria-busy={phase==='busy'||undefined}
    disabled={state.phase!=='ready'||!state.binding||phase==='busy'||phase==='conflict'||(!enabled&&phase!=='retry')||(!dirty&&(phase==='saved'||phase==='idle'))}
    onClick={()=>void save()}><span className="design-save-label">{phase==='busy'&&<Spinner/>}{phase==='retry'?t.retry:t.save}</span></button>
    <p role="status">{phase==='busy'?t.saving:phase==='saved'&&!dirty ? <Link to="/account/designs">{t.saved}</Link>:
      phase==='conflict'?<>{t.conflict} <Link to="/account/designs">{t.title}</Link></>:
      phase==='failed'||phase==='retry'?t.failed:null}</p>
    {state.phase==='reauth-required'?<CartSignInAgain admission={admission}/>:state.phase==='unavailable'||state.phase==='read-unavailable'
      ?<button className="product-text-button" type="button" onClick={state.phase==='unavailable'?retry:retryRead}>{t.retry}</button>:null}
  </div>;
}
