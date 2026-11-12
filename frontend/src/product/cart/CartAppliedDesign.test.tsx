import { StrictMode } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { readFileSync } from 'node:fs';
import { afterEach, expect, it, vi } from 'vitest';
import { authenticationFinished, authenticationStarted, createAppStore } from '../../store';
import { bindAccountCartReader } from './accountCartAdmission';
import CartAppliedDesign from './CartAppliedDesign';
vi.hoisted(()=>{vi.stubEnv('MODE','account-cart-local');});

const png='iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jmpkAAAAASUVORK5CYII=';
const font=readFileSync('public/fonts/AllertaStencil.ttf').toString('base64');
const applied={sku:'cobalt',templateRevision:'2',zone:'surface',objects:[
  {kind:'image',fit:'cover',data:png,size:84,x:240.25,y:278},
  {kind:'text',font:'resource',data:font,size:22.75,color:'#20251f',x:230.25,y:275,text:'ALICE'},
]};
function setup() {
  const store=createAppStore('en',vi.fn()),owner={document:'p',epoch:1,request:1,revision:'r'};
  store.dispatch(authenticationStarted({owner,phase:'CHECKING',notice:'NONE'}));
  store.dispatch(authenticationFinished({owner,result:{phase:'AUTHENTICATED',notice:'NONE',user:{id:'a',status:'ACTIVE'}}}));
  const isCurrentOwner=vi.fn(()=>true);
  const admission=bindAccountCartReader(store,{read:vi.fn(),recover:vi.fn()},{isCurrentOwner});
  const id=crypto.randomUUID(),fetcher=vi.spyOn(globalThis,'fetch');
  const view=render(<StrictMode><Provider store={store}><CartAppliedDesign admission={admission} lineId={id} variant="cobalt" swatch="#123456"/></Provider></StrictMode>);
  const click=()=>userEvent.click(screen.getByRole('button',{name:'View applied design'}));
  return {id,view,fetcher,click,admission,isCurrentOwner,store};
}
afterEach(()=>{vi.unstubAllGlobals();vi.restoreAllMocks();});
it('renders ordered account layers from the private GET with original image bytes and a font parsed from the exact bytes, without touching guest state',async()=>{
  const {id,view,click,fetcher,admission,store}=setup();
  const before=store.getState().productDemo,add=vi.fn(),remove=vi.fn(),sources:ArrayBuffer[]=[];
  Object.defineProperty(document,'fonts',{configurable:true,value:{add,delete:remove}});
  vi.stubGlobal('FontFace',class {constructor(_family:string,src:ArrayBuffer){sources.push(src);} async load(){return this;}});
  vi.stubGlobal('createImageBitmap',vi.fn(async()=>({width:1,height:1,close:vi.fn()})));
  vi.spyOn(HTMLCanvasElement.prototype,'getContext').mockReturnValue({drawImage:vi.fn()} as unknown as CanvasRenderingContext2D);
  vi.spyOn(HTMLCanvasElement.prototype,'toDataURL').mockReturnValue('intentionally-different-reencoding');
  fetcher.mockResolvedValueOnce(Response.json(applied));
  expect(fetcher).not.toHaveBeenCalled();await click();
  const preview=await screen.findByRole('img',{name:'View applied design'});
  expect([...preview.querySelectorAll('[data-object-kind]')].map(el=>el.getAttribute('data-object-kind'))).toEqual(['image','text']);
  expect(preview.querySelector('image')?.getAttribute('href')).toBe('data:image/png;base64,'+png);
  expect(preview.querySelector('tspan')).toHaveTextContent('ALICE');
  expect(preview.querySelector('tspan')).toHaveAttribute('x','230.25');
  expect(preview.querySelector('text')?.getAttribute('font-family')).toContain('Creastrix Custom');
  expect(Buffer.from(sources[0]).toString('base64')).toBe(font);
  expect(preview.querySelector('path[fill="#123456"]')).not.toBeNull();
  expect(fetcher).toHaveBeenCalledExactlyOnceWith('/api/account-cart/lines/'+id+'/design',expect.objectContaining({
    method:'GET',credentials:'same-origin',cache:'no-store',redirect:'error',
  }));
  expect(store.getState().productDemo).toBe(before);
  view.unmount();expect(remove).toHaveBeenCalledExactlyOnceWith(add.mock.calls[0][0]);admission.dispose();
});
it.each(['wrong variant','bad revision','unknown layer','foreign','ownership changed'])('does not render a partial or foreign preview: %s',async fault=>{
  const {click,fetcher,admission,view,isCurrentOwner}=setup();
  fetcher.mockImplementationOnce(async()=>{
    if(fault==='ownership changed') isCurrentOwner.mockReturnValue(false);
    return fault==='foreign' ? new Response(null,{status:404}) : Response.json({...applied,
      ...(fault==='wrong variant'?{sku:'chalk'}:fault==='bad revision'?{templateRevision:'1'}:fault==='unknown layer'?{objects:[{kind:'unknown'}]}:{})});
  });
  await click();
  if(fault!=='ownership changed') expect(await screen.findByRole('status')).toHaveTextContent('Unavailable');
  expect(screen.queryByRole('img')).not.toBeInTheDocument();expect(fetcher).toHaveBeenCalledOnce();
  view.unmount();admission.dispose();
});

it.each([false,true])('cold builtin preview uses the pinned file; changed bytes are unavailable (tampered: %s)',async tampered=>{
  const {view,click,fetcher,admission}=setup(),sources:ArrayBuffer[]=[];
  Object.defineProperty(document,'fonts',{configurable:true,value:{add:vi.fn(),delete:vi.fn()}});
  vi.stubGlobal('FontFace',class {constructor(_family:string,src:ArrayBuffer){sources.push(src);}async load(){return this;}});
  const file=new Uint8Array(readFileSync('public/fonts/Nunito.ttf'));if(tampered) file[file.length-1]^=1;
  fetcher.mockResolvedValueOnce(Response.json({...applied,objects:[
    {kind:'text',text:'Mira',font:'rounded',color:'#20251f',size:24,x:240,y:278},
  ]})).mockResolvedValueOnce(new Response(file));
  await click();
  if(tampered) {
    expect(await screen.findByRole('status')).toHaveTextContent('Unavailable');
    expect(screen.queryByRole('img')).not.toBeInTheDocument();expect(sources).toHaveLength(0);
  } else {
    expect((await screen.findByRole('img',{name:'View applied design'})).querySelector('text')).toHaveAttribute('font-family','"Creastrix Nunito", sans-serif');
    expect(Buffer.from(sources[0]).equals(file)).toBe(true);
  }
  view.unmount();admission.dispose();
});
