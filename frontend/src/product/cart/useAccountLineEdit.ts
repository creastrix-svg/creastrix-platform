import {useEffect,useRef,useState} from 'react';
import type {AccountCartAdmission} from './accountCartAdmission';
import type {Binding,EditAction} from './localCartCommands';

type Intent={id:string;lineId:string;version:string;change:EditAction};

/** No optimistic cart and no guest write. If an acknowledgement is lost the
 * GET reconciles reality; an explicit retry reuses the exact version/intent.
 * A reload reads the server and never dispatches an old edit automatically. */
export function useAccountLineEdit(admission:AccountCartAdmission, binding:Binding|undefined,version:string|undefined,undo:Set<string>){
  const [phase,setPhase]=useState<'idle'|'busy'|'unknown'|'refused'>('idle');
  const [notice,setNotice]=useState<EditAction['action']|undefined>();
  const [catalogEpoch,setCatalogEpoch]=useState(0);
  const pending=useRef<Intent|null>(null),active=useRef<AbortController|null>(null);
  useEffect(()=>()=>{active.current?.abort();active.current=null;pending.current=null;},[admission]);
  async function perform(intent:Intent){
    const edit=admission.editLine;
    if(active.current || !edit || !binding || binding.ownerId!==admission.userId || !admission.access.ownsDisplay())return;
    const op=new AbortController();active.current=op;pending.current=intent;
    const current=()=>active.current===op && !op.signal.aborted && admission.access.ownsDisplay();
    setPhase('busy');setNotice(undefined);
    try{
      const result=await edit(binding,intent.id,intent.lineId,intent.version,intent.change,op.signal);
      if(!current())return;
      if(intent.change.action==='remove' && result==='applied')undo.add(intent.lineId);
      else if(intent.change.action==='remove' || (result==='applied' && (intent.change.action==='restore'||intent.change.action==='dismiss')))
        undo.delete(intent.lineId);
      await admission.access.refreshRows();
      if(!current())return;
      pending.current=null;
      setPhase(result==='applied'?'idle':'refused');if(result==='applied')setNotice(intent.change.action);
      else setCatalogEpoch(n=>n+1); // A seller's stock/template change need not change the cart version.
    }catch{
      if(!current())return;
      // A lost acknowledgement is not an acknowledgement of removal: the UI
      // will show Undo only if the owner-scoped GET also returns a removed row.
      if(intent.change.action==='remove')undo.add(intent.lineId);
      await admission.access.refreshRows();
      if(current())setPhase('unknown');
    }finally{if(active.current===op)active.current=null;}
  }
  const submit=(lineId:string,change:EditAction)=>{
    if(active.current || pending.current || version===undefined)return;
    return perform({id:crypto.randomUUID(),lineId,version,change});
  };
  return {phase,notice,catalogEpoch,locked:!binding || !admission.editLine || version===undefined || phase==='busy'||phase==='unknown',submit,
    retry:()=>{if(phase==='unknown' && pending.current)return perform(pending.current);}};
}
