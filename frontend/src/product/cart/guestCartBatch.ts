import { connectedSku } from '../catalog/localCatalog';
import { permanentCatalogMode } from '../catalog/permanentCatalog';
import { MAX_LOCAL_DESIGN_CHARS, queueStudioStorage } from '../studio/session/studioStorage';
import { emptyGuestPartition, guestFormat, isolateLegacyCart } from './guestCartHandoff';
import { projectAppliedLoop, readAppliedLoopDesign, type AppliedLoopDesign } from './appliedLoopDesign';
import { initialStudioComposition } from '../studio/design/studioComposition';
import { isAppliedLoopVariant } from '../fixtures/loop';

type GuestLine = Readonly<{sku:string;offerId:string;variantId:string;quantity:string} &
  ({kind:'plain'}|{kind:'design';stageId:string;design:AppliedLoopDesign})>;
export type GuestBatch = Readonly<{ ownerId: string; namespace: string; batchId: string; lines: readonly GuestLine[] }>;
type Applied = Readonly<{ ownerId: string; namespace: string; batchId: string; state: 'APPLIED' }>;
type Retired = Readonly<{ ownerId: string; namespace: string; batchId: string; state: 'RETIRED' }>;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const key = (s: unknown): s is string => typeof s === 'string' && s.length <= 200 && /^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(s);
const fail = (code: string): never => { throw new Error('GUEST_CART_' + code); };
const object = (v: unknown): Record<string,unknown> =>
  !!v && typeof v === 'object' && !Array.isArray(v) ? v as Record<string,unknown> : fail('UNSUPPORTED');
const snapshot = (raw: unknown) => {
  if (typeof raw !== 'string' || raw.length > MAX_LOCAL_DESIGN_CHARS) return fail('UNSUPPORTED');
  const data=object(JSON.parse(raw));
  if (data.version !== 1 || !Array.isArray(data.lines)) return fail('UNSUPPORTED');
  return {data,lines:data.lines as unknown[]};
};
function verifyBatch(raw: unknown, ownerId: string): GuestBatch {
  const b=object(raw);
  if (b.ownerId!==ownerId || typeof b.namespace!=='string' || !uuid.test(b.namespace)
      || typeof b.batchId!=='string' || !uuid.test(b.batchId) || !Array.isArray(b.lines) || !b.lines.length || b.lines.length>1000)
    return fail('OUTBOX_INVALID');
  const seen = new Set<string>();
  const lines=b.lines.map((rawLine):GuestLine=>{
    const l=object(rawLine);
    if (!key(l.sku)||!key(l.offerId)||!key(l.variantId)
        ||typeof l.quantity!=='string'||!/^[1-9][0-9]{0,6}$/.test(l.quantity)
        || Number(l.quantity)>1_000_000) return fail('OUTBOX_INVALID');
    const base={sku:l.sku,offerId:l.offerId,variantId:l.variantId,quantity:l.quantity};
    if(l.kind==='plain' && Object.keys(l).length===5 && !seen.has('sku:'+l.sku)) {
      seen.add('sku:'+l.sku);
      return {kind:'plain',...base};
    }
    if(l.kind!=='design' || Object.keys(l).length!==7 || typeof l.stageId!=='string' || !uuid.test(l.stageId)
        || seen.has(l.stageId) || l.offerId!=='loop' || l.variantId!==l.sku) return fail('OUTBOX_INVALID');
    const design=readAppliedLoopDesign(l.design);
    if(design.sku!==l.sku) return fail('OUTBOX_INVALID');
    seen.add(l.stageId);
    return {kind:'design',...base,stageId:l.stageId,design};
  });
  return {ownerId,namespace:b.namespace,batchId:b.batchId,lines};
}

