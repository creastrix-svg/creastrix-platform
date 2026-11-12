import { useEffect, useRef, useState } from 'react';
import { firstLettering, type StudioObject } from '../studio/design/studioComposition';
import { createStudioFontLibrary, type StudioFontId } from '../studio/design/studioFonts';
import { loadNormalizedStudioImage, type StudioImage } from '../studio/design/studioImage';
import LetteringSketch from '../studio/preview/LetteringSketch';
import { base64Bytes } from '../studio/session/designSnapshot';
import { useProductLocale } from '../useProductLocale';
import type { VariantId } from '../fixtures/loop';
import { appliedDesignLimit, fontName, readAppliedLoopDesign } from './appliedLoopDesign';
import type { AccountCartAdmission } from './accountCartAdmission';
import { accountProductLabels, designLabels } from './accountProductLabels';

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

// Account preview owns a fresh, short-lived font library. Private objects
// never enter the guest editor, Redux, IndexedDB or any public catalog cache.
export default function CartAppliedDesign({admission,lineId,variant,swatch,secondSwatch}:{
  admission:AccountCartAdmission;lineId:string;variant:VariantId;swatch?:string;secondSwatch?:string;
}) {
  const locale=useProductLocale(), label=designLabels[locale][0];
  const active=useRef<AbortController|null>(null), fonts=useRef<ReturnType<typeof createStudioFontLibrary>|null>(null);
  const [state,setState]=useState<'idle'|'reading'|'failed'|StudioObject[]>('idle');
  useEffect(()=>()=>{ active.current?.abort();active.current=null;fonts.current?.dispose();fonts.current=null; },[admission,lineId]);
  async function show() {
    if(active.current || !admission.access.ownsDisplay()) return;
    const op=new AbortController(), signal=AbortSignal.any([op.signal,AbortSignal.timeout(30000)]);
    active.current=op;
    const lib=createStudioFontLibrary(); fonts.current=lib;
    const current=()=>active.current===op && !op.signal.aborted && admission.access.ownsDisplay();
    setState('reading');
    try {
      const response=await fetch('/api/account-cart/lines/'+encodeURIComponent(lineId)+'/design',{
        method:'GET',credentials:'same-origin',cache:'no-store',redirect:'error',headers:{Accept:'application/json'},signal,
      });
      if(!current() || response.status!==200 || response.redirected || response.headers.get('content-type')?.split(';')[0]!=='application/json')
        throw Error('DESIGN_UNAVAILABLE');
      const design=readAppliedLoopDesign(await boundedJson(response));
      if(!current() || design.sku!==variant) throw Error('DESIGN_UNAVAILABLE');
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
            font=custom.get(obj.data) ?? (await lib.loadCustom(new File([base64Bytes(obj.data)],fontName(obj.data)),{signal})).id;
            custom.set(obj.data,font);
          } else font=(await lib.loadBuiltin(obj.font,{signal})).id;
          objects.push({id,kind:'text',value:{text:obj.text,color:obj.color,font,size,x,y}});
        }
      }
      if(!current() || signal.aborted) throw Error('DESIGN_UNAVAILABLE');
      setState(objects);
    } catch {
      lib.dispose();
      if(current()) { active.current=null;setState('failed'); }
    }
  }
  return <div data-applied-design>{Array.isArray(state)
    ? <div data-private-design-preview className="product-design-thumbnail">
        <LetteringSketch variant={variant} colors={swatch && secondSwatch ? [swatch,secondSwatch] : [swatch??'#89918d']}
          objects={state} value={firstLettering({objects:state,selectedId:null},variant)} label={label} detail/>
      </div>
    : <>{state==='failed' && <span role="status">{accountProductLabels[locale][2]} · </span>}
      <button type="button" className="product-text-button" disabled={state==='reading'} onClick={()=>void show()}>{label}</button></>}
  </div>;
}
