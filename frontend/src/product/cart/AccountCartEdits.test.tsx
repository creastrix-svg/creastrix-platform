import {render,screen,within,waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {Provider} from 'react-redux';
import {MemoryRouter} from 'react-router';
import {afterEach,expect,it,vi} from 'vitest';
import {sameOwner} from '../../authBoundary';
import {authenticationFinished,authenticationStarted,createAppStore} from '../../store';
import {productCopy} from '../copy';
import {createLocalCartSession} from './localCartSession';
import LocalAccountStorefront from './LocalAccountStorefront';

vi.hoisted(()=>vi.stubEnv('MODE','shared-cart-local'));
vi.mock('@google/model-viewer',()=>({}));
vi.mock('./guestCartBatch',async orig=>({...await orig<typeof import('./guestCartBatch')>(),hasClaimedGuestWork:async()=>false}));
afterEach(()=>vi.restoreAllMocks());
const t=productCopy.en;
const texts=(s:string)=>({en:s,de:s,ru:s,it:s,pl:s,es:s,fr:s});
type Change={action:'quantity'|'remove'|'restore'|'dismiss';quantity?:string;expectedVersion:string;lineId:string};
function setup(removed=false){
  const owner=crypto.randomUUID(),ns=crypto.randomUUID(),ctx=crypto.randomUUID(),id=crypto.randomUUID(),archive=crypto.randomUUID(),designId=crypto.randomUUID();
  let qty='2',gone=removed,version=7,stock=16,price=3100,lookup=true,refuse=false,lost=false,hidden=false,pause:Promise<void>|undefined;
  let reads=0;const posts:{id:string;body:Change}[]=[],receipts=new Map<string,string>();
  const href='/products/custom-item',productId='custom-item',sku='print-pink';
  const api=vi.spyOn(window,'fetch').mockImplementation(async(path,init)=>{
    if(path==='/api/local-catalog/products'){
      reads++;const wait=pause;pause=undefined;if(wait)await wait;
      if(!lookup)throw new TypeError('offline');
      return Response.json({products:[{id:productId,name:'Print',href,personalizable:true,designFeeCents:500,currency:'EUR',
        variants:[{id:'rose',sku,name:texts('Rose'),cents:price,previousCents:null,stock,colors:['#eeeaaa'],checkTone:'dark',
          photos:['/product/print.webp'],model:null,studioFormat:'surface-print-v1',templateRevision:'2'}]}],
        entries:[{id:productId,name:'Print',href,kind:'offer',description:texts('Printed design'),material:texts('Ceramic'),
          title:texts('Print'),photo:null,thumb:null,categories:['living'],collectionId:null,collectionName:null,editionOrder:1,homeOrder:1,rating:{count:0,average:null}}],recommendations:[]});
    }
    if(path==='/api/account-cart')return Response.json({kind:'ready',ownerId:owner,namespace:ns,writeContext:ctx,guestIntent:null,version:String(version),cart:[
      ...hidden?[]:[{lineId:id,offerId:productId,offerLabel:'Print',variantId:'rose',variantLabel:'Rose',sku,href,listed:true,
        designId,designRevision:'1',templateRevision:'2',studioFormat:'surface-print-v1',designLabel:'Monogram',quantity:qty,removed:gone}],
      {lineId:archive,offerId:'archived',offerLabel:'Withdrawn cup',variantId:'old',variantLabel:'Sand',sku:'old-sand',href:null,listed:false,
        templateRevision:null,designLabel:null,quantity:'5',removed:false}]});
    if(path==='/auth/csrf')return Response.json({headerName:'X-CSRF-TOKEN',parameterName:'_csrf',token:'test'});
    if(init?.method==='POST' && String(path).startsWith('/api/account-cart/edits/')){
      const key=String(path).split('/').at(-1)!,body=JSON.parse(String(init.body)) as Change;posts.push({id:key,body});
      const headers=init.headers as Record<string,string>;
      expect(headers['X-Cart-Context']).toBe(ctx);expect(headers['X-Cart-Namespace']).toBe(ns);expect(body.lineId).toBe(id);
      if(receipts.has(key))return Response.json({state:'APPLIED',version:receipts.get(key)});
      expect(body.expectedVersion).toBe(String(version));if(refuse)return Response.json({error:'request_not_completed'},{status:422});
      if(body.action==='quantity')qty=body.quantity!;if(body.action==='remove')gone=true;
      if(body.action==='restore')gone=false;if(body.action==='dismiss')hidden=true;
      version++;receipts.set(key,String(version));if(lost){lost=false;throw new TypeError('lost acknowledgement');}
      return Response.json({state:'APPLIED',version:String(version)});
    }
    throw Error('Unexpected '+path);
  });
  function mount(){
    const store=createAppStore('en'),who={document:crypto.randomUUID(),epoch:1,request:1,revision:crypto.randomUUID()};
    store.dispatch(authenticationStarted({owner:who,phase:'CHECKING',notice:'NONE'}));
    store.dispatch(authenticationFinished({owner:who,result:{phase:'AUTHENTICATED',notice:'NONE',user:{id:owner,status:'ACTIVE'}}}));
    const session=createLocalCartSession(store,{isCurrentOwner:v=>sameOwner(v,who)},api),ui=render(<Provider store={store}><MemoryRouter initialEntries={['/preview/cart']}>
      <LocalAccountStorefront session={session}/></MemoryRouter></Provider>);
    return ()=>{ui.unmount();session.dispose();};
  }
  const row=()=>within(screen.getByRole('article',{name:/Print/})),total=()=>document.querySelector('.product-cart-subtotal strong')?.textContent;
  return {mount,row,total,id,posts,get version(){return version;},get reads(){return reads;},get quantity(){return qty;},
    change:(options:{stock?:number;qty?:string;price?:number;lookup?:boolean;refuse?:boolean;lost?:boolean;pause?:Promise<void>})=>{
      qty=options.qty??qty;stock=options.stock??stock;price=options.price??price;lookup=options.lookup??lookup;refuse=options.refuse??refuse;lost=options.lost??lost;pause=options.pause;},
  };
}
it('keeps same-visit undo for the exact design but never reopens it on a fresh cart screen',async()=>{
  const test=setup(),user=userEvent.setup();let close=test.mount();
  try{
    await waitFor(()=>expect(test.total()).toContain('72.00'));
    const old=within(screen.getByRole('article',{name:/Withdrawn cup/}));
    expect(old.getAllByTitle(t.priceUnavailable)).toHaveLength(2);expect(old.getByText('Unavailable')).toBeVisible();
    const input=test.row().getByRole('textbox');await user.clear(input);await user.type(input,'12');
    expect(test.posts).toHaveLength(0);await user.keyboard('{Enter}');await waitFor(()=>expect(test.total()).toContain('432.00'));
    expect(test.posts.map(v=>v.body)).toEqual([{lineId:test.id,expectedVersion:'7',action:'quantity',quantity:'12'}]);
    await user.click(test.row().getByRole('button',{name:/^Remove/}));await waitFor(()=>expect(test.row().getByRole('textbox')).toHaveValue('0'));
    await waitFor(()=>expect(test.row().getByRole('button',{name:/^Restore/})).toBeEnabled());
    expect(test.posts).toHaveLength(2);expect(test.total()).toContain('0.00');await user.click(test.row().getByRole('button',{name:/^Restore/}));
    await waitFor(()=>expect(test.total()).toContain('432.00'));close();close=test.mount();await waitFor(()=>expect(test.total()).toContain('432.00'));
    expect(test.row().getByRole('textbox')).toHaveValue('12');expect(test.row().getByText('Monogram')).toBeVisible();
    expect(test.row().getByRole('link',{name:'Print'})).toHaveAttribute('href','/products/custom-item?variant=rose&accountLine='+test.id);
    expect(test.posts.map(p=>p.body.action)).toEqual(['quantity','remove','restore']);
    await user.click(test.row().getByRole('button',{name:/^Remove/}));await test.row().findByRole('button',{name:/^Restore/});
    close();close=test.mount();await screen.findByRole('article',{name:/Withdrawn cup/});
    expect(screen.queryByRole('article',{name:/Print/})).not.toBeInTheDocument();
    expect(test.posts.map(p=>p.body.action)).toEqual(['quantity','remove','restore','remove']);
  }finally{close();}
});
it.each([0,2])('reduces overstocked demand from 12 to 11 without clamping it to %s',async stock=>{
  const test=setup();test.change({stock,qty:'12'});const close=test.mount();
  try{
    await waitFor(()=>expect(test.total()).toContain('432.00'));
    expect(test.row().getByRole('button',{name:t.increase+' · Rose · Monogram'})).toBeDisabled();
    await userEvent.click(test.row().getByRole('button',{name:t.decrease+' · Rose · Monogram'}));
    await waitFor(()=>expect(test.row().getByRole('textbox')).toHaveValue('11'));
    expect(test.posts.map(p=>p.body.quantity)).toEqual(['11']);
  }finally{close();}
});
it('refreshes catalog even when two stock refusals leave the cart version unchanged; never shows a false shortage or a stale price',async()=>{
  const test=setup(),user=userEvent.setup(),close=test.mount();let release!:()=>void;
  try{
    await waitFor(()=>expect(test.total()).toContain('72.00'));
    await user.click(test.row().getByRole('button',{name:/^Remove/}));
    await waitFor(()=>expect(test.row().getByRole('button',{name:/^Restore/})).toBeEnabled());
    const before=test.reads;
    test.change({stock:3,price:2000,refuse:true,pause:new Promise<void>(r=>{release=r;})});
    await user.click(test.row().getByRole('button',{name:/^Restore/}));
    await waitFor(()=>expect(test.reads).toBe(before+1));expect(test.row().queryByText(t.restoreLimited)).not.toBeInTheDocument();
    expect(test.row().queryByText('€36.00')).not.toBeInTheDocument();expect(test.row().getByRole('button',{name:/^Restore/})).toBeDisabled();
    release();await waitFor(()=>expect(test.row().getByRole('button',{name:/^Restore/})).toBeEnabled());expect(test.row().getByText('€25.00')).toBeVisible();
    test.change({stock:1});await user.click(test.row().getByRole('button',{name:/^Restore/}));await waitFor(()=>expect(test.row().getByText(t.restoreLimited)).toBeVisible());
    expect(test.reads).toBe(before+2);expect(test.version).toBe(8);expect(test.row().getByRole('button',{name:/^Restore/})).toBeDisabled();
  }finally{release?.();close();}
});
it('reconciles a lost acknowledgement without an automatic second mutation; explicit retry uses the original payload',async()=>{
  const test=setup(),close=test.mount(),user=userEvent.setup();
  try{
    await waitFor(()=>expect(test.total()).toContain('72.00'));test.change({lost:true});
    await user.click(test.row().getByRole('button',{name:/^Remove/}));
    const retry=await screen.findByRole('button',{name:'Try again'});expect(test.posts).toHaveLength(1);
    expect(test.row().getByRole('textbox')).toHaveValue('0');await user.click(retry);
    await waitFor(()=>expect(test.row().getByRole('button',{name:/^Restore/})).toBeEnabled());
    expect(test.posts[1]).toEqual(test.posts[0]);expect(test.version).toBe(8);
  }finally{close();}
});
