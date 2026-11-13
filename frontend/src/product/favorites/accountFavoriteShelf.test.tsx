import {StrictMode} from 'react';
import {Provider} from 'react-redux';
import {MemoryRouter} from 'react-router';
import {render,screen,within,waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {beforeAll,expect,it,vi} from 'vitest';
import {AuthenticationContext,type Authentication} from '../../authentication';
import {authenticationStarted,authenticationFinished,createAppStore} from '../../store';
import {sameOwner} from '../../authBoundary';
import App from '../../App';
import {createLocalCartSession} from '../cart/localCartSession';
import {catalogReady} from '../catalog/localCatalog';
import {productCopy} from '../copy';

vi.hoisted(()=>vi.stubEnv('MODE','shared-cart-local'));
vi.mock('@google/model-viewer',()=>({}));
// Load the app's lazy route module without loading a catalog or mounting any
// UI. Cold-entry tests exercise network/data readiness, not Vite transform time.
beforeAll(async()=>{await import('../cart/LocalAccountStorefront');});

it('opens a cold saved-products route with exact SKU links; an unpublished SKU can only be removed, and the owner delta survives a fresh store',async()=>{
  const a=crypto.randomUUID(),b=crypto.randomUUID();let actor:string=a;
  const namespace=crypto.randomUUID(),writeContext=crypto.randomUUID(),rows=new Set(['chalk','cobalt','old-teal']);
  const i18n=(s:string)=>({en:s,de:s,ru:s,it:s,pl:s,es:s,fr:s});
  const product={id:'loop',name:'Loop',href:'/products/loop',currency:'EUR',personalizable:true,designFeeCents:500,
    variants:['cobalt','chalk','coral','cobalt-chalk'].map(id=>({id,sku:id,name:i18n(id),cents:2400,previousCents:null,stock:2,
      colors:['#4488aa'],checkTone:'dark',photos:[],model:null,studioFormat:'surface-print-v1',templateRevision:'2'}))};
  const entry={id:'loop',name:'Loop',href:product.href,kind:'offer',description:i18n('A pot'),material:i18n('Clay'),
    title:i18n('Planter'),photo:null,thumb:null,categories:['living'],collectionId:null,collectionName:null,editionOrder:1,homeOrder:1,rating:{count:0,average:null}};
  const catalog={products:[product],entries:[entry],recommendations:[]};
  let revision=0;const written:string[]=[];
  const snapshot=()=>Response.json({ownerId:actor,version:String(revision),skus:actor===a?[...rows].sort():[]});
  const fetcher=vi.spyOn(window,'fetch').mockImplementation(async(input,init)=>{
    const url=String(input);
    if(url==='/api/local-catalog/products')return Response.json(catalog);
    if(url==='/api/account-cart')return Response.json({kind:'ready',ownerId:actor,namespace,writeContext,cart:[],version:'0',guestIntent:null});
    if(url==='/api/account-cart/favorites')return snapshot();
    if(url==='/auth/csrf')return Response.json({headerName:'X-CSRF-TOKEN',parameterName:'_csrf',token:'csrf'});
    if(url.startsWith('/api/account-cart/favorites/commands/')){
      expect(init?.method).toBe('POST');expect(new Headers(init?.headers).get('X-Cart-Context')).toBe(writeContext);
      const {sku,saved}=JSON.parse(String(init?.body));expect(saved).toBe(false);rows.delete(sku);revision++;
      written.push(sku);return snapshot();
    }
    throw Error('Unexpected URL '+url);
  });
  const user=userEvent.setup();
  function open(){
    const store=createAppStore('en'),owner={document:crypto.randomUUID(),epoch:1,request:1,revision:crypto.randomUUID()};
    store.dispatch(authenticationStarted({owner,phase:'CHECKING',notice:'NONE'}));
    store.dispatch(authenticationFinished({owner,result:{phase:'AUTHENTICATED',notice:'NONE',user:{id:actor,status:'ACTIVE'}}}));
    const session=createLocalCartSession(store,{isCurrentOwner:who=>sameOwner(who,owner)},fetcher);
    const view=render(<StrictMode><Provider store={store}><MemoryRouter initialEntries={['/preview/saved']}><App cartSession={session}/></MemoryRouter></Provider></StrictMode>);
    return ()=>{view.unmount();session.dispose();};
  }
  // Old origin-wide keys can legally belong to anybody. A fresh account must never display them.
  localStorage.setItem('creastrix.product.loop.favorites.v1.coral','1');expect(catalogReady()).toBe(false);
  let close=open();
  try{
    expect(await screen.findByRole('link',{name:'Loop · chalk'})).toHaveAttribute('href','/products/loop?variant=chalk');
    expect(screen.getByRole('link',{name:'Loop · cobalt'})).toHaveAttribute('href','/products/loop?variant=cobalt');
    const retired=screen.getByText('old-teal').closest('article')!;expect(within(retired).queryByRole('link')).toBeNull();
    await user.click(within(retired).getByRole('button'));await waitFor(()=>expect(screen.queryByText('old-teal')).toBeNull());
    await user.click(screen.getByRole('button',{name:productCopy.en.unfavorite+' · Loop · cobalt'}));
    await waitFor(()=>expect(screen.queryByRole('link',{name:'Loop · cobalt'})).toBeNull());close();close=open();
    expect(await screen.findByRole('link',{name:'Loop · chalk'})).toBeVisible();expect(screen.queryByRole('link',{name:'Loop · cobalt'})).toBeNull();
    close();actor=b;close=open();expect(await screen.findByText(productCopy.en.emptySaved)).toBeVisible();expect(screen.queryByText('Loop · chalk')).toBeNull();
    expect(localStorage.getItem('creastrix.product.loop.favorites.v1.coral')).toBe('1');expect(written).toEqual(['old-teal','cobalt']);
  }finally{close();fetcher.mockRestore();}
});

it.each(['cart','favorites'] as const)('shows an actionable local sign-in when the %s read reports stale access',async staleRead=>{
  const uid=crypto.randomUUID(),owner={document:'doc',epoch:1,request:1,revision:'rev'},store=createAppStore('en');
  const namespace=crypto.randomUUID(),writeContext=crypto.randomUUID();
  store.dispatch(authenticationStarted({owner,phase:'CHECKING',notice:'NONE'}));
  store.dispatch(authenticationFinished({owner,result:{phase:'AUTHENTICATED',notice:'NONE',user:{id:uid,status:'ACTIVE'}}}));
  const recover=vi.fn(),runtime={isCurrentOwner:()=>true,recover,reveal:vi.fn()} as unknown as Authentication;
  const http=vi.spyOn(window,'fetch').mockImplementation(async(url)=>{
    if(url==='/api/local-catalog/products')return Response.json({products:[],entries:[],recommendations:[]});
    if(staleRead==='favorites'&&url==='/api/account-cart')return Response.json({kind:'ready',ownerId:uid,namespace,writeContext,cart:[],version:'0',guestIntent:null});
    if(url===(staleRead==='cart'?'/api/account-cart':'/api/account-cart/favorites'))return Response.json({error:'request_not_completed'},{status:409});
    throw Error('Unexpected URL '+url);
  });
  const session=createLocalCartSession(store,runtime,http);
  const view=render(<Provider store={store}><MemoryRouter initialEntries={['/preview/saved']}><AuthenticationContext.Provider value={runtime}>
    <App cartSession={session}/></AuthenticationContext.Provider></MemoryRouter></Provider>);
  try{
    const signIn=await screen.findByRole('button',{name:'Sign in again'});
    expect(screen.queryByRole('button',{name:'Try again'})).toBeNull();expect(recover).not.toHaveBeenCalled();
    expect(signIn.closest('main')).not.toBeNull();
    await userEvent.click(signIn);expect(recover).toHaveBeenCalledTimes(1);
    expect(http.mock.calls.map(([url])=>url)).toContain(staleRead==='cart'?'/api/account-cart':'/api/account-cart/favorites');
    expect(http.mock.calls.every(([,init])=>init?.method==='GET')).toBe(true);
  }finally{view.unmount();session.dispose();http.mockRestore();}
});
