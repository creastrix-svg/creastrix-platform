import type {AppliedLoopDesign} from './appliedLoopDesign';

/** An isolated outbox for interrupted local account additions. The key is
 * owner + admitted namespace + request id; nothing here is a guest import.
 * Only the needed applied bytes are retained, until staging or for 24 hours
 * at most on the next use. A committed write, not a request-success event,
 * is what permits the sender to start network I/O. */
export function pendingDesignFile(key:string,action:'put'|'get'|'remove',design?:AppliedLoopDesign):Promise<AppliedLoopDesign|undefined> {
  return temporaryFile(key,action,design);
}
/** Guest library pointers live per tab, but tabs may be duplicated. The
 * browser-wide read/write transaction binds the file to exactly one login. */
export function guestLibraryFile(id:string,action:'put'|'reserve'|'readBound'|'discard',login?:string,design?:AppliedLoopDesign){
  return temporaryFile('guest-library:'+id,action,design,login);
}
function temporaryFile(key:string,action:'put'|'get'|'remove'|'reserve'|'readBound'|'discard',design?:AppliedLoopDesign,login?:string):Promise<AppliedLoopDesign|undefined>{
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
        else {const read=files.get(key);read.onsuccess=()=>{
          const file=read.result;
          if(!(file?.expires>now))return;
          if(action==='reserve'){
            if(file.login!==undefined||!login)return;
            files.put({...file,login},key);result=file.design;
          }else if(action==='discard'){
            if(file.login===login)files.delete(key);
          }else if(action==='get'||(login&&file.login===login))result=file.design;
        };}
      } catch {finish(false);}
    };
  });
}
