import {expect,it,vi} from 'vitest';
import {localCartCommands} from './localCartCommands';
import {accountCartHttp} from './accountCartHttp';
import {createAccountCartAccess} from './useAccountCartAccess';
import {createLocalCartSession} from './localCartSession';
import {authenticationStarted,authenticationFinished,createAppStore} from '../../store';
import * as guestCartBatch from './guestCartBatch';

const ownerId='10000000-0000-4000-8000-000000000001', namespace='20000000-0000-4000-8000-000000000001';
const batchId='30000000-0000-4000-8000-000000000001',writeContext='40000000-0000-4000-8000-000000000001';
const guard=()=>({ownerId,owns:vi.fn(()=>true)});
const csrf=()=>Response.json({parameterName:'_csrf',headerName:'X-CSRF-TOKEN',token:'csrf-test'});
it('uses current context+namespace on status, and never trusts the URL or a buyer ID from the payload',async()=>{
  const fetcher=vi.fn<typeof fetch>().mockResolvedValueOnce(Response.json({state:'UNKNOWN',version:null})).mockResolvedValueOnce(csrf())
    .mockResolvedValueOnce(Response.json({state:'PENDING',version:null}));
  const api=localCartCommands(fetcher,guard()),binding={ownerId,namespace,writeContext};
  const signal=new AbortController().signal,lines=[{sku:'kiln-blue',quantity:'3',designId:null,designRevision:null}];
  expect(await api.receipt(binding,batchId,'status',signal)).toBe('UNKNOWN');
  expect(await api.receipt(binding,batchId,'prepare',signal,lines)).toBe('PENDING');
  const base='/api/account-cart/imports/'+batchId;
  expect(fetcher.mock.calls.map(([path])=>path)).toEqual([base+'/status','/auth/csrf',base+'/prepare']);
  expect(fetcher.mock.calls[0][1]).toMatchObject({method:'GET',headers:{'X-Cart-Context':writeContext,'X-Cart-Namespace':namespace}});
  expect(fetcher.mock.calls[2][1]).toMatchObject({method:'POST',headers:{'X-Cart-Context':writeContext,'X-Cart-Namespace':namespace,
    'X-CSRF-TOKEN':'csrf-test'},body:JSON.stringify({lines})});
});
it('never sends a POST if the admission changes while acquiring CSRF',async()=>{
  const actor=guard();
  const fetcher=vi.fn<typeof fetch>().mockImplementationOnce(async()=>{actor.owns.mockReturnValue(false);return csrf();});
  await expect(localCartCommands(fetcher,actor).recover(writeContext,new AbortController().signal)).rejects.toThrow();
  expect(fetcher).toHaveBeenCalledTimes(1);
});
it('accepts recoverable only for a classified same-owner pending result, with retry only for a known rollback',async()=>{
  const fetcher=vi.fn<typeof fetch>()
    .mockResolvedValueOnce(Response.json({kind:'pending',ownerId,ticket:writeContext},{status:409}))
    .mockResolvedValueOnce(csrf()).mockResolvedValueOnce(Response.json({kind:'retryable'},{status:503}));
  const signal=new AbortController().signal;
  const api=accountCartHttp(fetcher,guard());
  expect(await api.read(signal)).toEqual({kind:'pending',ownerId,ticket:writeContext});
  expect(await api.recover(writeContext,signal)).toBe('retryable');
  expect(fetcher.mock.calls[2][0]).toBe('/api/account-cart/recover');
  const other=accountCartHttp(async()=>Response.json({kind:'pending',ownerId:batchId,ticket:writeContext},{status:409}),guard());
  expect(await other.read(signal)).toEqual({kind:'denied'});
});

const pending = (ticket=writeContext,owner=ownerId) => Response.json({kind:'pending',ownerId:owner,ticket},{status:409});
const ready = (context=writeContext,owner=ownerId) => Response.json({kind:'ready',ownerId:owner,namespace,writeContext:context,cart:[]});
const lost = () => new TypeError('network disconnected');
const interrupted = () => {
  const response = Response.json({});
  vi.spyOn(response,'text').mockRejectedValueOnce(lost());
  return response;
};
function scenario() {
  const actor = guard(), fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(pending());
  const access = createAccountCartAccess(ownerId,actor.owns,accountCartHttp(fetcher,actor));
  const calls = () => fetcher.mock.calls.map(([path,options]) => [options?.method,path]);
  const phase = (value:string) => vi.waitFor(() => expect(access.getSnapshot().phase).toBe(value));
  return {actor,fetcher,access,calls,phase};
}
const getCart = ['GET','/api/account-cart'], getCsrf = ['GET','/auth/csrf'], post = ['POST','/api/account-cart/recover'];

it.each(['fetch','body'] as const)('offers one explicit same-ticket retry after CSRF %s fails before POST',async failure=>{
  const {fetcher,access,calls,phase} = scenario();
  if (failure === 'fetch') fetcher.mockRejectedValueOnce(lost());
  else fetcher.mockResolvedValueOnce(interrupted());
  fetcher.mockResolvedValueOnce(csrf()).mockResolvedValueOnce(Response.json({kind:'recovered'})).mockResolvedValueOnce(ready());
  access.start();
  await phase('unavailable');
  access.start();
  expect(calls()).toEqual([getCart,getCsrf]);
  access.retry(); access.retry();
  await phase('ready');
  expect(calls()).toEqual([getCart,getCsrf,getCsrf,post,getCart]);
  expect(fetcher.mock.calls[3][1]?.body).toBe(JSON.stringify({ticket:writeContext}));
});

