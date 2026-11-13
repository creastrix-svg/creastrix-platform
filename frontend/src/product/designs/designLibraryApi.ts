import type {AccountCartAdmission} from '../cart/accountCartAdmission';
import {appliedDesignLimit,readAppliedLoopDesign} from '../cart/appliedLoopDesign';
import {localCartCommands,tokenId,type Binding} from '../cart/localCartCommands';
import {isCatalogKey} from '../catalog/localCatalog';

const path='/api/account-cart/library';
export class LibraryConflict extends Error {}
const invalid=():never=>{throw new Error('DESIGN_LIBRARY_UNAVAILABLE');};
const label=(s:unknown)=>typeof s==='string'&&s.trim()&&s.length<=512?s:invalid();
const version=(s:unknown)=>typeof s==='string'&&/^[1-9][0-9]{0,18}$/.test(s)&&BigInt(s)<=9223372036854775807n?s:invalid();
const record=(v:unknown):Record<string,unknown>=>v!==null&&typeof v==='object'&&!Array.isArray(v)?v as Record<string,unknown>:invalid();
export function isLibraryId(v:string){try{return tokenId(v)===v&&v===v.toLowerCase();}catch{return false;}}
/** Only the already-mounted chooser may retain an editor across a passive
 * focus check. The parent remounts for every ordinary navigation or sign-in.
 * This identity is UI continuity only: reads and writes still use live admission. */
export const editorScope=(a:AccountCartAdmission)=>JSON.stringify([a.userId,a.owner.document,a.owner.revision]);
function readItem(raw:unknown){
  const o=record(raw);
  const key=(v:unknown):v is string=>typeof v==='string'&&v.length<=200&&isCatalogKey(v);
  if(!key(o.sku)||!key(o.offerId)||!key(o.variantId)||typeof o.id!=='string'||!isLibraryId(o.id))return invalid();
  if(o.caption!=null&&(typeof o.caption!=='string'||[...o.caption].length>32))return invalid();
  return {id:o.id,version:version(o.version),sku:o.sku,offerId:o.offerId,variantId:o.variantId,
    offerLabel:label(o.offerLabel),variantLabel:label(o.variantLabel),caption:o.caption as string|null|undefined};
}
export type LibraryItem=ReturnType<typeof readItem>;
/** No account cache or caller-supplied URL. Guard again after the last byte:
 * late responses cannot install creative work into a successor admission. */
async function read(admission:AccountCartAdmission,url:string,signal:AbortSignal,max:number){
  const check=()=>{if(signal.aborted || !admission.access.ownsDisplay())invalid();};
  check();const response=await fetch(url,{signal,method:'GET',credentials:'same-origin',cache:'no-store',redirect:'error',headers:{Accept:'application/json'}});
  check();if(response.status!==200||response.redirected||!response.body||response.headers.get('Content-Type')?.split(';')[0]!=='application/json'
    ||Number(response.headers.get('Content-Length'))>max)invalid();
  const reader=response.body!.getReader(),decoder=new TextDecoder('utf-8',{fatal:true});let size=0,raw='';
  try{for(;;){const {done,value}=await reader.read();check();if(done)break;size+=value.byteLength;
    if(size>max){await reader.cancel();invalid();}raw+=decoder.decode(value,{stream:true});}
    const result=record(JSON.parse(raw+decoder.decode()));
    if(result.ownerId!==admission.userId)return invalid();
    return result;
  }finally{reader.releaseLock();}
}
export async function listDesigns(admission:AccountCartAdmission,signal:AbortSignal){
  const value=await read(admission,path,signal,2*1024*1024);
  if(!Array.isArray(value.designs)||value.designs.length>200) return invalid();
  const items=value.designs.map(readItem);if(new Set(items.map(item=>item.id)).size!==items.length)return invalid();return items;
}
export async function openDesign(admission:AccountCartAdmission,id:string,signal:AbortSignal){
  if(!isLibraryId(id))return invalid();
  const result=await read(admission,path+'/'+id,signal,appliedDesignLimit+8192);
  const item=readItem(result.item),design=readAppliedLoopDesign(result.design);
  if(item.id!==id||item.sku!==design.sku)return invalid();return {item,design};
}
export type LibraryChange={action:'save';id:string;expectedVersion:string;designId:string}
  | {action:'delete';id:string;expectedVersion:string}|{action:'copy';id:string;expectedVersion:string;copyId:string};
export function libraryCommands(admission:AccountCartAdmission){
  const commands=localCartCommands(window.fetch.bind(window),{ownerId:admission.userId,owns:()=>admission.access.ownsDisplay()});
  return {stage:commands.stage,async change(binding:Binding,intent:string,body:LibraryChange,signal:AbortSignal){
    const result=await commands.library(binding,intent,body,signal);
    if(result==='conflict')throw new LibraryConflict('DESIGN_LIBRARY_CHANGED');
    return result;
  }};
}
