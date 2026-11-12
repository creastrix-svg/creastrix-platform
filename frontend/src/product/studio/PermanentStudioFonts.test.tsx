import { useReducer } from 'react';
import { MemoryRouter } from 'react-router';
import { Provider } from 'react-redux';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeAll, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { createAppStore } from '../../store';
import { loadLocalCatalog } from '../catalog/localCatalog';
import { designFontHint } from '../cart/accountProductLabels';
import { readAppliedLoopDesign } from '../cart/appliedLoopDesign';
import { defaultLettering } from './design/personalization';
import { compositionReducer, initialStudioComposition } from './design/studioComposition';
import { createStudioFontLibrary } from './design/studioFonts';
import PersonalizationView from './PersonalizationView';
import { PersonalizationSession } from './session/personalizationSession';
import { StudioCreativeProvider } from './session/studioCreativeSession';
import { personalizationCopy } from './personalizationCopy';
import { studioExtensionCopy } from './studioExtensionCopy';

vi.hoisted(()=>{vi.stubEnv('MODE','account-cart-local');});
const bytes=(name:string)=>new Uint8Array(readFileSync('public/fonts/'+name+'.ttf'));
const p=personalizationCopy.en, e=studioExtensionCopy.en;
beforeAll(async()=>{
  const variants=['cobalt','coral','chalk','cobalt-chalk'].map(id=>({id,sku:id,label:id,cents:3000,stock:5,swatch:'#335588'}));
  const http=vi.spyOn(window,'fetch').mockResolvedValueOnce(Response.json([{id:'loop',label:'Loop',currency:'EUR',
    studioFormat:'loop-studio-v1',studioFeeCents:500,templateRevision:'2',variants}]));
  try { await loadLocalCatalog(); } finally { http.mockRestore(); }
});
afterEach(()=>{vi.unstubAllGlobals();vi.restoreAllMocks();});
it.each([['rounded','Nunito'],['editorial','CormorantGaramond'],['handwritten','Caveat']] as const)(
  'passes only the verified %s bytes to a fresh FontFace and rejects same-length replacements',async(id,name)=>{
    const exact=bytes(name),wrong=exact.slice();wrong[wrong.length-1]^=1;
    const seen:ArrayBuffer[]=[], add=vi.fn();
    Object.defineProperty(document,'fonts',{configurable:true,value:{add,delete:vi.fn()}});
    vi.stubGlobal('FontFace',class {constructor(_family:string,source:ArrayBuffer){seen.push(source);} async load(){return this;}});
    const http=vi.spyOn(window,'fetch').mockResolvedValueOnce(new Response(wrong)).mockResolvedValueOnce(new Response(exact));
    const lib=createStudioFontLibrary();
    try {
      await expect(lib.loadBuiltin(id)).rejects.toThrow();
      expect(seen).toHaveLength(0);
      expect((await lib.loadBuiltin(id)).id).toBe(id);
      expect(Buffer.from(seen[0]).equals(exact)).toBe(true); expect(add).toHaveBeenCalledOnce();
      expect(http).toHaveBeenCalledWith(expect.stringMatching('/fonts/'+name+'\\.ttf\\?revision=[0-9a-f]{64}$'),expect.objectContaining({credentials:'omit',redirect:'error'}));
    } finally { lib.dispose(); }
  });
it.each(['sans','serif','mono'])('does not substitute a legacy applied OS font %s',font=>{
  expect(()=>readAppliedLoopDesign({sku:'cobalt',templateRevision:'2',zone:'surface',objects:[
    {kind:'text',...defaultLettering('cobalt'),font,text:'Legacy'},
  ]})).toThrow('GUEST_CART_UNSUPPORTED');
});
it('waits for its default face before applying and rejects WOFF2 in the picker without changing the design',async()=>{
  let finish!:(response:Response)=>void;
  const http=vi.spyOn(window,'fetch').mockReturnValueOnce(new Promise<Response>(done=>{finish=done;}));
  Object.defineProperty(document,'fonts',{configurable:true,value:{add:vi.fn(),delete:vi.fn()}});
  vi.stubGlobal('FontFace',class {async load(){return this;}});
  vi.spyOn(HTMLCanvasElement.prototype,'getContext').mockReturnValue({measureText:()=>({width:30})} as unknown as CanvasRenderingContext2D);
  function Session() {
    const [state,dispatch]=useReducer(compositionReducer,undefined,initialStudioComposition);
    return <PersonalizationSession.Provider value={{state,dispatch}}><StudioCreativeProvider><PersonalizationView/></StudioCreativeProvider></PersonalizationSession.Provider>;
  }
  const page=render(<Provider store={createAppStore('en')}><MemoryRouter initialEntries={['/products/loop/personalize?variant=cobalt']}><Session/></MemoryRouter></Provider>);
  fireEvent.change(screen.getByRole('textbox',{name:p.textLabel}),{target:{value:'TEST'}});
  const apply=screen.getByRole('button',{name:p.apply}),input=screen.getByLabelText(e.uploadFont,{selector:'input'});
  expect(defaultLettering('cobalt').font).toBe('rounded');
  expect(screen.queryByRole('button',{name:'Aa '+p.sans})).toBeNull();
  expect(input).toHaveAttribute('accept','.ttf,.otf');expect(apply).toBeDisabled();
  await act(async()=>{ finish(new Response(bytes('Nunito'))); });
  await waitFor(()=>expect(apply).toBeEnabled());
  fireEvent.change(input,{target:{files:[new File([new Uint8Array(100)],'wrong.woff2')]}});
  await waitFor(()=>expect(screen.getByText(designFontHint.en)).toBeVisible());
  expect(screen.getByRole('button',{name:'Aa '+e.fontRounded})).toHaveAttribute('aria-pressed','true');
  expect(http).toHaveBeenCalledOnce(); page.unmount();
});
