import { afterEach, describe, expect, it, vi } from 'vitest';
import { CONFIRMATION_TIMEOUT_MS, createEmailConfirmation, validConfirmationCode, validConfirmationEmail } from './emailConfirmation';
import type { ConfirmationResult } from './emailConfirmation';

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
const csrf = (token = 'fixture-token') => Response.json({ token, parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' });
const confirmed = () => Response.json({ result: 'confirmed' });
const accepted = () => Response.json({ result: 'code_request_accepted' }, { status: 202 });
async function tick() { for (let i = 0; i < 24; i++) await Promise.resolve(); }
function streamedResponse(status = 200, cleanup: Promise<void> = Promise.resolve()) {
  let controller!: ReadableStreamDefaultController<Uint8Array>;
  const cancel = vi.fn(() => cleanup);
  const response = new Response(new ReadableStream<Uint8Array>({ start(next) { controller = next; }, cancel }), { status });
  return {
    response, cancel,
    chunk(bytes: Uint8Array) { controller.enqueue(bytes); },
    complete(text: string) {
      try { controller.enqueue(new TextEncoder().encode(text)); controller.close(); return true; }
      catch { return false; } // A cancelled real stream can no longer accept a late chunk.
    },
    reject() { controller.error(new Error('fixture-body-failed')); },
  };
}
const clients: ReturnType<typeof createEmailConfirmation>[] = [];
afterEach(() => { clients.splice(0).forEach(client => client.dispose()); vi.useRealTimers(); });

function fixture(initialEnabled = true) {
  let enabled = initialEnabled;
  const requests: { path: RequestInfo | URL; init?: RequestInit; response: ReturnType<typeof deferred<Response>> }[] = [];
  const fetch = vi.fn<typeof globalThis.fetch>((path, init) => {
    const response = deferred<Response>();
    requests.push({ path, init, response });
    return response.promise;
  });
  const changed = vi.fn<(result: ConfirmationResult) => void>();
  const client = createEmailConfirmation({ enabled: () => enabled, fetch, changed });
  clients.push(client);
  const reply = async (index: number, response: Response) => { requests[index].response.resolve(response); await tick(); };
  return { client, fetch, requests, changed, reply, setEnabled: (next: boolean) => { enabled = next; } };
}

describe('bounded provider-only confirmation transport (finite mocks, not real provider evidence)', () => {
  it('preserves email case and sends only the two fixed POST payloads, each with exactly one fresh CSRF read', async () => {
    const f = fixture();
    const first = f.client.confirm('MixedCase@example.test', 'Ab9');
    expect(f.requests).toHaveLength(1);
    await f.reply(0, csrf('token-one'));
    expect(f.requests[1].init).toMatchObject({
      method: 'POST', credentials: 'same-origin', cache: 'no-store', redirect: 'error',
      headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': 'token-one' },
      body: JSON.stringify({ email: 'MixedCase@example.test', code: 'Ab9' }),
    });
    await f.reply(1, confirmed());
    await first;
    expect(f.changed).toHaveBeenLastCalledWith('CONFIRMED');
    const second = f.client.resend('OtherCase@example.test');
    await f.reply(2, csrf('token-two'));
    expect(f.requests[3].init?.body).toBe(JSON.stringify({ email: 'OtherCase@example.test' }));
    expect(f.requests[3].init?.headers).toEqual({ 'Content-Type': 'application/json', 'X-CSRF-TOKEN': 'token-two' });
    await f.reply(3, accepted());
    await second;
    expect(f.changed).toHaveBeenLastCalledWith('CODE_REQUEST_ACCEPTED');
    expect(f.requests.map(request => request.path)).toEqual(['/auth/csrf', '/auth/confirm-email', '/auth/csrf', '/auth/resend-confirmation-code']);
    for (const index of [0, 2]) expect(f.requests[index].init).toMatchObject({ method: 'GET', credentials: 'same-origin', cache: 'no-store', redirect: 'error', body: undefined, headers: undefined });
  });

  it.each(['', 'a'.repeat(129), ' padded@example.test', 'padded@example.test ', 'two words@example.test', 'line\n@example.test', 'a\u0000b', 'a\u0085b', 'a\u00a0b'])('rejects unsafe email input locally: case %#', async email => {
    const f = fixture();
    expect(validConfirmationEmail(email)).toBe(false);
    await f.client.confirm(email, '123');
    await f.client.resend(email);
    expect(f.changed).toHaveBeenLastCalledWith('INVALID_INPUT');
    expect(f.fetch).not.toHaveBeenCalled();
  });

  it.each(['', 'x'.repeat(2049), ' 123', '123 ', '12 3', '1\n2', '1\u00002', '1\u00852'])('rejects unsafe confirmation code locally: case %#', async code => {
    const f = fixture();
    expect(validConfirmationCode(code)).toBe(false);
    await f.client.confirm('valid@example.test', code);
    expect(f.changed).toHaveBeenLastCalledWith('INVALID_INPUT');
    expect(f.fetch).not.toHaveBeenCalled();
  });

  it('accepts the exact safe length bounds without normalization or requiring a guessed email format', () => {
    expect(validConfirmationEmail('A')).toBe(true);
    expect(validConfirmationEmail('A'.repeat(128))).toBe(true);
    expect(validConfirmationCode('A')).toBe(true);
    expect(validConfirmationCode('A'.repeat(2048))).toBe(true);
  });

  it('is disabled without even a CSRF call, and disposed clients cannot start work', async () => {
    const f = fixture(false);
    await f.client.confirm('case@example.test', '123');
    await f.client.resend('case@example.test');
    expect(f.changed).toHaveBeenLastCalledWith('DISABLED');
    f.setEnabled(true);
    f.client.dispose();
    await f.client.confirm('case@example.test', '123');
    expect(f.fetch).not.toHaveBeenCalled();
  });

  it('allows only one in-flight action and never retries a pending action', async () => {
    const f = fixture();
    const first = f.client.confirm('first@example.test', '123');
    await f.client.confirm('other@example.test', '456');
    await f.client.resend('other@example.test');
    expect(f.requests).toHaveLength(1);
    await f.reply(0, csrf());
    await f.reply(1, confirmed());
    await first;
    expect(f.changed.mock.calls.map(([result]) => result)).toEqual(['PENDING', 'CONFIRMED']);
  });

  it.each([[400, 'NOT_COMPLETED'], [403, 'DENIED'], [429, 'RATE_LIMITED'], [503, 'UNKNOWN'], [401, 'UNKNOWN'], [409, 'UNKNOWN'], [500, 'UNKNOWN']] as const)('maps POST %i to %s without reading or exposing opaque recipient/provider error bodies', async (status, result) => {
    const f = fixture();
    const run = f.client.confirm('case@example.test', '123');
    await f.reply(0, csrf());
    const response = Response.json({ error: 'private-recipient-provider-message' }, { status });
    const read = vi.spyOn(response, 'text');
    const streamRead = vi.spyOn(response.body!, 'getReader');
    await f.reply(1, response);
    await run;
    expect(f.changed).toHaveBeenLastCalledWith(result);
    expect(read).not.toHaveBeenCalled();
    expect(streamRead).not.toHaveBeenCalled();
    expect(f.requests).toHaveLength(2);
  });

  it.each([[400, 'NOT_COMPLETED'], [403, 'DENIED'], [429, 'RATE_LIMITED'], [503, 'UNKNOWN']] as const)('CSRF %i is %s and never advances to a provider POST', async (status, result) => {
    const f = fixture();
    const run = f.client.resend('case@example.test');
    await f.reply(0, new Response('opaque-private-error', { status }));
    await run;
    expect(f.changed).toHaveBeenLastCalledWith(result);
    expect(f.requests).toHaveLength(1);
  });

  it.each([null, [], {}, { token: 't', parameterName: '_csrf' }, { token: 't', parameterName: '_csrf', headerName: 'Wrong' },
    { token: 't', parameterName: 'Wrong', headerName: 'X-CSRF-TOKEN' }, { token: 'bad token', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' },
    { token: 't', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN', recipient: 'private' }])('malformed CSRF is UNKNOWN without POST: case %#', async payload => {
    const f = fixture();
    const run = f.client.confirm('case@example.test', '123');
    await f.reply(0, Response.json(payload));
    await run;
    expect(f.changed).toHaveBeenLastCalledWith('UNKNOWN');
    expect(f.requests).toHaveLength(1);
  });

  it.each(['confirm', 'resend'] as const)('requires the exact %s success body, not arbitrary JSON or SPA HTML', async action => {
    for (const body of ['<html>SPA fallback</html>', '{invalid', 'null', '[]', '{}', '{"result":"other"}', '{"result":"confirmed","email":"private"}', 'x'.repeat(8193)]) {
      const f = fixture();
      const run = action === 'confirm' ? f.client.confirm('case@example.test', '123') : f.client.resend('case@example.test');
      await f.reply(0, csrf());
      await f.reply(1, new Response(body, { status: action === 'confirm' ? 200 : 202 }));
      await run;
      expect(f.changed).toHaveBeenLastCalledWith('UNKNOWN');
      expect(f.changed).not.toHaveBeenCalledWith('CONFIRMED');
      expect(f.changed).not.toHaveBeenCalledWith('CODE_REQUEST_ACCEPTED');
    }
  });

  it.each(['confirm', 'resend'] as const)('rejects the other action’s success status and schema for %s', async action => {
    const f = fixture();
    const run = action === 'confirm' ? f.client.confirm('case@example.test', '123') : f.client.resend('case@example.test');
    await f.reply(0, csrf());
    await f.reply(1, action === 'confirm' ? accepted() : confirmed());
    await run;
    expect(f.changed).toHaveBeenLastCalledWith('UNKNOWN');
  });

  it.each(['csrf', 'post'] as const)('rejects a redirected %s response without following it', async stage => {
    const f = fixture();
    const run = f.client.confirm('case@example.test', '123');
    if (stage === 'post') await f.reply(0, csrf());
    const response = stage === 'csrf' ? csrf() : confirmed();
    Object.defineProperty(response, 'redirected', { value: true });
    await f.reply(stage === 'csrf' ? 0 : 1, response);
    await run;
    expect(f.changed).toHaveBeenLastCalledWith('UNKNOWN');
    expect(f.requests).toHaveLength(stage === 'csrf' ? 1 : 2);
  });

  it.each(['csrf-fetch', 'csrf-body', 'post-fetch', 'post-body'] as const)('bounds the whole action including delayed %s; timeout is UNKNOWN, not rollback or an automatic retry', async stage => {
    vi.useFakeTimers();
    const f = fixture();
    const body = streamedResponse();
    const run = f.client.confirm('case@example.test', '123');
    if (stage === 'csrf-body') {
      await f.reply(0, body.response);
    }
    if (stage === 'post-fetch' || stage === 'post-body') await f.reply(0, csrf());
    if (stage === 'post-body') {
      await f.reply(1, body.response);
    }
    await vi.advanceTimersByTimeAsync(CONFIRMATION_TIMEOUT_MS);
    await run;
    expect(f.changed).toHaveBeenLastCalledWith('UNKNOWN');
    expect(f.requests[0].init?.signal?.aborted).toBe(true);
    expect(f.requests).toHaveLength(stage.startsWith('post') ? 2 : 1);
    if (stage.endsWith('body')) expect(body.cancel).toHaveBeenCalledTimes(1);
    body.complete('{"result":"confirmed"}');
    await tick();
    expect(f.changed).toHaveBeenLastCalledWith('UNKNOWN');
  });

  it.each(['csrf', 'post'] as const)('lost %s transport acknowledgement remains UNKNOWN without exposing/logging input or error text', async stage => {
    const log = vi.spyOn(console, 'log');
    const error = vi.spyOn(console, 'error');
    const write = vi.spyOn(Storage.prototype, 'setItem');
    const f = fixture();
    const run = f.client.confirm('private@example.test', 'private-code');
    if (stage === 'post') await f.reply(0, csrf());
    f.requests[stage === 'csrf' ? 0 : 1].response.reject(new Error('private-code and provider-detail'));
    await run;
    expect(f.changed.mock.calls.map(([result]) => result)).toEqual(['PENDING', 'UNKNOWN']);
    expect(log).not.toHaveBeenCalled();
    expect(error).not.toHaveBeenCalled();
    expect(write).not.toHaveBeenCalled();
  });

  it.each(['success', 'failure'] as const)('cancelled old POST fetch %s cannot update the newer email/action', async mode => {
    const f = fixture();
    const old = f.client.confirm('old@example.test', 'old-code');
    await f.reply(0, csrf());
    f.client.cancel();
    const next = f.client.resend('NewCase@example.test');
    await f.reply(2, csrf('new-token'));
    await f.reply(3, accepted());
    await next;
    const calls = [...f.changed.mock.calls];
    if (mode === 'success') await f.reply(1, confirmed());
    else f.requests[1].response.reject(new Error('old-error'));
    await old;
    await tick();
    expect(f.changed.mock.calls).toEqual(calls);
    expect(f.requests[1].init?.signal?.aborted).toBe(true);
    expect(f.requests[3].init?.body).toBe(JSON.stringify({ email: 'NewCase@example.test' }));
  });

  it.each(['resolve', 'reject'] as const)('cancelled old success body %s cannot overwrite a newer result, even just after the old body settles', async mode => {
    const f = fixture();
    const body = streamedResponse();
    const old = f.client.confirm('old@example.test', 'old-code');
    await f.reply(0, csrf());
    await f.reply(1, body.response);
    if (mode === 'resolve') body.complete('{"result":"confirmed"}'); else body.reject();
    f.client.cancel();
    const next = f.client.resend('new@example.test');
    await f.reply(2, csrf());
    await f.reply(3, accepted());
    await Promise.all([old, next]);
    expect(f.changed.mock.calls.map(([result]) => result)).toEqual(['PENDING', 'PENDING', 'CODE_REQUEST_ACCEPTED']);
  });

  it('cancel during CSRF body prevents POST and disabled permission at completion discards a late result', async () => {
    const f = fixture();
    const body = streamedResponse();
    const old = f.client.confirm('old@example.test', 'old-code');
    await f.reply(0, body.response);
    f.client.cancel();
    expect(body.complete('{"token":"fixture","parameterName":"_csrf","headerName":"X-CSRF-TOKEN"}')).toBe(false);
    await old;
    expect(f.requests).toHaveLength(1);
    const next = f.client.resend('new@example.test');
    await f.reply(1, csrf());
    f.setEnabled(false);
    await f.reply(2, accepted());
    await next;
    expect(f.changed.mock.calls.map(([result]) => result)).toEqual(['PENDING', 'PENDING']);
  });

  it('enforces an 8192-byte stream cap before an unclosed oversized body can be materialized', async () => {
    const f = fixture();
    const body = streamedResponse();
    const run = f.client.confirm('case@example.test', '123');
    await f.reply(0, csrf());
    await f.reply(1, body.response);
    body.chunk(new TextEncoder().encode('é'.repeat(4097))); // 8194 bytes, only 4097 characters; deliberately never close.
    await run;
    expect(f.changed).toHaveBeenLastCalledWith('UNKNOWN');
    expect(body.cancel).toHaveBeenCalledTimes(1);
    expect(body.response.body?.locked).toBe(false);
  });

  it('accepts an exact 8192-byte well-formed success body and rejects malformed UTF-8', async () => {
    const f = fixture();
    const value = '{"result":"confirmed"}';
    const run = f.client.confirm('case@example.test', '123');
    await f.reply(0, csrf());
    await f.reply(1, new Response(' '.repeat(8192 - value.length) + value));
    await run;
    expect(f.changed).toHaveBeenLastCalledWith('CONFIRMED');
    const next = f.client.resend('case@example.test');
    await f.reply(2, csrf());
    await f.reply(3, new Response(new Uint8Array([0xff]), { status: 202 }));
    await next;
    expect(f.changed).toHaveBeenLastCalledWith('UNKNOWN');
  });

  it('cancel releases its owned reader and settles without waiting for foreign never-ending cleanup', async () => {
    const f = fixture();
    const body = streamedResponse(200, new Promise<void>(() => undefined));
    const run = f.client.confirm('case@example.test', '123');
    await f.reply(0, body.response);
    f.client.cancel();
    await run;
    expect(body.cancel).toHaveBeenCalledTimes(1);
    expect(body.response.body?.locked).toBe(false);
    expect(f.requests).toHaveLength(1);
    expect(f.changed.mock.calls.map(([result]) => result)).toEqual(['PENDING']);
  });
});
