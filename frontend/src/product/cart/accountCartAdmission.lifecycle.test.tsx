import { StrictMode } from 'react';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { afterEach, expect, it, vi } from 'vitest';
import { createAuthentication } from '../../authentication';
import { AUTH_BOUNDARY_KEY } from '../../authBoundary';
import { bindAuthenticationLifecycle } from '../../authenticationLifecycle';
import { authenticationFinished, authenticationStarted, createAppStore } from '../../store';
import AccountCartView from './AccountCartView';
import { bindAccountCartReader, type AccountCartLines } from './accountCartAdmission';
import type { AccountCartTransport } from './useAccountCartAccess';

type Transport = AccountCartTransport<AccountCartLines>;
const buyer = { id: '11111111-1111-4111-8111-111111111111', status: 'ACTIVE' } as const;
const ready = { kind: 'ready', ownerId: buyer.id, cart: [
  { lineId: 'one', offerLabel: 'Private cart line', variantLabel: 'Blue', designLabel: null, quantity: '2' },
] } as const;
const pending = { kind: 'pending', ownerId: buyer.id, ticket: 'original' } as const;
const disposers: (() => void)[] = [];
afterEach(() => { act(() => { for (const dispose of disposers.splice(0)) dispose(); }); });
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
}

async function setup() {
  const store = createAppStore('en', vi.fn());
  const shared = new Map<string, string>();
  const auth = createAuthentication({
    enabled: true,
    fetch: async path => {
      if (path !== '/api/me') throw new Error('Unexpected auth request');
      return Response.json(buyer);
    },
    storage: () => ({ getItem: key => shared.get(key) ?? null, setItem: (key, value) => { shared.set(key, value); } }),
    nonce: () => crypto.randomUUID(),
    closePrivate: vi.fn(), openPrivate: vi.fn(),
    started: (owner, phase, notice) => { store.dispatch(authenticationStarted({ owner, phase, notice })); },
    finished: (owner, result) => { store.dispatch(authenticationFinished({ owner, result })); },
  });
  const lifecycle = bindAuthenticationLifecycle(auth);
  disposers.push(lifecycle.dispose);
  await lifecycle.started;
  const read = vi.fn<Transport['read']>(), recover = vi.fn<Transport['recover']>();
  const admission = bindAccountCartReader(store, { read, recover }, auth);
  disposers.push(admission.dispose);
  const mount = () => render(<StrictMode><Provider store={store}><AccountCartView admission={admission} /></Provider></StrictMode>);
  function peerChangesMarker() {
    // Another document has committed its marker, but this one's storage event has not run yet.
    shared.set(AUTH_BOUNDARY_KEY, JSON.stringify({ nonce: 'new-peer', phase: 'transition' }));
    expect(store.getState().authentication.phase).toBe('AUTHENTICATED');
  }
  function assertRetired() {
    expect(admission.access.getSnapshot().phase).toBe('inactive');
    expect(store.getState().authentication.notice).toBe('PEER_CHANGED');
    expect(screen.queryByText('Private cart line')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
  }
  return { read, recover, mount, peerChangesMarker, assertRetired };
}

it.each(['ready', 'pending'] as const)('rejects a late %s read before admitting private rows or recovery', async kind => {
  const { read, recover, mount, peerChangesMarker, assertRetired } = await setup();
  const result = deferred<Awaited<ReturnType<Transport['read']>>>();
  read.mockReturnValueOnce(result.promise);
  mount();
  await waitFor(() => expect(read).toHaveBeenCalledTimes(1));
  peerChangesMarker();
  await act(async () => { result.resolve(kind === 'ready' ? ready : pending); });
  assertRetired();
  expect(recover).not.toHaveBeenCalled();
  expect(read.mock.calls[0][0].aborted).toBe(true);
});

it('does not issue manual recovery when the account revision changes before Retry', async () => {
  const { read, recover, mount, peerChangesMarker, assertRetired } = await setup();
  read.mockResolvedValueOnce(pending);
  recover.mockResolvedValueOnce('retryable');
  mount();
  const retry = await screen.findByRole('button', { name: 'Retry' });
  peerChangesMarker();
  await userEvent.click(retry);
  assertRetired();
  expect(recover).toHaveBeenCalledTimes(1);
  expect(read).toHaveBeenCalledTimes(1);
});

it('does not fetch private lines after a recovery acknowledgement from a former revision', async () => {
  const { read, recover, mount, peerChangesMarker, assertRetired } = await setup();
  const acknowledgement = deferred<Awaited<ReturnType<Transport['recover']>>>();
  read.mockResolvedValueOnce(pending);
  recover.mockReturnValueOnce(acknowledgement.promise);
  mount();
  await waitFor(() => expect(recover).toHaveBeenCalledTimes(1));
  peerChangesMarker();
  await act(async () => { acknowledgement.resolve('recovered'); });
  assertRetired();
  expect(read).toHaveBeenCalledTimes(1);
});
