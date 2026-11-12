import type { Authentication } from '../../authentication';
import { sameOwner } from '../../authBoundary';
import type { AppStore } from '../../store';
import { bindAccountCartReader, type AccountCartAdmission } from './accountCartAdmission';
import { accountCartHttp } from './accountCartHttp';
import { guestCartTransfer, type TransferPhase } from './guestCartTransfer';
import { sealGuestCart } from './guestCartBatch';

/** One reader per verified document admission, not per React render or route. */
export function createLocalCartSession(store: AppStore, auth: Pick<Authentication,'isCurrentOwner'>, fetcher: typeof fetch) {
  let active: AccountCartAdmission | null = null;
  let transfer: ReturnType<typeof guestCartTransfer>|null = null;
  let state:TransferPhase = 'idle';
  const listeners = new Set<() => void>();
  const notify = () => { for (const listener of listeners) listener(); };
  const synchronize = () => {
    const now = store.getState().authentication;
    if (active && now.phase === 'AUTHENTICATED' && sameOwner(now.owner, active.owner) && now.user?.id === active.userId) return;
    const previous = active;
    active = null;
    transfer?.dispose(); transfer = null; state = 'idle';
    previous?.dispose();
    if (now.phase === 'AUTHENTICATED' && now.owner && now.user) {
      const owner = now.owner, ownerId = now.user.id;
      const guard = {ownerId, owns:() => {
        const live = store.getState().authentication;
        return live.phase === 'AUTHENTICATED' && live.user?.id === ownerId && sameOwner(live.owner,owner) && auth.isCurrentOwner(owner);
      }};
      try {
        const http = accountCartHttp(fetcher,guard);
        const task = guestCartTransfer(fetcher,guard,next => { state = next; notify(); },()=>{
          if (!guard.owns()) return;
          // A pre-import GET may still be in flight. Retire its entire handle;
          // no old result can replace the post-receipt current-cart read.
          active?.dispose(); active = bindAccountCartReader(store,transport,auth); notify();
        });
        const transport = {
          ...http,
          async read(signal: AbortSignal, expectedTicket?: string) {
            if (signal.aborted || !guard.owns()) return {kind:'denied' as const};
            const shared=expectedTicket===undefined ? task.openingRead() : undefined;
            const result=await (shared??http.read(signal,expectedTicket));
            return !signal.aborted && guard.owns() ? result : {kind:'denied' as const};
          },
        };
        active = bindAccountCartReader(store, transport, auth);
        transfer = task;
        void task.run();
      }
      catch (error) {
        // A peer may have advanced the shared marker before its storage event.
        // The auth boundary has already invalidated that admission; no guest fallback.
        if (!(error instanceof Error && error.message === 'CART_ADMISSION_REQUIRED')) throw error;
      }
    }
    if (active !== previous) notify();
  };
  const unsubscribe = store.subscribe(synchronize);
  synchronize();
  return {
    sealBeforeLogin:sealGuestCart,
    getSnapshot: () => active,
    getTransferSnapshot:() => state,
    retryTransfer:() => { if (state === 'pending' || state === 'binding') void transfer?.run(); },
    subscribe(notify: () => void) { listeners.add(notify); return () => { listeners.delete(notify); }; },
    dispose() { unsubscribe(); transfer?.dispose(); active?.dispose(); active = null; listeners.clear(); },
  };
}
export type LocalCartSession = ReturnType<typeof createLocalCartSession>;
