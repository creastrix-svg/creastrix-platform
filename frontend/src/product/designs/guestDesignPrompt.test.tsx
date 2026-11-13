import {useState} from 'react';
import {Provider} from 'react-redux';
import {MemoryRouter,Route,Routes,useLocation} from 'react-router';
import {act,render,screen,within} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {createAppStore} from '../../store';
import ProductExperience from '../ProductExperience';
import {catalogReady} from '../catalog/localCatalog';
import type {AppliedLoopDesign} from '../cart/appliedLoopDesign';
import AccountDesignsRoute from './AccountDesignsRoute';
import GuestDesignSave from './GuestDesignSave';
import {MissingLibraryDesign} from './LibraryStudio';
import {holdGuestDesign} from './guestDesignHandoff';

vi.hoisted(()=>vi.stubEnv('MODE','shared-cart-local'));
vi.mock('@google/model-viewer',()=>({}));
vi.mock('./guestDesignHandoff',async original=>({
  ...await original<typeof import('./guestDesignHandoff')>(),holdGuestDesign:vi.fn(),
}));
const draftId='12a245d6-7766-495d-be88-5acc26278ad6';
const studio='/products/loop/personalize?variant=cobalt';
const draft=(text:string):AppliedLoopDesign=>({sku:'cobalt',templateRevision:'2',zone:'surface',
  objects:[{kind:'text',text,font:'rounded',color:'#20251f',x:240,y:278,size:22}]});
const snapshot=vi.fn(draft);
beforeEach(()=>{
  snapshot.mockClear();
  vi.mocked(holdGuestDesign).mockReset().mockResolvedValue(draftId);
  vi.stubGlobal('fetch',vi.fn().mockRejectedValue(new Error('Unexpected network access')));
});
afterEach(()=>vi.unstubAllGlobals());

function GuestEditor(){
  const [text,setText]=useState('My drawing');
  return <><textarea aria-label="Draft lettering" value={text} onChange={event=>setText(event.target.value)}/>
    <GuestDesignSave sku="cobalt" enabled snapshot={()=>snapshot(text)}/></>;
}
function LoginLocation(){
  const {pathname,search}=useLocation();
  return <output aria-label="Login route">{pathname}{search}</output>;
}
function show(route=studio,invalidEditor=false){
  render(<Provider store={createAppStore('en')}><MemoryRouter initialEntries={[route]}><Routes>
    <Route path="/products/loop/personalize" element={invalidEditor?<MissingLibraryDesign/>:<GuestEditor/>}/>
    <Route path="/account/designs" element={<AccountDesignsRoute/>}/>
    <Route path="/login" element={<LoginLocation/>}/>
  </Routes></MemoryRouter></Provider>);
  return userEvent.setup();
}

it.each(['cancel','escape'] as const)('lets a guest dismiss the save invitation with %s without taking the draft out of the studio',async how=>{
  const user=show(),editor=screen.getByRole('textbox',{name:'Draft lettering'});
  await user.type(editor,' with lettering');
  const save=screen.getByRole('button',{name:'Save design'});
  await user.click(save);
  const dialog=within(screen.getByRole('dialog'));
  expect(dialog.getByRole('button',{name:'Sign in'})).toBeEnabled();
  const register=dialog.getByRole('button',{name:/create (an )?account|register|sign up/i});
  expect(register).toBeDisabled();
  expect(register).toHaveAccessibleDescription(/not available|not supported|not enabled/i);
  if(how==='escape')await user.keyboard('{Escape}');
  else await user.click(dialog.getByRole('button',{name:/cancel|close/i}));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(save).toHaveFocus();
  expect(editor).toHaveValue('My drawing with lettering');
  expect(snapshot).not.toHaveBeenCalled();
  expect(holdGuestDesign).not.toHaveBeenCalled();
  expect(fetch).not.toHaveBeenCalled();
});

