import { beforeAll, expect, it, vi } from 'vitest';
import { createAppStore } from '../../store';
import { loadLocalCatalog } from '../catalog/localCatalog';
import { cartAdded, cartHydrated, cartLinePrice, cartTotal } from '../demoStore';
import { defaultLettering } from '../studio/design/personalization';
import { saveDesign } from '../studio/session/designSnapshot';
import { initialStudioComposition } from '../studio/design/studioComposition';
import { restoreStudio, serializeStudio } from '../studio/session/studioStorage';
import { createStudioFontLibrary } from '../studio/design/studioFonts';

vi.hoisted(()=>{vi.stubEnv('MODE','account-cart-local');});

beforeAll(async()=>{
  const variants=['cobalt','coral','chalk','cobalt-chalk'].map(id=>({id,sku:id,label:id,cents:3020,stock:6,swatch:'#10507d'}));
  const fetcher=vi.spyOn(window,'fetch').mockResolvedValueOnce(Response.json([{id:'loop',label:'Loop',currency:'EUR',
    studioFormat:'loop-studio-v1',studioFeeCents:375,templateRevision:'2',variants}]));
  try { await loadLocalCatalog(); } finally { fetcher.mockRestore(); }
});
it('pins the applied catalog revision when buying and counts its server fee exactly once per designed unit',()=>{
  const store=createAppStore('en'), design=saveDesign({selectedId:null,objects:[{id:'studio-object-1',kind:'text',value:{...defaultLettering('cobalt'),text:'Mine'}}]});
  store.dispatch(cartAdded({variant:'cobalt',quantity:2,design}));
  store.dispatch(cartAdded({variant:'cobalt',quantity:1}));
  const demo=store.getState().productDemo;
  expect(demo.cart).toEqual([{variant:'cobalt',quantity:2,id:'design-1',design,templateRevision:'2'},{variant:'cobalt',quantity:1}]);
  expect(cartLinePrice(demo.cart[0])).toEqual({baseCents:3020,feeCents:375,unitCents:3395});
  expect(cartTotal(demo)).toBe(9810);
  const saved=JSON.parse(serializeStudio(initialStudioComposition(),{drafts:{},applied:{},customFonts:[]},demo));
  expect(saved.lines[0]).toHaveProperty('templateRevision','2');expect(saved.lines[1]).not.toHaveProperty('templateRevision');
});

it('preserves original normalized PNG file identity after reload and coalesces a second addition',async()=>{
  const src='data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jmpkAAAAASUVORK5CYII=';
  const layer={kind:'image' as const,id:'studio-object-7',fit:'cover' as const,value:{image:{id:1,canvas:document.createElement('canvas'),aspect:1,src},size:85,x:240,y:278}};
  const drawing={objects:[layer],selectedId:layer.id}, initial=initialStudioComposition(), store=createAppStore('en');
  store.dispatch(cartAdded({variant:'cobalt',quantity:1,design:saveDesign(drawing)}));
  const raw=serializeStudio({...initial,applied:{cobalt:drawing}},{drafts:{},applied:{},customFonts:[]},store.getState().productDemo);
  vi.stubGlobal('createImageBitmap',vi.fn(async()=>({width:1,height:1,close:vi.fn()})));
  vi.spyOn(HTMLCanvasElement.prototype,'getContext').mockReturnValue({drawImage:vi.fn()} as unknown as CanvasRenderingContext2D);
  const reencode=vi.spyOn(HTMLCanvasElement.prototype,'toDataURL').mockReturnValue('different-encoder-result');
  const fonts=createStudioFontLibrary();
  try {
    const saved=await restoreStudio(raw,fonts);
    const next=createAppStore('en');next.dispatch(cartHydrated({lines:saved.lines,serial:saved.serial}));
    next.dispatch(cartAdded({variant:'cobalt',quantity:1,design:saveDesign(saved.composition.applied.cobalt)}));
    expect(reencode).toHaveBeenCalledOnce();
    expect(next.getState().productDemo.cart).toMatchObject([{id:'design-1',quantity:2,design:{objects:[{value:{image:{src}}}]}}]);
    expect(JSON.parse(serializeStudio(saved.composition,saved,next.getState().productDemo)).assets).toEqual([src]);
  } finally { fonts.dispose();vi.restoreAllMocks();vi.unstubAllGlobals(); }
});
