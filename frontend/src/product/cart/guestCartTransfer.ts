import { loadLocalCatalog } from '../catalog/localCatalog';
import { ackGuestBatch, claimGuestCart, freezeGuestBatch, hasClaimedGuestWork, retainRetiredBatch } from './guestCartBatch';
import { accountCartHttp } from './accountCartHttp';
import { localCartCommands, type Guard, type ImportItem } from './localCartCommands';

export type TransferPhase = 'idle'|'syncing'|'pending'|'binding'|'unsupported'|'applied'|'retired';

/** Login orchestration, separate from displaying or recovering the account
 * cart. A recovered binding never independently starts a shopping operation.
 * All uncertain outcomes keep the original durable batch, also across reload. */
export function guestCartTransfer(fetcher:typeof fetch, guard:Guard, phase:(value:TransferPhase)=>void,
                                 refreshCart:()=>void) {
  const lifetime = new AbortController();
  const owns = () => !lifetime.signal.aborted && guard.owns();
  // An overlapping cart mount observes the same initial read (including a
  // transient GET failure). Do not memoize old cart rows on later mounts.
  let first=true;
  let opening:ReturnType<ReturnType<typeof accountCartHttp>['read']>|undefined;
  let busy = false;
  async function run() {
    if (busy || !owns()) return;
    busy = true;
    const {signal} = lifetime;
    try {
      const reader = accountCartHttp(fetcher,guard);
      const request = reader.read(signal);
      if(first) { first=false; opening=request; }
      const result = await request;
      opening=undefined;
      if (!owns()) return;
      if (result.kind !== 'ready' && result.kind !== 'pending') { phase('pending'); return; }
      const intent=reader.guestIntent();
      if(intent) await claimGuestCart(guard.ownerId,intent,owns);
      if (!owns()) return;
      if (!await hasClaimedGuestWork(guard.ownerId,owns)) { if (owns()) phase('idle'); return; }
      if (!owns()) return;
      if (result.kind === 'pending') { phase('binding'); return; }
      const binding = reader.binding();
      if (!binding) { phase('pending'); return; }
      phase('syncing');
      await loadLocalCatalog();
      if (!owns()) return;
      const commands = localCartCommands(fetcher,guard);
      // An older owner-bound receipt and a fresh contribution can coexist.
      // Drain only already claimed batches; an ack never captures new units.
      let applied = false;
      while (owns()) {
        const batch = await freezeGuestBatch(guard.ownerId,binding.namespace,owns);
        if (!owns()) return;
        if (!batch) { phase(applied ? 'applied' : 'idle'); return; }
        const receipt = (action:'prepare'|'status'|'apply', lines?:ImportItem[]) =>
          commands.receipt(binding,batch.batchId,action,signal,lines);
        let state = await receipt('status');
        if (state === 'UNKNOWN') {
          // Staging precedes prepare; a lost stage reply reuses its persisted
          // UUID. A known receipt never re-uploads or re-prepares resources.
          const lines: ImportItem[]=[];
          for(const line of batch.lines) {
            const ref = line.kind==='design' ? await commands.stage(binding,line.stageId,line.design,signal)
              : {designId:null,designRevision:null};
            lines.push({sku:line.sku,quantity:line.quantity,...ref});
          }
          state=await receipt('prepare',lines);
        }
        if (state === 'PENDING') state = await receipt('apply');
        if (!owns()) return;
        if (state === 'RETIRED') {
          await retainRetiredBatch(batch,{ownerId:binding.ownerId,namespace:binding.namespace,batchId:batch.batchId,state},owns);
          if(owns()) phase('retired');
          return;
        }
        if (state !== 'APPLIED') { phase('pending'); return; }
        await ackGuestBatch(batch,{ownerId:binding.ownerId,namespace:binding.namespace,batchId:batch.batchId,state},owns);
        applied = true;
        if (owns()) refreshCart();
      }
    } catch (error) {
      if (owns()) phase(error instanceof Error && error.message === 'GUEST_CART_UNSUPPORTED' ? 'unsupported' : 'pending');
    } finally { opening=undefined; busy = false; }
  }
  return {
    run,
    openingRead: () => opening,
    dispose:() => { lifetime.abort(); opening=undefined; },
  };
}
