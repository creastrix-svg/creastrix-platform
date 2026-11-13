import type {AccountCartAdmission} from '../cart/accountCartAdmission';
import {localCartCommands,type Binding} from '../cart/localCartCommands';
import {guestFavoriteList,setGuestFavorite,subscribeGuestFavorites,syncGuestFavorites} from './guestFavoriteHandoff';
import type {FavoriteSnapshot} from './favoriteSnapshot';
import {allowedFavoriteSku} from './favoriteSnapshot';
import {unavailableShelf,type Shelf} from './favoriteShelf';

function channel(){
  let model:Shelf=unavailableShelf;
  const listeners=new Set<()=>void>();
  return {subscribe(fn:()=>void){listeners.add(fn);return ()=>{listeners.delete(fn);};},snapshot:()=>model,
    publish(next:Shelf){model=next;for(const fn of listeners)fn();}};
}
export function guestFavoriteSession(stillGuest:()=>boolean){
  const view=channel();
  let current:Awaited<ReturnType<typeof guestFavoriteList>>|null=null,writing=false,failed=false,life=0,readId=0;
  const owns=(generation:number)=>generation===life&&stillGuest();
  function publish(){view.publish({ids:current?.skus??[],busy:writing||!current,loading:!current&&!failed,problem:failed,
    retry:()=>{void read();},set:(sku,saved)=>{void write(sku,saved);}});}
  async function read(){
    const generation=life,request=++readId;
    try{const found=await guestFavoriteList();if(!owns(generation)||request!==readId)return;
      current=found;failed=false;
    }catch{if(!owns(generation)||request!==readId)return;current=null;failed=true;}
    publish();
  }
  async function write(sku:string,saved:boolean){
    if(!stillGuest()||!current||writing||!allowedFavoriteSku(sku))return;
    const generation=life;writing=true;publish();
    try{await setGuestFavorite(sku,saved,()=>owns(generation),current.partition);if(owns(generation))await read();}
    catch{if(owns(generation)){failed=true;current=null;}}
    finally{if(owns(generation)){writing=false;publish();}}
  }
  return {...view,start(){life++;current=null;failed=false;writing=false;publish();
    const stop=subscribeGuestFavorites(()=>{void read();});void read();
    return ()=>{life++;stop();};}};
}

/** One shelf per verified admission, never persisted in the guest Redux store.
 * Other tabs may legitimately update the set. Responses describe current state;
 * compare the server revision, not the order promises happened to settle. */
