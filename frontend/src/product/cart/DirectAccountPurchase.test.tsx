import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { StrictMode } from 'react';
import { Provider } from 'react-redux';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeAll, expect, it, vi } from 'vitest';
import { authenticationFinished, authenticationStarted, createAppStore } from '../../store';
import { sameOwner } from '../../authBoundary';
import { loadLocalCatalog } from '../catalog/localCatalog';
import * as catalog from '../catalog/localCatalog';
import { productCopy } from '../copy';
import { cartAdded } from '../demoStore';
import LocalAccountStorefront from './LocalAccountStorefront';
import { createLocalCartSession } from './localCartSession';
import {AuthenticationContext,type Authentication} from '../../authentication';

vi.hoisted(() => vi.stubEnv('MODE', 'account-cart-local'));
vi.mock('@google/model-viewer', () => ({}));
// These buyers are already signed in; nothing from a prior guest visit is eligible for import.
vi.mock('./guestCartBatch', async original => ({
  ...await original<typeof import('./guestCartBatch')>(), hasClaimedGuestWork: async () => false,
}));

const sku = 'tide-cobalt', tide = '/products/tide', cart = '/preview/cart', copy = productCopy.en;
const a = '10000000-0000-4000-8000-000000000001', b = '10000000-0000-4000-8000-000000000002';
const namespace = 'e0000000-0000-4000-a000-000000000001';
const writeContext = 'd0000000-0000-4000-a000-000000000001';
const lineId = '50000000-0000-4000-8000-000000000001';
const pathPattern = /^\/api\/account-cart\/additions\/([a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12})$/;
const enabledAdd = () => waitFor(() => {
  const button=screen.getByRole('button',{name:copy.add});
  expect(button).toBeEnabled();
  return button;
});
const shownQuantity = (n:number) => screen.findByText(copy.alreadyInCart.replace('{n}', String(n)));
function deferred() { let done!:() => void; const promise = new Promise<void>(resolve => { done = resolve; }); return {promise,done}; }

beforeAll(async () => {
  const offers = [
    {id:'tide', label:'Tide', variant:'cobalt', sku, enabled:true},
    {id:'bowl', label:'Bowl', variant:'plain', sku:'bowl-plain', enabled:false},
  ].map(p => ({id:p.id, label:p.label, currency:'EUR', templateRevision:null, variants:[
    {id:p.variant, sku:p.sku, label:'Blue', stock:3, cents:2400, swatch:'#244bcc', directAddSupported:p.enabled},
  ]}));
  const http = vi.spyOn(window,'fetch').mockResolvedValueOnce(Response.json(offers));
  try { await loadLocalCatalog(); } finally { http.mockRestore(); }
});
afterEach(() => { sessionStorage.clear(); vi.restoreAllMocks(); });

/** Network double only; real card, account reader, session journal and guarded command transport.
 * Independent per-owner quantities & receipts distinguish a second write from a lost reply. */
