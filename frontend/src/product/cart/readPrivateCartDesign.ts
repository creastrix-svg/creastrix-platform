import { createStudioFontLibrary, type StudioFontId, type LoadedStudioFont } from '../studio/design/studioFonts';
import { loadNormalizedStudioImage, type StudioImage } from '../studio/design/studioImage';
import { base64Bytes } from '../studio/session/designSnapshot';
import { appliedDesignLimit, fontName, readAppliedLoopDesign,type AppliedLoopDesign } from './appliedLoopDesign';
import type { AccountCartAdmission } from './accountCartAdmission';
import type {StudioObject} from '../studio/design/studioComposition';

async function boundedJson(response:Response) {
  if(!response.body || Number(response.headers.get('Content-Length'))>appliedDesignLimit) throw Error('DESIGN_UNAVAILABLE');
  const reader=response.body.getReader(), decoder=new TextDecoder('utf-8',{fatal:true});
  let size=0, raw='';
  try {
    for(;;) {
      const chunk=await reader.read();
      if(chunk.done) break;
      size+=chunk.value.byteLength;
      if(size>appliedDesignLimit) { await reader.cancel(); throw Error('DESIGN_UNAVAILABLE'); }
      raw+=decoder.decode(chunk.value,{stream:true});
    }
    return JSON.parse(raw+decoder.decode());
  } finally { reader.releaseLock(); }
}


/** Decode the private response into a caller-owned font library. Do not publish
 * bytes to catalog/guest state; guard both the read and each async decode. */
export async function readPrivateCartDesign(admission:AccountCartAdmission,lineId:string,sku:string,
  lib:ReturnType<typeof createStudioFontLibrary>, signal:AbortSignal, loadedFonts?:LoadedStudioFont[]) {
  const current=()=>!signal.aborted && admission.access.ownsDisplay();
  if(!current())throw Error('DESIGN_UNAVAILABLE');
  const response=await fetch('/api/account-cart/lines/'+encodeURIComponent(lineId)+'/design',{
    method:'GET',credentials:'same-origin',cache:'no-store',redirect:'error',headers:{Accept:'application/json'},signal,
  });
  if(!current() || response.status!==200 || response.redirected || response.headers.get('content-type')?.split(';')[0]!=='application/json')
    throw Error('DESIGN_UNAVAILABLE');
  const design=readAppliedLoopDesign(await boundedJson(response));
  if(!current() || design.sku!==sku) throw Error('DESIGN_UNAVAILABLE');
  return decodePrivateLayers(design,lib,signal,current,loadedFonts);
}
export async function decodePrivateLayers(design:AppliedLoopDesign,lib:ReturnType<typeof createStudioFontLibrary>,
  signal:AbortSignal,current:()=>boolean,loadedFonts?:LoadedStudioFont[]){
  const images=new Map<string,StudioImage>(), custom=new Map<string,StudioFontId>(), objects:StudioObject[]=[];
  for(const obj of design.objects) {
    if(!current() || signal.aborted) throw Error('DESIGN_UNAVAILABLE');
    const id='studio-object-'+(1+objects.length), {size,x,y}=obj;
    if(obj.kind==='image') {
      let image=images.get(obj.data);
      if(!image) {
        const decoded=await loadNormalizedStudioImage(new File([base64Bytes(obj.data)],'preview.png',{type:'image/png'}),signal);
        image={...decoded,src:'data:image/png;base64,'+obj.data};images.set(obj.data,image);
      }
      objects.push({id,kind:'image',fit:obj.fit,value:{size,x,y,image}});
    } else {
      let font:StudioFontId;
      if(obj.font==='resource') {
        const existing=custom.get(obj.data);
        if(existing)font=existing;
        else {
          const loaded=lib.findCustom(obj.data)??await lib.loadCustom(new File([base64Bytes(obj.data)],fontName(obj.data)),{signal});
          font=loaded.id;loadedFonts?.push(loaded);
        }
        custom.set(obj.data,font);
      } else font=(await lib.loadBuiltin(obj.font,{signal})).id;
      objects.push({id,kind:'text',value:{text:obj.text,color:obj.color,font,size,x,y}});
    }
  }
  if(!current() || signal.aborted) throw Error('DESIGN_UNAVAILABLE');

  return objects;
}