// Raw bytes are a CAS token. Include a fresh token on each local-candidate save
// so an A→B→A edit never revives a stale tab's permission. The partition ID
// changes only when contributions move out of the editable guest snapshot.
export function guestPartition(raw: string | null): string | undefined {
  if (!permanentCatalogMode || raw===null) return undefined;
  const value=object(JSON.parse(raw)).guestPartition;
  return value===undefined ? undefined : typeof value==='string' && uuid.test(value) ? value : fail('UNSUPPORTED');
}
export function versionGuestSave(value: string, expected: string | null): string {
  if (!permanentCatalogMode) return value;
  const data=snapshot(value).data;
  if(expected!==null) {
    const before=snapshot(expected);
    if(before.lines.length && before.data.guestFormat!==guestFormat) return fail('UNPROVEN');
  }
  const result=JSON.stringify({...data,guestFormat,guestPartition:guestPartition(expected),guestWrite:crypto.randomUUID()});
  return result.length<=MAX_LOCAL_DESIGN_CHARS ? result : fail('UNSUPPORTED');
}

const partitionEvent='creastrix:guest-cart-partition';
function announcePartition() {
  window.dispatchEvent(new Event(partitionEvent));
  try {
    const channel = new BroadcastChannel(partitionEvent);
    channel.postMessage('changed'); channel.close();
  } catch { /* The durable CAS still fences tabs without channel support. */ }
}
export function subscribeGuestPartition(listener: () => void) {
  if (!permanentCatalogMode) return () => {};
  window.addEventListener(partitionEvent,listener);
  let channel: BroadcastChannel | undefined;
  try { channel=new BroadcastChannel(partitionEvent); channel.onmessage=listener; } catch { /* Recheck on remount. */ }
  return () => { window.removeEventListener(partitionEvent,listener); channel?.close(); };
}

// Unsent claims are independent of the already namespaced v1 outbox. Several
// admissions can bring fresh guest units while an older batch awaits its receipt.
const claimPrefix = 'guest-cart-claim-v1';
const intentKey = (intentId:string) => ['guest-cart-intent-v1',intentId];
const claimKeys = (ownerId: string) => IDBKeyRange.bound([claimPrefix,ownerId], [claimPrefix,ownerId,[]]);
type Source = 'current'|'firstClaim'|'claimKey'|{intentId:string}|null;

// Serialize the selected local record with ONLY this owner's pending key.
// A bounded compound-key lookup never examines another buyer's claims.
function run<T>(ownerId:string|null, current:()=>boolean, mode:IDBTransactionMode,
  source:Source,
  work:(raw:unknown,pending:unknown,store:IDBObjectStore,pendingKey:string[])=>T, committed?:(value:T)=>void): Promise<T> {
  return queueStudioStorage(()=>new Promise<T>((resolve,reject)=>{
    let db:IDBDatabase|undefined, tx:IDBTransaction|undefined, settled=false, result:T;
    let failure:unknown=new Error('GUEST_CART_TRANSACTION');
    const finish=(ok:boolean)=>{
      if(settled) return;
      settled=true; clearTimeout(timer); db?.close();
      if (!ok) { reject(failure); return; }
      // A committed partition still invalidates local checkpoints if the former
      // admission ended while IDB was committing. Do not return its batch then.
      try {
        committed?.(result);
        if (current()) resolve(result); else reject(new Error('GUEST_CART_ADMISSION_CHANGED'));
      } catch(error){reject(error);}
    };
    const abort=(error:unknown)=>{failure=error; try {tx?.abort();} finally {finish(false);} };
    const timer=setTimeout(()=>abort(new Error('GUEST_CART_TIMEOUT')),5000);
    try {
      if (!permanentCatalogMode || (ownerId!==null && !uuid.test(ownerId)) || !current()) return abort(new Error('GUEST_CART_ADMISSION_CHANGED'));
      const open=indexedDB.open('creastrix-studio-local',1);
      open.onupgradeneeded=()=>{if(!open.result.objectStoreNames.contains('snapshot')) open.result.createObjectStore('snapshot');};
      open.onblocked=open.onerror=()=>finish(false);
      open.onsuccess=()=>{
        db=open.result;
        if(settled){db.close();return;}
        try {
          db.onversionchange=()=>db?.close();
          tx=db.transaction('snapshot',mode,mode==='readwrite' ? {durability:'strict'} : undefined);
          tx.oncomplete=()=>finish(true); tx.onabort=tx.onerror=()=>finish(false);
          const store=tx.objectStore('snapshot'), pendingKey=ownerId ? ['guest-cart-batch-v1',ownerId] : [];
          const raw = source === 'current' ? store.get('current')
            : source === 'firstClaim' && ownerId ? store.openCursor(claimKeys(ownerId))
            : source === 'claimKey' && ownerId ? store.getKey(claimKeys(ownerId))
            : source && typeof source==='object' ? store.get(intentKey(source.intentId)) : undefined;
          const pending=ownerId!==null ? store.get(pendingKey) : undefined;
          (pending ?? raw!).onsuccess=()=>{
            try {
              if(!current()) return fail('ADMISSION_CHANGED');
              result=work(raw?.result,pending?.result,store,pendingKey);
            } catch(error){abort(error);}
          };
        } catch(error){abort(error);}
      };
    } catch(error){abort(error);}
  }));
}

