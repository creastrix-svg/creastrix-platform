import { afterEach, expect, it, vi } from 'vitest';
import { createDirectCartAdditions } from './directCartAdditions';

const owner=crypto.randomUUID(), ns=crypto.randomUUID(), ctx=crypto.randomUUID(), product='another-enabled-item';
const binding={ownerId:owner,namespace:ns,writeContext:ctx};
afterEach(()=>sessionStorage.clear());
function setup(replies:(()=>Response)[]) {
  const requests:{id:string;quantity:string}[]=[], refresh=vi.fn();
  const fetcher:typeof fetch=async(input,init)=>{
    if(input==='/auth/csrf')return Response.json({headerName:'X-CSRF-TOKEN',parameterName:'_csrf',token:'accepted'});
    requests.push({id:String(input).split('/').pop()!,quantity:JSON.parse(String(init?.body)).quantity});
    return replies.shift()!();
  };
  const create=()=>createDirectCartAdditions(fetcher,{ownerId:owner,owns:()=>true},refresh,()=>sessionStorage,sku=>sku===product);
  return {actions:create(),create,requests,refresh};
}
const ok=()=>Response.json({state:'APPLIED',version:'2'}), reject=(status:number)=>()=>Response.json({error:'request_not_completed'},{status});
it('keeps conflicts and malformed receipts uncertain and reuses exact intent only on explicit retry',async()=>{
  const {actions,create,requests,refresh}=setup([reject(409),()=>Response.json({state:'APPLIED',version:'0'}),ok]);
  actions.open(binding,product);
  await actions.submit(binding,product,2,false);
  expect(actions.getSnapshot().phase).toBe('conflict');
  await actions.submit(binding,product,1,false);
  actions.dispose();
  const reentry=create();reentry.open(binding,product);
  expect(requests).toHaveLength(1);
  await reentry.submit(binding,product,1,true);
  expect(reentry.getSnapshot().phase).toBe('pending');expect(refresh).toHaveBeenCalledTimes(2);
  await reentry.submit(binding,product,1,true);
  expect(requests).toHaveLength(3);expect(requests.every(v=>v.id===requests[0].id&&v.quantity==='2')).toBe(true);
  expect(refresh).toHaveBeenCalledTimes(3);expect(sessionStorage.length).toBe(0);
});
it('a definite rejection clears that intent, reads current cart and allows only a new click to create another intent',async()=>{
  const {actions,requests,refresh}=setup([reject(422),ok]);
  actions.open(binding,product);await actions.submit(binding,product,3,false);
  expect(actions.getSnapshot().phase).toBe('rejected');expect(refresh).toHaveBeenCalledOnce();expect(sessionStorage.length).toBe(0);
  await actions.submit(binding,product,1,true);expect(requests).toHaveLength(1);
  await actions.submit(binding,product,1,false);expect(requests[1].id).not.toBe(requests[0].id);expect(requests[1].quantity).toBe('1');
});
it('refuses substituted retry content and never creates a replacement command in the background',async()=>{
  const {actions,requests}=setup([reject(503)]);
  actions.open(binding,product);await actions.submit(binding,product,2,false);
  const key=sessionStorage.key(0)!;
  sessionStorage.setItem(key,JSON.stringify({...JSON.parse(sessionStorage.getItem(key)!),quantity:'1'}));
  await actions.submit(binding,product,2,true);
  expect(actions.getSnapshot().phase).toBe('storage');expect(requests).toHaveLength(1);
});
it('a corrupt journal or disabled server capability never becomes a fresh add',async()=>{
  const {actions,requests}=setup([]);
  sessionStorage.setItem('creastrix.local.cart-add.v1:'+owner+':'+ns+':'+product,'broken');
  actions.open(binding,product);expect(actions.getSnapshot().phase).toBe('storage');
  await actions.submit(binding,product,1,false);await actions.submit(binding,'unlisted',1,false);
  expect(requests).toHaveLength(0);
});
it('a withdrawn variant can only reconcile an actual owner-bound record, never start a new gesture',async()=>{
  const {actions}=setup([reject(503)]);actions.open(binding,product);
  await actions.submit(binding,product,2,false);actions.dispose();
  const sent:string[]=[], refresh=vi.fn();
  const disabled=createDirectCartAdditions(async(path,init)=>{
    if(path==='/auth/csrf')return Response.json({headerName:'X-CSRF-TOKEN',parameterName:'_csrf',token:'abc'});
    sent.push(String(path));
    expect(JSON.parse(String(init?.body))).toEqual({sku:product,quantity:'2'});
    return reject(422)();
  },{ownerId:owner,owns:()=>true},refresh,()=>sessionStorage,()=>false);
  const journal=disabled.pendingFor(binding)!;
  expect(journal).toHaveLength(1);expect(disabled.pendingFor({...binding,ownerId:crypto.randomUUID()})).toEqual([]);
  expect(disabled.pendingFor({...binding,namespace:crypto.randomUUID()})).toEqual([]);
  await disabled.submit(binding,product,2,false);
  await disabled.retryExisting(binding,{...journal[0],owner:crypto.randomUUID()});
  expect(sent).toEqual([]);
  await disabled.retryExisting(binding,journal[0]);
  expect(sent).toEqual(['/api/account-cart/additions/'+journal[0].id]);expect(refresh).toHaveBeenCalledOnce();
  expect(disabled.pendingFor(binding)).toEqual([]);
  await disabled.retryExisting(binding,journal[0]);expect(sent).toHaveLength(1);
});
