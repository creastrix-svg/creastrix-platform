import type {AppliedLoopDesign} from './appliedLoopDesign';

/** An isolated outbox for interrupted local account additions. The key is
 * owner + admitted namespace + request id; nothing here is a guest import.
 * Only the needed applied bytes are retained, until staging or for 24 hours
 * at most on the next use. A committed write, not a request-success event,
 * is what permits the sender to start network I/O. */
export function pendingDesignFile(key:string,action:'put'|'get'|'remove',design?:AppliedLoopDesign):Promise<AppliedLoopDesign|undefined> {
  return new Promise((resolve,reject)=>{
    const now=Date.now(), req=indexedDB.open('creastrix-local-account-cart-outbox',1);
    let done=false, db:IDBDatabase|undefined, tx:IDBTransaction|undefined, result:AppliedLoopDesign|undefined;
    const timeout=setTimeout(()=>{try{tx?.abort();}catch{/* already settled */}finish(false);},10000);
    function finish(ok:boolean){if(done)return;done=true;clearTimeout(timeout);db?.close();if(ok)resolve(result);else reject(Error('ACCOUNT_OUTBOX_UNAVAILABLE'));}
    req.onblocked=req.onerror=()=>finish(false);
    req.onupgradeneeded=()=>{if(done){req.transaction?.abort();return;}req.result.createObjectStore('files').createIndex('expiry','expires');};
    req.onsuccess=()=>{
      db=req.result;if(done){db.close();return;}
      try {
        tx=db.transaction('files','readwrite');tx.onabort=tx.onerror=()=>finish(false);tx.oncomplete=()=>finish(true);
        const files=tx.objectStore('files');
        const expired=files.index('expiry').openKeyCursor(IDBKeyRange.upperBound(now));
        expired.onsuccess=()=>{const cursor=expired.result;if(cursor){files.delete(cursor.primaryKey);cursor.continue();}};
        if(action==='put')files.put({design,expires:now+86400000},key);
        else if(action==='remove')files.delete(key);
        else {const read=files.get(key);read.onsuccess=()=>{if(read.result?.expires>now)result=read.result.design;};}
      } catch {finish(false);}
    };
  });
}