it('waits for an explicit sign-in and the completed local handoff before navigating, without leaking the design into the URL',async()=>{
  let release!:(id:string)=>void;
  vi.mocked(holdGuestDesign).mockImplementationOnce(()=>new Promise(resolve=>{release=resolve;}));
  const user=show();
  await user.click(screen.getByRole('button',{name:'Save design'}));
  expect(holdGuestDesign).not.toHaveBeenCalled();
  await user.click(within(screen.getByRole('dialog')).getByRole('button',{name:'Sign in'}));
  expect(holdGuestDesign).toHaveBeenCalledExactlyOnceWith(draft('My drawing'));
  expect(screen.queryByLabelText('Login route')).not.toBeInTheDocument();
  release(draftId);
  expect((await screen.findByLabelText('Login route')).textContent).toBe('/login?design='+draftId);
  expect(fetch).not.toHaveBeenCalled();
});

it('does not leave the editor when the draft cannot be retained for sign-in',async()=>{
  vi.mocked(holdGuestDesign).mockRejectedValueOnce(new Error('Local storage unavailable'));
  const user=show();
  await user.click(screen.getByRole('button',{name:'Save design'}));
  await user.click(within(screen.getByRole('dialog')).getByRole('button',{name:'Sign in'}));
  expect(await screen.findByText('We could not complete this action.')).toBeVisible();
  expect(screen.getByRole('textbox',{name:'Draft lettering',hidden:true})).toHaveValue('My drawing');
  expect(screen.queryByLabelText('Login route')).not.toBeInTheDocument();
  expect(fetch).not.toHaveBeenCalled();
});

it.each(['cancel','escape'] as const)('stays in the studio after %s even if the local handoff is already pending',async how=>{
  let release!:(id:string)=>void;
  vi.mocked(holdGuestDesign).mockImplementationOnce(()=>new Promise(resolve=>{release=resolve;}));
  const user=show();
  const save=screen.getByRole('button',{name:'Save design'});
  await user.click(save);
  const dialog=within(screen.getByRole('dialog')),signIn=dialog.getByRole('button',{name:'Sign in'});
  await user.click(signIn);
  await user.keyboard('{Enter}');
  expect(holdGuestDesign).toHaveBeenCalledTimes(1);
  if(how==='escape')await user.keyboard('{Escape}');
  else await user.click(dialog.getByRole('button',{name:'Cancel'}));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  await act(async()=>{release(draftId);});
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(screen.queryByLabelText('Login route')).not.toBeInTheDocument();
  expect(screen.getByRole('textbox',{name:'Draft lettering'})).toHaveValue('My drawing');
  expect(save).toBeEnabled();
});

it.each(['/account/designs','/products/loop/personalize?savedDesign=unavailable'])(
  'a guest opening %s directly sees no My designs navigation or private data',async route=>{
    show(route,route!== '/account/designs');
    if(route==='/account/designs')expect((await screen.findByLabelText('Login route')).textContent).toBe('/login');
    else expect(screen.getByRole('link',{name:'Sign in'})).toHaveAttribute('href','/login');
    expect(screen.queryByRole('heading',{name:/my designs/i})).not.toBeInTheDocument();
    expect(screen.queryByRole('link',{name:/my designs/i})).not.toBeInTheDocument();
    expect(screen.queryByRole('button',{name:/my designs/i})).not.toBeInTheDocument();
    expect(document.querySelector('[data-private-account]')).toBeNull();
    expect(holdGuestDesign).not.toHaveBeenCalled();
    expect(fetch).not.toHaveBeenCalled();
  });

it('redirects a signed-out cold deep link through the real storefront without loading the catalog or private library',async()=>{
  expect(catalogReady()).toBe(false);
  render(<Provider store={createAppStore('en')}><MemoryRouter initialEntries={['/account/designs']}><Routes>
    <Route path="/login" element={<LoginLocation/>}/>
    <Route path="*" element={<ProductExperience/>}/>
  </Routes></MemoryRouter></Provider>);
  expect((await screen.findByLabelText('Login route')).textContent).toBe('/login');
  expect(catalogReady()).toBe(false);
  expect(document.querySelector('[data-private-account]')).toBeNull();
  expect(fetch).not.toHaveBeenCalled();
});
