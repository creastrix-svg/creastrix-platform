import {useEffect,useMemo,useState,type PropsWithChildren} from 'react';
import {useStore} from 'react-redux';
import type {RootState} from '../../store';
import {cartUndoExpired} from '../demoStore';
import {CartUndoVisitContext,newCartUndoVisit} from './cartUndoVisit';

/** Lives above transient sign-in checks, but only while the cart route is
 * open. Guest undo is Redux-only: expiration never sends an HTTP mutation. */
export default function CartUndoVisitScope({children,open}:PropsWithChildren<{open:boolean}>){
  const store=useStore<RootState>();
  const [returnedAt,setReturnedAt]=useState<{revision:number}|null>(null);
  const visit=useMemo(()=>open?newCartUndoVisit(Math.max(store.getState().productDemo.cartChange.revision,returnedAt?.revision??0)):null,[open,store,returnedAt]);
  useEffect(()=>{
    if(!visit)return;
    const leave=()=>{store.dispatch(cartUndoExpired(store.getState().productDemo.cartChange.revision));};
    const returnFromCache=(event:PageTransitionEvent)=>{if(event.persisted)setReturnedAt({revision:store.getState().productDemo.cartChange.revision});};
    // A Back navigation may resume the exact same JS document. Treat it as
    // a new visit, unlike ordinary window focus or switching browser tabs.
    window.addEventListener('pagehide',leave);
    window.addEventListener('pageshow',returnFromCache);
    store.dispatch(cartUndoExpired(visit.guestRevision));
    return ()=>{window.removeEventListener('pagehide',leave);window.removeEventListener('pageshow',returnFromCache);leave();};
  },[store,visit]);
  return <CartUndoVisitContext.Provider value={visit}>{children}</CartUndoVisitContext.Provider>;
}