function fakeApi() {
  const qty = new Map<string,number>(), receipts = new Map<string,{amount:number;version:string}>();
  const calls:{path:string;method:string;init:RequestInit;actor:string}[] = [];
  const controls:{delay?:Promise<void>;holdCsrf?:Promise<void>;dropNextAck?:boolean;loseBeforeCommit?:boolean;
    withdrawn?:boolean;denyRead?:boolean;expired?:boolean;offline?:boolean;context?:string} = {};
  const fetchFor = (actor:string) => vi.fn<typeof fetch>(async (input, init = {}) => {
    const path=String(input), method=init.method ?? 'GET';
    calls.push({path,method,init,actor});
    if(path==='/api/account-cart' && method==='GET') {
      if(controls.expired)return Response.json({error:'request_not_completed'},{status:409});
      if(controls.offline)return Response.json({error:'request_not_completed'},{status:503});
      if(controls.denyRead) return Response.json({error:'denied'}, {status:403});
      const count=qty.get(actor) ?? 0;
      return Response.json({kind:'ready',ownerId:actor,namespace,writeContext:controls.context??writeContext,guestIntent:null,cart:count?[
        {lineId,offerId:'tide',variantId:'cobalt',offerLabel:'Tide',variantLabel:'Blue',designLabel:null,
          templateRevision:null,listed:!controls.withdrawn,quantity:String(count)},
      ]:[]});
    }
    if(path==='/auth/csrf' && method==='GET'){
      if(controls.holdCsrf) await controls.holdCsrf;
      return Response.json({headerName:'X-CSRF-TOKEN',parameterName:'_csrf',token:'one-token'});
    }
    const id=path.match(pathPattern)?.[1];
    if(!id || method!=='POST') throw Error('Unexpected test HTTP '+method+' '+path);
    const body=JSON.parse(String(init.body)), headers=new Headers(init.headers);
    expect(headers.get('X-CSRF-TOKEN')).toBe('one-token'); expect(headers.get('X-Cart-Namespace')).toBe(namespace);
    expect(headers.get('X-Cart-Context')).toBe(controls.context??writeContext); expect(headers.get('Content-Type')).toBe('application/json');
    expect(init).toMatchObject({credentials:'same-origin',cache:'no-store',redirect:'error'});
    expect(body).toEqual({sku,quantity:expect.stringMatching(/^[1-3]$/)});
    const key=actor+'/'+id, amount=Number(body.quantity);
    if(controls.loseBeforeCommit){controls.loseBeforeCommit=false;throw new TypeError('Request not delivered');}
    if(receipts.has(key)) expect(receipts.get(key)!.amount).toBe(amount);
    else if(controls.withdrawn) return Response.json({error:'request_not_completed'},{status:422});
    else {qty.set(actor,(qty.get(actor) ?? 0)+amount);receipts.set(key,{amount,version:String(receipts.size+1)});}
    if(controls.delay) await controls.delay;
    if(controls.dropNextAck){controls.dropNextAck=false;throw new TypeError('Result lost after commit');}
    return Response.json({state:'APPLIED',version:receipts.get(key)!.version});
  });
  return {qty,calls,receipts,controls,fetchFor, posts:()=>calls.filter(c=>c.method==='POST')};
}
type Api=ReturnType<typeof fakeApi>;
function visit(api:Api, actor=a, route=tide,recover?:()=>Promise<void>) {
  const store=createAppStore('en');
  const owner={document:crypto.randomUUID(),epoch:1,request:1,revision:crypto.randomUUID()};
  store.dispatch(authenticationStarted({owner,phase:'CHECKING',notice:'NONE'}));
  store.dispatch(authenticationFinished({owner,result:{phase:'AUTHENTICATED',notice:'NONE',user:{id:actor,status:'ACTIVE'}}}));
  // Decoy local line: it must neither drive signed-in counts nor receive the direct addition.
  store.dispatch(cartAdded({variant:sku,quantity:1}));
  const guest=store.getState().productDemo.cart;
  const session=createLocalCartSession(store,{isCurrentOwner:other=>sameOwner(other,owner)},api.fetchFor(actor));
  const root=render(<StrictMode><Provider store={store}><MemoryRouter initialEntries={[route]}>
    <AuthenticationContext.Provider value={recover?{recover} as Authentication:null}><LocalAccountStorefront session={session}/></AuthenticationContext.Provider>
  </MemoryRouter></Provider></StrictMode>);
  return {phase:()=>session.getSnapshot()?.access.getSnapshot().phase,
    leaveIdentity:()=>store.dispatch(authenticationStarted({owner:{...owner,epoch:owner.epoch+1,revision:crypto.randomUUID()},phase:'CHECKING',notice:'NONE'})),
    guestUntouched:()=>expect(store.getState().productDemo.cart).toEqual(guest), close:()=>{root.unmount();session.dispose();} };
}