export type FavoritePending={id:string;sku:string;saved:boolean};
export type FavoriteJournal={pending:FavoritePending|null};
export function createFavoriteJournal(){
  let previous:string|null=null,journal:FavoriteJournal={pending:null};
  return (scope:string)=>{
    if(scope!==previous){previous=scope;journal={pending:null};}
    return journal;
  };
}
export function accountFavoriteSession(admission:AccountCartAdmission,fetcher:typeof fetch,journal:FavoriteJournal={pending:null}){
  const view=channel(),api=localCartCommands(fetcher,{ownerId:admission.userId,owns:()=>admission.access.ownsDisplay()});
  let life:AbortController|null=null,latest:FavoriteSnapshot|null=null,binding:Binding|null=null;
  let sending=false,readError=false,writeError=false,importError=false,importBlocked=false,terminal=false,reauth=false;
  let importSeen:string|null=null,reading=0;
  const live=(attempt:AbortController)=>life===attempt&&!attempt.signal.aborted&&admission.access.ownsDisplay();
  const install=(data:FavoriteSnapshot)=>{if(!latest||BigInt(data.version)>=BigInt(latest.version))latest=data;};
  function publish(){
    view.publish({private:true,ids:binding&&latest&&!reauth?latest.skus:[],busy:reauth||!binding||!latest||sending||!!journal.pending||importBlocked,
      loading:!latest&&!readError&&!reauth,problem:!reauth&&(readError||writeError),importProblem:importError,terminal,reauth:reauth?admission:undefined,retry,
      set:(sku,saved)=>{if(!reauth&&!importBlocked&&!journal.pending&&!sending&&binding&&latest&&life&&live(life)&&allowedFavoriteSku(sku)){
        journal.pending={id:crypto.randomUUID(),sku,saved};void send(life,binding);
      }}});
  }
  async function read(attempt:AbortController){
    const seq=++reading;
    try{const data=await api.listFavorites(attempt.signal);if(!live(attempt)||seq!==reading)return;
      // Cart admission and the shelf are separate reads. Once the shelf itself
      // rejects that admission, retire its data and in-flight work until sign-in.
      if(data==='conflict'){
        reauth=true;latest=null;binding=null;attempt.abort();publish();return;
      }
      install(data);readError=false;
    }catch{if(!live(attempt)||seq!==reading)return;readError=true;}
    publish();
  }
  async function send(attempt:AbortController,access:Binding){
    if(importBlocked||sending||!journal.pending)return;
    const {id,sku,saved}=journal.pending;sending=true;writeError=false;publish();
    try{const result=await api.changeFavorites(access,id,{sku,saved},attempt.signal);
      if(!live(attempt))return;
      journal.pending=null;
      if(result==='conflict'){reauth=true;}
      else if(result==='invalid'){writeError=true;await read(attempt);}else install(result);
    }catch{if(live(attempt))writeError=true;} // Unknown commit: keep ID and explicit desired state for Retry.
    finally{if(live(attempt)){sending=false;publish();}}
  }
  async function merge(attempt:AbortController,access:Binding,proof:string){
    if(importSeen===proof)return;
    // A durable GET is enough to display the owner's shelf, but not to edit it
    // before the original login union completes. A late first import would
    // otherwise restore a SKU the buyer just removed. Unknown delivery retains
    // the same outbox, proof and gate; only a confirmed sync releases it.
    importSeen=proof;importError=false;importBlocked=true;publish();
    try{const disposition=await syncGuestFavorites(admission.userId,proof,()=>live(attempt),async batch=>{
      const result=await api.changeFavorites(access,batch.id,{skus:batch.skus},attempt.signal);
      if(!live(attempt))throw Error('FAVORITES_IMPORT_NOT_CONFIRMED');
      if(result==='invalid')return 'terminal' as const;
      if(result==='conflict'){reauth=true;throw Error('FAVORITES_IMPORT_NOT_CONFIRMED');}
      install(result);publish();
    });
      if(live(attempt)){terminal=disposition==='terminal';importBlocked=false;publish();}
    }catch{if(live(attempt)){importError=true;publish();}}
  }
  function retry(){
    if(reauth||!life||!live(life))return;
    const state=admission.access.getSnapshot();
    if(state.phase==='unavailable'){admission.access.retry();return;}
    if(state.phase==='read-unavailable'){admission.access.retryRead();return;}
    if(!binding)return;
    if(importBlocked){
      if(importError&&state.phase==='ready'&&state.guestIntent){importSeen=null;void merge(life,binding,state.guestIntent);}
      return;
    }
    if(journal.pending){void send(life,binding);return;}
    writeError=false;void read(life);
  }
  return {...view,start(){
    const attempt=new AbortController();life=attempt;latest=null;binding=null;readError=false;writeError=!!journal.pending;importError=false;importBlocked=false;importSeen=null;sending=false;terminal=false;reauth=false;
    function change(){
      if(!live(attempt))return;
      const state=admission.access.getSnapshot(),next=state.phase==='ready'?state.binding:null;
      if(!next||next.ownerId!==admission.userId){
        if(state.phase==='reauth-required'||state.phase==='blocked')reauth=true;
        binding=null;latest=null;readError=state.phase!=='opening'&&state.phase!=='recovering';publish();return;
      }
      const first=binding===null;binding=next;
      if(first)void read(attempt);
      if(state.phase==='ready'&&state.guestIntent)void merge(attempt,binding,state.guestIntent);
      publish();
    }
    const unsub=admission.access.subscribe(change);admission.access.start();change();
    return ()=>{attempt.abort();unsub();};
  }};
}
