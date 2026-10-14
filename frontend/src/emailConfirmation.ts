import { createContext } from 'react';

export type ConfirmationResult = 'IDLE' | 'PENDING' | 'CONFIRMED' | 'CODE_REQUEST_ACCEPTED'
  | 'INVALID_INPUT' | 'DENIED' | 'NOT_COMPLETED' | 'RATE_LIMITED' | 'UNKNOWN' | 'DISABLED';
export const CONFIRMATION_TIMEOUT_MS = 30_000;

// An injectable transport keeps finite DOM tests disconnected from real accounts.
export const EmailConfirmationContext = createContext<{ enabled: boolean; fetch: typeof fetch } | null>(null);

function forbidden(value: string) {
  return /\s/u.test(value) || [...value].some(character => {
    const code = character.charCodeAt(0);
    return code <= 0x1f || (code >= 0x7f && code <= 0x9f);
  });
}
export function validConfirmationEmail(email: string) { return email.length >= 1 && email.length <= 128 && !forbidden(email); }
export function validConfirmationCode(code: string) { return code.length >= 1 && code.length <= 2048 && !forbidden(code); }

function exactObject(value: unknown, keys: string[]): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)
      || Object.keys(value).length !== keys.length || !keys.every(key => Object.hasOwn(value, key))) {
    throw new Error('CONFIRMATION_RESPONSE_INVALID');
  }
  return value as Record<string, unknown>;
}
function csrfToken(value: unknown): string {
  const data = exactObject(value, ['token', 'parameterName', 'headerName']);
  if (typeof data.token !== 'string' || !/^[A-Za-z0-9_+/=-]{1,2048}$/.test(data.token)
      || data.parameterName !== '_csrf' || data.headerName !== 'X-CSRF-TOKEN') {
    throw new Error('CONFIRMATION_RESPONSE_INVALID');
  }
  return data.token;
}
function denial(status: number): ConfirmationResult {
  return status === 403 ? 'DENIED' : status === 400 ? 'NOT_COMPLETED' : status === 429 ? 'RATE_LIMITED' : 'UNKNOWN';
}

type Action = 'confirm' | 'resend';
type Payload = { email: string; code?: string };
type Reader = ReadableStreamDefaultReader<Uint8Array>;
type Operation = { epoch: number; abort: AbortController; deadline: Promise<never>; timer: ReturnType<typeof setTimeout>; payload: Payload; reader: Reader | null };

function discardBody(response: Response) {
  try { void response.body?.cancel().catch(() => undefined); } catch { /* Best-effort resource control, not rollback. */ }
}
function releaseReader(reader: Reader, cancel: boolean) {
  // A foreign stream's cancel promise may never settle; ownership must not wait for it.
  if (cancel) { try { void reader.cancel().catch(() => undefined); } catch { /* Ownership is already discarded. */ } }
  try { reader.releaseLock(); } catch { /* A non-cooperative reader must not block cancellation. */ }
}

/** Provider-only work has no dependency on the authentication controller's Q/intent state.
 * Abort bounds resources; it neither rolls back a provider action nor proves non-delivery. */