it('credits a signed-in purchase only once, reads server totals, and never posts on a cold cart reload',async()=>{
  const api=fakeApi(), user=userEvent.setup(), first=visit(api), gate=deferred();
  try {
    const add=await enabledAdd();
    expect(screen.queryByText(copy.alreadyInCart.replace('{n}','1'))).not.toBeInTheDocument();
    await user.click(screen.getByRole('button',{name:copy.increase}));
    api.controls.delay=gate.promise;
    await user.dblClick(add);
    await waitFor(()=>expect(api.posts()).toHaveLength(1));
    expect(add).toBeDisabled();
    expect(add).toHaveAttribute('aria-busy','true');
    expect(add).toHaveAccessibleName(copy.add);
    expect(add.querySelector('.product-spinner')).toBeVisible();
    expect(screen.getByRole('button',{name:copy.decrease})).toBeDisabled();
    expect(screen.getByRole('button',{name:copy.increase})).toBeDisabled();
    expect(screen.getByRole('textbox',{name:copy.quantity})).toBeDisabled();
    expect(screen.getByRole('banner').querySelector('a[href="/preview/cart"]')).toBeVisible();
    expect(screen.queryByText(copy.alreadyInCart.replace('{n}','2'))).not.toBeInTheDocument();
    await act(async()=>gate.done());
    expect(await shownQuantity(2)).toBeVisible();
    expect(screen.getByRole('button',{name:copy.add}).querySelector('.product-spinner')).toBeNull();
    expect(api.calls.slice(api.calls.indexOf(api.posts()[0])+1).some(r=>r.path==='/api/account-cart'&&r.method==='GET')).toBe(true);
    first.guestUntouched();
    await user.click(screen.getByRole('link',{name:copy.viewCart}));
    const lines=screen.getAllByRole('listitem').filter(n=>n.hasAttribute('data-account-cart-line'));
    expect(lines).toHaveLength(1);expect(lines[0]).toHaveTextContent(/Tide.*× 2/);expect(lines[0]).toHaveAttribute('data-account-cart-line',lineId);
  } finally {gate.done();first.close();}
  const cold=visit(api,a,cart);
  try {
    const link=await screen.findByRole('link',{name:'Tide'});
    expect(link.closest('li')).toHaveTextContent(/× 2/);
    expect(api.posts()).toHaveLength(1);expect(api.receipts.size).toBe(1);
    await user.click(link);
    await user.click(await enabledAdd());expect(await shownQuantity(3)).toBeVisible();
    expect(new Set(api.posts().map(r=>r.path)).size).toBe(2);
    cold.guestUntouched();
  } finally {cold.close();}
});

it.each([tide,cart])('offers intentional fresh sign-in, never a purchase or guest fallback, on a refused cart context at %s',async route=>{
  const api=fakeApi();api.controls.expired=true;const recover=vi.fn(async()=>{});
  const page=visit(api,a,route,recover);
  try{
    const button=await screen.findByRole('button',{name:'Sign in again'});expect(button).toBeEnabled();
    expect(screen.queryByRole('button',{name:copy.add})).not.toBeInTheDocument();
    expect(recover).not.toHaveBeenCalled();expect(api.posts()).toEqual([]);
    if(route===tide){
      const plus=screen.getByRole('button',{name:copy.increase}),minus=screen.getByRole('button',{name:copy.decrease});
      await userEvent.click(plus);await userEvent.click(plus);await userEvent.click(minus);
      expect(screen.getByRole('textbox',{name:copy.quantity})).toHaveValue('2');
      expect(api.posts()).toEqual([]);
    }
    await userEvent.click(button);expect(recover).toHaveBeenCalledOnce();expect(api.posts()).toEqual([]);page.guestUntouched();
  }finally{page.close();}
});
it('recovers an unavailable cart on the product card by GET, then waits for a separate add click',async()=>{
  const api=fakeApi();api.controls.offline=true;const page=visit(api);
  try{
    const retry=await screen.findByRole('button',{name:'Retry cart access'});
    await userEvent.click(screen.getByRole('button',{name:copy.increase}));
    expect(screen.getByRole('textbox',{name:copy.quantity})).toHaveValue('2');
    api.controls.offline=false;
    await userEvent.click(retry);const add=await enabledAdd();expect(api.posts()).toEqual([]);
    await userEvent.click(add);expect(await shownQuantity(2)).toBeVisible();expect(api.posts()).toHaveLength(1);page.guestUntouched();
  }finally{page.close();}
});

