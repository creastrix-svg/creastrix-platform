import { useMemo, useSyncExternalStore } from 'react';
import type { Binding } from './localCartCommands';

type CartRead<T> =
  | { kind: 'ready'; ownerId: string; cart: T; binding?: Binding }
  | { kind: 'pending'; ownerId: string; ticket: string }
  | { kind: 'read-unavailable' }
  | { kind: 'denied' };

/** The adapter must validate wire data, enforce deadlines and bind both calls to
 * this admission. Recovery may restore access only; it must not import or edit
 * lines. With an expected ticket the read must verify that the server's
 * current binding belongs to that claim, even if it is already ready. */
export interface AccountCartTransport<T> {
  read(signal: AbortSignal, expectedTicket?: string): Promise<CartRead<T>>;
  recover(ticket: string, signal: AbortSignal): Promise<'recovered' | 'retryable' | 'unknown' | 'denied'>;
}

export type AccountCartState<T> =
  | { phase: 'inactive' | 'opening' | 'recovering' | 'unavailable' | 'read-unavailable' | 'blocked' }
  | { phase: 'ready'; cart: T; binding?: Binding };

const inactive = { phase: 'inactive' } as const;
const opening = { phase: 'opening' } as const;
const noSubscription = () => () => {};
const disconnected = () => inactive;
const noRetry = () => {};

/** Share one handle for the admission, including across cart/header mounts. The
 * authentication owner must retire it synchronously on logout, suspension or
 * change of admission, even if the next login belongs to the same internal User. */
export function createAccountCartAccess<T>(
  ownerId: string, isCurrent: () => boolean, transport: AccountCartTransport<T>,
) {
  let state: AccountCartState<T> = { phase: 'opening' };
  let ticket: string | undefined;
  let retryReadMode: 'refresh' | 'reconcile' = 'refresh';
  let started = false;
  const lifetime = new AbortController();
  const listeners = new Set<() => void>();

  function set(next: AccountCartState<T>) {
    state = next;
    for (const listener of listeners) listener();
  }
  function retire() {
    if (lifetime.signal.aborted) return;
    ticket = undefined;
    lifetime.abort();
    set(inactive);
  }
  function current() {
    if (!lifetime.signal.aborted && isCurrent()) return true;
    retire();
    return false;
  }
  function stop() {
    if (!current()) return;
    ticket = undefined;
    set({ phase: 'blocked' });
  }
  async function read(mode: 'initial' | 'refresh' | 'reconcile'): Promise<void> {
    if (!current()) return;
    try {
      const result = await transport.read(lifetime.signal, ticket);
      if (!current()) return;
      if (result.kind === 'read-unavailable') {
        retryReadMode = mode === 'reconcile' ? 'reconcile' : 'refresh';
        return set({ phase: 'read-unavailable' });
      }
      if (result.kind === 'denied' || result.ownerId !== ownerId) return stop();
      if (result.kind === 'ready') {
        ticket = undefined;
        set({ phase: 'ready', cart: result.cart, ...(result.binding ? {binding:result.binding} : {}) });
      } else if (result.kind === 'pending' && mode === 'initial' && result.ticket) {
        ticket = result.ticket;
        await recover();
      } else if (result.kind === 'pending' && mode === 'reconcile' && ticket && result.ticket === ticket) {
        // The server still reports a known rollback for this claim. Only a
        // new explicit click may resubmit it; reconciliation itself is a GET.
        set({ phase: 'unavailable' });
      } else stop(); // A recovery acknowledgement cannot lead into a second automatic bind.
    } catch { stop(); }
  }
  async function recover() {
    const pending = ticket;
    if (!pending || !current()) return;
    set({ phase: 'recovering' });
    if (!current()) return;
    try {
      const result = await transport.recover(pending, lifetime.signal);
      if (!current()) return;
      if (result === 'retryable') set({ phase: 'unavailable' });
      else if (result === 'recovered') await read('refresh');
      else if (result === 'unknown') await read('reconcile');
      else stop();
    } catch { stop(); }
  }
  return {
    subscribe(listener: () => void) { listeners.add(listener); return () => { listeners.delete(listener); }; },
    ownsDisplay: current,
    getSnapshot: () => state,
    start() {
      if (!current()) return false;
      if (!started) {
        started = true;
        void read('initial');
      }
      return true;
    },
    retry() { if (state.phase === 'unavailable') void recover(); },
    retryRead() {
      if (state.phase !== 'read-unavailable' || !current()) return;
      // Take the slot synchronously; a second click or mount cannot repeat it.
      // A retry of a GET is never permission to run a recovery command.
      set(opening);
      void read(retryReadMode);
    },
    retire,
  };
}

export type AccountCartAccess<T> = ReturnType<typeof createAccountCartAccess<T>>;

export function useAccountCartAccess<T>(access: AccountCartAccess<T> | null) {
  // A cached handle belongs to a sign-in, not to every future screen mount.
  // React may call getSnapshot during render; never reconcile auth (or reveal
  // cached rows) there. Check the live owner on each committed subscription.
  const display = useMemo(() => {
    if (!access) return { subscribe: noSubscription, snapshot: disconnected };
    let admitted: boolean | null = null;
    return {
      snapshot: () => admitted === null ? opening : admitted ? access.getSnapshot() : inactive,
      subscribe(listener: () => void) {
        const detach = access.subscribe(listener);
        admitted = access.start();
        listener();
        return () => { admitted = null; detach(); };
      },
    };
  }, [access]);
  const state = useSyncExternalStore<AccountCartState<T>>(display.subscribe, display.snapshot, disconnected);
  return { state, retry: access?.retry ?? noRetry, retryRead: access?.retryRead ?? noRetry };
}
