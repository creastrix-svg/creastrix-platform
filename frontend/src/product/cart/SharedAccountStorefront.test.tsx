import {render,screen,waitFor,within} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {Provider} from 'react-redux';
import {MemoryRouter} from 'react-router';
import {beforeAll,afterEach,expect,it,vi} from 'vitest';
import {readFileSync} from 'node:fs';
import {authenticationFinished,authenticationStarted,createAppStore} from '../../store';
import {sameOwner} from '../../authBoundary';
import {loadLocalCatalog} from '../catalog/localCatalog';
import {productCopy} from '../copy';
import {personalizationCopy} from '../studio/personalizationCopy';
import {studioExtensionCopy} from '../studio/studioExtensionCopy';
import {createLocalCartSession} from './localCartSession';
import LocalAccountStorefront from './LocalAccountStorefront';
import type {AppliedLoopDesign} from './appliedLoopDesign';

vi.hoisted(()=>vi.stubEnv('MODE','shared-cart-local'));
vi.mock('@google/model-viewer',()=>({}));
vi.mock('./guestCartBatch',async orig=>({...await orig<typeof import('./guestCartBatch')>(),hasClaimedGuestWork:async()=>false}));
const {outbox}=vi.hoisted(()=>({outbox:new Map<string,AppliedLoopDesign>()}));
vi.mock('./pendingDesignFiles',()=>({pendingDesignFile:async(key:string,op:string,data?:AppliedLoopDesign)=>{
  if(op==='put')outbox.set(key,data!);else if(op==='remove')outbox.delete(key);return outbox.get(key);
}}));
const label=(s:string)=>({en:s,de:s,ru:s,it:s,pl:s,es:s,fr:s});
const ids=['cobalt','coral','chalk','cobalt-chalk'];
const product={id:'loop',name:'Loop',href:'/products/loop',personalizable:true,designFeeCents:500,currency:'EUR',
  variants:ids.map(id=>({id,sku:id,name:label(id),cents:2200,previousCents:null,stock:12,colors:['#aaaaff'],checkTone:'dark',
    photos:['/product/reference-asset-01.webp'],model:null,studioFormat:'surface-print-v1',templateRevision:'2'}))};
const entry={id:product.id,name:product.name,href:product.href,kind:'offer',description:label('Pot'),material:label('Clay'),
  title:label('Pot'),photo:null,thumb:null,categories:['living'],collectionId:null,collectionName:null,editionOrder:1,homeOrder:1,rating:{count:0,average:null}};
const alternate={...product,id:'azur',name:'Azur Print',href:'/collections/objects/azur-print',
  variants:[{...product.variants[0],id:'satin',sku:'custom-satin'}]};
const archived={...alternate,id:'archived',name:'Retired studio',href:'/products/archived',
  variants:[{...alternate.variants[0],id:'silver',sku:'archived-silver',studioFormat:null,templateRevision:null}]};
const catalog={products:[product,alternate,archived],entries:[entry,
  ...[alternate,archived].map((p,i)=>({...entry,id:p.id,name:p.name,href:p.href,editionOrder:i+3,homeOrder:i+3}))],recommendations:[]};
