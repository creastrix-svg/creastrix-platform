import type { AppliedLoopDesign } from './appliedLoopDesign';
import type {LibraryChange} from '../designs/designLibraryApi';
import {allowedFavoriteSku,readFavoriteSnapshot} from '../favorites/favoriteSnapshot';

/** Commands are only wired by the dedicated loopback development entry. Never
 * accept a caller-provided URL or send a server ticket to another origin. */
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export type Binding = Readonly<{ownerId:string; namespace:string; writeContext:string}>;
export type Guard = Readonly<{ownerId:string; owns:() => boolean}>;
export type DesignRef = Readonly<{designId:string;designRevision:'1'}>;
export type EditAction = {action:'quantity';quantity:string} | {action:'remove'|'restore'|'dismiss'};
export type ImportItem = {sku:string; designId:string|null; designRevision:string|null; quantity:string};
const refused = (): never => { throw new Error('CART_COMMAND_UNAVAILABLE'); };
const object = (v:unknown): Record<string,unknown> => v && typeof v === 'object' && !Array.isArray(v) ? v as Record<string,unknown> : refused();
export const tokenId = (v:unknown) => typeof v === 'string' && uuid.test(v) ? v : refused();

// Only an interrupted exchange has a delivery ambiguity. Refusals and
// malformed, fully received replies are never classified as connectivity loss.
class TransportLoss extends Error {
  readonly postStarted: boolean;
  constructor(postStarted:boolean) { super('CART_TRANSPORT_LOSS'); this.postStarted = postStarted; }
}
function still(guard:Guard, signal:AbortSignal) { if (signal.aborted || !guard.owns()) refused(); }
export function localCartCommands(fetcher:typeof fetch, guard:Guard) {
  async function request(path:string, signal:AbortSignal, command:boolean, extra:Record<string,string>, payload?:unknown,statuses=[200]) {
    const budget = AbortSignal.any([signal,AbortSignal.timeout(10000)]);
    const init:RequestInit = {credentials:'same-origin',cache:'no-store',redirect:'error',signal:budget};
    const headers:Record<string,string> = {Accept:'application/json',...extra};
    async function exchange(url:string, options:RequestInit, statuses:number[]) {
      still(guard,signal);
      if (budget.aborted) throw new TransportLoss(false); // No request from this exchange was sent.
      const postStarted = options.method === 'POST';
      function interrupted(error:unknown):never {
        still(guard,signal);
        if (error instanceof TypeError || (error instanceof DOMException && (error.name === 'NetworkError'
            || error.name === 'TimeoutError' || (budget.aborted && error.name === 'AbortError'))))
          throw new TransportLoss(postStarted);
        throw error;
      }
      const response = await fetcher(url,{...init,...options}).catch(interrupted);
      still(guard,signal);
      if (response.redirected || !statuses.includes(response.status)
          || response.headers.get('content-type')?.split(';')[0] !== 'application/json') return refused();
      const value = object(JSON.parse(await response.text().catch(interrupted)));
      still(guard,signal);
      if (budget.aborted) throw new TransportLoss(postStarted);
      return {status:response.status,value};
    }
    if (command) {
      const {value:csrf} = await exchange('/auth/csrf',{method:'GET',headers:{Accept:'application/json'}},[200]);
      if (csrf.headerName !== 'X-CSRF-TOKEN' || csrf.parameterName !== '_csrf'
          || typeof csrf.token !== 'string' || !/^[0-9a-z_+/=-]{1,2048}$/i.test(csrf.token)) return refused();
      headers['X-CSRF-TOKEN'] = csrf.token;
      if (payload !== undefined) headers['Content-Type'] = 'application/json';
    }
    return exchange(path,{headers,method:command?'POST':'GET',
      ...(payload === undefined ? {} : {body:JSON.stringify(payload)})},statuses);
  }
  return {
    async listFavorites(signal:AbortSignal){
      const {status,value}=await request('/api/account-cart/favorites',signal,false,{},undefined,[200,409]);
      if(status===409)return value.error==='request_not_completed'&&Object.keys(value).length===1?'conflict' as const:refused();
      return readFavoriteSnapshot(value,guard.ownerId);
    },
    async changeFavorites(binding:Binding,id:string,command:{sku:string;saved:boolean}|{skus:readonly string[]},signal:AbortSignal){
      if(binding.ownerId!==guard.ownerId)return refused();
      const transfer='skus' in command;
      if(transfer ? command.skus.length>1000||!command.skus.every(allowedFavoriteSku)||new Set(command.skus).size!==command.skus.length
        : !allowedFavoriteSku(command.sku)||typeof command.saved!=='boolean')return refused();
      const {value,status}=await request('/api/account-cart/favorites/'+(transfer?'imports':'commands')+'/'+tokenId(id),signal,true,{
        'X-Cart-Namespace':tokenId(binding.namespace),'X-Cart-Context':tokenId(binding.writeContext),
      },command,[200,409,422,503]);
      if((status===409||status===422)&&value.error==='request_not_completed')return status===422?'invalid' as const:'conflict' as const;
      if(status!==200)return refused(); // Unknown delivery: repeat only the original intent.
      return readFavoriteSnapshot(value,guard.ownerId);
    },
    async library(binding:Binding,intent:string,body:LibraryChange,signal:AbortSignal){
      if(binding.ownerId!==guard.ownerId || !/^(0|[1-9][0-9]{0,18})$/.test(body.expectedVersion)
        || BigInt(body.expectedVersion)>9223372036854775807n || (body.action!=='save'&&body.expectedVersion==='0'))return refused();
      tokenId(body.id);if(body.action==='copy')tokenId(body.copyId);if(body.action==='save')tokenId(body.designId);
      const {value,status}=await request('/api/account-cart/library/commands/'+tokenId(intent),signal,true,{
        'X-Cart-Namespace':tokenId(binding.namespace),'X-Cart-Context':tokenId(binding.writeContext),
      },body,[200,409,422]);
      if(status===409)return 'conflict' as const;
      if(status===422)throw Error('DESIGN_LIBRARY_REFUSED');
      const expected=body.action==='copy'?1n:BigInt(body.expectedVersion)+1n;
      if(Object.keys(value).length!==2||value.id!==(body.action==='copy'?body.copyId:body.id)
        ||value.version!==String(expected)||expected>9223372036854775807n)return refused();
      return {id:String(value.id),version:String(expected)};
    },
    async editLine(binding:Binding,intentId:string,lineId:string,expectedVersion:string,change:EditAction,signal:AbortSignal) {
      if(binding.ownerId!==guard.ownerId || !/^(0|[1-9][0-9]{0,18})$/.test(expectedVersion)
        || (change.action==='quantity' && (!/^[1-9][0-9]{0,6}$/.test(change.quantity)||Number(change.quantity)>1_000_000)))return refused();
      const {status,value}=await request('/api/account-cart/edits/'+tokenId(intentId),signal,true,{
        'X-Cart-Namespace':tokenId(binding.namespace),'X-Cart-Context':tokenId(binding.writeContext),
      },{lineId:tokenId(lineId),expectedVersion,...change},[200,409,422,503]);
      if(status===200 && value.state==='APPLIED' && typeof value.version==='string' && /^[1-9][0-9]{0,18}$/.test(value.version)
        && (BigInt(value.version)===BigInt(expectedVersion)+1n || (change.action==='quantity' && value.version===expectedVersion))
        && Object.keys(value).length===2)return 'applied' as const;
      if((status===409 || status===422) && value.error==='request_not_completed' && Object.keys(value).length===1)return 'rejected' as const;
      return refused();
    },
    async addPlain(binding:Binding, requestId:string, sku:string, quantity:string, signal:AbortSignal, design?:DesignRef) {
      if (binding.ownerId!==guard.ownerId || sku.length>200 || !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(sku)
          || !/^[1-9][0-9]{0,6}$/.test(quantity) || Number(quantity)>1_000_000) return refused();
      if(design && (tokenId(design.designId)!==design.designId || design.designRevision!=='1'))return refused();
      const {status,value}=await request('/api/account-cart/additions/'+tokenId(requestId),signal,true,{
        'X-Cart-Namespace':tokenId(binding.namespace), 'X-Cart-Context':tokenId(binding.writeContext),
      },{sku,quantity,...(design?{designId:design.designId,designRevision:design.designRevision}:{})},[200,409,422,503]);
      if(status===200 && value.state==='APPLIED' && typeof value.version==='string' && /^[1-9][0-9]{0,18}$/.test(value.version)
          && BigInt(value.version)<=9223372036854775807n) {
        if(design && Object.keys(value).length===3)return {appliedLine:tokenId(value.lineId)};
        if(!design && Object.keys(value).length===2)return 'applied' as const;
      }
      if(value.error==='request_not_completed' && Object.keys(value).length===1) {
        if(status===422) return 'rejected' as const;
        if(status===409) return 'conflict' as const;
      }
      return refused(); // No conclusive acknowledgement: keep the original intent.
    },
    async stage(binding:Binding,stageId:string,design:AppliedLoopDesign,signal:AbortSignal) {
      if(guard.ownerId!==binding.ownerId) return refused();
      const id=tokenId(stageId);
      const {value,status}=await request('/api/account-cart/designs/'+id,signal,true,{
        'X-Cart-Namespace':tokenId(binding.namespace),'X-Cart-Context':tokenId(binding.writeContext),
      },design,[200,422]);
      if(status===422) throw new Error('GUEST_CART_UNSUPPORTED');
      if(value.designId!==id || value.designRevision!=='1' || Object.keys(value).length!==2) return refused();
      return {designId:id,designRevision:'1'};
    },
    async recover(ticket:string,signal:AbortSignal):Promise<'recovered'|'retryable'|'unknown'|'denied'> {
      try {
        const {status,value} = await request('/api/account-cart/recover',signal,true,{}, {ticket:tokenId(ticket)},[200,503]);
        if (Object.keys(value).length !== 1) return 'denied';
        return status === 200 && value.kind === 'recovered' ? 'recovered'
          : status === 503 && value.kind === 'retryable' ? 'retryable'
          : status === 503 && value.error === 'request_not_completed' ? 'unknown' : 'denied';
      } catch (error) {
        still(guard,signal);
        if (error instanceof TransportLoss) return error.postStarted ? 'unknown' : 'retryable';
        throw error;
      }
    },
    async receipt(binding:Binding, batchId:string, action:'prepare'|'apply'|'status'|'retire',signal:AbortSignal,lines?:readonly ImportItem[]) {
      if (guard.ownerId !== binding.ownerId || (action === 'prepare') !== !!lines) return refused();
      const path = '/api/account-cart/imports/' + tokenId(batchId) + '/' + action;
      const {status,value} = await request(path,signal,action !== 'status',{
        'X-Cart-Namespace':tokenId(binding.namespace),'X-Cart-Context':tokenId(binding.writeContext),
      },lines ? {lines} : undefined);
      if (status !== 200 || !['PENDING','APPLIED','RETIRED','UNKNOWN'].includes(String(value.state))) return refused();
      if (value.state === 'APPLIED' ? typeof value.version !== 'string' || !/^(0|[1-9][0-9]{0,18})$/.test(value.version) : value.version !== null) return refused();
      return value.state as 'APPLIED'|'PENDING'|'RETIRED'|'UNKNOWN';
    },
  };
}
