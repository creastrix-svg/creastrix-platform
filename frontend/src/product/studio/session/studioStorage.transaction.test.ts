import { afterEach, expect, it, vi } from 'vitest';
import { studioStorage } from './studioStorage';

// Controlled IDB lifecycle events test transaction ownership; actual browser
// storage/reload remains a separate browser gate, not claimed by this fake.
function controlled() {
  const opened: { onsuccess?: () => void; onblocked?: () => void; result: unknown }[] = [];
  const transactions: ReturnType<typeof makeTransaction>[] = [];
  function makeTransaction() {
    const request = { result: undefined as unknown, onsuccess: undefined as (() => void) | undefined };
    const store = { get: vi.fn(() => request), put: vi.fn() };
    const tx = { request, store, objectStore: () => store, oncomplete: undefined as (() => void) | undefined,
      onabort: undefined as (() => void) | undefined, onerror: undefined as (() => void) | undefined, abort: vi.fn(() => tx.onabort?.()) };
    return tx;
  }
  const db = { close: vi.fn(), transaction: vi.fn(() => { const tx = makeTransaction(); transactions.push(tx); return tx; }), onversionchange: undefined };
  const factory = { open: vi.fn(() => { const request = { result: db }; opened.push(request); return request; }) };
  vi.stubGlobal('indexedDB', factory);
  return { opened, transactions, db, factory, async open(index = 0) { await Promise.resolve(); opened[index].onsuccess?.(); return transactions.at(-1)!; } };
}
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); });
it('acknowledges a save only after transaction completion, never the read or put request', async () => {
  const c = controlled(), done = vi.fn(); const operation = studioStorage('write', 'new', 'old').then(done);
  const tx = await c.open(); tx.request.result = 'old'; tx.request.onsuccess?.();
  expect(tx.store.put).toHaveBeenCalledWith('new', 'current');
  await Promise.resolve(); expect(done).not.toHaveBeenCalled();
  tx.oncomplete?.(); await operation;
  expect(done).toHaveBeenCalledWith('old'); expect(c.db.close).toHaveBeenCalledTimes(1);
});
it('does not overwrite a concurrent tab change and releases the queue after abort', async () => {
  const c = controlled(); const operation = studioStorage('write', 'mine', 'expected');
  const failed = expect(operation).rejects.toThrow('STUDIO_STORAGE_TRANSACTION');
  const tx = await c.open(); tx.request.result = 'other-tab'; tx.request.onsuccess?.();
  await failed; expect(tx.store.put).not.toHaveBeenCalled(); expect(tx.abort).toHaveBeenCalledOnce();
  const read = studioStorage('read'); const later = await c.open(1); later.request.result = 'other-tab'; later.request.onsuccess?.(); later.oncomplete?.();
  await expect(read).resolves.toBe('other-tab');
});
it('rejects a put followed by transaction abort and leaves the committed record unchanged', async () => {
  const c = controlled(), operation = studioStorage('write', 'A', 'S'), failed = expect(operation).rejects.toThrow('STUDIO_STORAGE_TRANSACTION');
  const tx = await c.open(); tx.request.result = 'S'; tx.request.onsuccess?.();
  expect(tx.store.put).toHaveBeenCalledWith('A', 'current'); tx.onabort?.(); await failed;
  const read = studioStorage('read'), next = await c.open(1); next.request.result = 'S'; next.request.onsuccess?.(); next.oncomplete?.();
  await expect(read).resolves.toBe('S');
});
it('queues a remount read behind the previous storefront write', async () => {
  const c = controlled(), write = studioStorage('write', 'A', null), read = studioStorage('read');
  const first = await c.open(); first.request.onsuccess?.();
  expect(c.factory.open).toHaveBeenCalledTimes(1);
  first.oncomplete?.(); await write;
  const next = await c.open(1); next.request.result = 'A'; next.request.onsuccess?.(); next.oncomplete?.();
  await expect(read).resolves.toBe('A'); expect(c.factory.open).toHaveBeenCalledTimes(2);
});
it('rejects a non-string stored record without replacing it', async () => {
  const c = controlled(), operation = studioStorage('write', 'new', null), failed = expect(operation).rejects.toThrow();
  const tx = await c.open(); tx.request.result = { corrupt: true }; tx.request.onsuccess?.();
  await failed; expect(tx.store.put).not.toHaveBeenCalled();
});
it('bounds an open timeout and closes a late connection without writing', async () => {
  vi.useFakeTimers(); const c = controlled(), operation = studioStorage('write', 'new', null), failed = expect(operation).rejects.toThrow('STUDIO_STORAGE_TIMEOUT');
  await Promise.resolve(); await vi.advanceTimersByTimeAsync(5000); await failed;
  c.opened[0].onsuccess?.(); expect(c.db.close).toHaveBeenCalledOnce(); expect(c.db.transaction).not.toHaveBeenCalled();
});
it('aborts its stalled transaction rather than reporting a successful request as saved', async () => {
  vi.useFakeTimers(); const c = controlled(), operation = studioStorage('write', 'new', null), failed = expect(operation).rejects.toThrow();
  const tx = await c.open(); tx.request.onsuccess?.();
  await vi.advanceTimersByTimeAsync(5000); await failed; expect(tx.abort).toHaveBeenCalledOnce();
});
