import { StrictMode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import {
  createAccountCartAccess, useAccountCartAccess, type AccountCartAccess, type AccountCartTransport,
} from './useAccountCartAccess';

type Cart = readonly { lineId: string; units: number }[];
type Transport = AccountCartTransport<Cart>;
type Read = Awaited<ReturnType<Transport['read']>>;
type Recover = Awaited<ReturnType<Transport['recover']>>;
const lines: Cart = [{ lineId: 'first', units: 3 }, { lineId: 'second', units: 4 }];
const ready: Read = { kind: 'ready', ownerId: 'owner', cart: lines };
const pending: Read = { kind: 'pending', ownerId: 'owner', ticket: 'same-session-ticket' };
const setup = () => {
  const read = vi.fn<Transport['read']>();
  const recover = vi.fn<Transport['recover']>();
  const current = vi.fn(() => true);
  return { handle: createAccountCartAccess('owner', current, { read, recover }), read, recover, current };
};
function deferred<T>() {
  let finish!: (value: T) => void;
  const promise = new Promise<T>(resolve => { finish = resolve; });
  return { promise, finish };
}
function mount(handle: AccountCartAccess<Cart> | null) {
  return renderHook(({ access }) => useAccountCartAccess(access), {
    initialProps: { access: handle }, wrapper: StrictMode,
  });
}

describe('same-session cart access (no guest import or authentication transitions)', () => {
  it('does not contact a transport when no admitted handle exists', () => {
    const { result } = mount(null);
    act(() => result.current.retry());
    expect(result.current.state).toEqual({ phase: 'inactive' });
  });
  it('opens an already bound cart without recovery and issues one read across strict/multiple mounts', async () => {
    const { handle, read, recover } = setup();
    read.mockResolvedValue(ready);
    const first = mount(handle);
    const second = mount(handle);
    await waitFor(() => expect(first.result.current.state).toEqual({ phase: 'ready', cart: lines }));
    expect(second.result.current.state).toEqual(first.result.current.state);
    expect(read).toHaveBeenCalledTimes(1);
    expect(recover).not.toHaveBeenCalled();
  });
  it('recovers once, and only a fresh authorised read may publish lines', async () => {
    const { handle, read, recover } = setup();
    const fresh = deferred<Read>();
    read.mockResolvedValueOnce(pending).mockReturnValueOnce(fresh.promise);
    recover.mockResolvedValue('recovered');
    const { result } = mount(handle);
    await waitFor(() => expect(read).toHaveBeenCalledTimes(2));
    expect(result.current.state).toEqual({ phase: 'recovering' });
    await act(async () => fresh.finish(ready));
    expect(result.current.state).toEqual({ phase: 'ready', cart: lines });
    expect(recover).toHaveBeenCalledExactlyOnceWith('same-session-ticket', expect.any(AbortSignal));
  });
  it('never retries a failed read on remount; an explicit click runs only one GET, even if double-clicked', async () => {
    const { handle, read, recover } = setup();
    const inFlight = deferred<Read>();
    read.mockResolvedValueOnce({kind:'read-unavailable'}).mockReturnValueOnce(inFlight.promise);
    const first = mount(handle);
    await waitFor(() => expect(first.result.current.state).toEqual({phase:'read-unavailable'}));
    first.unmount();
    const {result} = mount(handle);
    act(() => result.current.retry());
    expect(read).toHaveBeenCalledTimes(1);
    act(() => { result.current.retryRead(); result.current.retryRead(); });
    expect(result.current.state).toEqual({phase:'opening'});
    expect(read).toHaveBeenCalledTimes(2);
    await act(async () => inFlight.finish(pending));
    expect(result.current.state).toEqual({phase:'blocked'});
    expect(recover).not.toHaveBeenCalled();
  });
  it('does not submit a read retry for a superseded admission', async () => {
    const {handle, read, current, recover} = setup();
    read.mockResolvedValueOnce({kind:'read-unavailable'});
    const {result} = mount(handle);
    await waitFor(() => expect(result.current.state).toEqual({phase:'read-unavailable'}));
    current.mockReturnValue(false);
    act(() => result.current.retryRead());
    expect(result.current.state).toEqual({phase:'inactive'});
    expect(read).toHaveBeenCalledTimes(1);
    expect(recover).not.toHaveBeenCalled();
  });
  it('reads only on a transient failure after an acknowledged binding; never retries the binding itself', async () => {
    const {handle, read, recover} = setup();
    read.mockResolvedValueOnce(pending).mockResolvedValueOnce({kind:'read-unavailable'}).mockResolvedValueOnce(ready);
    recover.mockResolvedValueOnce('recovered');
    const {result} = mount(handle);
    await waitFor(() => expect(result.current.state).toEqual({phase:'read-unavailable'}));
    act(() => { result.current.retry(); result.current.retryRead(); });
    await waitFor(() => expect(result.current.state).toEqual({phase:'ready',cart:lines}));
    expect(recover).toHaveBeenCalledTimes(1);
    expect(read).toHaveBeenCalledTimes(3);
  });
  it('stops after an unavailable automatic attempt; only explicit Retry reuses the original immutable ticket', async () => {
    const { handle, read, recover } = setup();
    const manual = deferred<Recover>();
    read.mockResolvedValueOnce(pending).mockResolvedValueOnce(ready);
    recover.mockResolvedValueOnce('retryable').mockReturnValueOnce(manual.promise);
    const first = mount(handle);
    await waitFor(() => expect(first.result.current.state).toEqual({ phase: 'unavailable' }));
    first.unmount();
    const { result } = mount(handle);
    expect(result.current.state).toEqual({ phase: 'unavailable' });
    expect(recover).toHaveBeenCalledTimes(1);
    act(() => { result.current.retry(); result.current.retry(); });
    expect(result.current.state).toEqual({ phase: 'recovering' });
    expect(recover.mock.calls.map(([ticket]) => ticket)).toEqual(['same-session-ticket', 'same-session-ticket']);
    await act(async () => manual.finish('recovered'));
    expect(result.current.state).toEqual({ phase: 'ready', cart: lines });
    expect(read).toHaveBeenCalledTimes(2);
  });
  it('never displays a different owner on either read or starts recovery for their pending ticket', async () => {
    for (const responses of [
      [{ ...ready, ownerId: 'other' }], [{ ...pending, ownerId: 'other' }],
      [pending, { ...ready, ownerId: 'other' }], [pending, { ...pending, ticket: 'another-ticket' }],
    ]) {
      const { handle, read, recover } = setup();
      for (const response of responses) read.mockResolvedValueOnce(response);
      recover.mockResolvedValue('recovered');
      const { result, unmount } = mount(handle);
      await waitFor(() => expect(result.current.state).toEqual({ phase: 'blocked' }));
      act(() => result.current.retry());
      expect(recover).toHaveBeenCalledTimes(responses.length - 1);
      unmount();
    }
  });
  it.each(['denied', 'malformed'] as const)('does not retry %s recovery', async failure => {
    const { handle, read, recover } = setup();
    read.mockResolvedValueOnce(pending);
    if (failure === 'denied') recover.mockResolvedValue('denied');
    else recover.mockRejectedValue(new Error('invalid response'));
    const { result } = mount(handle);
    await waitFor(() => expect(result.current.state).toEqual({ phase: 'blocked' }));
    act(() => result.current.retry());
    expect(recover).toHaveBeenCalledTimes(1);
    expect(read).toHaveBeenCalledTimes(1);
  });
  it('fails closed when admission changes before automatic recovery can begin', async () => {
    const { handle, read, recover, current } = setup();
    const foreign = deferred<Read>();
    read.mockReturnValue(foreign.promise);
    const { result } = mount(handle);
    current.mockReturnValue(false);
    await act(async () => foreign.finish(pending));
    expect(result.current.state).toEqual({ phase: 'inactive' });
    expect(recover).not.toHaveBeenCalled();
  });
  it('does not reuse an old Retry after a change of session even for the same internal User', async () => {
    const { handle, read, recover, current } = setup();
    read.mockResolvedValueOnce(pending);
    recover.mockResolvedValue('retryable');
    const { result } = mount(handle);
    await waitFor(() => expect(result.current.state).toEqual({ phase: 'unavailable' }));
    current.mockReturnValue(false);
    act(() => result.current.retry());
    expect(result.current.state).toEqual({ phase: 'inactive' });
    expect(recover).toHaveBeenCalledTimes(1);
  });
  it('ignores an old completion after a switch; a new handle never renders the previous cart', async () => {
    const old = setup(), next = setup();
    const inFlight = deferred<Recover>();
    old.read.mockResolvedValue(pending);
    old.recover.mockReturnValue(inFlight.promise);
    next.read.mockResolvedValue({ kind: 'ready', ownerId: 'owner', cart: [] });
    const { result, rerender } = mount(old.handle);
    await waitFor(() => expect(old.recover).toHaveBeenCalledTimes(1));
    act(() => old.handle.retire());
    expect(old.recover.mock.calls[0][1].aborted).toBe(true);
    rerender({ access: next.handle });
    await waitFor(() => expect(result.current.state).toEqual({ phase: 'ready', cart: [] }));
    await act(async () => inFlight.finish('recovered'));
    expect(old.read).toHaveBeenCalledTimes(1);
    expect(old.handle.getSnapshot()).toEqual({ phase: 'inactive' });
    expect(next.handle.getSnapshot()).toEqual({ phase: 'ready', cart: [] });
    act(() => next.handle.retire());
    expect(result.current.state).toEqual({ phase: 'inactive' });
  });
});
