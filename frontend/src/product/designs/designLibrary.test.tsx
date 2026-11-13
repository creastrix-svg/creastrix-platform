import {afterEach,beforeAll,expect,it,vi} from 'vitest';
import {render,screen,waitFor,within} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {readFileSync} from 'node:fs';
import {Provider} from 'react-redux';
import {MemoryRouter} from 'react-router';
import {sameOwner} from '../../authBoundary';
import {authenticationFinished,authenticationStarted,createAppStore} from '../../store';
import {createLocalCartSession} from '../cart/localCartSession';
import LocalAccountStorefront from '../cart/LocalAccountStorefront';
import {loadLocalCatalog} from '../catalog/localCatalog';
import type {AppliedLoopDesign} from '../cart/appliedLoopDesign';
import {designLibraryCopy} from './designLibraryCopy';
import type {LibraryChange,LibraryItem} from './designLibraryApi';

vi.hoisted(()=>vi.stubEnv('MODE','shared-cart-local'));
vi.mock('@google/model-viewer',()=>({}));
vi.mock('../cart/guestCartBatch',async original=>({...await original<typeof import('../cart/guestCartBatch')>(),hasClaimedGuestWork:async()=>false}));
const tr=(s:string)=>({en:s,de:s,es:s,fr:s,it:s,pl:s,ru:s});
const variants=['cobalt','chalk','coral','cobalt-chalk'].map(id=>({id,sku:id,cents:2400,previousCents:null,stock:20,colors:['#333333'],
  name:tr(id),checkTone:'dark',photos:['/product/asset.webp'],model:null,templateRevision:'2',studioFormat:'surface-print-v1'}));
const feed={products:[{id:'loop',href:'/products/loop',name:'Loop',personalizable:true,designFeeCents:500,currency:'EUR',variants}],
  entries:[{id:'loop',href:'/products/loop',name:'Loop',kind:'offer',description:tr('Clay pot'),material:tr('Clay'),title:tr('Pot'),
    photo:null,thumb:null,categories:['living'],collectionId:null,collectionName:null,editionOrder:1,homeOrder:1,rating:{count:0,average:null}}],recommendations:[]};
