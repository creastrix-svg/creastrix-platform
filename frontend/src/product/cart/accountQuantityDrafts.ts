import {sameOwner,type Owner} from '../../authBoundary';
import type {AuthenticationState} from '../../store';

export type AccountQuantityDraft = {get(sku:string):number;set(sku:string,quantity:number):void};

/** Document-local purchase intentions, never guest cart rows or write credentials.
 * Passive checking seals this map until the same viewer is verified. Observe
 * every auth transition synchronously so an intermediate logout cannot be
 * hidden by a batched render. A different document starts from an empty map. */
export function createAccountQuantityDrafts(){
  let viewer:{id:string;doc:string;revision:string}|undefined;
  let active:Owner|null=null;
  let amounts=new Map<string,number>();
  function clear(){if(viewer){amounts.clear();amounts=new Map();}viewer=undefined;active=null;}
  function observe({owner,phase,user}:AuthenticationState){
    const sameDocument=!!owner && !!viewer && viewer.doc===owner.document && viewer.revision===owner.revision;
    if(phase==='CHECKING' && sameDocument){active=null;return;}
    if(phase!=='AUTHENTICATED'||!user||!owner){clear();return;}
    if(!sameDocument || viewer?.id!==user.id){clear();viewer={id:user.id,doc:owner.document,revision:owner.revision};}
    active=owner;
  }
  function bind(owner:Owner,owns:()=>boolean):AccountQuantityDraft{
    const values=amounts;
    const current=()=>values===amounts && sameOwner(active,owner);
    return {
      // A render-time read must not call the authentication controller (its
      // live marker check can itself cause a synchronous Redux transition).
      get:sku=>current()?values.get(sku)??1:1,
      set(sku,n){
        if(!current()||!owns() || !Number.isSafeInteger(n)||n<1||n>1_000_000
          ||sku.length>200||!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(sku))return;
        values.delete(sku);
        if(n===1)return;
        if(values.size>=1000)values.delete(values.keys().next().value!);
        values.set(sku,n);
      },
    };
  }
  return {observe,bind,clear};
}
