import { localCartCommands, tokenId, type Binding, type Guard, type DesignRef } from './localCartCommands';
import { connectedSku, isCatalogKey } from '../catalog/localCatalog';
import {readAppliedLoopDesign,type AppliedLoopDesign} from './appliedLoopDesign';
import {pendingDesignFile} from './pendingDesignFiles';

export type DirectIntent = Readonly<{owner:string;namespace:string;sku:string;id:string;quantity:string;design?:DesignRef;staged?:boolean;digest?:string}>;
type Intent = DirectIntent;
type Phase = 'closed'|'ready'|'sending'|'pending'|'conflict'|'rejected'|'storage';
type Snapshot = Readonly<{scope:string;phase:Phase;quantity?:number}>;
const closed:Snapshot=Object.freeze({scope:'',phase:'closed'});
const storeKey=(scope:string)=>'creastrix.local.cart-add.v1:'+scope;
export const additionScope=(b:Binding,sku:string)=>b.ownerId+':'+b.namespace+':'+sku;
const quantityOk=(q:unknown):q is string=>typeof q==='string' && /^[1-9][0-9]{0,6}$/.test(q) && Number(q)<=1_000_000;
export async function purchaseDesignDigest(applied:AppliedLoopDesign){
  const stable=readAppliedLoopDesign(applied);
  const hash=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(JSON.stringify(stable)));
  return Array.from(new Uint8Array(hash),v=>v.toString(16).padStart(2,'0')).join('');
}

/** Each user gesture creates an intent in this tab, not in the guest cart. Do not store
 * a session ticket or CSRF token. A remount/read only *offers* retry; only a new click
 * can submit, with the original payload and a currently admitted account binding. */
