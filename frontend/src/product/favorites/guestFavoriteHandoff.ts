import {sharedCatalogMode} from '../catalog/permanentCatalog';
import {queueStudioStorage} from '../studio/session/studioStorage';

/** Browser-only, synthetic storefront. Never read legacy heart markers: they
 * were shared by guests and signed-in users and cannot establish ownership. */
export type GuestFavoriteList = Readonly<{partition:string;skus:readonly string[]}>;
type Sealed = Readonly<{id:string;skus:readonly string[];ownerId:string|null;terminal?:true}>;
export type GuestFavoriteBatch = Readonly<{id:string;skus:readonly string[]}>;
export const guestFavoriteKey='guest-favorites-current-v1';
const sealedKey=(id:string)=>['guest-favorites-login-v1',id];
const eventName='creastrix:guest-favorites-changed';
const uuid=(value:unknown):value is string=>typeof value==='string'&&/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(value);
const skuKey=(s:unknown):s is string=>typeof s==='string'&&s.length<=200&&/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(s);
const invalid=():never=>{throw Error('GUEST_FAVORITES_INVALID');};
const none=():GuestFavoriteList=>({partition:crypto.randomUUID(),skus:[]});

function skus(value:unknown):readonly string[]{
  if(!Array.isArray(value)||value.length>1000||!value.every(skuKey)||
    new Set(value).size!==value.length||value.some((sku,i)=>i>0&&value[i-1]>=sku))return invalid();
  return value;
}
function list(value:unknown):GuestFavoriteList{
  if(!value||typeof value!=='object'||!('partition' in value)||!uuid(value.partition)||!('skus' in value))return invalid();
  return {partition:value.partition,skus:skus(value.skus)};
}
function batch(value:unknown,id:string):Sealed {
  if(!value||typeof value!=='object'||!('id' in value)||value.id!==id||!('ownerId' in value)||
    !(value.ownerId===null||uuid(value.ownerId))||!('skus' in value))return invalid();
  const items=skus(value.skus);if(!items.length)return invalid();
  if('terminal' in value && (value.terminal!==true||value.ownerId===null))return invalid();
  return {id,skus:items,ownerId:value.ownerId,...('terminal' in value?{terminal:true as const}:{})};
}
export function notifyGuestFavorites(){
  window.dispatchEvent(new Event(eventName));
  try{const channel=new BroadcastChannel(eventName);channel.postMessage('changed');channel.close();}catch{/* Guarded writes still check the durable partition. */}
}
export function subscribeGuestFavorites(listener:()=>void){
  window.addEventListener(eventName,listener);
  let channel:BroadcastChannel|undefined;
  try{channel=new BroadcastChannel(eventName);channel.onmessage=listener;}catch{/* Re-read on next mount. */}
  return ()=>{window.removeEventListener(eventName,listener);channel?.close();};
}