/** A dirty edit belongs to its original CAS baseline, not the next guest.
 * Save the exact studio bytes (including referenced resources), without
 * changing current or exposing a correction record to any transfer reader.
 * Repeated saves from this checkpoint address only its random private key. */
export function quarantineGuestEdit(id:string,base:string|null,local:string):Promise<boolean> {
  if(!uuid.test(id) || (base!==null && base.length>MAX_LOCAL_DESIGN_CHARS) || local.length>MAX_LOCAL_DESIGN_CHARS)
    return fail('UNSUPPORTED');
  return run(null,()=>true,'readwrite','current',(raw,_pending,store)=>{
    if(raw!==undefined && typeof raw!=='string') return fail('UNSUPPORTED');
    store.put({base,snapshot:local},['guest-cart-edit-conflict-v1',id]);
    return guestPartition(raw??null)!==guestPartition(base);
  });
}

// A fresh editor has one empty default text object for each surface. Archive
// actual guest work (including explicit deletion of a default layer), not
// untouched editor defaults on every empty sign-in. The archive has no buyer
// selector or storefront reader; no other guest can hydrate these resources.
const defaultDrafts=Object.fromEntries(Object.entries(initialStudioComposition().histories)
  .map(([target,h])=>[target,JSON.stringify({objects:h!.present.objects})]));
function hasStudioWork(d:Record<string,unknown>) {
  return ['applied','creativeDrafts','creativeApplied','fonts','assets'].some(k=>d[k] && Object.keys(Object(d[k])).length>0)
    || Object.entries(Object(d.drafts??{})).some(([k,v])=>JSON.stringify(v)!==defaultDrafts[k]);
}
function heldCart(intent:string,lines:unknown[],d:Record<string,unknown>) {
  if(!lines.some(line=>!!line && typeof line==='object' && 'design' in line)) return {intent,lines};
  const wantedFonts=new Set<string>(), wantedAssets=new Set<number>();
  for(const raw of lines) {
    const layers=(raw as {design?:{objects?:unknown[]}}|null)?.design?.objects;
    if(!Array.isArray(layers)) continue;
    for(const rawLayer of layers) {
      const layer=rawLayer as {kind?:unknown;value?:{font?:unknown;image?:{asset?:unknown}}}|null;
      if(layer?.kind==='text' && typeof layer.value?.font==='string') wantedFonts.add(layer.value.font);
      const index=layer?.value?.image?.asset;
      if(layer?.kind==='image' && typeof index==='number' && Number.isSafeInteger(index)) wantedAssets.add(index);
    }
  }
  // Preserve indexes used by purchased layers, without copying the contents
  // of any unreferenced asset. Malformed/unresolved rows remain unsupported.
  const assets=Array.isArray(d.assets) ? d.assets.map((src,i)=>wantedAssets.has(i)?src:null) : [];
  const fonts=Array.isArray(d.fonts) ? d.fonts.filter(f=>wantedFonts.has(f?.id)).map(f=>({id:f.id,resource:f.resource})) : [];
  return {intent,lines,assets,fonts};
}