it('retries an uncertain original action by explicit gesture after remount, even when its commit used all stock',async()=>{
  const api=fakeApi(), user=userEvent.setup();
  api.qty.set(a,1);api.controls.dropNextAck=true;
  const first=visit(api);
  try {
    const add=await enabledAdd();
    await user.click(screen.getByRole('button',{name:copy.increase}));
    await user.click(add);
    expect(await screen.findByRole('button',{name:/retry/i})).toBeEnabled();
    expect(api.qty.get(a)).toBe(3);expect(api.posts()).toHaveLength(1);
  } finally {first.close();}
  const other=visit(api,b);
  try {await enabledAdd();expect(screen.queryByRole('button',{name:/retry/i})).not.toBeInTheDocument();expect(api.posts()).toHaveLength(1);}
  finally {other.close();}
  // Same buyer/namespace, but a new server write ticket: never replay credentials from the former admission.
  api.controls.context=crypto.randomUUID();
  const same=visit(api);
  try {
    const retry=await screen.findByRole('button',{name:/retry/i});
    expect(api.posts()).toHaveLength(1);expect(retry).toBeEnabled();
    await user.click(retry);
    await waitFor(()=>expect(screen.queryByRole('button',{name:/retry/i})).not.toBeInTheDocument());
    expect(await shownQuantity(3)).toBeVisible();
    const attempts=api.posts();
    expect(attempts).toHaveLength(2);expect(attempts[1].path).toBe(attempts[0].path);
    expect(attempts.map(r=>r.actor)).toEqual([a,a]);expect(attempts[1].init.body).toBe(attempts[0].init.body);
    expect(api.qty.has(b)).toBe(false);expect(api.receipts.size).toBe(1);
    same.guestUntouched();
  } finally {same.close();}
});

it('keeps other catalog offers read-only rather than falling back to the guest reducer',async()=>{
  const api=fakeApi(), page=visit(api,a,'/products/bowl');
  try {
    const add=await screen.findByRole('button',{name:copy.add});
    expect(add).toBeDisabled();await userEvent.click(add);page.guestUntouched();expect(api.posts()).toEqual([]);
  } finally {page.close();}
});

it('shows the committed two units in the same SPA cart after a lost reply without automatically retrying',async()=>{
  const api=fakeApi(), user=userEvent.setup(), page=visit(api);
  api.controls.dropNextAck=true;
  try {
    const add=await enabledAdd();
    await user.click(screen.getByRole('button',{name:copy.increase}));await user.click(add);
    expect(await screen.findByRole('button',{name:'Retry this addition'})).toBeEnabled();
    expect(api.posts()).toHaveLength(1);expect(api.qty.get(a)).toBe(2);
    await user.click(screen.getByRole('link',{name:copy.viewCart}));
    const row=await screen.findByRole('link',{name:'Tide'});
    expect(row.closest('li')).toHaveAttribute('data-account-cart-line',lineId);
    expect(row.closest('li')).toHaveTextContent(/× 2/);
    expect(api.calls.slice(api.calls.indexOf(api.posts()[0])+1).some(r=>r.path==='/api/account-cart'&&r.method==='GET')).toBe(true);
    expect(api.posts()).toHaveLength(1);

    await user.click(row);
    const retry=await screen.findByRole('button',{name:'Retry this addition'});
    expect(await shownQuantity(2)).toBeVisible();
    expect(api.posts()).toHaveLength(1);
    await user.click(retry);
    await waitFor(()=>expect(screen.queryByRole('button',{name:'Retry this addition'})).not.toBeInTheDocument());
    expect(await shownQuantity(2)).toBeVisible();
    expect(api.posts().map(r=>r.path)).toEqual([api.posts()[0].path,api.posts()[0].path]);
    expect(api.posts().map(r=>r.init.body)).toEqual([JSON.stringify({sku,quantity:'2'}),JSON.stringify({sku,quantity:'2'})]);
    expect(api.receipts.size).toBe(1);page.guestUntouched();
  } finally {page.close();}
});

