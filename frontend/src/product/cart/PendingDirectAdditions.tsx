import {useMemo,useSyncExternalStore} from 'react';
import type {AccountCartAdmission} from './accountCartAdmission';
import {additionScope,type DirectCartAdditions,type DirectIntent} from './directCartAdditions';
import {directAdditionCopy} from './directAdditionCopy';
import type {Binding} from './localCartCommands';
import {useProductLocale} from '../useProductLocale';

type Inputs={admission:AccountCartAdmission;writer:DirectCartAdditions;binding:Binding};
function observePending({admission,writer,binding}:Inputs) {
  let view:{snapshot:ReturnType<DirectCartAdditions['getSnapshot']>;pending:DirectIntent[]|null}|null=null;
  return {get:()=>view,subscribe:(listener:()=>void)=>{
    const update=()=>{view=admission.access.ownsDisplay()?{snapshot:writer.getSnapshot(),pending:writer.pendingFor(binding)}:null;listener();};
    const detach=writer.subscribe(update);update();
    return ()=>{detach();view=null;};
  }};
}

/** This is receipt recovery, not a catalog-based buying surface. Do not load a
 * product or submit a command on mount: withdrawn items still need an outcome. */
export function PendingDirectAdditions({admission,writer,binding}:Inputs) {
  const copy=directAdditionCopy[useProductLocale()];
  const display=useMemo(()=>observePending({admission,writer,binding}),[admission,binding,writer]);
  const view=useSyncExternalStore(display.subscribe,display.get,()=>null);
  if(!view)return null;
  const {snapshot,pending}=view;
  if(!pending)return <p role="status">{copy[6]}</p>;
  return <>{pending.map(intent=>{
    const selected=snapshot.scope===additionScope(binding,intent.sku);
    return <div data-pending-addition key={intent.id} className="product-buy-feedback">
      <span>{intent.sku} · {copy[3].replace('{n}',intent.quantity)}</span>
      {selected && snapshot.phase==='storage' ? <span role="status">{copy[6]}</span> :
        <button type="button" className="product-text-button" disabled={snapshot.phase==='sending'}
          onClick={()=>{if(admission.access.ownsDisplay())void writer.retryExisting(binding,intent);}}>{copy[4]}</button>}
    </div>;
  })}{snapshot.phase==='rejected' && snapshot.scope.startsWith(binding.ownerId+':'+binding.namespace+':')
    && <p role="status">{copy[5]}</p>}</>;
}
