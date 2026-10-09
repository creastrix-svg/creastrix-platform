import { createContext } from 'react';
import { createAuthBoundary } from './authBoundary';
import type { BoundaryStorage, Owner } from './authBoundary';

export type User = { id: string; status: 'ACTIVE' };
export type Phase = 'CHECKING' | 'ANONYMOUS' | 'AUTHENTICATED' | 'TRANSITION'
  | 'RECOVERY_REQUIRED' | 'DENIED' | 'UNAVAILABLE';
export type Notice = 'NONE' | 'SESSION_MISSING' | 'PEER_CHANGED' | 'STORAGE_UNAVAILABLE'
  | 'DEV_ONLY' | 'REQUEST_UNAVAILABLE' | 'LOGOUT_UNKNOWN' | 'SECURITY_DENIED'
  | 'LOCAL_LOGOUT_COMPLETED' | 'CLEANUP_ONLY' | 'RECOVERY_READY' | 'RECOVERY_READY_CLEANUP_ONLY'
  | 'LOGIN_NAVIGATION' | 'SUSPENDED';
export type Outcome = { phase: Phase; notice: Notice; user: User | null };
export type Csrf = { token: string; parameterName: '_csrf'; headerName: 'X-CSRF-TOKEN' };
type Kind = 'me' | 'login' | 'logout' | 'recover';
type Operation = { owner: Owner; kind: Kind; abort: AbortController; deadline: Promise<never>; timer: ReturnType<typeof setTimeout> };
export const READ_TIMEOUT_MS = 10_000;
export const ACTION_TIMEOUT_MS = 30_000;

function object(value: unknown, keys: string[]): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)
      || Object.keys(value).length !== keys.length || !keys.every(key => Object.hasOwn(value, key))) throw new Error('AUTH_RESPONSE_INVALID');
  return value as Record<string, unknown>;
}
function user(value: unknown): User {
  const data = object(value, ['id', 'status']);
  if (typeof data.id !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(data.id)
      || data.status !== 'ACTIVE') throw new Error('AUTH_RESPONSE_INVALID');
  return { id: data.id, status: 'ACTIVE' };
}
function csrf(value: unknown): Csrf {
  const data = object(value, ['token', 'parameterName', 'headerName']);
  if (typeof data.token !== 'string' || !/^[A-Za-z0-9_+/=-]{1,2048}$/.test(data.token)
      || data.parameterName !== '_csrf' || data.headerName !== 'X-CSRF-TOKEN') throw new Error('AUTH_RESPONSE_INVALID');
  return { token: data.token, parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };
}
const outcome = (phase: Phase, notice: Notice = 'NONE', ownUser: User | null = null): Outcome => ({ phase, notice, user: ownUser });