// Use the same object store as the cart login boundary. Each read/edit commits
// before returning and each edit checks the partition that the buyer actually saw.
function transact<T>(key:IDBValidKey,live:()=>boolean,work:(value:unknown,store:IDBObjectStore)=>T):Promise<T>{
  return queueStudioStorage(()=>new Promise<T>((resolve,reject)=>{
    if(!sharedCatalogMode||!live()){reject(Error('GUEST_FAVORITES_UNAVAILABLE'));return;}
    let db:IDBDatabase|undefined,tx:IDBTransaction|undefined,done=false,result:T;
    function finish(ok:boolean){
      if(done)return;done=true;clearTimeout(deadline);db?.close();
      if(ok&&live())resolve(result);else reject(Error('GUEST_FAVORITES_UNAVAILABLE'));
    }
    function stop(){try{tx?.abort();}catch{/* Already finished. */}finish(false);}
    const deadline=setTimeout(stop,5000);
    try{
      const opening=indexedDB.open('creastrix-studio-local',1);
      opening.onupgradeneeded=()=>{if(done){opening.transaction?.abort();return;}opening.result.createObjectStore('snapshot');};
      opening.onerror=opening.onblocked=()=>finish(false);
      opening.onsuccess=()=>{
        db=opening.result;if(done){db.close();return;}
        try{
          db.onversionchange=()=>db?.close();
          tx=db.transaction('snapshot','readwrite',{durability:'strict'});
          tx.onabort=tx.onerror=()=>finish(false);tx.oncomplete=()=>finish(true);
          const store=tx.objectStore('snapshot'),get=store.get(key);
          get.onsuccess=()=>{try{if(!live())return stop();result=work(get.result,store);}catch{stop();}};
        }catch{stop();}
      };
    }catch{stop();}
  }));
}
export function guestFavoriteList():Promise<GuestFavoriteList>{
  return transact(guestFavoriteKey,()=>true,(raw,store)=>{
    if(raw!==undefined)return list(raw);
    const empty=none();store.add(empty,guestFavoriteKey);return empty;
  });
}
export async function setGuestFavorite(sku:string,desired:boolean,stillGuest:()=>boolean,partition:string){
  if(!skuKey(sku)||!uuid(partition)||typeof desired!=='boolean')return invalid();
  const changed=await transact(guestFavoriteKey,stillGuest,(raw,store)=>{
    const current=list(raw);
    if(current.partition!==partition)return invalid();
    const marked=new Set(current.skus);if(marked.has(sku)===desired)return false;
    if(desired)marked.add(sku);else marked.delete(sku);
    if(marked.size>1000)return invalid();
    store.put({partition,skus:[...marked].sort()},guestFavoriteKey);return true;
  });
  if(changed)notifyGuestFavorites();
}

/** Invoked synchronously INSIDE the cart's native sign-in transaction. Losing
 * the navigation cannot expose an orphan to the next buyer. Even an empty
 * rendered partition is replaced so delayed clicks in another tab cannot land. */
export function sealGuestFavorites(raw:unknown,store:IDBObjectStore,intent:string):string|null{
  if(!uuid(intent))return invalid();
  if(raw===undefined)return null;
  const current=list(raw);
  if(current.skus.length)store.add({id:intent,ownerId:null,skus:current.skus},sealedKey(intent));
  store.put(none(),guestFavoriteKey);
  return current.skus.length?intent:null;
}

/** The supplied proof is from the authenticated server response, not the URL,
 * cookie jar or earlier browser session. Never enumerate unclaimed batches. */
export async function syncGuestFavorites(ownerId:string,proof:string|null,live:()=>boolean,
                                         importer:(b:GuestFavoriteBatch)=>Promise<void|'terminal'>):Promise<void|'terminal'>{
  if(proof===null)return;
  if(!uuid(ownerId)||!uuid(proof))return invalid();
  const key=sealedKey(proof);
  const sealed=await transact(key,live,(raw,store)=>{
    if(raw===undefined)return null;
    const known=batch(raw,proof);
    if(known.ownerId!==null&&known.ownerId!==ownerId)return invalid();
    if(known.terminal)return 'terminal' as const;
    if(known.ownerId===null)store.put({...known,ownerId},key);
    return {id:proof,skus:known.skus};
  });
  if(!sealed)return;
  if(sealed==='terminal')return 'terminal';
  if(!live())return invalid();
  // The caller validates the durable server receipt before resolving. Failure
  // leaves the exact sealed set for a retry under the same login proof.
  const result=await importer(sealed);
  return transact(key,live,(raw,store)=>{
    if(raw===undefined)return; // Another tab received the same receipt.
    const known=batch(raw,proof);
    if(known.ownerId!==ownerId||known.skus.length!==sealed.skus.length||
      !known.skus.every((sku,i)=>sku===sealed.skus[i]))return invalid();
    // A definitive rejection is not a transport retry or an acknowledgement
    // of import. Keep the exact private selection without resending it.
    if(known.terminal||result==='terminal'){
      if(!known.terminal)store.put({...known,terminal:true},key);
      return 'terminal' as const;
    }
    store.delete(key);
  });
}