export function createEmailConfirmation(options: {
  enabled: () => boolean;
  fetch: typeof fetch;
  changed: (result: ConfirmationResult) => void;
}) {
  let epoch = 0;
  let active: Operation | null = null;
  let disposed = false;

  function erase(payload: Payload) { payload.email = ''; payload.code = undefined; }
  function cancel() {
    epoch++;
    if (active) {
      const old = active;
      active = null;
      erase(old.payload);
      if (old.reader) { releaseReader(old.reader, true); old.reader = null; }
      clearTimeout(old.timer);
      old.abort.abort();
    }
  }
  function owns(op: Operation) { return !disposed && active === op && epoch === op.epoch && options.enabled(); }
  function finish(op: Operation, result: ConfirmationResult) { if (owns(op)) options.changed(result); }
  async function request(op: Operation, path: '/auth/csrf' | '/auth/confirm-email' | '/auth/resend-confirmation-code', token?: string) {
    if (!owns(op)) return null;
    const body = token ? JSON.stringify(op.payload) : undefined;
    if (token) erase(op.payload);
    const fetched = options.fetch(path, {
      method: token ? 'POST' : 'GET', credentials: 'same-origin', cache: 'no-store', redirect: 'error',
      signal: op.abort.signal, headers: token ? { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': token } : undefined,
      body,
    });
    // A transport ignoring abort may settle after the race; discard that unowned body too.
    void fetched.then(response => { if (!owns(op)) discardBody(response); }, () => undefined);
    const response = await Promise.race([fetched, op.deadline]);
    if (!owns(op)) { discardBody(response); return null; }
    if (response.redirected) { discardBody(response); throw new Error('CONFIRMATION_RESPONSE_INVALID'); }
    return response;
  }
  async function json(op: Operation, response: Response): Promise<unknown> {
    if (!owns(op)) return undefined;
    if (!response.body) throw new Error('CONFIRMATION_RESPONSE_INVALID');
    const reader = response.body.getReader();
    op.reader = reader;
    const decoder = new TextDecoder('utf-8', { fatal: true });
    let bytes = 0;
    let body = '';
    let complete = false;
    try {
      while (owns(op)) {
        const chunk = await Promise.race([reader.read(), op.deadline]);
        if (!owns(op)) return undefined;
        if (chunk.done) { complete = true; break; }
        bytes += chunk.value.byteLength;
        if (bytes > 8192) throw new Error('CONFIRMATION_RESPONSE_INVALID');
        body += decoder.decode(chunk.value, { stream: true });
      }
      if (!owns(op)) return undefined;
      body += decoder.decode();
      return JSON.parse(body) as unknown;
    } finally {
      if (op.reader === reader) { op.reader = null; releaseReader(reader, !complete); }
    }
  }
  async function run(action: Action, payload: Payload) {
    if (disposed || active) { erase(payload); return; }
    if (!options.enabled()) { erase(payload); options.changed('DISABLED'); return; }
    if (!validConfirmationEmail(payload.email) || (action === 'confirm' && !validConfirmationCode(payload.code ?? ''))) {
      erase(payload); options.changed('INVALID_INPUT'); return;
    }
    const abort = new AbortController();
    let timer!: ReturnType<typeof setTimeout>;
    const deadline = new Promise<never>((_, reject) => {
      abort.signal.addEventListener('abort', () => { reject(new Error('CONFIRMATION_REQUEST_STOPPED')); }, { once: true });
      timer = setTimeout(() => { abort.abort(); }, CONFIRMATION_TIMEOUT_MS);
    });
    void deadline.catch(() => undefined);
    const op: Operation = { epoch: ++epoch, abort, deadline, timer, payload, reader: null };
    active = op;
    options.changed('PENDING');
    try {
      // Exactly one fresh CSRF read belongs to each explicit action; no echo/retry or live-Q gate.
      const csrf = await request(op, '/auth/csrf');
      if (!csrf) return;
      if (csrf.status !== 200) { discardBody(csrf); finish(op, denial(csrf.status)); return; }
      const token = csrfToken(await json(op, csrf));
      if (!owns(op)) return;
      const response = await request(op, action === 'confirm' ? '/auth/confirm-email' : '/auth/resend-confirmation-code', token);
      if (!response) return;
      const expectedStatus = action === 'confirm' ? 200 : 202;
      if (response.status !== expectedStatus) { discardBody(response); finish(op, denial(response.status)); return; }
      const value = await json(op, response);
      if (!owns(op)) return;
      const data = exactObject(value, ['result']);
      if (data.result !== (action === 'confirm' ? 'confirmed' : 'code_request_accepted')) {
        throw new Error('CONFIRMATION_RESPONSE_INVALID');
      }
      finish(op, action === 'confirm' ? 'CONFIRMED' : 'CODE_REQUEST_ACCEPTED');
    } catch {
      // Lost acknowledgements are UNKNOWN, never proof that the action did not happen.
      finish(op, 'UNKNOWN');
    } finally {
      erase(op.payload);
      clearTimeout(op.timer);
      if (active === op) active = null;
    }
  }
  return {
    confirm(email: string, code: string) { return run('confirm', { email, code }); },
    resend(email: string) { return run('resend', { email }); },
    cancel,
    dispose() { cancel(); disposed = true; },
  };
}
