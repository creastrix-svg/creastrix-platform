import { cleanLetteringText, letteringColors, type LetteringColor } from '../studio/design/personalization';
import { MAX_CUSTOM_FONT_BYTES } from '../studio/design/studioFonts';
import { isLoopV2Font, type LoopV2Font } from '../studio/design/loopV2Fonts';
import { rasterDimensions, STUDIO_NORMALIZED_IMAGE_BYTES } from '../studio/design/studioImage';
import type { ImageFit } from '../studio/design/studioComposition';
import { base64Bytes } from '../studio/session/designSnapshot';

// A versioned projection of one applied surface, not the persistence format of
// the editor. Browser object IDs, filenames, library entries and history have
// no meaning in the account store. An unresolved reference is never plain.
export const appliedDesignLimit = 24 * 1024 * 1024;
const prefix = 'data:image/png;base64,';
const unsupported = (): never => { throw new Error('GUEST_CART_UNSUPPORTED'); };
const rec = (v:unknown):Record<string,unknown> => !!v && typeof v==='object' && !Array.isArray(v) ? v as Record<string,unknown> : unsupported();
const exact = (v:Record<string,unknown>,keys:string[]) => {
  if(Object.keys(v).length!==keys.length || !keys.every(k=>Object.hasOwn(v,k))) unsupported();
};
const number = (v:unknown,min:number,max:number):number => typeof v==='number' && Number.isFinite(v) && v>=min && v<=max ? v : unsupported();
const data = (v:unknown,limit:number):string => {
  if(typeof v!=='string' || !v.length || v.length>4*Math.ceil(limit/3) || v.length%4 || !/^[A-Za-z0-9+/]+={0,2}$/.test(v)
      || v.length*3/4-(v.endsWith('==')?2:v.endsWith('=')?1:0)>limit) return unsupported();
  return v;
};
export const loopFinishes = ['cobalt','coral','chalk','cobalt-chalk'] as const;
type Finish = typeof loopFinishes[number];
export const isLoopFinish = (s: unknown): s is Finish => loopFinishes.some(id=>id===s);
export type BuiltinFont = LoopV2Font;
type Geometry = {size:number;x:number;y:number};
type TextLayer = Geometry & {kind:'text';text:string;color:LetteringColor} & ({font:BuiltinFont}|{font:'resource';data:string});
type ImageLayer = Geometry & {kind:'image';fit:ImageFit;data:string};
export type AppliedLoopDesign = {sku:Finish;templateRevision:'2';zone:'surface';objects:(TextLayer|ImageLayer)[]};

export function normalizedSize(raw:string) {
  try {
    const bytes=base64Bytes(raw.slice(0,44)), size=rasterDimensions(bytes);
    if(bytes[0]!==137 || size.width>1536 || size.height>1536) return unsupported();
    return size;
  } catch { return unsupported(); }
}
export function fontName(raw:string) {
  const head=base64Bytes(raw.slice(0,16));
  if(head.length<12) return unsupported();
  const signature=new DataView(head.buffer).getUint32(0);
  return signature===0x00010000 ? 'font.ttf' : signature===0x4f54544f ? 'font.otf' : unsupported();
}

export function readAppliedLoopDesign(raw:unknown):AppliedLoopDesign {
  const value=rec(raw);
  exact(value,['sku','templateRevision','zone','objects']);
  if(!isLoopFinish(value.sku) || value.templateRevision!=='2' || value.zone!=='surface' || !Array.isArray(value.objects)
      || !value.objects.length || value.objects.length>8) return unsupported();
  let resourceChars=0;
  const objects=value.objects.map((raw):TextLayer|ImageLayer=>{
    const o=rec(raw), x=number(o.x,-1000,1000), y=number(o.y,-1000,1000);
    if(o.kind==='image') {
      exact(o,['kind','fit','data','size','x','y']);
      if(o.fit!=='free' && o.fit!=='cover' && o.fit!=='contain') return unsupported();
      const bytes=data(o.data,STUDIO_NORMALIZED_IMAGE_BYTES);
      resourceChars+=bytes.length;
      normalizedSize(bytes);
      return {kind:'image',data:bytes,fit:o.fit,size:number(o.size,20,150),x,y};
    }
    if(o.kind!=='text' || typeof o.text!=='string' || o.text.length>4096 || /\p{Surrogate}/u.test(o.text)
        || cleanLetteringText(o.text)!==o.text || !letteringColors.some(c=>c===o.color)) return unsupported();
    exact(o,['kind','text','color','font','size','x','y',...(o.font==='resource'?['data']:[])]);
    const geometry={kind:'text' as const,text:o.text,color:o.color as LetteringColor,size:number(o.size,18,32),x,y};
    if(o.font==='resource') {
      const bytes=data(o.data,MAX_CUSTOM_FONT_BYTES);
      fontName(bytes); resourceChars+=bytes.length;
      return {...geometry,font:'resource',data:bytes};
    }
    return isLoopV2Font(o.font) ? {...geometry,font:o.font} : unsupported();
  });
  const design={sku:value.sku,templateRevision:'2' as const,zone:'surface' as const,objects};
  if(resourceChars>appliedDesignLimit || !objects.some(o=>o.kind==='image'||o.text.trim())
      || new TextEncoder().encode(JSON.stringify(design)).length>appliedDesignLimit) return unsupported();
  return design;
}

/** Resolve only the references actually used in the purchased layer array.
 * Never iterate over drafts or reinterpret a runtime custom ID as a builtin. */
export function projectAppliedLoop(sku:string,revision:unknown,packed:unknown,assets:unknown,fonts:unknown):AppliedLoopDesign {
  const design=rec(packed);
  exact(design,['objects']);
  if(!Array.isArray(design.objects) || design.objects.length>8 || !Array.isArray(assets) || !Array.isArray(fonts)) return unsupported();
  const ids=new Set<string>();
  const objects=design.objects.map(raw=>{
    const o=rec(raw),v=rec(o.value);
    if(typeof o.id!=='string' || !/^studio-object-[1-9][0-9]{0,11}$/.test(o.id) || ids.has(o.id)) return unsupported();
    ids.add(o.id);
    if(o.kind==='image') {
      exact(o,['id','kind','fit','value']); exact(v,['image','size','x','y']);
      const image=rec(v.image);
      exact(image,['asset','aspect']);
      if(typeof image.asset!=='number' || !Number.isSafeInteger(image.asset) || image.asset<0 || image.asset>=assets.length) return unsupported();
      const src=assets[image.asset];
      if(typeof src!=='string' || !src.startsWith(prefix)) return unsupported();
      const bytes=src.slice(prefix.length), {width,height}=normalizedSize(bytes);
      if(image.aspect!==width/height) return unsupported();
      return {kind:'image',data:bytes,fit:o.fit,size:v.size,x:v.x,y:v.y};
    }
    exact(o,['id','kind','value']); exact(v,['text','color','font','size','x','y']);
    if(o.kind!=='text' || typeof v.font!=='string') return unsupported();
    if(!v.font.startsWith('custom-')) return {kind:'text',...v};
    const matches=fonts.filter(f=>f && typeof f==='object' && f.id===v.font);
    if(matches.length!==1) return unsupported();
    const r=rec(rec(matches[0]).resource),bytes=data(r.data,MAX_CUSTOM_FONT_BYTES);
    if(r.name!==fontName(bytes)) return unsupported();
    return {kind:'text',...v,font:'resource',data:bytes};
  });
  return readAppliedLoopDesign({sku,templateRevision:revision,zone:'surface',objects});
}
