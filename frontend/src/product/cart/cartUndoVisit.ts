import {createContext,useContext,useState} from 'react';

/** Presentation only, never a write grant. Store row IDs, not files, account
 * data or credentials. A new cart route/refresh creates an empty visit. */
export function newCartUndoVisit(guestRevision:number){
  let currentScope='',removed=new Set<string>();
  return {guestRevision,account(scope:string){
    if(scope!==currentScope){currentScope=scope;removed=new Set();}
    return removed;
  }};
}
export const CartUndoVisitContext=createContext<ReturnType<typeof newCartUndoVisit>|null>(null);
export function useCartUndoVisit(guestRevision=0){
  const visit=useContext(CartUndoVisitContext),[local]=useState(()=>newCartUndoVisit(guestRevision));
  return visit??local;
}