/** One document owns its work. Abort does not undo cookies, delivered responses or server mutations. */
export function createAuthentication(options: {
  enabled: boolean;
  fetch: typeof fetch;
  storage: () => BoundaryStorage;
  nonce: () => string;
  closePrivate: () => void;
  openPrivate: () => void;
  started: (owner: Owner, phase: Phase, notice: Notice) => void;
  finished: (owner: Owner, result: Outcome) => void;
}) {
  let active: Operation | null = null;
  let started = false;
  let suspended = false;
  let disposed = false;
  let navigating = false;
  const boundary = createAuthBoundary({
    storage: options.storage, nonce: options.nonce, closePrivate: options.closePrivate,
    invalidated(owner, reason) {
      cancel();
      options.started(owner, reason === 'STORAGE_UNAVAILABLE' ? 'UNAVAILABLE' : 'RECOVERY_REQUIRED',
        reason === 'DISPOSED' ? 'SUSPENDED' : reason);
    },
  });

  function cancel() {
    if (active) { clearTimeout(active.timer); active.abort.abort(); active = null; }
    navigating = false;
  }
  function owns(op: Operation): boolean { return active === op && !disposed && boundary.current(op.owner); }
  function begin(kind: Kind, announce: boolean): Operation | null {
    if (disposed) return null;
    cancel();
    if (!options.enabled) {
      options.closePrivate();
      const disabledOwner = { document: 'disabled', epoch: 0, request: 0, revision: 'disabled' };
      options.started(disabledOwner, 'CHECKING', 'NONE');
      options.finished(disabledOwner, outcome('UNAVAILABLE', 'DEV_ONLY'));
      return null;
    }
    const owner = boundary.begin(kind === 'me' ? 'checking' : 'transition', announce);
    if (!owner) return null;
    options.started(owner, kind === 'me' ? 'CHECKING' : 'TRANSITION', 'NONE');
    const abort = new AbortController();
    let timer!: ReturnType<typeof setTimeout>;
    const deadline = new Promise<never>((_, reject) => {
      abort.signal.addEventListener('abort', () => { reject(new Error('AUTH_REQUEST_CANCELLED')); }, { once: true });
      timer = setTimeout(() => { abort.abort(); reject(new Error('AUTH_REQUEST_TIMEOUT')); },
        kind === 'me' ? READ_TIMEOUT_MS : ACTION_TIMEOUT_MS);
    });
    // Cancellation also bounds a fetch/body implementation that ignores its AbortSignal.
    void deadline.catch(() => undefined);
    const op = { owner, kind, abort, deadline, timer };
    active = op;
    return op;
  }

  function finish(op: Operation, result: Outcome) {
    // No completion marker write/CAS claim. The final check and Redux commit have no await between them.
    if (owns(op)) {
      if (result.phase !== 'AUTHENTICATED') options.closePrivate();
      options.finished(op.owner, result);
    }
  }
  function deny(op: Operation, status: number) {
    finish(op, status === 401 ? outcome('ANONYMOUS', 'SESSION_MISSING')
      : status === 403 ? outcome('DENIED', 'SECURITY_DENIED')
        : outcome('UNAVAILABLE', 'REQUEST_UNAVAILABLE'));
  }
  async function request(op: Operation, path: '/api/me' | '/auth/csrf' | '/auth/logout', token?: Csrf) {
    if (!owns(op)) return null;
    const response = await Promise.race([options.fetch(path, {
      method: token ? 'POST' : 'GET', credentials: 'same-origin', cache: 'no-store', redirect: 'error',
      signal: op.abort.signal, headers: token ? { [token.headerName]: token.token } : undefined,
    }), op.deadline]);
    if (!owns(op)) return null;
    if (response.redirected) throw new Error('AUTH_RESPONSE_INVALID');
    return response;
  }
  async function json(op: Operation, response: Response): Promise<unknown> {
    if (!owns(op)) return undefined;
    const value: unknown = await Promise.race([response.json(), op.deadline]);
    return owns(op) ? value : undefined;
  }
  async function getCsrf(op: Operation) {
    const response = await request(op, '/auth/csrf');
    if (!response) return null;
    if (response.status !== 200) { deny(op, response.status); return null; }
    const payload = await json(op, response);
    return owns(op) ? csrf(payload) : null;
  }
  async function echo(op: Operation) {
    if (!await getCsrf(op) || !owns(op)) return null;
    // The second request actually echoes browser-owned cookies. It is not a retry of a failed action.
    return getCsrf(op);
  }
  async function run(op: Operation, work: () => Promise<void>) {
    try { await work(); }
    catch {
      // Neither a stale error nor an unknown mutation result may be converted into logout success.
      if (owns(op)) finish(op, outcome('UNAVAILABLE', op.kind === 'logout' || op.kind === 'recover' ? 'LOGOUT_UNKNOWN' : 'REQUEST_UNAVAILABLE'));
      if (active === op) navigating = false;
    } finally {
      clearTimeout(op.timer);
      if (active === op) active = null;
    }
  }
  async function check(announce = false) {
    if (navigating) return;
    if (active?.kind !== 'me' && active !== null) return;
    const op = begin('me', announce);
    if (!op) return;
    await run(op, async () => {
      const response = await request(op, '/api/me');
      if (!response) return;
      if (response.status !== 200) { deny(op, response.status); return; }
      const payload = await json(op, response);
      if (owns(op)) finish(op, outcome('AUTHENTICATED', 'NONE', user(payload)));
    });
  }

  async function cleanup(recovery: boolean) {
    if (navigating) return;
    if (active?.kind !== 'me' && active !== null) return;
    const op = begin(recovery ? 'recover' : 'logout', true);
    if (!op) return;
    await run(op, async () => {
      const token = await getCsrf(op);
      if (!token || !owns(op)) return;
      const response = await request(op, '/auth/logout', token);
      if (!response) return;
      const status = response.status;
      if (status !== 204 && status !== 409) { deny(op, status); return; }
      if (status === 204) {
        const body = await Promise.race([response.text(), op.deadline]);
        if (!owns(op)) return;
        if (body !== '') throw new Error('AUTH_RESPONSE_INVALID');
      }
      if (!recovery) {
        finish(op, status === 204 ? outcome('ANONYMOUS', 'LOCAL_LOGOUT_COMPLETED') : outcome('RECOVERY_REQUIRED', 'CLEANUP_ONLY'));
        return;
      }
      if (!owns(op) || !await echo(op) || !owns(op)) return;
      finish(op, outcome('ANONYMOUS', status === 204 ? 'RECOVERY_READY' : 'RECOVERY_READY_CLEANUP_ONLY'));
    });
  }

  return {
    start() { if (started || disposed) return Promise.resolve(); started = true; return check(true); },
    recheck() { return check(); },
    async login(submit: (token: Csrf) => void) {
      if (navigating) return;
      if (active?.kind !== 'me' && active !== null) return;
      const op = begin('login', true);
      if (!op) return;
      await run(op, async () => {
        const token = await echo(op);
        if (!token || !owns(op)) return;
        options.finished(op.owner, outcome('TRANSITION', 'LOGIN_NAVIGATION'));
        if (!owns(op)) return;
        // Only this still-owned synchronous callback may submit a native top-level form.
        navigating = true;
        submit(token);
      });
    },
    logout() { return cleanup(false); },
    recover() { return cleanup(true); },
    reconcile() { boundary.reconcile(); },
    suspend() { suspended = true; boundary.suspend(); },
    resume() {
      if (disposed || !started) return Promise.resolve();
      if (!suspended && active !== null) return Promise.resolve();
      suspended = false;
      return check(); // No shared nonce write: focus/pageshow must not start mutual invalidation loops.
    },
    reveal(owner: Owner) {
      if (!suspended && !disposed && boundary.current(owner)) options.openPrivate();
    },
    dispose() { if (!disposed) { boundary.dispose(); disposed = true; } },
  };
}

export type Authentication = ReturnType<typeof createAuthentication>;
export const AuthenticationContext = createContext<Authentication | null>(null);