beforeAll(async()=>{const mock=vi.spyOn(window,'fetch').mockResolvedValue(Response.json(feed));try{await loadLocalCatalog();}finally{mock.mockRestore();}});
afterEach(()=>{vi.unstubAllGlobals();vi.restoreAllMocks();});
function api(){
  const ownerId=crypto.randomUUID(),namespace=crypto.randomUUID(),writeContext=crypto.randomUUID(),stages=new Map<string,AppliedLoopDesign>();
  const items=new Map<string,{item:LibraryItem;designId:string}>(),receipts=new Map<string,{id:string;version:string}>(),writes:string[]=[];
  const library='/api/account-cart/library';let lost=false,drop=false,conflict=false,responseOwner=ownerId;
  const fetcher=vi.spyOn(window,'fetch').mockImplementation(async(u,init={})=>{
    const url=String(u);
    if(url.startsWith('/fonts/Nunito.ttf?'))return new Response(readFileSync('public/fonts/Nunito.ttf'));
    if(url==='/api/local-catalog/products')return Response.json(feed);
    if(url==='/api/account-cart')return Response.json({kind:'ready',cart:[],ownerId,namespace,writeContext,guestIntent:null});
    if(url==='/auth/csrf')return Response.json({headerName:'X-CSRF-TOKEN',parameterName:'_csrf',token:'one'});
    if(init.method==='GET' && url===library)return Response.json({ownerId:responseOwner,designs:[...items.values()].map(i=>i.item)});
    const id=url.split('/').at(-1)!;
    if(init.method==='GET' && url.startsWith(library+'/')){const snap=items.get(id);return snap?Response.json({ownerId:responseOwner,item:snap.item,design:stages.get(snap.designId)}):Response.json({},{status:404});}
    if(init.method==='POST'){
      expect(new Headers(init.headers).get('X-Cart-Context')).toBe(writeContext);writes.push(url);
      const payload=JSON.parse(String(init.body));
      if(url.startsWith('/api/account-cart/designs/')){stages.set(id,payload);return Response.json({designId:id,designRevision:'1'});}
      if(url.startsWith(library+'/commands/')){
        if(receipts.has(id))return Response.json(receipts.get(id));
        const body=payload as LibraryChange,previous=items.get(body.id),version=String(Number(body.expectedVersion)+1);
        if(conflict||body.expectedVersion!==(previous?.item.version??'0'))return Response.json({error:'request_not_completed'},{status:409});
        const result=body.action==='copy'?{id:body.copyId,version:'1'}:{id:body.id,version};
        if(body.action==='save'){
          const design=stages.get(body.designId)!,text=design.objects.find(o=>o.kind==='text');
          items.set(body.id,{designId:body.designId,item:{...result,sku:'cobalt',offerId:'loop',variantId:'cobalt',offerLabel:'Loop',variantLabel:'Cobalt',caption:text?.text??null}});
        }else if(body.action==='delete')items.delete(body.id);
        else items.set(body.copyId,{designId:previous!.designId,item:{...previous!.item,...result}});
        receipts.set(id,result);if(drop&&!lost){lost=true;throw new TypeError('lost');}return Response.json(result);
      }
    }
    throw Error('Unexpected '+url);
  });
  Object.defineProperty(document,'fonts',{configurable:true,value:{add:vi.fn(),delete:vi.fn()}});
  vi.stubGlobal('FontFace',class {async load(){return this;}});
  vi.spyOn(HTMLCanvasElement.prototype,'getContext').mockReturnValue({measureText:(text:string)=>({width:text.length*6})} as unknown as CanvasRenderingContext2D);
  function show(route:string){
    const store=createAppStore('en'),owner={document:crypto.randomUUID(),epoch:1,request:1,revision:crypto.randomUUID()};
    store.dispatch(authenticationStarted({owner,phase:'CHECKING',notice:'NONE'}));store.dispatch(authenticationFinished({owner,result:{phase:'AUTHENTICATED',notice:'NONE',user:{id:ownerId,status:'ACTIVE'}}}));
    const session=createLocalCartSession(store,{isCurrentOwner:o=>sameOwner(o,owner)},window.fetch);
    const ui=render(<Provider store={store}><MemoryRouter initialEntries={[route]}><LocalAccountStorefront session={session}/></MemoryRouter></Provider>);
    return ()=>{ui.unmount();session.dispose();};
  }
  return {show,items,writes,stages,fetcher,dropAck:()=>{drop=true;},denyStale:()=>{conflict=true;},otherResponse:()=>{responseOwner=crypto.randomUUID();}};
}
it('saves an un-applied draft, retries one lost acknowledgement, reopens it in a new document, edits a new version, copies and deletes only the selected design',async()=>{
  const a=api(),user=userEvent.setup(),t=designLibraryCopy.en;let close=a.show('/products/loop/personalize?variant=cobalt');
  try{
    const input=await screen.findByRole('textbox',{name:'Your text'});await user.type(input,'Anna');
    const save=screen.getByRole('button',{name:t.save});await waitFor(()=>expect(save).toBeEnabled());a.dropAck();
    await user.click(save);await user.click(await screen.findByRole('button',{name:t.retry}));
    expect(await screen.findByText(t.saved)).toBeVisible();expect(a.items.size).toBe(1);
    expect(a.writes[1]).toBe(a.writes[2]);expect(a.writes.filter(p=>p.includes('/additions/'))).toEqual([]);
    expect(input).toHaveValue('Anna');
    const savedId=[...a.items.keys()][0],first=a.items.get(savedId)!.designId;
    close();close=a.show('/account/designs');const open=await screen.findByRole('link',{name:t.open});await user.click(open);
    const restored=await screen.findByRole('textbox',{name:'Your text'});expect(restored).toHaveValue('Anna');
    await user.clear(restored);await user.type(restored,'Maria');await user.click(screen.getByRole('button',{name:t.save}));
    expect(await screen.findByText(t.saved)).toBeVisible();expect(a.items.get(savedId)?.item.version).toBe('2');
    expect(a.stages.get(first)?.objects[0]).toMatchObject({text:'Anna'});
    close();close=a.show('/account/designs');await user.click(await screen.findByRole('button',{name:t.copy}));
    await waitFor(()=>expect(screen.getAllByRole('button',{name:t.remove})).toHaveLength(2));
    await user.click(screen.getAllByRole('button',{name:t.remove})[1]);const dialog=await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button',{name:t.confirm}));await waitFor(()=>expect(a.items.size).toBe(1));expect(a.items.has(savedId)).toBe(true);
    expect(a.writes.every(p=>p.startsWith('/api/account-cart/designs/')||p.startsWith('/api/account-cart/library/commands/'))).toBe(true);
  }finally{close();}
});
it('does not stage or create a new item from an invalid library link',async()=>{
  const a=api(), close=a.show('/products/loop/personalize?variant=cobalt&savedDesign=not-a-key');
  try{expect(await screen.findByText(designLibraryCopy.en.unavailable)).toBeVisible();expect(a.writes).toEqual([]);
    expect(screen.queryByRole('button',{name:designLibraryCopy.en.save})).toBeNull();
  }finally{close();}
});
it('preserves a newer entry and the unacknowledged editor text on a version conflict',async()=>{
  const a=api(),user=userEvent.setup(),t=designLibraryCopy.en;let close=a.show('/products/loop/personalize?variant=cobalt');
  try{
    await user.type(await screen.findByRole('textbox',{name:'Your text'}),'First');
    await user.click(screen.getByRole('button',{name:t.save}));await screen.findByText(t.saved);
    const [id,first]=[...a.items.entries()][0];
    close();close=a.show('/products/loop/personalize?variant=cobalt&savedDesign='+id);
    const restored=await screen.findByRole('textbox',{name:'Your text'}),save=screen.getByRole('button',{name:t.save});
    expect(restored).toHaveValue('First');expect(save).toBeDisabled();
    await user.clear(restored);await user.type(restored,'Unsaved');a.denyStale();await user.click(save);
    expect(await screen.findByText(t.conflict)).toBeVisible();expect(restored).toHaveValue('Unsaved');expect(save).toBeDisabled();
    expect(a.items.get(id)).toEqual(first);expect(screen.queryByText(t.saved)).not.toBeInTheDocument();
  }finally{close();}
});
it('does not render shelf metadata or private layers when the server responded for a different buyer',async()=>{
  const a=api(),user=userEvent.setup(),t=designLibraryCopy.en;let close=a.show('/products/loop/personalize?variant=cobalt');
  try{
    await user.type(await screen.findByRole('textbox',{name:'Your text'}),'Private caption');
    await user.click(screen.getByRole('button',{name:t.save}));await screen.findByText(t.saved);
    const id=[...a.items.keys()][0];close();a.otherResponse();
    close=a.show('/account/designs');await screen.findByRole('alert');
    expect(screen.queryByText('Private caption')).not.toBeInTheDocument();
    close();close=a.show('/products/loop/personalize?variant=cobalt&savedDesign='+id);
    expect(await screen.findByText(t.failed)).toBeVisible();expect(screen.queryByRole('textbox',{name:'Your text'})).toBeNull();
  }finally{close();}
});
