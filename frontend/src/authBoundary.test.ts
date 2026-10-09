import { describe, expect, it, vi } from 'vitest';
import { AUTH_BOUNDARY_KEY, createAuthBoundary, sameOwner } from './authBoundary';

function shared() {
  const values = new Map<string, string>();
  return { getItem: vi.fn((key: string) => values.get(key) ?? null), setItem: vi.fn((key: string, value: string) => { values.set(key, value); }) };
}
function tab(storage = shared(), prefix = 'tab') {
  let sequence = 0;
  const closePrivate = vi.fn();
  const invalidated = vi.fn();
  const boundary = createAuthBoundary({ storage: () => storage, nonce: () => prefix + '-' + ++sequence, closePrivate, invalidated });
  return { boundary, storage, closePrivate, invalidated };
}

describe('document ownership and non-secret peer signal', () => {
  it('publishes only document creation or explicit intent, never reads or lifecycle events', () => {
    const t = tab();
    const first = t.boundary.begin('checking', true)!;
    expect(JSON.parse(t.storage.getItem(AUTH_BOUNDARY_KEY)!)).toEqual({ nonce: 'tab-2', phase: 'checking' });
    expect(t.boundary.current(first)).toBe(true);
    const later = t.boundary.begin('checking')!;
    expect(t.boundary.current(first)).toBe(false);
    expect(t.boundary.current(later)).toBe(true);
    t.boundary.suspend();
    t.boundary.begin('checking');
    t.boundary.reconcile();
    expect(t.storage.setItem).toHaveBeenCalledTimes(1);
    t.boundary.begin('transition', true);
    expect(t.storage.setItem).toHaveBeenCalledTimes(2);
    expect(JSON.parse(t.storage.getItem(AUTH_BOUNDARY_KEY)!)).toEqual({ nonce: 'tab-3', phase: 'transition' });
  });

  it('checks current storage without waiting for a storage event and closes synchronously', () => {
    const t = tab();
    const owner = t.boundary.begin('checking', true)!;
    t.storage.setItem(AUTH_BOUNDARY_KEY, JSON.stringify({ nonce: 'peer-new', phase: 'transition' }));
    const closes = t.closePrivate.mock.calls.length;
    expect(t.boundary.current(owner)).toBe(false);
    expect(t.closePrivate).toHaveBeenCalledTimes(closes + 1);
    expect(t.invalidated).toHaveBeenLastCalledWith(expect.anything(), 'PEER_CHANGED');
  });

  it('two tabs and out-of-order wakeups do not publish back, restore a nonce or create loops', () => {
    const storage = shared();
    const a = tab(storage, 'a');
    const b = tab(storage, 'b');
    const oldA = a.boundary.begin('checking', true)!;
    b.boundary.begin('checking', true);
    a.boundary.reconcile();
    const newA = a.boundary.begin('checking')!;
    b.boundary.reconcile();
    a.boundary.reconcile(); // A late event is just a wakeup; only the current value is read.
    expect(a.boundary.current(oldA)).toBe(false);
    expect(a.boundary.current(newA)).toBe(true);
    const bIntent = b.boundary.begin('transition', true)!;
    a.boundary.reconcile();
    b.boundary.reconcile();
    a.boundary.reconcile();
    expect(b.boundary.current(bIntent)).toBe(true);
    expect(storage.setItem).toHaveBeenCalledTimes(3);
    expect(JSON.parse(storage.getItem(AUTH_BOUNDARY_KEY)!)).toEqual({ nonce: 'b-3', phase: 'transition' });
  });

  it('characterizes simultaneous intents as invalidation, not a global ordering guarantee', () => {
    const storage = shared();
    const a = tab(storage, 'a');
    const b = tab(storage, 'b');
    const intentA = a.boundary.begin('transition', true)!;
    const intentB = b.boundary.begin('transition', true)!;
    expect(a.boundary.current(intentA)).toBe(false);
    expect(b.boundary.current(intentB)).toBe(true);
    expect(storage.setItem).toHaveBeenCalledTimes(2);
  });

  it.each([null, '', '{}', 'not-json', '{"nonce":"n","phase":"settled"}', '{"nonce":"n","phase":["checking"]}', '{"nonce":"n","phase":"checking","user":"private"}',
    JSON.stringify({ nonce: 'n'.repeat(81), phase: 'checking' }), 'x'.repeat(257)])('fails closed on absent or invalid shared state: case %#', value => {
    const t = tab();
    if (value !== null) t.storage.setItem(AUTH_BOUNDARY_KEY, value);
    expect(t.boundary.begin('checking')).toBeNull();
    expect(t.invalidated).toHaveBeenLastCalledWith(expect.anything(), 'STORAGE_UNAVAILABLE');
  });

  it.each(['read', 'write', 'access'] as const)('fails closed on %s storage failure', mode => {
    const storage = shared();
    if (mode === 'read') storage.getItem.mockImplementation(() => { throw new Error('blocked'); });
    if (mode === 'write') storage.setItem.mockImplementation(() => { throw new Error('blocked'); });
    const invalidated = vi.fn();
    const boundary = createAuthBoundary({ storage: () => { if (mode === 'access') throw new Error('blocked'); return storage; }, nonce: () => 'n', closePrivate: vi.fn(), invalidated });
    expect(boundary.begin('checking', true)).toBeNull();
    expect(invalidated).toHaveBeenLastCalledWith(expect.anything(), 'STORAGE_UNAVAILABLE');
  });

  it('old owners cannot diagnose broken storage and suspend/dispose invalidate without writes', () => {
    const t = tab();
    const old = t.boundary.begin('checking', true)!;
    const current = t.boundary.begin('checking')!;
    t.storage.getItem.mockImplementation(() => { throw new Error('blocked'); });
    expect(t.boundary.current(old)).toBe(false);
    expect(t.invalidated).not.toHaveBeenCalled();
    expect(t.boundary.current(current)).toBe(false);
    expect(t.invalidated).toHaveBeenCalledTimes(1);
    t.boundary.suspend();
    t.boundary.dispose();
    expect(t.boundary.current(current)).toBe(false);
    expect(t.boundary.begin('transition', true)).toBeNull();
    expect(t.storage.setItem).toHaveBeenCalledTimes(1);
  });

  it('requires every owner field, not just request ID', () => {
    const owner = { document: 'doc', epoch: 1, request: 1, revision: 'rev' };
    expect(sameOwner(owner, owner)).toBe(true);
    expect(sameOwner(null, owner)).toBe(false);
    for (const changed of [{ document: 'other' }, { epoch: 2 }, { request: 2 }, { revision: 'other' }]) {
      expect(sameOwner(owner, { ...owner, ...changed })).toBe(false);
    }
  });
});
