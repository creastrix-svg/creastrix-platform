import {useEffect,useRef,useState} from 'react';
import type {AccountCartAdmission} from '../cart/accountCartAdmission';
import {useAccountCartAccess} from '../cart/useAccountCartAccess';
import {useProductLocale} from '../useProductLocale';
import {designLibraryCopy} from './designLibraryCopy';
import {matchingGuestDesign,loadHeldDesign,forgetHeldDesign} from './guestDesignHandoff';
import {libraryCommands} from './designLibraryApi';

/** Explicit confirmation in the identified buyer's library. A stale tab,
 * same email or a later sign-in does not prove the guest draft is theirs. */
export default function GuestDesignImport({admission,onSaved}:{admission:AccountCartAdmission;onSaved:()=>void}){
  const {state}=useAccountCartAccess(admission.access),t=designLibraryCopy[useProductLocale()];
  const [phase,set]=useState<'ready'|'saving'|'failed'>('ready'),flight=useRef<AbortController|null>(null);
  useEffect(()=>()=>{flight.current?.abort();flight.current=null;},[admission]);
  if(state.phase!=='ready'||!state.binding||!state.guestIntent)return null;
  const proof=state.guestIntent,binding=state.binding,held=matchingGuestDesign(proof);
  if(!held)return null;
  async function confirm(){
    if(!held||flight.current||!admission.access.ownsDisplay())return;
    const op=new AbortController();flight.current=op;set('saving');
    const current=()=>!op.signal.aborted&&admission.access.ownsDisplay()&&matchingGuestDesign(proof)?.draft===held.draft;
    try{const design=await loadHeldDesign(held,proof);if(!current())return;
      const api=libraryCommands(admission);await api.stage(binding,held.stage,design,op.signal);
      if(!current())return;
      await api.change(binding,held.command,{action:'save',id:held.draft,expectedVersion:'0',designId:held.stage},op.signal);
      if(!current())return;
      await forgetHeldDesign(held);if(!op.signal.aborted&&admission.access.ownsDisplay())onSaved();
    }catch{if(!op.signal.aborted)set('failed');}finally{if(flight.current===op)flight.current=null;}
  }
  return <section className="studio-library-actions"><p>{t.guestReady}</p><button className="product-secondary"
    type="button" disabled={phase==='saving'} onClick={()=>void confirm()}>{phase==='saving'?t.saving:t.save}</button>
    {phase==='failed'&&<p role="alert">{t.failed}</p>}</section>;
}