it.each(['fetch','body','server-uncertain'] as const)('reconciles a lost recovery %s reply with a GET; ready needs no second POST',async failure=>{
  const {fetcher,access,calls,phase} = scenario();
  fetcher.mockResolvedValueOnce(csrf());
  if (failure === 'fetch') fetcher.mockRejectedValueOnce(lost());
  else fetcher.mockResolvedValueOnce(failure === 'body' ? interrupted() : Response.json({error:'request_not_completed'},{status:503}));
  fetcher.mockResolvedValueOnce(ready());
  access.start();
  await phase('ready');
  access.retry(); access.retryRead();
  expect(calls()).toEqual([getCart,getCsrf,post,getCart]);
});

it('keeps an unresolved claim across GET failures; only a matching pending read enables an explicit POST',async()=>{
  const {fetcher,access,calls,phase} = scenario();
  fetcher.mockResolvedValueOnce(csrf()).mockRejectedValueOnce(lost()).mockRejectedValueOnce(lost())
    .mockResolvedValueOnce(pending()).mockResolvedValueOnce(csrf())
    .mockResolvedValueOnce(Response.json({kind:'recovered'})).mockResolvedValueOnce(ready());
  access.start();
  await phase('read-unavailable');
  access.retry(); access.start();
  expect(calls()).toEqual([getCart,getCsrf,post,getCart]);
  access.retryRead(); access.retryRead();
  await phase('unavailable');
  expect(calls()).toEqual([getCart,getCsrf,post,getCart,getCart]);
  access.retry(); access.retry();
  await phase('ready');
  expect(calls()).toEqual([getCart,getCsrf,post,getCart,getCart,getCsrf,post,getCart]);
  expect([2,6].map(i=>fetcher.mock.calls[i][1]?.body)).toEqual(Array(2).fill(JSON.stringify({ticket:writeContext})));
});

it.each([
  ['401',()=>new Response(null,{status:401})],['403',()=>new Response(null,{status:403})],
  ['different pending ticket',()=>pending(batchId)],['different ready ticket',()=>ready(batchId)],
  ['different pending owner',()=>pending(writeContext,batchId)],['different ready owner',()=>ready(writeContext,batchId)],
  ['malformed reply',()=>Response.json({kind:'ready',ownerId,cart:[]})],
] as const)('fails closed on %s during reconciliation',async(_,response)=>{
  const {fetcher,access,calls,phase} = scenario();
  fetcher.mockResolvedValueOnce(csrf()).mockRejectedValueOnce(lost()).mockResolvedValueOnce(response());
  access.start();
  await phase('blocked');
  access.retry(); access.retryRead();
  expect(calls()).toEqual([getCart,getCsrf,post,getCart]);
});

it.each(['csrf','post'] as const)('keeps denied and malformed %s responses terminal',async stage=>{
  for (const response of [new Response('{',{headers:{'content-type':'application/json'}}),Response.json({kind:'invalid'}),
    new Response(null,{status:401}),new Response(null,{status:403})]) {
    const {fetcher,access,calls,phase} = scenario();
    if (stage === 'post') fetcher.mockResolvedValueOnce(csrf());
    fetcher.mockResolvedValueOnce(response);
    access.start();
    await phase('blocked');
    access.retry(); access.retryRead();
    expect(calls()).toEqual(stage === 'csrf' ? [getCart,getCsrf] : [getCart,getCsrf,post]);
  }
});

it.each(['csrf','post'] as const)('a lost %s response after a session change never enables retry or reconciliation',async stage=>{
  const {actor,fetcher,access,calls,phase} = scenario();
  if (stage === 'post') fetcher.mockResolvedValueOnce(csrf());
  fetcher.mockImplementationOnce(async()=>{actor.owns.mockReturnValue(false); throw lost();});
  access.start();
  await phase('inactive');
  access.retry(); access.retryRead();
  expect(calls()).toEqual(stage === 'csrf' ? [getCart,getCsrf] : [getCart,getCsrf,post]);
});

it('does not publish another ticket’s ready cart through the local session after an uncertain recovery POST',async()=>{
  // No guest contribution: the real session checkpoint completes without an import.
  const claim = vi.spyOn(guestCartBatch,'claimGuestCart').mockResolvedValue(false);
  const work = vi.spyOn(guestCartBatch,'hasClaimedGuestWork').mockResolvedValue(false);
  const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(pending()).mockResolvedValueOnce(csrf())
    .mockRejectedValueOnce(lost()).mockResolvedValueOnce(ready(batchId));
  const store = createAppStore('en',vi.fn());
  const session = createLocalCartSession(store,{isCurrentOwner:()=>true},fetcher);
  try {
    const owner = {document:'test-page',epoch:1,request:1,revision:'one'};
    store.dispatch(authenticationStarted({owner,phase:'CHECKING',notice:'NONE'}));
    store.dispatch(authenticationFinished({owner,result:{phase:'AUTHENTICATED',notice:'NONE',user:{id:ownerId,status:'ACTIVE'}}}));
    const access = session.getSnapshot()!.access;
    const states:string[] = [];
    access.subscribe(()=>states.push(access.getSnapshot().phase));
    access.start();
    await vi.waitFor(()=>expect(access.getSnapshot()).toEqual({phase:'blocked'}));
    expect(states).not.toContain('ready');
    access.retry(); access.retryRead();
    expect(fetcher.mock.calls.map(([path,options])=>[options?.method,path])).toEqual([getCart,getCsrf,post,getCart]);
    expect(fetcher.mock.calls[2][1]?.body).toBe(JSON.stringify({ticket:writeContext}));
  } finally {
    session.dispose(); claim.mockRestore(); work.mockRestore();
  }
});
