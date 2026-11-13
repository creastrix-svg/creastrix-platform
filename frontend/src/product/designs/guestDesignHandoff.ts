import {guestLibraryFile} from '../cart/pendingDesignFiles';
import {readAppliedLoopDesign,type AppliedLoopDesign} from '../cart/appliedLoopDesign';
import {tokenId} from '../cart/localCartCommands';

const pointer='creastrix.library-handoff.v1';
type Ticket={draft:string;stage:string;command:string;login:string|null};
function read():Ticket|null{
  const raw=sessionStorage.getItem(pointer);
  if(!raw||raw.length>600)return null;
  const v=JSON.parse(raw);return {draft:tokenId(v.draft),stage:tokenId(v.stage),command:tokenId(v.command),login:v.login===null?null:tokenId(v.login)};
}
/** Take only the composition the guest explicitly chose. No filenames, image
 * bytes or text in URLs or sessionStorage; the temporary IDB record expires. */
export async function holdGuestDesign(input:AppliedLoopDesign){
  const before=read(), t:Ticket={draft:crypto.randomUUID(),stage:crypto.randomUUID(),command:crypto.randomUUID(),login:null};
  await guestLibraryFile(t.draft,'put',undefined,readAppliedLoopDesign(input));
  sessionStorage.setItem(pointer,JSON.stringify(t));
  if(before&&!before.login)await guestLibraryFile(before.draft,'discard').catch(()=>{});
  return t.draft;
}
export async function bindGuestDesignLogin(draft:string,current:()=>boolean){
  const t=read();
  if(!t||t.draft!==draft||t.login!==null||!current())throw Error('DESIGN_HANDOFF_UNAVAILABLE');
  const login=crypto.randomUUID();
  if(!await guestLibraryFile(t.draft,'reserve',login)||!current())throw Error('DESIGN_HANDOFF_UNAVAILABLE');
  const latest=read();
  if(latest?.draft!==draft||latest.login!==null)throw Error('DESIGN_HANDOFF_UNAVAILABLE');
  // Bind before sealing any cart lines. If storing the pointer fails, login
  // stops without detaching the guest cart from its browser partition.
  t.login=login;sessionStorage.setItem(pointer,JSON.stringify(t));return login;
}
export function matchingGuestDesign(proof?:string|null){
  try{const t=read();return proof&&t?.login===proof?t:null;}catch{return null;}
}
export async function loadHeldDesign(t:Ticket,proof:string){
  if(matchingGuestDesign(proof)?.draft!==t.draft)throw Error('DESIGN_HANDOFF_UNAVAILABLE');
  const held=await guestLibraryFile(t.draft,'readBound',proof);
  if(!held||matchingGuestDesign(proof)?.draft!==t.draft)throw Error('DESIGN_HANDOFF_UNAVAILABLE');
  return readAppliedLoopDesign(held);
}
export async function forgetHeldDesign(t:Ticket){
  if(read()?.draft===t.draft)sessionStorage.removeItem(pointer);
  if(t.login)await guestLibraryFile(t.draft,'discard',t.login).catch(()=>{});
}