/** Only the guest login form can detach current. Never put the UUID of an
 * older orphan into a new login. If the form or callback dies after commit,
 * the next buyer starts with a separate editable guest partition. */
export async function sealGuestCart(stillCurrent:()=>boolean,designLoginIntent?:string):Promise<string|null> {
  if(designLoginIntent!==undefined && !uuid.test(designLoginIntent))return fail('UNPROVEN');
  const result=await run(null,stillCurrent,'readwrite','current',(raw,_pending,store)=>{
    if(raw===undefined) {
      // Fence an unsaved edit in another document whose CAS baseline is null.
      // An empty sign-in still must not leave that old baseline publishable.
      store.put(emptyGuestPartition(),'current');
      return {intent:designLoginIntent??null,changed:true};
    }
    if(typeof raw!=='string') return fail('UNSUPPORTED');
    const eligible=isolateLegacyCart(raw,store,MAX_LOCAL_DESIGN_CHARS);
    const {data,lines}=snapshot(eligible);
    if(hasStudioWork(data)) store.add(eligible,['guest-studio-archive-v1',crypto.randomUUID()]);
    if(!lines.length) {
      // Even an empty existing cart may have live undo/CAS checkpoints in
      // another tab. Fence those at the login boundary without creating an
      // import intent. Quarantine already rotated the partition if needed.
      if(eligible===raw) {
        const empty=emptyGuestPartition();
        if(empty.length>MAX_LOCAL_DESIGN_CHARS) return fail('UNSUPPORTED');
        store.put(empty,'current');
      }
      return {intent:designLoginIntent??null,changed:true};
    }
    const intent=designLoginIntent??crypto.randomUUID();
    const held=heldCart(intent,lines,data);
    const next=emptyGuestPartition();
    if(next.length>MAX_LOCAL_DESIGN_CHARS) return fail('UNSUPPORTED');
    store.add(held,intentKey(intent));
    store.put(next,'current');
    return {intent,changed:true};
  },value=>{ if(value.changed) announcePartition(); });
  return result.intent;
}

/** Bind only the exact intent authenticated by this login's cart read.
 * Re-observing that proof is idempotent; no scan for "a likely" guest record. */
export async function claimGuestCart(ownerId:string,intentId:string,stillCurrent:()=>boolean):Promise<boolean> {
  if(!uuid.test(intentId)) return fail('UNPROVEN');
  return run(ownerId,stillCurrent,'readwrite',{intentId},(raw,_pending,store)=>{
    if(raw===undefined) return false;
    const held=object(raw);
    if(held.intent!==intentId || !Array.isArray(held.lines) || !held.lines.length) return fail('OUTBOX_INVALID');
    const {lines,assets,fonts}=held;
    store.add({ownerId,batchId:intentId,lines,...(assets===undefined ? {} : {assets}),...(fonts===undefined ? {} : {fonts})},
      [claimPrefix,ownerId,intentId]);
    store.delete(intentKey(intentId));
    return true;
  });
}

export function hasClaimedGuestWork(ownerId:string, stillCurrent:()=>boolean): Promise<boolean> {
  return run(ownerId,stillCurrent,'readonly','claimKey',(claimKey,pending)=>claimKey!==undefined || pending!==undefined);
}