beforeAll(async()=>{
  const fetcher=vi.spyOn(window,'fetch').mockResolvedValue(Response.json(catalog));
  try{await loadLocalCatalog();}finally{fetcher.mockRestore();}
});
afterEach(()=>{vi.restoreAllMocks();vi.unstubAllGlobals();sessionStorage.clear();outbox.clear();});
it('opens a saved cart line with a retired template as unavailable, never as a plain sale or a new design',async()=>{
  const a=crypto.randomUUID(),namespace=crypto.randomUUID(),writeContext=crypto.randomUUID(),lineId=crypto.randomUUID(),t=productCopy.en;
  const read=vi.spyOn(window,'fetch').mockImplementation(async(path,init)=>{
    if(path==='/api/local-catalog/products')return Response.json(catalog);
    if(path!=='/api/account-cart' || init?.method!=='GET')throw Error('Unexpected request');
    return Response.json({kind:'ready',ownerId:a,namespace,writeContext,guestIntent:null,cart:[{
      lineId,offerId:archived.id,variantId:'silver',sku:'archived-silver',offerLabel:archived.name,variantLabel:'Silver',href:archived.href,
      listed:true,templateRevision:'2',designId:crypto.randomUUID(),designRevision:'1',studioFormat:'surface-print-v1',designLabel:'Artwork',quantity:'2',
    }]});
  });
  const store=createAppStore('en'),owner={document:crypto.randomUUID(),epoch:1,request:1,revision:crypto.randomUUID()};
  store.dispatch(authenticationStarted({owner,phase:'CHECKING',notice:'NONE'}));
  store.dispatch(authenticationFinished({owner,result:{phase:'AUTHENTICATED',notice:'NONE',user:{id:a,status:'ACTIVE'}}}));
  const session=createLocalCartSession(store,{isCurrentOwner:other=>sameOwner(other,owner)},read);
  const view=render(<Provider store={store}><MemoryRouter initialEntries={['/preview/cart']}><LocalAccountStorefront session={session}/></MemoryRouter></Provider>);
  try {
    await userEvent.click(await screen.findByRole('link',{name:archived.name}));
    expect(await screen.findByText(/saved design is no longer available/)).toBeVisible();
    expect(screen.queryByRole('button',{name:t.add})).not.toBeInTheDocument();
    expect(screen.queryByRole('link',{name:t.personalButton})).not.toBeInTheDocument();
    expect(screen.getByRole('link',{name:t.viewCart})).toHaveAttribute('href','/preview/cart');
    expect(read.mock.calls.every(([url,init])=>['/api/account-cart','/api/local-catalog/products'].includes(String(url))&&init?.method==='GET')).toBe(true);
  }finally{view.unmount();session.dispose();}
});
it.each([false,true])('applies, buys, and reopens server artwork without extra writes (new unrecognised product = %s)',async(generic)=>{
  const a=crypto.randomUUID(),namespace=crypto.randomUUID(),writeContext=crypto.randomUUID(),lineId=crypto.randomUUID();
  const p=personalizationCopy.en,t=productCopy.en,user=userEvent.setup();
  const offer=generic?alternate:product,variant=offer.variants[0],sku=variant.sku;
  let saved:AppliedLoopDesign|undefined,designId:string|undefined,added=false,posts=0;
  Object.defineProperty(document,'fonts',{configurable:true,value:{add:vi.fn(),delete:vi.fn()}});
  vi.stubGlobal('FontFace',class {async load(){return this;}});
  vi.spyOn(HTMLCanvasElement.prototype,'getContext').mockReturnValue({measureText:(text:string)=>({width:text.length*10})} as unknown as CanvasRenderingContext2D);
  const fetcher=vi.spyOn(window,'fetch').mockImplementation(async(input,init={})=>{
    const path=String(input);
    if(path==='/api/local-catalog/products')return Response.json(catalog);
    if(path.startsWith('/fonts/Nunito.ttf?'))return new Response(readFileSync('public/fonts/Nunito.ttf'));
    if(path==='/api/account-cart')return Response.json({kind:'ready',ownerId:a,namespace,writeContext,guestIntent:null,cart:added?[{
      lineId,offerId:offer.id,offerLabel:offer.name,variantId:variant.id,variantLabel:'Blue',sku,href:offer.href,
      designId,designRevision:'1',designLabel:'Printed',studioFormat:'surface-print-v1',templateRevision:'2',quantity:'1',listed:true,
    }]:[]});
    if(path==='/auth/csrf')return Response.json({headerName:'X-CSRF-TOKEN',parameterName:'_csrf',token:'csrf'});
    if(path==='/api/account-cart/lines/'+lineId+'/design')return Response.json(saved);
    if(init.method==='POST'){
      ++posts;
      if(path.includes('/designs/')){
        saved=JSON.parse(String(init.body));designId=path.split('/').at(-1);return Response.json({designId,designRevision:'1'});
      }
      if(path.includes('/additions/') && designId){
        expect(JSON.parse(String(init.body))).toEqual({sku,quantity:'1',designId,designRevision:'1'});
        added=true;return Response.json({state:'APPLIED',version:'1',lineId});
      }
    }
    throw Error('Unexpected HTTP '+path);
  });
  function show(path:string){
    const store=createAppStore('en'),owner={document:crypto.randomUUID(),epoch:1,request:1,revision:crypto.randomUUID()};
    store.dispatch(authenticationStarted({owner,phase:'CHECKING',notice:'NONE'}));
    store.dispatch(authenticationFinished({owner,result:{phase:'AUTHENTICATED',notice:'NONE',user:{id:a,status:'ACTIVE'}}}));
    const session=createLocalCartSession(store,{isCurrentOwner:other=>sameOwner(other,owner)},window.fetch);
    const ui=render(<Provider store={store}><MemoryRouter initialEntries={[path]}><LocalAccountStorefront session={session}/></MemoryRouter></Provider>);
    return ()=>{ui.unmount();session.dispose();};
  }
  let close=show(generic?offer.href+'?variant='+variant.id+'&personalize=surface':'/products/loop/personalize?variant=cobalt');
  try{
    const copy=studioExtensionCopy.en;
    const ai=await screen.findByRole('button',{name:copy.createWithAi});
    expect(ai).toBeDisabled();
    expect(ai).toHaveAccessibleDescription(copy.generationUnavailable);
    expect(within(ai).getByText(copy.generationUnavailable)).toBeVisible();
    await user.click(ai);
    expect(screen.queryByRole('textbox',{name:copy.aiPrompt})).toBeNull();
    expect(posts).toBe(0);
    await user.type(await screen.findByRole('textbox',{name:p.textLabel}),'My Loop');
    const apply=screen.getByRole('button',{name:p.apply});await waitFor(()=>expect(apply).toBeEnabled());await user.click(apply);
    await user.click(screen.getByRole('link',{name:p.back}));
    if(!generic)expect(document.querySelector('.product-applied-lettering')).toHaveTextContent('My Loop');
    const add=screen.getByRole('button',{name:t.add});await waitFor(()=>expect(add).toBeEnabled());await user.click(add);
    await waitFor(()=>expect(added).toBe(true));
    expect(saved?.objects).toEqual([expect.objectContaining({kind:'text',text:'My Loop',font:'rounded'})]);
    close();close=show('/preview/cart');
    const cartRow=await screen.findByText(offer.name,{selector:'article h2 a'});
    expect(cartRow).toHaveAttribute('href',offer.href+'?variant='+variant.id+'&accountLine='+lineId);
    await user.click(cartRow);
    if(generic){
      await user.click(await screen.findByRole('link',{name:t.personalButton}));
      expect(await screen.findByRole('textbox',{name:p.textLabel})).toHaveValue('My Loop');
    } else {
      await waitFor(()=>expect(document.querySelector('.product-applied-lettering')).toHaveTextContent('My Loop'));
      expect(within(document.querySelector('.product-applied-lettering')!).getByRole('img')).toBeInTheDocument();
    }
    expect(fetcher.mock.calls.some(([path])=>String(path).endsWith('/lines/'+lineId+'/design'))).toBe(true);
    expect(posts).toBe(2);
  }finally{close();}
});