it.each([true,false])('recovers the original action from the private cart with no catalog fallback; receipt exists: %s',async applied=>{
  const api=fakeApi(), user=userEvent.setup(), first=visit(api);
  api.controls.dropNextAck=applied;api.controls.loseBeforeCommit=!applied;
  try {
    const add=await enabledAdd();await user.click(screen.getByRole('button',{name:copy.increase}));await user.click(add);
    expect(await screen.findByRole('button',{name:'Retry this addition'})).toBeEnabled();
    expect(api.receipts.size).toBe(applied?1:0);
  } finally {first.close();}
  // The seller withdraws Tide. The former product page and its capability cannot be used
  // as the path to resolve the buyer's previously recorded, still-uncertain action.
  api.controls.withdrawn=true;
  vi.spyOn(catalog,'connectedSku').mockReturnValue(undefined);
  vi.spyOn(catalog,'connectedOffer').mockReturnValue(undefined);
  const loader=vi.spyOn(catalog,'loadLocalCatalog').mockRejectedValue(new TypeError('No catalog available'));
  const other=visit(api,b,cart);
  try {
    expect(await screen.findByText(copy.emptyCart)).toBeVisible();
    expect(screen.queryByRole('button',{name:'Retry this addition'})).not.toBeInTheDocument();
    expect(screen.queryByText(/tide-cobalt/i)).not.toBeInTheDocument();expect(api.posts()).toHaveLength(1);
  } finally {other.close();}
  api.controls.context=crypto.randomUUID();
  const same=visit(api,a,cart);
  try {
    const retry=await screen.findByRole('button',{name:'Retry this addition'});
    expect(retry.closest('[data-pending-addition]')).toHaveTextContent('tide-cobalt · Addition not confirmed: 2.');
    expect(retry).toBeEnabled();expect(screen.queryByRole('button',{name:copy.add})).not.toBeInTheDocument();
    expect(api.posts()).toHaveLength(1);expect(loader).not.toHaveBeenCalled();
    await user.click(retry);
    await waitFor(()=>expect(screen.queryByRole('button',{name:'Retry this addition'})).not.toBeInTheDocument());
    if(applied){
      const rows=screen.getAllByRole('listitem').filter(n=>n.hasAttribute('data-account-cart-line'));
      expect(rows).toHaveLength(1);expect(rows[0]).toHaveTextContent(/Tide.*× 2/);expect(rows[0]).toHaveAttribute('data-account-cart-line',lineId);
    }else expect(await screen.findByText(copy.emptyCart)).toBeVisible();
    expect(api.posts()).toHaveLength(2);const [sent,replay]=api.posts();
    expect(replay.path).toBe(sent.path);expect(JSON.parse(String(replay.init.body))).toEqual({sku,quantity:'2'});
    expect(replay.actor).toBe(a);expect(new Headers(replay.init.headers).get('X-Cart-Context')).toBe(api.controls.context);
    expect(api.receipts.size).toBe(applied?1:0);expect(api.qty.get(a)??0).toBe(applied?2:0);same.guestUntouched();
  } finally {same.close();}
  const reopened=visit(api,a,cart);
  try {
    await waitFor(()=>expect(reopened.phase()).toBe('ready'));
    expect(screen.queryByRole('button',{name:'Retry this addition'})).not.toBeInTheDocument();
    expect(api.posts()).toHaveLength(2);expect(loader).not.toHaveBeenCalled();
  } finally {reopened.close();}
});

it('cannot buy on a refused account read even though the catalog marks Tide available',async()=>{
  const api=fakeApi();api.controls.denyRead=true;
  const page=visit(api);
  try {
    await waitFor(()=>expect(page.phase()).toBe('blocked'));
    const button=screen.queryByRole('button',{name:copy.add});
    if(button){expect(button).toBeDisabled();await userEvent.click(button);}
    expect(api.posts()).toEqual([]);page.guestUntouched();
  } finally {page.close();}
});

it('does not send a purchase if writing its pre-request journal fails',async()=>{
  const api=fakeApi(), page=visit(api);
  const storage=window.sessionStorage, descriptor=Object.getOwnPropertyDescriptor(window,'sessionStorage');
  try {
    const add=await enabledAdd();
    const writes=vi.fn(()=>{
      throw new DOMException('Unavailable','QuotaExceededError');
    });
    Object.defineProperty(window,'sessionStorage',{configurable:true,value:{getItem:(key:string)=>storage.getItem(key),setItem:writes}});
    await userEvent.click(add);
    expect(writes).toHaveBeenCalled();expect(api.posts()).toEqual([]);expect(api.receipts.size).toBe(0);page.guestUntouched();
  } finally {
    if(descriptor)Object.defineProperty(window,'sessionStorage',descriptor);
    else Reflect.deleteProperty(window,'sessionStorage');
    page.close();
  }
});

it('does not send the old signed-in purchase if the active buyer changes during CSRF acquisition',async()=>{
  const api=fakeApi(), hold=deferred(), first=visit(api);
  api.controls.holdCsrf=hold.promise;
  try {
    await userEvent.click(await enabledAdd());
    await waitFor(()=>expect(api.calls.some(c=>c.path==='/auth/csrf')).toBe(true));
    act(()=>{first.leaveIdentity();});
    await act(async()=>hold.done());
    expect(api.posts()).toEqual([]);first.guestUntouched();
  } finally {hold.done();first.close();}
  const next=visit(api,b);
  try {await enabledAdd();expect(screen.queryByRole('button',{name:/retry/i})).not.toBeInTheDocument();expect(api.qty.size).toBe(0);}
  finally {next.close();}
});
