import { afterEach, describe, expect, it, vi } from 'vitest';
import { AUTH_BOUNDARY_KEY } from './authBoundary';
import { ACTION_TIMEOUT_MS, createAuthentication, READ_TIMEOUT_MS } from './authentication';
import type { Authentication, Csrf } from './authentication';
import { authenticationFinished, authenticationStarted, createAppStore } from './store';

const A = { id: '11111111-1111-4111-8111-111111111111', status: 'ACTIVE' } as const;
const B = { id: '22222222-2222-4222-8222-222222222222', status: 'ACTIVE' } as const;
const csrf = (token = 'fixture-csrf') => ({ token, parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' });
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
const json = (body: unknown, status = 200) => Response.json(body, { status });
const status = (code: number) => new Response(null, { status: code });
async function tick() { for (let i = 0; i < 24; i++) await Promise.resolve(); }
const controllers: Authentication[] = [];
afterEach(() => { controllers.splice(0).forEach(controller => controller.dispose()); vi.useRealTimers(); });

function fixture(enabled = true) {
  const requests: { path: RequestInfo | URL; init?: RequestInit; response: ReturnType<typeof deferred<Response>> }[] = [];
  const fetch = vi.fn<typeof globalThis.fetch>((path, init) => {
    const response = deferred<Response>();
    requests.push({ path, init, response });
    return response.promise;
  });
  const values = new Map<string, string>();
  const storage = { getItem: vi.fn((key: string) => values.get(key) ?? null), setItem: vi.fn((key: string, value: string) => { values.set(key, value); }) };
  const store = createAppStore('de', vi.fn());
  const closePrivate = vi.fn();
  const openPrivate = vi.fn();
  let n = 0;
  const controller = createAuthentication({ enabled, fetch, storage: () => storage, nonce: () => 'doc-' + ++n, closePrivate, openPrivate,
    started: (owner, phase, notice) => { store.dispatch(authenticationStarted({ owner, phase, notice })); },
    finished: (owner, result) => { store.dispatch(authenticationFinished({ owner, result })); },
  });
  controllers.push(controller);
  const state = () => store.getState().authentication;
  const reply = async (index: number, response: Response) => { expect(requests[index]).toBeDefined(); requests[index].response.resolve(response); await tick(); };
  const authenticate = async () => { const result = controller.start(); await reply(0, json(A)); await result; };
  const peer = () => { storage.setItem(AUTH_BOUNDARY_KEY, JSON.stringify({ nonce: 'new-peer', phase: 'transition' })); controller.reconcile(); };
  return { controller, requests, fetch, store, storage, closePrivate, openPrivate, state, reply, authenticate, peer };
}

describe('owned current account responses', () => {
  it.each([[200, 'AUTHENTICATED'], [401, 'ANONYMOUS'], [403, 'DENIED'], [503, 'UNAVAILABLE'], [409, 'UNAVAILABLE'], [404, 'UNAVAILABLE']] as const)('current %i becomes %s without invented identity', async (code, phase) => {
    const f = fixture();
    const run = f.controller.start();
    await f.reply(0, code === 200 ? json(A) : status(code));
    await run;
    expect(f.state().phase).toBe(phase);
    expect(f.state().user).toEqual(code === 200 ? A : null);
    expect(f.store.getState().ui.language).toBe('de');
    expect(f.requests[0].path).toBe('/api/me');
    expect(f.requests[0].init).toMatchObject({ method: 'GET', credentials: 'same-origin', cache: 'no-store', redirect: 'error' });
    expect(f.requests[0].init?.headers).toBeUndefined();
    expect(f.storage.setItem).toHaveBeenCalledTimes(1);
    expect(JSON.parse(f.storage.getItem(AUTH_BOUNDARY_KEY)!)).toEqual({ nonce: 'doc-2', phase: 'checking' });
  });

  it.each([null, {}, { ...A, name: 'not-provided' }, { ...A, status: 'SUSPENDED' }, { id: 7, status: 'ACTIVE' }, { id: 'invalid', status: 'ACTIVE' }])('fails closed on invalid own account payload: case %#', async payload => {
    const f = fixture();
    const run = f.controller.start();
    await f.reply(0, json(payload));
    await run;
    expect(f.state()).toMatchObject({ phase: 'UNAVAILABLE', notice: 'REQUEST_UNAVAILABLE', user: null });
  });

  it.each([200, 401, 403, 503])('old A response %i cannot replace or clear a newer B', async code => {
    const f = fixture();
    const old = f.controller.start();
    const newer = f.controller.recheck();
    await f.reply(1, json(B));
    await newer;
    const snapshot = f.state();
    await f.reply(0, code === 200 ? json(A) : status(code));
    await old;
    expect(f.state()).toEqual(snapshot);
    expect(f.state().user).toEqual(B);
    expect(f.requests[0].init?.signal?.aborted).toBe(true);
    expect(f.storage.setItem).toHaveBeenCalledTimes(1);
  });

  it.each(['resolve', 'reject'] as const)('rechecks ownership after a deferred body parse %s, including its failure', async mode => {
    const f = fixture();
    const body = deferred<unknown>();
    const response = json(A);
    const parse = vi.spyOn(response, 'json').mockImplementation(() => body.promise);
    const old = f.controller.start();
    await f.reply(0, response);
    expect(parse).toHaveBeenCalledTimes(1);
    if (mode === 'resolve') body.resolve(A); else body.reject(new Error('parse-failed'));
    // Parsing has settled, but its async continuation has not run. A new owner takes over now.
    const newer = f.controller.recheck();
    await f.reply(1, json(B));
    await Promise.all([old, newer]);
    expect(f.state()).toMatchObject({ phase: 'AUTHENTICATED', user: B });
    const again = f.controller.recheck(); // An old finally must not clear or disable later work.
    await f.reply(2, status(401));
    await again;
    expect(f.state()).toMatchObject({ phase: 'ANONYMOUS', user: null });
  });

  it('an old transport error/finally cannot erase a current in-flight read or B', async () => {
    const f = fixture();
    const old = f.controller.start();
    const newer = f.controller.recheck();
    f.requests[0].response.reject(new Error('network-failed'));
    await old;
    expect(f.state().phase).toBe('CHECKING');
    await f.reply(1, json(B));
    await newer;
    expect(f.state().user).toEqual(B);
  });

  it('a malformed current JSON body, redirect or network failure is not anonymous success', async () => {
    for (const kind of ['json', 'redirect', 'network']) {
      const f = fixture();
      const run = f.controller.start();
      if (kind === 'network') f.requests[0].response.reject(new Error('network-failed'));
      else {
        const response = kind === 'json' ? new Response('{invalid', { status: 200 }) : json(A);
        if (kind === 'redirect') Object.defineProperty(response, 'redirected', { value: true });
        await f.reply(0, response);
      }
      await run;
      expect(f.state()).toMatchObject({ phase: 'UNAVAILABLE', notice: 'REQUEST_UNAVAILABLE', user: null });
      expect(f.requests).toHaveLength(1);
    }
  });

  it('fresh denial after an authenticated account closes it, without changing language', async () => {
    const f = fixture();
    await f.authenticate();
    const run = f.controller.recheck();
    expect(f.state().user).toBeNull();
    expect(f.state().phase).toBe('CHECKING');
    await f.reply(1, status(401));
    await run;
    expect(f.state()).toMatchObject({ phase: 'ANONYMOUS', user: null });
    expect(f.store.getState().ui.language).toBe('de');
  });
});

describe('explicit bounded login, cleanup and recovery', () => {
  it('does two sequential CSRF reads then one synchronous native submission, no fetch login or second action', async () => {
    const f = fixture();
    await f.authenticate();
    const submit = vi.fn<(token: Csrf) => void>();
    const run = f.controller.login(submit);
    expect(f.state()).toMatchObject({ phase: 'TRANSITION', user: null });
    expect(f.requests).toHaveLength(2);
    await Promise.all([f.controller.login(submit), f.controller.logout(), f.controller.recover(), f.controller.recheck()]);
    expect(f.requests).toHaveLength(2);
    await f.reply(1, json(csrf('first-token')));
    expect(f.requests).toHaveLength(3);
    await f.reply(2, json(csrf('rotated-token')));
    await run;
    expect(submit).toHaveBeenCalledExactlyOnceWith(csrf('rotated-token'));
    expect(f.requests.map(request => request.path)).toEqual(['/api/me', '/auth/csrf', '/auth/csrf']);
    expect(f.state()).toMatchObject({ phase: 'TRANSITION', notice: 'LOGIN_NAVIGATION', user: null });
    await f.controller.login(submit);
    await f.controller.recheck();
    expect(f.requests).toHaveLength(3);
    expect(f.storage.setItem).toHaveBeenCalledTimes(2);
    expect(JSON.parse(f.storage.getItem(AUTH_BOUNDARY_KEY)!)).toEqual({ nonce: 'doc-3', phase: 'transition' });
  });

  it.each(['first', 'echo'] as const)('stale %s CSRF parse cannot submit or issue another network step', async stage => {
    const f = fixture();
    await f.authenticate();
    const submit = vi.fn();
    const body = deferred<unknown>();
    const response = json(csrf());
    vi.spyOn(response, 'json').mockImplementation(() => body.promise);
    const run = f.controller.login(submit);
    if (stage === 'echo') await f.reply(1, json(csrf()));
    const index = stage === 'echo' ? 2 : 1;
    await f.reply(index, response);
    f.peer();
    const newer = f.controller.recheck();
    await f.reply(index + 1, json(B));
    body.resolve(csrf());
    await Promise.all([run, newer]);
    expect(submit).not.toHaveBeenCalled();
    expect(f.state().user).toEqual(B);
    expect(f.requests).toHaveLength(index + 2);
  });

  it.each([{ ...csrf(), parameterName: 'other' }, { ...csrf(), headerName: 'other' }, { ...csrf(), token: '' }, { ...csrf(), token: 'x'.repeat(2049) }, { ...csrf(), extra: true }])('rejects malformed CSRF without a POST: case %#', async payload => {
    const f = fixture();
    await f.authenticate();
    const run = f.controller.logout();
    await f.reply(1, json(payload));
    await run;
    expect(f.requests.map(request => request.path)).toEqual(['/api/me', '/auth/csrf']);
    expect(f.state().user).toBeNull();
    expect(f.state().phase).toBe('UNAVAILABLE');
  });

  it.each([401, 403, 409, 503])('CSRF refusal %i never implies cleanup or triggers the next login request', async code => {
    const f = fixture();
    await f.authenticate();
    const submit = vi.fn();
    const run = f.controller.login(submit);
    await f.reply(1, status(code));
    await run;
    expect(submit).not.toHaveBeenCalled();
    expect(f.requests).toHaveLength(2);
    expect(f.state().user).toBeNull();
    expect(f.state().notice).not.toBe('CLEANUP_ONLY');
    expect(f.state().notice).not.toBe('LOCAL_LOGOUT_COMPLETED');
  });

  it.each([[204, 'ANONYMOUS', 'LOCAL_LOGOUT_COMPLETED'], [409, 'RECOVERY_REQUIRED', 'CLEANUP_ONLY'],
    [403, 'DENIED', 'SECURITY_DENIED'], [503, 'UNAVAILABLE', 'REQUEST_UNAVAILABLE']] as const)('logout %i is %s / %s, never upgraded to broader logout', async (code, phase, notice) => {
    const f = fixture();
    await f.authenticate();
    const run = f.controller.logout();
    await f.reply(1, json(csrf('fresh-csrf')));
    expect(f.requests[2]).toMatchObject({ path: '/auth/logout', init: { method: 'POST', headers: { 'X-CSRF-TOKEN': 'fresh-csrf' } } });
    await f.reply(2, status(code));
    await run;
    expect(f.state()).toMatchObject({ phase, notice, user: null });
    expect(f.requests).toHaveLength(3);
  });

  it.each([204, 409])('recovery %i waits for cleanup then bootstrap+echo, leaving full login explicit', async code => {
    const f = fixture();
    await f.authenticate();
    const run = f.controller.recover();
    await f.reply(1, json(csrf()));
    expect(f.requests).toHaveLength(3);
    await f.reply(2, status(code));
    expect(f.requests).toHaveLength(4);
    await f.reply(3, json(csrf()));
    expect(f.requests).toHaveLength(5);
    await f.reply(4, json(csrf('after-cleanup')));
    await run;
    expect(f.requests.map(request => request.path)).toEqual(['/api/me', '/auth/csrf', '/auth/logout', '/auth/csrf', '/auth/csrf']);
    expect(f.state()).toMatchObject({ phase: 'ANONYMOUS', notice: code === 204 ? 'RECOVERY_READY' : 'RECOVERY_READY_CLEANUP_ONLY', user: null });
  });

  it.each([403, 503])('recovery cleanup %i stops without further bootstrap or automatic login', async code => {
    const f = fixture();
    await f.authenticate();
    const run = f.controller.recover();
    await f.reply(1, json(csrf()));
    await f.reply(2, status(code));
    await run;
    expect(f.requests).toHaveLength(3);
    expect(f.state().phase).not.toBe('ANONYMOUS');
  });

  it.each(['logout', 'recover'] as const)('old %s 204/409/503 completion cannot alter newer B; it does not undo server effects', async action => {
    for (const code of [204, 409, 503]) {
      const f = fixture();
      await f.authenticate();
      const old = f.controller[action]();
      await f.reply(1, json(csrf()));
      f.peer();
      const newer = f.controller.recheck();
      await f.reply(3, json(B));
      await newer;
      await f.reply(2, status(code));
      await old;
      expect(f.state()).toMatchObject({ phase: 'AUTHENTICATED', user: B });
      expect(f.requests).toHaveLength(4);
    }
  });

  it('a stale recovery bootstrap parse/error cannot issue its echo or erase B', async () => {
    const f = fixture();
    await f.authenticate();
    const old = f.controller.recover();
    await f.reply(1, json(csrf()));
    await f.reply(2, status(204));
    const body = deferred<unknown>();
    const response = json(csrf());
    vi.spyOn(response, 'json').mockImplementation(() => body.promise);
    await f.reply(3, response);
    f.peer();
    const newer = f.controller.recheck();
    await f.reply(4, json(B));
    body.reject(new Error('old-parse-failed'));
    await Promise.all([old, newer]);
    expect(f.state().user).toEqual(B);
    expect(f.requests).toHaveLength(5);
  });

  it('unknown logout transport failure is not success, not retried, and explicit recheck remains available', async () => {
    const f = fixture();
    await f.authenticate();
    const run = f.controller.logout();
    await f.reply(1, json(csrf()));
    f.requests[2].response.reject(new Error('delivery-unknown'));
    await run;
    expect(f.state()).toMatchObject({ phase: 'UNAVAILABLE', notice: 'LOGOUT_UNKNOWN', user: null });
    expect(f.requests).toHaveLength(3);
    const recheck = f.controller.recheck();
    await f.reply(3, status(401));
    await recheck;
    expect(f.state().phase).toBe('ANONYMOUS');
  });

  it('a native submission failure does not leave the controller permanently in navigation', async () => {
    const f = fixture();
    await f.authenticate();
    const run = f.controller.login(() => { throw new Error('submit-failed'); });
    await f.reply(1, json(csrf()));
    await f.reply(2, json(csrf()));
    await run;
    expect(f.state()).toMatchObject({ phase: 'UNAVAILABLE', user: null });
    const recheck = f.controller.recheck();
    await f.reply(3, status(401));
    await recheck;
    expect(f.state().phase).toBe('ANONYMOUS');
  });
});

describe('finite lifecycle, shared storage and deadlines', () => {
  it('starts once, does not publish on completion/recheck/resume, and closes before current revalidation', async () => {
    const f = fixture();
    const first = f.controller.start();
    await f.controller.start();
    await f.reply(0, json(A));
    await first;
    const previous = f.state().owner!;
    f.controller.reveal(previous);
    expect(f.openPrivate).toHaveBeenCalledTimes(1);
    const closes = f.closePrivate.mock.calls.length;
    f.controller.suspend();
    expect(f.closePrivate).toHaveBeenCalledTimes(closes + 1);
    expect(f.state().user).toBeNull();
    f.controller.reveal(previous);
    expect(f.openPrivate).toHaveBeenCalledTimes(1);
    const restored = f.controller.resume();
    expect(f.state().phase).toBe('CHECKING');
    await f.reply(1, json(B));
    await restored;
    f.controller.reveal(f.state().owner!);
    expect(f.openPrivate).toHaveBeenCalledTimes(2);
    expect(f.storage.setItem).toHaveBeenCalledTimes(1);
    f.controller.dispose();
    f.controller.reveal(f.state().owner!);
    await f.controller.resume();
    await f.controller.recheck();
    expect(f.requests).toHaveLength(2);
    expect(f.openPrivate).toHaveBeenCalledTimes(2);
  });

  it('peer changes without an event are caught before a completion can become authenticated', async () => {
    const f = fixture();
    const run = f.controller.start();
    f.storage.setItem(AUTH_BOUNDARY_KEY, JSON.stringify({ nonce: 'peer', phase: 'checking' }));
    await f.reply(0, json(A));
    await run;
    expect(f.state()).toMatchObject({ phase: 'RECOVERY_REQUIRED', notice: 'PEER_CHANGED', user: null });
    expect(f.requests).toHaveLength(1);
    expect(f.storage.setItem).toHaveBeenCalledTimes(2);
  });

  it('shared storage failure disables authentication with no fallback publication or fetch', async () => {
    const f = fixture();
    f.storage.setItem.mockImplementation(() => { throw new Error('storage-blocked'); });
    await f.controller.start();
    expect(f.state()).toMatchObject({ phase: 'UNAVAILABLE', notice: 'STORAGE_UNAVAILABLE', user: null });
    expect(f.fetch).not.toHaveBeenCalled();
  });

  it('disconnected preview does not touch auth storage or make a request', async () => {
    const f = fixture(false);
    await f.controller.start();
    await f.controller.login(vi.fn());
    expect(f.state()).toMatchObject({ phase: 'UNAVAILABLE', notice: 'DEV_ONLY', user: null });
    expect(f.fetch).not.toHaveBeenCalled();
    expect(f.storage.getItem).not.toHaveBeenCalled();
    expect(f.storage.setItem).not.toHaveBeenCalled();
  });

  it.each(['read', 'logout', 'parse'] as const)('bounds a nonresponsive %s even when it ignores abort; no automatic retry', async kind => {
    vi.useFakeTimers();
    const f = fixture();
    let run: Promise<void>;
    if (kind === 'logout') {
      await f.authenticate();
      run = f.controller.logout();
      await f.reply(1, json(csrf()));
    } else {
      run = f.controller.start();
      if (kind === 'parse') {
        const response = json(A);
        vi.spyOn(response, 'json').mockImplementation(() => deferred<unknown>().promise);
        await f.reply(0, response);
      }
    }
    await vi.advanceTimersByTimeAsync(kind === 'logout' ? ACTION_TIMEOUT_MS : READ_TIMEOUT_MS);
    await run;
    expect(f.state()).toMatchObject({ phase: 'UNAVAILABLE', notice: kind === 'logout' ? 'LOGOUT_UNKNOWN' : 'REQUEST_UNAVAILABLE', user: null });
    expect(f.requests.at(-1)?.init?.signal?.aborted).toBe(true);
    expect(f.requests).toHaveLength(kind === 'logout' ? 3 : 1);
    expect(vi.getTimerCount()).toBe(0);
  });
});