export function createDirectCartAdditions(fetcher:typeof fetch,guard:Guard,refresh:(creditedSku?:string)=>void,
  getStorage:()=>Storage=()=>window.sessionStorage,
  eligible:(sku:string)=>boolean=sku=>connectedSku(sku)?.variant.directAddSupported===true,
  files:typeof pendingDesignFile=pendingDesignFile) {
  const commands=localCartCommands(fetcher,guard),life=new AbortController();
  const appliedLines=new Map<string,string>();
  const listeners=new Set<()=>void>();
  const live=()=>!life.signal.aborted && guard.owns();
  let snap=closed, busy=false, expected:Intent|null=null;
  const set=(next:Snapshot)=>{snap=next;for(const fn of listeners)fn();};
  function valid(b:Binding,sku:string) { return live() && b.ownerId===guard.ownerId && isCatalogKey(sku); }
  function read(b:Binding,sku:string):Intent|null {
    const raw=getStorage().getItem(storeKey(additionScope(b,sku)));
    if(raw===null)return null;
    const p:unknown=JSON.parse(raw);
    if(!p || typeof p!=='object') throw Error('INVALID_INTENT');
    const v=p as Record<string,unknown>;
    let design:DesignRef|undefined;
    if(v.design!==undefined){
      if(!v.design || typeof v.design!=='object' || Object.keys(v.design).length!==2 ||
          !('designId' in v.design) || !('designRevision' in v.design) || v.design.designRevision!=='1' || typeof v.staged!=='boolean'
          || typeof v.digest!=='string'|| !/^[0-9a-f]{64}$/.test(v.digest))throw Error('INVALID_INTENT');
      design={designId:tokenId(v.design.designId),designRevision:'1'};
    }
    if(Object.keys(v).length!==(design?8:5) || v.owner!==b.ownerId || v.namespace!==b.namespace || v.sku!==sku
      || !quantityOk(v.quantity)) throw Error('INVALID_INTENT');
    return {owner:b.ownerId,namespace:b.namespace,sku,id:tokenId(v.id),quantity:v.quantity,...(design?{design,staged:v.staged===true,digest:String(v.digest)}:{})};
  }
  function same(a:Intent|null,b:Intent) {return a?.id===b.id && a.quantity===b.quantity && a.design?.designId===b.design?.designId && a.staged===b.staged && a.digest===b.digest;}
  // Read only records for the currently verified buyer and server namespace.
  // The cart can reconcile a saved receipt even after an offer disappears entirely.
  function pendingFor(b:Binding):Intent[]|null {
    if(!live() || b.ownerId!==guard.ownerId)return [];
    const prefix=storeKey(b.ownerId+':'+b.namespace+':'),pending:Intent[]=[];
    try {
      const storage=getStorage();
      for(let i=0;i<storage.length;i++){
        const key=storage.key(i);
        if(!key?.startsWith(prefix))continue;
        const sku=key.slice(prefix.length);
        if(!isCatalogKey(sku))throw Error('INVALID_INTENT');
        const intent=read(b,sku);
        if(intent)pending.push(intent);
      }
      return pending;
    } catch {return null;}
  }
  function open(b:Binding,sku:string) {
    const scope=additionScope(b,sku);
    if(!valid(b,sku)||busy)return;
    try {
      const previous=read(b,sku);
      if(snap.scope===scope && expected && !same(previous,expected))throw Error('INTENT_MISMATCH');
      expected=previous;
      const phase=previous ? (snap.scope===scope && snap.phase==='conflict'?'conflict':'pending')
        : (snap.scope===scope && snap.phase==='rejected'?'rejected':'ready');
      set({scope,phase,...(previous?{quantity:Number(previous.quantity)}:{})});
    } catch {set({scope,phase:'storage'});}
  }
  async function submit(b:Binding,sku:string,count:number,retry:boolean,applied?:AppliedLoopDesign) {
    const scope=additionScope(b,sku);
    if(!valid(b,sku)||(!retry&&!eligible(sku))||busy||snap.scope!==scope)return false;
    if(retry ? !['pending','conflict'].includes(snap.phase) : !['ready','rejected'].includes(snap.phase))return false;
    busy=true;
    async function reserve():Promise<Intent|null> {
      const previous=read(b,sku);
      if(retry) {
        if(!expected || !previous || !same(previous,expected))throw Error('INTENT_MISMATCH');
        return previous;
      }
      if(previous){expected=previous;set({scope,phase:'pending',quantity:Number(previous.quantity)});return null;}
      if(!Number.isSafeInteger(count)||!quantityOk(String(count)))return null;
      // Artwork hashing and local storage can take time before the first HTTP
      // request. Freeze the displayed quantity for the same gesture we locked.
      if(applied)set({scope,phase:'sending',quantity:count});
      const intent:Intent={owner:b.ownerId,namespace:b.namespace,sku,id:crypto.randomUUID(),quantity:String(count),
        ...(applied ? {design:{designId:crypto.randomUUID(),designRevision:'1'},staged:false,digest:await purchaseDesignDigest(applied)} : {})};
      if(applied){
        const value=readAppliedLoopDesign(applied);
        if(value.sku!==sku)throw Error('DESIGN_SKU');
        await files(scope+':'+intent.id,'put',value);
        if(!valid(b,sku)||read(b,sku))throw Error('ADMISSION_CHANGED');
      }
      getStorage().setItem(storeKey(scope),JSON.stringify(intent));
      if(!same(read(b,sku),intent))throw Error('INTENT_NOT_SAVED');
      return intent;
    }
    try {
      let intent:Intent|null;
      try { intent=await reserve(); } catch {if(live())set({scope,phase:'storage'});return false;}
      if(!intent || !valid(b,sku))return false;
      expected=intent;
      set({scope,phase:'sending',quantity:Number(intent.quantity)});
      let result:Awaited<ReturnType<typeof commands.addPlain>>;
      try {
        if(intent.design && !intent.staged){
          const fileKey=scope+':'+intent.id;
          const stored=await files(fileKey,'get');
          if(!stored)throw Error('STAGE_NOT_RETAINED');
          const bytes=readAppliedLoopDesign(stored);
          if(bytes.sku!==sku || await purchaseDesignDigest(bytes)!==intent.digest || !valid(b,sku)||!same(read(b,sku),intent))throw Error('DESIGN_UNAVAILABLE');
          await commands.stage(b,intent.design.designId,bytes,life.signal);
          if(!valid(b,sku)||!same(read(b,sku),intent))throw Error('ADMISSION_CHANGED');
          intent={...intent,staged:true};
          getStorage().setItem(storeKey(scope),JSON.stringify(intent));
          if(!same(read(b,sku),intent))throw Error('INTENT_NOT_SAVED');
          expected=intent;
          // Once staged, all subsequent retries use its immutable reference.
          // A failed cache deletion has no bearing on purchase acknowledgement.
          await files(fileKey,'remove').catch(()=>{});
        }
        result=await commands.addPlain(b,intent.id,sku,intent.quantity,life.signal,intent.design);
      }
      catch(error) {
        if(!live())return false;
        if(intent.design && !intent.staged && error instanceof Error &&
            (error.message==='GUEST_CART_UNSUPPORTED' || error.message==='STAGE_NOT_RETAINED')){
          // No addition can have started before the staged marker is durable.
          // A definitive refusal (or expired local artwork) frees the SKU for
          // a corrected design. Never send the equivalent plain SKU instead.
          result='rejected';
          await files(scope+':'+intent.id,'remove').catch(()=>{});
        } else {
          set({scope,phase:'pending',quantity:Number(intent.quantity)});
          // Delivery is unknown. Keep the intent, retire pre-click rows, and read
          // the cart afresh. Its quantity alone can never confirm this command.
          refresh();
          return false;
        }
      }
      if(!live())return false;
      // The receipt identifies the representative line, which may retain an
      // older design ID. Associate it only with the exact immutable intent;
      // neither the currently visible draft nor its label proves equivalence.
      if(intent.digest && typeof result==='object')appliedLines.set(scope+':'+intent.digest,result.appliedLine);
      if(result==='conflict') {set({scope,phase:'conflict',quantity:Number(intent.quantity)});refresh();return false;}
      try {
        if(!same(read(b,sku),intent))throw Error('INTENT_MISMATCH');
        getStorage().removeItem(storeKey(scope));
        if(read(b,sku)!==null)throw Error('INTENT_NOT_CLEARED');
        expected=null;
        set({scope,phase:result==='applied'||typeof result==='object'?'ready':'rejected'});
      } catch {set({scope,phase:'storage'});}
      const credited=result==='applied'||typeof result==='object';
      // Reset only the acknowledged SKU, even if its card has already unmounted.
      // Uncertain delivery or rejection must preserve the buyer's selection.
      refresh(credited?sku:undefined);
      return credited;
    } finally {busy=false;}
  }
  function retryExisting(b:Binding,intent:Intent) {
    if(!valid(b,intent.sku)||b.ownerId!==intent.owner||b.namespace!==intent.namespace||busy)return;
    const scope=additionScope(b,intent.sku);
    try {
      if(!same(read(b,intent.sku),intent) || (snap.scope===scope && expected && !same(intent,expected)))
        throw Error('INTENT_MISMATCH');
    } catch {set({scope,phase:'storage'});return;}
    expected=intent;
    set({scope,phase:'pending',quantity:Number(intent.quantity)});
    // Explicit replay only; this path cannot create a new ID or change the payload.
    return submit(b,intent.sku,0,true);
  }
  return {open,submit,pendingFor,retryExisting,lineFor:(b:Binding,sku:string,digest:string)=>valid(b,sku)?appliedLines.get(additionScope(b,sku)+':'+digest):undefined,
    getSnapshot:()=>snap,subscribe:(fn:()=>void)=>{listeners.add(fn);return ()=>{listeners.delete(fn);};},
    dispose(){life.abort();expected=null;appliedLines.clear();set(closed);listeners.clear();}};
}
export type DirectCartAdditions=ReturnType<typeof createDirectCartAdditions>;