export async function freezeGuestBatch(ownerId:string, namespace:string, stillCurrent:()=>boolean): Promise<GuestBatch|null> {
  if (!uuid.test(namespace)) return fail('NAMESPACE');
  return run(ownerId,stillCurrent,'readwrite','firstClaim',(raw,pending,store,pendingKey)=>{
    if(pending!==undefined) {
      const batch=verifyBatch(pending,ownerId);
      if(batch.namespace!==namespace) return fail('NAMESPACE');
      return batch;
    }
    const cursor=raw as IDBCursorWithValue|null;
    if(!cursor) return null;
    const {ownerId:claimedOwner,batchId,lines,assets,fonts}=object(cursor.value);
    if(claimedOwner!==ownerId || typeof batchId!=='string' || !uuid.test(batchId)
        || JSON.stringify(cursor.key)!==JSON.stringify([claimPrefix,ownerId,batchId])
        || !Array.isArray(lines) || !lines.length) return fail('OUTBOX_INVALID');
    if(lines.length>1000) return fail('UNSUPPORTED');
    const designIds=new Set<string>();
    let remaining=MAX_LOCAL_DESIGN_CHARS;
    const captured=lines.map((rawLine):GuestLine=>{
      const l=object(rawLine);
      if(!key(l.variant)
          || typeof l.quantity!=='number'||!Number.isSafeInteger(l.quantity)||l.quantity<1||l.quantity>1_000_000)
        return fail('UNSUPPORTED');
      const match=connectedSku(l.variant);
      if(!match || match.variant.sku!==l.variant) return fail('UNSUPPORTED');
      const base={sku:l.variant,offerId:match.product.id,variantId:match.variant.id,quantity:String(l.quantity)};
      if(Object.keys(l).length===2) return {kind:'plain',...base};
      if(Object.keys(l).length!==5 || typeof l.id!=='string' || !/^design-[1-9][0-9]{0,11}$/.test(l.id)
          || designIds.has(l.id) || !isAppliedLoopVariant(l.variant,l.templateRevision)) return fail('UNSUPPORTED');
      designIds.add(l.id);
      const design=projectAppliedLoop(l.variant,l.templateRevision,l.design,assets,fonts);
      remaining-=JSON.stringify(design).length;
      if(remaining<0) return fail('UNSUPPORTED');
      return {kind:'design',...base,stageId:crypto.randomUUID(),design};
    });
    const batch=verifyBatch({ownerId,namespace,batchId,lines:captured},ownerId);
    store.add(batch,pendingKey); cursor.delete();
    return batch;
  });
}

/** An exact terminal not-applied receipt frees the outbox, not the units.
 * Keep the exact batch privately for correction; never reconstruct a public
 * guest cart from a previously owned quantity or silently reimport it. */
export function retainRetiredBatch(batch:GuestBatch, ack:Retired, stillCurrent:()=>boolean): Promise<boolean> {
  return run(batch.ownerId,stillCurrent,'readwrite',null,(_raw,pending,store,pendingKey)=>{
    if(ack.state!=='RETIRED'||ack.ownerId!==batch.ownerId||ack.namespace!==batch.namespace||ack.batchId!==batch.batchId)
      return fail('ACK_MISMATCH');
    if(pending===undefined) return false;
    const stored=verifyBatch(pending,batch.ownerId);
    if(stored.batchId!==batch.batchId) return false;
    if(stored.namespace!==batch.namespace||JSON.stringify(stored.lines)!==JSON.stringify(verifyBatch(batch,batch.ownerId).lines))
      return fail('ACK_MISMATCH');
    store.add({state:'RETIRED',batch:pending},['guest-cart-retired-v1',batch.ownerId,batch.batchId]);
    store.delete(pendingKey);
    return true;
  });
}

export function ackGuestBatch(batch:GuestBatch, ack:Applied, stillCurrent:()=>boolean): Promise<void> {
  return run(batch.ownerId,stillCurrent,'readwrite',null,(_raw,pending,store,pendingKey)=>{
    if(ack.state!=='APPLIED'||ack.ownerId!==batch.ownerId||ack.namespace!==batch.namespace||ack.batchId!==batch.batchId)
      return fail('ACK_MISMATCH');
    if(pending===undefined) return; // Another tab has already released it.
    const stored=verifyBatch(pending,batch.ownerId);
    if(stored.batchId!==batch.batchId) return; // A late ack cannot clear a successor.
    if(stored.namespace!==batch.namespace || JSON.stringify(stored.lines)!==JSON.stringify(verifyBatch(batch,batch.ownerId).lines))
      return fail('ACK_MISMATCH');
    store.delete(pendingKey); // Never touches active guest additions or private studio data.
  });
}
