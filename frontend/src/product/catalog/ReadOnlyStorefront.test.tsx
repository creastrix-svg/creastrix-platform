import {StrictMode} from 'react';
import {Provider} from 'react-redux';
import {MemoryRouter} from 'react-router';
import ReadOnlyStorefront from './ReadOnlyStorefront';
import {act,render,screen,waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {expect,it,vi} from 'vitest';
import {AuthenticationContext,type Authentication} from '../../authentication';
import {authenticationStarted,authenticationFinished,createAppStore} from '../../store';
import {sameOwner,type Owner} from '../../authBoundary';
import LocalAccountStorefront from '../cart/LocalAccountStorefront';
import {createLocalCartSession} from '../cart/localCartSession';
import {productCopy} from '../copy';

vi.hoisted(()=>vi.stubEnv('MODE','shared-cart-local'));
vi.mock('@google/model-viewer',()=>({}));
vi.mock('../cart/guestCartBatch',async original=>({...await original<typeof import('../cart/guestCartBatch')>(),hasClaimedGuestWork:async()=>false}));
const tr=(value:string)=>({en:value,de:value,ru:value,pl:value,it:value,fr:value,es:value});
const offer={id:'kiln-cup',name:'Kiln cup',href:'/products/kiln-cup',currency:'EUR',personalizable:false,
  designFeeCents:0,variants:[{id:'blue',sku:'blue-cup',name:tr('Blue'),cents:1800,previousCents:null,stock:2,colors:['#112233'],
    photos:[],model:null,checkTone:'dark'}]};
const reviewOffer={...offer,id:'reviews',href:'/products/reviews',name:'Reviews lamp',variants:[offer.variants[0],{...offer.variants[0],id:'chalk',name:tr('Chalk'),cents:1500}].map(v=>({...v,sku:'lamp-'+v.id}))};
const feed={products:[offer,reviewOffer],entries:[offer,reviewOffer].map(o=>({id:o.id,name:o.name,href:o.href,kind:'offer',description:tr('A cup made of clay'),material:tr('Clay'),title:tr('Cup'),
  categories:['living'],collectionId:null,collectionName:null,photo:null,thumb:null,editionOrder:1,homeOrder:null,rating:{count:0,average:null}})),recommendations:[]};

it('keeps private routes sealed, shows neutral loading and public browsing, then restores the verified route after Retry',async()=>{
  const store=createAppStore('en'),who=crypto.randomUUID();
  let owner:Owner={document:crypto.randomUUID(),epoch:1,request:1,revision:crypto.randomUUID()},allowed=false;
  const verify=vi.fn(async()=>{owner={...owner,request:2};store.dispatch(authenticationStarted({owner,phase:'CHECKING',notice:'NONE'}));});
  const loginRuntime={isCurrentOwner:(other:Owner)=>sameOwner(other,owner),recheck:verify} satisfies Pick<Authentication,'isCurrentOwner'|'recheck'>;
  const http=vi.spyOn(window,'fetch').mockImplementation(async(url,init)=>{
    if(url==='/api/local-catalog/products'&&init?.method==='GET'&&init.credentials==='omit')return Response.json(feed);
    if(allowed&&url==='/api/account-cart'&&init?.method==='GET')return Response.json({kind:'ready',ownerId:who,
      namespace:crypto.randomUUID(),writeContext:crypto.randomUUID(),guestIntent:null,version:'1',cart:[]});
    throw Error('Unexpected request: '+url);
  });
  store.dispatch(authenticationStarted({owner,phase:'CHECKING',notice:'NONE'}));
  const session=createLocalCartSession(store,loginRuntime,http),choose=userEvent.setup();
  const app=render(<StrictMode><Provider store={store}><MemoryRouter initialEntries={['/preview/cart']}>
    <AuthenticationContext.Provider value={loginRuntime as unknown as Authentication}><LocalAccountStorefront session={session}/></AuthenticationContext.Provider></MemoryRouter></Provider></StrictMode>);
  try{
    expect(screen.getByRole('status')).toHaveTextContent('Loading');expect(http).not.toHaveBeenCalled();
    act(()=>store.dispatch(authenticationFinished({owner,result:{phase:'UNAVAILABLE',notice:'REQUEST_UNAVAILABLE',user:null}})));
    const publicLink=await screen.findByRole('link',{name:'Kiln cup'});expect(publicLink).toHaveAttribute('href',offer.href);
    expect(screen.queryByText(/sign in|authentication is currently unavailable/i)).not.toBeInTheDocument();
    expect(screen.queryByRole('link',{name:/cart|saved products|my designs/i})).not.toBeInTheDocument();
    expect(document.querySelector('[data-private-account]')).toBeNull();
    await choose.click(publicLink);
    expect(await screen.findByRole('heading',{name:'Kiln cup'})).toBeVisible();
    expect(screen.queryByText('Shopping tools are temporarily unavailable.')).toBeNull();
    expect(screen.getByText('A cup made of clay')).toBeVisible();
    expect(screen.queryByRole('button',{name:productCopy.en.add})).toBeNull();
    await choose.click(screen.getByRole('link',{name:productCopy.en.browseCollection}));
    expect(await screen.findByText('Kiln cup')).toBeVisible();
    expect(screen.queryByText('Shopping tools are temporarily unavailable.')).toBeNull();expect(verify).not.toHaveBeenCalled();
    expect(http.mock.calls.every(([u,i])=>u==='/api/local-catalog/products'&&i?.method==='GET'&&i.credentials==='omit')).toBe(true);
    await choose.click(screen.getByRole('button',{name:'Try again'}));
    expect(verify).toHaveBeenCalledTimes(1);expect(screen.getByRole('status')).toHaveTextContent('Loading');
    allowed=true;act(()=>store.dispatch(authenticationFinished({owner,result:{phase:'AUTHENTICATED',notice:'NONE',user:{id:who,status:'ACTIVE'}}})));
    await waitFor(()=>expect(screen.getByRole('link',{name:productCopy.en.cart})).toBeVisible());
    expect(screen.queryByText('Shopping tools are temporarily unavailable.')).toBeNull();
  }finally{app.unmount();session.dispose();http.mockRestore();}
});

it.each([
  ['/products/reviews?variant=chalk&from=search','Chalk',true],
  ['/products/reviews/reviews/new?rating=5&variant=chalk','Chalk',true],
  ['/products/reviews?variant=absent',null,false],
  ['/products/reviews?variant=blue&variant=chalk',null,false],
] as const)('matches the exact published item before review suffixes and never substitutes another selected finish: %s',async(path,color,hasPrice)=>{
  const http=vi.spyOn(window,'fetch').mockResolvedValue(Response.json(feed));
  const ui=render(<Provider store={createAppStore('en')}><MemoryRouter initialEntries={[path]}><ReadOnlyStorefront phase="UNAVAILABLE"/></MemoryRouter></Provider>);
  try{
    expect(await screen.findByRole('heading',{name:'Reviews lamp'})).toBeInTheDocument();
    if(color)expect(screen.getByText(new RegExp(color+' ·'))).toBeVisible();
    const price=ui.container.querySelector('.public-product strong');
    if(hasPrice)expect(price).toHaveTextContent('15');else expect(price).toBeNull();
  }finally{ui.unmount();http.mockRestore();}
});

it.each(['DENIED','RECOVERY_REQUIRED'] as const)('offers the account recovery route only after a confirmed %s',async phase=>{
  const store=createAppStore('en'),owner={document:crypto.randomUUID(),epoch:1,request:1,revision:crypto.randomUUID()};
  const http=vi.spyOn(window,'fetch').mockImplementation(async()=>Response.json(feed));
  store.dispatch(authenticationStarted({owner,phase:'CHECKING',notice:'NONE'}));
  const session=createLocalCartSession(store,{isCurrentOwner:other=>sameOwner(owner,other)},http);
  const ui=render(<Provider store={store}><MemoryRouter initialEntries={['/preview/saved']}><LocalAccountStorefront session={session}/></MemoryRouter></Provider>);
  try{
    expect(screen.queryByRole('link',{name:'Recover account access'})).toBeNull();
    act(()=>store.dispatch(authenticationFinished({owner,result:{phase,notice:phase==='DENIED'?'SECURITY_DENIED':'PEER_CHANGED',user:null}})));
    expect(await screen.findByRole('link',{name:'Recover account access'})).toHaveAttribute('href','/login');
    await screen.findByRole('link',{name:'Kiln cup'});
    expect(screen.queryByRole('button',{name:'Try again'})).toBeNull();
    expect(ui.container.querySelector('[data-private-account]')).toBeNull();
  }finally{ui.unmount();session.dispose();http.mockRestore();}
});
