import {afterEach,expect,it,vi} from 'vitest';
import {waitFor} from '@testing-library/react';
import {authenticationFinished,authenticationStarted,createAppStore} from '../../store';
import {bindAccountCartReader} from '../cart/accountCartAdmission';
import {accountFavoriteSession,type FavoriteJournal} from './favoriteSession';
import {localCartCommands,type Binding} from '../cart/localCartCommands';
import {guestStorageFixture} from '../../test/guestStorageFixture';
vi.hoisted(()=>vi.stubEnv('MODE','shared-cart-local'));
afterEach(()=>vi.unstubAllGlobals());

function admitted(guestIntent?:string){
  const ownerId=crypto.randomUUID(),binding:Binding={ownerId,namespace:crypto.randomUUID(),writeContext:crypto.randomUUID()};
  const document={document:crypto.randomUUID(),epoch:1,request:1,revision:crypto.randomUUID()};
  const store=createAppStore('en');
  store.dispatch(authenticationStarted({owner:document,phase:'CHECKING',notice:'NONE'}));
  store.dispatch(authenticationFinished({owner:document,result:{phase:'AUTHENTICATED',notice:'NONE',user:{id:ownerId,status:'ACTIVE'}}}));
  const admission=bindAccountCartReader(store,{read:async()=>({kind:'ready',ownerId,cart:[],binding,guestIntent}),recover:async()=>'denied'},
    {isCurrentOwner:()=>true});
  return {admission,binding};
}
function endpoint(binding:Binding){
  const ids=new Set<string>(),receipts=new Map<string,string>(),sent:{path:string;body:string}[]=[];
  let version=0,lost=false,wrong=false;
  const state=()=>Response.json({ownerId:wrong?crypto.randomUUID():binding.ownerId,skus:Array.from(ids).sort(),version:String(version)});
  const fetcher=vi.fn<typeof fetch>(async(input,init)=>{
    const path=String(input);
    if(path==='/api/account-cart/favorites'){expect(init?.method).toBe('GET');return state();}
    if(path==='/auth/csrf')return Response.json({headerName:'X-CSRF-TOKEN',parameterName:'_csrf',token:'csrf1'});
    expect(path).toMatch(/^\/api\/account-cart\/favorites\/commands\/[a-f0-9-]{36}$/);
    const headers=new Headers(init?.headers),body=String(init?.body);
    expect(headers.get('X-Cart-Context')).toBe(binding.writeContext);
    expect(headers.get('X-Cart-Namespace')).toBe(binding.namespace);
    expect(headers.get('X-CSRF-TOKEN')).toBe('csrf1');
    sent.push({path,body});
    if(receipts.has(path)){expect(receipts.get(path)).toEqual(body);return state();}
    receipts.set(path,body);
    const {saved,sku}=JSON.parse(body);if(saved)ids.add(sku);else ids.delete(sku);version++;
    if(lost){lost=false;throw TypeError('receipt not received');}
    return state();
  });
  return {fetcher,ids,sent,lose:()=>{lost=true;},changeOwner:()=>{wrong=true;}};
}
it('keeps two colours as distinct entries and removing one survives a fresh account screen',async()=>{
  const {admission,binding}=admitted(),api=endpoint(binding);
  const first=accountFavoriteSession(admission,api.fetcher),stop=first.start();
  await waitFor(()=>expect(first.snapshot().busy).toBe(false));
  first.snapshot().set('cobalt',true);await waitFor(()=>expect(first.snapshot().ids).toEqual(['cobalt']));
  first.snapshot().set('chalk',true);await waitFor(()=>expect(first.snapshot().ids).toEqual(['chalk','cobalt']));
  first.snapshot().set('cobalt',false);await waitFor(()=>expect(first.snapshot().ids).toEqual(['chalk']));
  stop();const fresh=accountFavoriteSession(admission,api.fetcher),end=fresh.start();
  try{await waitFor(()=>expect(fresh.snapshot().ids).toEqual(['chalk']));expect(api.sent).toHaveLength(3);}
  finally{end();admission.dispose();}
});
it('retries an unknown command using the same id and desired state even after the owner transport refreshes',async()=>{
  const {admission,binding}=admitted(),api=endpoint(binding),journal:FavoriteJournal={pending:null};
  const first=accountFavoriteSession(admission,api.fetcher,journal),stop=first.start();
  await waitFor(()=>expect(first.snapshot().busy).toBe(false));api.lose();first.snapshot().set('chalk',true);
  await waitFor(()=>expect(first.snapshot().problem).toBe(true));
  expect(first.snapshot().ids).toEqual([]);expect(api.ids.has('chalk')).toBe(true);
  first.snapshot().set('cobalt',true);stop();
  const fresh=accountFavoriteSession(admission,api.fetcher,journal),end=fresh.start();
  try{
    await waitFor(()=>expect(fresh.snapshot().ids).toEqual(['chalk']));expect(fresh.snapshot().problem).toBe(true);
    fresh.snapshot().retry();await waitFor(()=>expect(fresh.snapshot().problem).toBe(false));
    expect(api.sent).toHaveLength(2);expect(api.sent[0]).toEqual(api.sent[1]);
  }finally{end();admission.dispose();}
});
it('does not show a shelf when the response identity does not match the current buyer',async()=>{
  const {admission,binding}=admitted(),api=endpoint(binding);api.ids.add('chalk');api.changeOwner();
  const shelf=accountFavoriteSession(admission,api.fetcher),stop=shelf.start();
  try{await waitFor(()=>expect(shelf.snapshot().problem).toBe(true));expect(shelf.snapshot().ids).toEqual([]);
    shelf.snapshot().set('chalk',false);expect(api.sent).toEqual([]);}
  finally{stop();admission.dispose();}
});
it('retires a previously loaded shelf when its next read loses shopping access',async()=>{
  const {admission,binding}=admitted(),api=endpoint(binding);api.ids.add('chalk');
  const shelf=accountFavoriteSession(admission,api.fetcher),stop=shelf.start();
  try{
    await waitFor(()=>expect(shelf.snapshot().ids).toEqual(['chalk']));
    const previouslyReady=shelf.snapshot();
    api.fetcher.mockResolvedValueOnce(Response.json({error:'request_not_completed'},{status:409}));
    previouslyReady.retry();
    await waitFor(()=>expect(shelf.snapshot().reauth).toBe(admission));
    expect(shelf.snapshot()).toMatchObject({ids:[],busy:true,loading:false,problem:false});
    previouslyReady.set('chalk',false);shelf.snapshot().retry();
    expect(api.fetcher).toHaveBeenCalledTimes(2);expect(api.sent).toEqual([]);
  }finally{stop();admission.dispose();}
});
it('offers a read retry instead of sign-in after a temporary favorites outage',async()=>{
  const {admission,binding}=admitted(),api=endpoint(binding);api.ids.add('chalk');
  api.fetcher.mockResolvedValueOnce(Response.json({error:'request_not_completed'},{status:503}));
  const shelf=accountFavoriteSession(admission,api.fetcher),stop=shelf.start();
  try{
    await waitFor(()=>expect(shelf.snapshot().problem).toBe(true));
    expect(shelf.snapshot().reauth).toBeUndefined();shelf.snapshot().retry();
    await waitFor(()=>expect(shelf.snapshot().ids).toEqual(['chalk']));
    expect(shelf.snapshot()).toMatchObject({busy:false,problem:false});
    expect(api.fetcher).toHaveBeenCalledTimes(2);expect(api.sent).toEqual([]);
  }finally{stop();admission.dispose();}
});
it('does not install a delayed owner A response after the admission has been retired',async()=>{
  const {admission,binding}=admitted();let release!:(r:Response)=>void;
  const fetcher=vi.fn<typeof fetch>(()=>new Promise(resolve=>{release=resolve;}));
  const shelf=accountFavoriteSession(admission,fetcher),stop=shelf.start();
  await waitFor(()=>expect(fetcher).toHaveBeenCalledTimes(1));
  admission.dispose();stop();release(Response.json({ownerId:binding.ownerId,version:'1',skus:['chalk']}));
  await new Promise(resolve=>setTimeout(resolve,5));expect(shelf.snapshot().ids).toEqual([]);
});
it.each(['lost receipt','refusal'] as const)('will not remove Chalk before its first guest import is confirmed, including %s',async failure=>{
  const proof=crypto.randomUUID(),{admission,binding}=admitted(proof),sealedKey=JSON.stringify(['guest-favorites-login-v1',proof]);
  const db=guestStorageFixture([[sealedKey,{id:proof,ownerId:null,skus:['chalk','cobalt']}]]);
  let version=1,imports=0,removes=0,finish!:()=>void;
  const rows=new Set(['chalk']),state=()=>Response.json({ownerId:binding.ownerId,skus:[...rows].sort(),version:String(version)});
  const fetcher=vi.fn<typeof fetch>(async(url,init)=>{
    if(url==='/auth/csrf')return Response.json({headerName:'X-CSRF-TOKEN',parameterName:'_csrf',token:'csrf'});
    if(url==='/api/account-cart/favorites')return state();
    if(url==='/api/account-cart/favorites/imports/'+proof){
      imports++;expect(JSON.parse(String(init?.body))).toEqual({skus:['chalk','cobalt']});
      if(imports===1){await new Promise<void>(resolve=>{finish=resolve;});if(failure==='refusal')return Response.json({error:'request_not_completed'},{status:422});
        rows.add('cobalt');version++;throw TypeError('lost after commit');}
      if(failure==='refusal')return Response.json({error:'request_not_completed'},{status:422});
      return state();
    }
    if(String(url).includes('/favorites/commands/')){removes++;rows.delete('chalk');version++;return state();}
    throw Error('Unexpected request');
  });
  const shelf=accountFavoriteSession(admission,fetcher),stop=shelf.start();
  try{
    await waitFor(()=>expect(shelf.snapshot().ids).toEqual(['chalk']));await waitFor(()=>expect(imports).toBe(1));
    const early=shelf.snapshot();expect(early.busy).toBe(true);early.set('chalk',false);expect(removes).toBe(0);
    finish();
    if(failure==='refusal'){
      await waitFor(()=>expect(shelf.snapshot().terminal).toBe(true));
      expect(shelf.snapshot().busy).toBe(false);expect(shelf.snapshot().importProblem).toBe(false);
      expect(db.get(sealedKey)).toMatchObject({id:proof,ownerId:binding.ownerId,skus:['chalk','cobalt'],terminal:true});
      shelf.snapshot().set('chalk',false);await waitFor(()=>expect(shelf.snapshot().ids).toEqual([]));
      shelf.snapshot().retry();expect(imports).toBe(1);expect(removes).toBe(1);
    }else{
      await waitFor(()=>expect(shelf.snapshot().importProblem).toBe(true));
      expect(db.has(sealedKey)).toBe(true);early.set('chalk',false);expect(removes).toBe(0);
      shelf.snapshot().retry();await waitFor(()=>expect(imports).toBe(2));
      await waitFor(()=>expect(shelf.snapshot().busy).toBe(false));expect(db.has(sealedKey)).toBe(false);
      shelf.snapshot().set('chalk',false);await waitFor(()=>expect(shelf.snapshot().ids).toEqual(['cobalt']));expect(removes).toBe(1);}
  }finally{stop();admission.dispose();}
});
it('rejects an invalid or oversized favorites reply, without filtering unknowns into another SKU',async()=>{
  const {admission,binding}=admitted(),snapshot={...binding,skus:['withdrawn-sku'],version:'1'};
  const fetcher=vi.fn<typeof fetch>();
  const api=localCartCommands(fetcher,{ownerId:binding.ownerId,owns:()=>true}),signal=new AbortController().signal;
  for(const bad of [{skus:['a','a']},{version:'01'},{version:'9223372036854775808'},{skus:['/wrong']},{skus:Array.from({length:1001},(_,i)=>'sku-'+i)}]){
    fetcher.mockResolvedValueOnce(Response.json({...snapshot,...bad}));
    await expect(api.listFavorites(signal)).rejects.toThrow();
  }
  fetcher.mockResolvedValueOnce(Response.json(snapshot));expect(await api.listFavorites(signal)).toMatchObject({skus:['withdrawn-sku']});admission.dispose();
});
