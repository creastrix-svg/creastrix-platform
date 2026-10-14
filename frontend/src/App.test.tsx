import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { StrictMode } from 'react';
import { Provider } from 'react-redux';
import { MemoryRouter } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import App from './App';
import { AuthenticationContext, createAuthentication } from './authentication';
import type { Authentication, Notice, Phase } from './authentication';
import { EmailConfirmationContext } from './emailConfirmation';
import { copy } from './i18n';
import { authenticationFinished, authenticationStarted, createAppStore } from './store';
import { initialLanguage, LANGUAGE_KEY } from './language';
import type { Language } from './language';

const A = { id: '11111111-1111-4111-8111-111111111111', status: 'ACTIVE' };
const B = { id: '22222222-2222-4222-8222-222222222222', status: 'ACTIVE' };
const controllers: Authentication[] = [];
afterEach(() => { controllers.splice(0).forEach(runtime => runtime.dispose()); delete document.documentElement.dataset.authPrivate; vi.unstubAllEnvs(); });

function show(path = '/', language: Language = 'en') {
  const store = createAppStore(language);
  const view = render(<Provider store={store}><MemoryRouter initialEntries={[path]}><App /></MemoryRouter></Provider>);
  return { ...view, store, user: userEvent.setup() };
}

function connected(path = '/account', language: Language = 'en', enabled = true) {
  const requests: { path: RequestInfo | URL; init?: RequestInit; resolve: (response: Response) => void }[] = [];
  const fetch = vi.fn<typeof globalThis.fetch>((path, init) => new Promise<Response>(resolve => { requests.push({ path, init, resolve }); }));
  const store = createAppStore(language);
  let nonce = 0;
  const runtime = createAuthentication({
    enabled, fetch, storage: () => localStorage, nonce: () => 'ui-' + ++nonce,
    closePrivate: () => {
      document.documentElement.dataset.authPrivate = 'closed';
      document.querySelectorAll<HTMLInputElement>('input[data-auth-csrf]').forEach(input => { input.value = ''; });
    },
    openPrivate: () => { document.documentElement.dataset.authPrivate = 'open'; },
    started: (owner, phase, notice) => { store.dispatch(authenticationStarted({ owner, phase, notice })); },
    finished: (owner, result) => { store.dispatch(authenticationFinished({ owner, result })); },
  });
  controllers.push(runtime);
  const started = runtime.start();
  const view = render(<StrictMode><Provider store={store}><AuthenticationContext value={runtime}>
    <MemoryRouter initialEntries={[path]}><App /></MemoryRouter>
  </AuthenticationContext></Provider></StrictMode>);
  const reply = async (index: number, response: Response) => {
    await act(async () => { requests[index].resolve(response); for (let i = 0; i < 24; i++) await Promise.resolve(); });
  };
  return { ...view, store, runtime, requests, fetch, reply, started, user: userEvent.setup() };
}

describe('public routes, language and keyboard support', () => {
  it('opens a protected account route from home without inventing an identity or workspace list', async () => {
    const { user, store } = show();
    expect(screen.getByRole('heading', { level: 1, name: copy.en.homeTitle })).toBeVisible();
    await user.click(screen.getByRole('link', { name: copy.en.openAccount }));
    expect(screen.getByRole('heading', { level: 1, name: copy.en.accountTitle })).toBeVisible();
    expect(screen.getByText(copy.en.status.CHECKING)).toBeVisible();
    expect(screen.queryByText(A.id)).not.toBeInTheDocument();
    expect(screen.queryByText('Alex Morgan')).not.toBeInTheDocument();
    expect(screen.queryByText(copy.en.workspaceBody)).not.toBeInTheDocument();
    expect(screen.getByRole('main')).toHaveFocus();
    expect(store.getState().authentication.user).toBeNull();
    expect(localStorage.length).toBe(0);
  });

  it('has no password/email controls and disables unimplemented signup and social methods', () => {
    show('/login');
    for (const method of [copy.en.signup, copy.en.google, copy.en.apple]) expect(screen.getByRole('button', { name: method })).toBeDisabled();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(document.querySelector('input[type=password],input[type=email]')).toBeNull();
    expect(screen.getByRole('button', { name: copy.en.continueLogin })).toBeDisabled();
    expect(screen.getByText(copy.en.methodsNote)).toBeVisible();
  });

  it.each(['en', 'de'] as const)('supports protected direct account navigation and labels in %s', language => {
    show('/account', language);
    const t = copy[language];
    expect(screen.getByRole('heading', { level: 1, name: t.accountTitle })).toBeVisible();
    const nav = screen.getByRole('navigation', { name: t.navigation });
    expect(within(nav).getByRole('link', { name: t.account })).toHaveAttribute('aria-current', 'page');
    expect(document.documentElement.lang).toBe(language);
    expect(document.title).toBe(t.account + ' · Creastrix');
  });

  it('changes DE/EN labels and title; remount restores language but never identity', async () => {
    const { user, unmount } = show('/login');
    await user.click(screen.getByRole('button', { name: 'Deutsch' }));
    expect(screen.getByRole('heading', { level: 1, name: copy.de.loginTitle })).toBeVisible();
    expect(screen.getByRole('group', { name: copy.de.language })).toBeVisible();
    expect(document.title).toBe(copy.de.login + ' · Creastrix');
    expect(localStorage.getItem(LANGUAGE_KEY)).toBe('de');
    unmount();
    const { store } = show('/account', initialLanguage('en'));
    expect(store.getState().ui.language).toBe('de');
    expect(store.getState().authentication.user).toBeNull();
  });

  it('handles unknown routes and provides a working way home', async () => {
    const { user } = show('/missing');
    expect(screen.getByRole('heading', { name: copy.en.notFoundTitle })).toBeVisible();
    await user.click(screen.getByRole('link', { name: copy.en.backHome }));
    expect(screen.getByRole('heading', { name: copy.en.homeTitle })).toBeVisible();
  });

  it.each(['/account/', '/ACCOUNT'])('keeps title consistent with the router for %s', path => {
    show(path);
    expect(screen.getByRole('heading', { name: copy.en.accountTitle })).toBeVisible();
    expect(document.title).toBe(copy.en.account + ' · Creastrix');
  });

  it('keeps early skip link, keyboard navigation and the selected brand', async () => {
    const { user } = show();
    expect(screen.getByRole('img', { name: 'Creastrix' })).toHaveAttribute('src', '/brand/creastrix-logo-horizontal-black.svg');
    await user.tab();
    expect(screen.getByRole('link', { name: copy.en.skip })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('link', { name: 'Creastrix — Home' })).toHaveFocus();
    await user.tab();
    await user.tab();
    expect(within(screen.getByRole('navigation', { name: copy.en.navigation })).getByRole('link', { name: copy.en.login })).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(screen.getByRole('heading', { name: copy.en.loginTitle })).toBeVisible();
  });
});

describe('state-bound own-account UI (DOM units, not a browser authentication proof)', () => {
  it.each(['en', 'de'] as const)('renders only validated id/status and voluntary workspace messaging in %s', async language => {
    const f = connected('/account', language);
    expect(screen.queryByText(A.id)).not.toBeInTheDocument();
    await f.reply(0, Response.json(A));
    await f.started;
    const t = copy[language];
    expect(screen.getByText(A.id)).toBeVisible();
    expect(screen.getByText(t.activeAccess)).toBeVisible();
    expect(screen.getByText(t.workspaceBody)).toBeVisible();
    expect(screen.getByText(t.workspaceChoice)).toBeVisible();
    expect(screen.getByText(t.workspaceBoundary)).toBeVisible();
    expect(document.documentElement.dataset.authPrivate).toBe('open');
    expect(screen.queryByText('Alex Morgan')).not.toBeInTheDocument();
    expect(screen.queryByText(/No workspaces yet/)).not.toBeInTheDocument();
    expect(f.requests).toHaveLength(1); // StrictMode does not own bootstrap.
  });

  it.each([[401, 'ANONYMOUS'], [403, 'DENIED'], [503, 'UNAVAILABLE']] as const)('displays distinct current %i / %s without private content', async (code, phase) => {
    const f = connected();
    await f.reply(0, new Response(null, { status: code }));
    await f.started;
    expect(screen.getByText(copy.en.status[phase])).toBeVisible();
    expect(screen.queryByText(A.id)).not.toBeInTheDocument();
    expect(document.querySelector('[data-private-account]')).toBeNull();
    expect(document.documentElement.dataset.authPrivate).toBe('closed');
  });

  it('does not accept account navigation or auth=failed as identity evidence', async () => {
    const f = connected('/login?auth=failed');
    expect(screen.getByText(copy.en.callbackFailed)).toBeVisible();
    expect(screen.getByRole('button', { name: copy.en.continueLogin })).toBeDisabled();
    await f.reply(0, Response.json(B));
    await f.started;
    expect(f.store.getState().authentication.user).toEqual(B);
    expect(screen.getByText(copy.en.status.AUTHENTICATED)).toBeVisible();
    expect(screen.getByText(copy.en.callbackFailed)).toBeVisible();
    expect(screen.queryByRole('button', { name: copy.en.continueLogin })).not.toBeInTheDocument();
  });

  it('uses a native same-window POST form after echo, not a fetch/provider redirect', async () => {
    const f = connected('/login');
    let submittedToken = '';
    const nativeSubmit = vi.spyOn(HTMLFormElement.prototype, 'submit').mockImplementation(function (this: HTMLFormElement) {
      // Observe the form at submission, not after React has discarded transient hidden input state.
      submittedToken = this.querySelector<HTMLInputElement>('input[data-auth-csrf]')!.value;
    });
    await f.reply(0, new Response(null, { status: 401 }));
    await f.started;
    await f.user.click(screen.getByRole('button', { name: copy.en.continueLogin }));
    expect(screen.getByRole('button', { name: copy.en.continueLogin })).toBeDisabled();
    const csrf = { token: 'fixture-first', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };
    await f.reply(1, Response.json(csrf));
    await f.reply(2, Response.json({ ...csrf, token: 'fixture-echo' }));
    expect(nativeSubmit).toHaveBeenCalledTimes(1);
    const form = document.querySelector('form')!;
    expect(form).toHaveAttribute('method', 'post');
    expect(form).toHaveAttribute('action', '/auth/login');
    expect(form).toHaveAttribute('target', '_self');
    expect(submittedToken).toBe('fixture-echo');
    expect(f.requests.map(request => request.path)).toEqual(['/api/me', '/auth/csrf', '/auth/csrf']);
    expect(f.requests.every(request => request.init?.method === 'GET')).toBe(true);
    expect(localStorage.getItem(LANGUAGE_KEY)).toBeNull();
    expect(Object.keys(JSON.parse(localStorage.getItem('creastrix.auth.boundary')!))).toEqual(['nonce', 'phase']);
  });

  it('clears private DOM/state before logout and labels cleanup-only separately', async () => {
    const f = connected();
    await f.reply(0, Response.json(A));
    await f.started;
    await f.user.click(screen.getByRole('button', { name: copy.en.logout }));
    expect(screen.queryByText(A.id)).not.toBeInTheDocument();
    expect(f.store.getState().authentication.user).toBeNull();
    expect(document.documentElement.dataset.authPrivate).toBe('closed');
    await f.reply(1, Response.json({ token: 'fixture', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' }));
    await f.reply(2, new Response(null, { status: 409 }));
    expect(screen.getByText(copy.en.notice.CLEANUP_ONLY)).toBeVisible();
    expect(screen.queryByText(copy.en.notice.LOCAL_LOGOUT_COMPLETED)).not.toBeInTheDocument();
    expect(f.requests).toHaveLength(3);
  });

  it('suspend closes the curtain synchronously before React commits; resume waits for fresh validation', async () => {
    const f = connected();
    await f.reply(0, Response.json(A));
    await f.started;
    act(() => {
      f.runtime.suspend();
      expect(document.documentElement.dataset.authPrivate).toBe('closed');
      expect(f.store.getState().authentication.user).toBeNull();
    });
    expect(screen.queryByText(A.id)).not.toBeInTheDocument();
    let restored!: Promise<void>;
    act(() => { restored = f.runtime.resume(); });
    expect(document.documentElement.dataset.authPrivate).toBe('closed');
    await f.reply(1, Response.json(B));
    await restored;
    expect(screen.getByText(B.id)).toBeVisible();
    expect(document.documentElement.dataset.authPrivate).toBe('open');
  });

  it('disconnected preview is honest and does not perform authentication', async () => {
    const f = connected('/login', 'en', false);
    await f.started;
    expect(screen.getByText(copy.en.notice.DEV_ONLY)).toBeVisible();
    expect(screen.getByRole('button', { name: copy.en.continueLogin })).toBeDisabled();
    expect(f.fetch).not.toHaveBeenCalled();
    expect(localStorage.getItem('creastrix.auth.boundary')).toBeNull();
  });
});

function confirmationFixture(options: { path?: string; language?: Language; enabled?: boolean; defaultTransport?: boolean; phase?: Phase } = {}) {
  const requests: { path: RequestInfo | URL; init?: RequestInit; resolve: (response: Response) => void; reject: (error: Error) => void }[] = [];
  const fetch = vi.fn<typeof globalThis.fetch>((path, init) => new Promise<Response>((resolve, reject) => { requests.push({ path, init, resolve, reject }); }));
  const store = createAppStore(options.language ?? 'en', vi.fn());
  let epoch = 0;
  function phase(next: Phase, notice: Notice = 'NONE') {
    const owner = { document: 'confirmation-ui', epoch: ++epoch, request: epoch, revision: 'fixture-' + epoch };
    store.dispatch(authenticationStarted({ owner, phase: next, notice }));
    if (next === 'AUTHENTICATED') store.dispatch(authenticationFinished({ owner, result: { phase: next, notice, user: { id: A.id, status: 'ACTIVE' } } }));
  }
  phase(options.phase ?? 'ANONYMOUS');
  // No authentication bootstrap is started by this fixture or by the recovery page.
  const runtime = createAuthentication({ enabled: false, fetch, storage: () => localStorage, nonce: () => 'unused',
    closePrivate: vi.fn(), openPrivate: vi.fn(), started: vi.fn(), finished: vi.fn() });
  controllers.push(runtime);
  const login = vi.spyOn(runtime, 'login');
  const logout = vi.spyOn(runtime, 'logout');
  const recover = vi.spyOn(runtime, 'recover');
  const recheck = vi.spyOn(runtime, 'recheck');
  const transport = options.defaultTransport ? null : { enabled: options.enabled ?? true, fetch };
  const view = render(<StrictMode><Provider store={store}><AuthenticationContext value={runtime}>
    <EmailConfirmationContext value={transport}><MemoryRouter initialEntries={[options.path ?? '/confirm-email']}><App /></MemoryRouter></EmailConfirmationContext>
  </AuthenticationContext></Provider></StrictMode>);
  const reply = async (index: number, response: Response) => {
    await act(async () => { requests[index].resolve(response); for (let i = 0; i < 24; i++) await Promise.resolve(); });
  };
  const user = userEvent.setup();
  const inputs = () => ({
    email: screen.getByRole('textbox', { name: copy[options.language ?? 'en'].confirmation.email }) as HTMLInputElement,
    code: screen.getByRole('textbox', { name: copy[options.language ?? 'en'].confirmation.code }) as HTMLInputElement,
  });
  async function fill(email = 'MixedCase@example.test', code = 'Code-123') {
    const fields = inputs();
    await user.type(fields.email, email);
    await user.type(fields.code, code);
  }
  return { ...view, store, fetch, requests, reply, user, inputs, fill, phase, login, logout, recover, recheck };
}
const confirmationCsrf = () => Response.json({ token: 'fixture-confirmation', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' });

describe('transient confirmation recovery UI (finite DOM mocks, not provider/browser proof)', () => {
  it.each(['en', 'de'] as const)('opens the direct confirmation route with localized title, exactly email/code and no password in %s', language => {
    show('/confirm-email?email=ignored@example.test&code=ignored-code', language);
    const t = copy[language].confirmation;
    expect(screen.getByRole('heading', { name: t.title })).toBeVisible();
    expect(document.title).toBe(t.page + ' · Creastrix');
    expect(document.documentElement.lang).toBe(language);
    expect(screen.getAllByRole('textbox')).toHaveLength(2);
    for (const field of screen.getAllByRole('textbox')) expect(field).toHaveValue('');
    expect(document.querySelector('input[type=password],input[type=hidden]')).toBeNull();
    expect(screen.getByText(t.boundary)).toBeVisible();
    expect(screen.getByRole('link', { name: t.backLogin })).toHaveAttribute('href', '/login');
    expect(localStorage.length).toBe(0);
  });

  it('provides a keyboard-operable recovery link from the unchanged hosted-login page', async () => {
    const f = confirmationFixture({ path: '/login' });
    const link = screen.getByRole('link', { name: copy.en.confirmation.link });
    expect(document.querySelector('input[type=password],input[type=email]')).toBeNull();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    for (let i = 0; i < 20 && document.activeElement !== link; i++) await f.user.tab();
    expect(link).toHaveFocus();
    await f.user.keyboard('{Enter}');
    expect(screen.getByRole('heading', { name: copy.en.confirmation.title })).toBeVisible();
    expect(screen.getByRole('main')).toHaveFocus();
    await f.user.tab();
    expect(f.inputs().email).toHaveFocus();
    await f.user.type(f.inputs().email, 'Case@example.test');
    await f.user.tab();
    expect(f.inputs().code).toHaveFocus();
    await f.user.type(f.inputs().code, '123');
    await f.user.tab();
    expect(screen.getByRole('button', { name: copy.en.confirmation.confirm })).toHaveFocus();
    await f.user.keyboard('{Enter}');
    expect(f.requests.map(request => request.path)).toEqual(['/auth/csrf']);
    expect(f.login).not.toHaveBeenCalled();
  });

  it('switches recovery copy and title between DE and EN without persisting its form values', async () => {
    const f = confirmationFixture();
    await f.fill();
    await f.user.click(screen.getByRole('button', { name: 'Deutsch' }));
    expect(screen.getByRole('heading', { name: copy.de.confirmation.title })).toBeVisible();
    expect(document.title).toBe(copy.de.confirmation.page + ' · Creastrix');
    expect(screen.getByRole('textbox', { name: copy.de.confirmation.email })).toHaveValue('MixedCase@example.test');
    expect(localStorage.length).toBe(0); // This fixture injects a non-persisting language preference.
    await f.user.click(screen.getByRole('button', { name: 'English' }));
    expect(screen.getByRole('heading', { name: copy.en.confirmation.title })).toBeVisible();
    expect(f.fetch).not.toHaveBeenCalled();
  });

  it.each(['en', 'de'] as const)('acknowledges only actual confirmation 200, with no sign-in/access implication in %s', async language => {
    const f = confirmationFixture({ language });
    const t = copy[language].confirmation;
    const authentication = f.store.getState().authentication;
    const write = vi.spyOn(Storage.prototype, 'setItem');
    const log = vi.spyOn(console, 'log');
    const error = vi.spyOn(console, 'error');
    await f.fill();
    await f.user.click(screen.getByRole('button', { name: t.confirm }));
    expect(screen.getByRole('status')).toHaveTextContent(t.result.PENDING);
    await f.reply(0, confirmationCsrf());
    expect(f.requests[1].init?.body).toBe(JSON.stringify({ email: 'MixedCase@example.test', code: 'Code-123' }));
    await f.reply(1, Response.json({ result: 'confirmed' }));
    expect(screen.getByRole('status')).toHaveTextContent(t.result.CONFIRMED);
    expect(screen.getByRole('link', { name: t.backLogin })).toHaveAttribute('href', '/login');
    expect(f.store.getState().authentication).toEqual(authentication);
    for (const action of [f.login, f.logout, f.recover, f.recheck]) expect(action).not.toHaveBeenCalled();
    expect(write).not.toHaveBeenCalled();
    expect(log).not.toHaveBeenCalled();
    expect(error).not.toHaveBeenCalled();
    expect(document.body).not.toHaveTextContent('fixture-confirmation');
    expect(f.requests.map(request => request.path)).toEqual(['/auth/csrf', '/auth/confirm-email']);
  });

  it.each(['en', 'de'] as const)('resend 202 is a conditional acknowledgement, never mail-delivery success in %s', async language => {
    const f = confirmationFixture({ language });
    const t = copy[language].confirmation;
    await f.user.type(f.inputs().email, 'Case@example.test');
    await f.user.click(screen.getByRole('button', { name: t.resend }));
    await f.reply(0, confirmationCsrf());
    expect(f.requests[1].init?.body).toBe(JSON.stringify({ email: 'Case@example.test' }));
    await f.reply(1, Response.json({ result: 'code_request_accepted' }, { status: 202 }));
    expect(screen.getByRole('status')).toHaveTextContent(t.result.CODE_REQUEST_ACCEPTED);
    expect(screen.queryByText(t.result.CONFIRMED)).not.toBeInTheDocument();
    expect(f.inputs().code).toHaveValue('');
    expect(f.requests.map(request => request.path)).toEqual(['/auth/csrf', '/auth/resend-confirmation-code']);
  });

  it.each([[400, 'NOT_COMPLETED'], [403, 'DENIED'], [429, 'RATE_LIMITED'], [503, 'UNKNOWN']] as const)('renders generic %i / %s without recipient or provider messages', async (status, result) => {
    const f = confirmationFixture();
    await f.fill();
    await f.user.click(screen.getByRole('button', { name: copy.en.confirmation.confirm }));
    await f.reply(0, confirmationCsrf());
    await f.reply(1, Response.json({ error: 'private-provider-recipient-detail' }, { status }));
    expect(screen.getByRole('status')).toHaveTextContent(copy.en.confirmation.result[result]);
    expect(document.body).not.toHaveTextContent('private-provider-recipient-detail');
    expect(screen.queryByText(copy.en.confirmation.result.CONFIRMED)).not.toBeInTheDocument();
    expect(f.requests).toHaveLength(2);
  });

  it.each(['malformed', 'redirect', 'lost-ack'] as const)('never reports success for %s', async kind => {
    const f = confirmationFixture();
    await f.fill();
    await f.user.click(screen.getByRole('button', { name: copy.en.confirmation.confirm }));
    await f.reply(0, confirmationCsrf());
    if (kind === 'lost-ack') await act(async () => { f.requests[1].reject(new Error('opaque-private-error')); });
    else {
      const response = kind === 'malformed' ? new Response('<html>SPA</html>', { status: 200 }) : Response.json({ result: 'confirmed' });
      if (kind === 'redirect') Object.defineProperty(response, 'redirected', { value: true });
      await f.reply(1, response);
    }
    expect(screen.getByRole('status')).toHaveTextContent(copy.en.confirmation.result.UNKNOWN);
    expect(screen.queryByText(copy.en.confirmation.result.CONFIRMED)).not.toBeInTheDocument();
    expect(f.requests).toHaveLength(2);
  });

  it('rejects padded input without silently trimming it and does not send invalid local fields', async () => {
    const f = confirmationFixture();
    await f.fill(' Padded@example.test', '123');
    expect(f.inputs().email).toHaveValue(' Padded@example.test');
    await f.user.click(screen.getByRole('button', { name: copy.en.confirmation.confirm }));
    expect(screen.getByRole('status')).toHaveTextContent(copy.en.confirmation.result.INVALID_INPUT);
    expect(f.fetch).not.toHaveBeenCalled();
  });

  it('disables duplicate actions but permits edits to discard a pending request', async () => {
    const f = confirmationFixture();
    await f.fill();
    const button = screen.getByRole('button', { name: copy.en.confirmation.confirm });
    await f.user.click(button);
    expect(button).toBeDisabled();
    expect(screen.getByRole('button', { name: copy.en.confirmation.resend })).toBeDisabled();
    fireEvent.submit(document.querySelector('.confirmation-form')!);
    expect(f.requests).toHaveLength(1);
    await f.user.type(f.inputs().code, '4');
    expect(f.requests[0].init?.signal?.aborted).toBe(true);
    expect(button).toBeEnabled();
    await f.reply(0, confirmationCsrf());
    expect(f.requests).toHaveLength(1);
    expect(screen.getByRole('status')).toHaveTextContent(copy.en.confirmation.ready);
  });

  it.each(['resolve', 'reject'] as const)('editing the email while a success body is delayed discards old %s and keeps the newer resend result', async mode => {
    const f = confirmationFixture();
    await f.fill();
    await f.user.click(screen.getByRole('button', { name: copy.en.confirmation.confirm }));
    await f.reply(0, confirmationCsrf());
    let body!: ReadableStreamDefaultController<Uint8Array>;
    const cancel = vi.fn();
    const response = new Response(new ReadableStream<Uint8Array>({ start(controller) { body = controller; }, cancel }));
    await f.reply(1, response);
    await f.user.clear(f.inputs().email);
    await f.user.type(f.inputs().email, 'NewCase@example.test');
    await f.user.click(screen.getByRole('button', { name: copy.en.confirmation.resend }));
    await f.reply(2, confirmationCsrf());
    expect(f.requests[3].init?.body).toBe(JSON.stringify({ email: 'NewCase@example.test' }));
    await f.reply(3, Response.json({ result: 'code_request_accepted' }, { status: 202 }));
    await act(async () => {
      if (mode === 'resolve') {
        expect(() => { body.enqueue(new TextEncoder().encode('{"result":"confirmed"}')); }).toThrow();
      } else body.error(new Error('old-private-error'));
    });
    expect(screen.getByRole('status')).toHaveTextContent(copy.en.confirmation.result.CODE_REQUEST_ACCEPTED);
    expect(f.requests[1].init?.signal?.aborted).toBe(true);
    expect(cancel).toHaveBeenCalledTimes(1);
    expect(response.body?.locked).toBe(false);
  });

  it.each(['cancel', 'navigation', 'unmount'] as const)('%s synchronously clears fields and discards a late POST response; returning mounts an empty form', async action => {
    const f = confirmationFixture();
    await f.fill();
    const fields = f.inputs();
    await f.user.click(screen.getByRole('button', { name: copy.en.confirmation.confirm }));
    await f.reply(0, confirmationCsrf());
    if (action === 'cancel') await f.user.click(screen.getByRole('button', { name: copy.en.confirmation.cancel }));
    if (action === 'navigation') await f.user.click(screen.getByRole('link', { name: copy.en.confirmation.backLogin }));
    if (action === 'unmount') f.unmount();
    expect(fields.email.value).toBe('');
    expect(fields.code.value).toBe('');
    expect(f.requests[1].init?.signal?.aborted).toBe(true);
    await f.reply(1, Response.json({ result: 'confirmed' }));
    expect(screen.queryByText(copy.en.confirmation.result.CONFIRMED)).not.toBeInTheDocument();
    if (action === 'navigation') {
      await f.user.click(screen.getByRole('link', { name: copy.en.confirmation.link }));
      for (const field of Object.values(f.inputs())) expect(field).toHaveValue('');
    }
    if (action === 'unmount') {
      const fresh = confirmationFixture();
      for (const field of Object.values(fresh.inputs())) expect(field).toHaveValue('');
      expect(fresh.fetch).not.toHaveBeenCalled();
    }
    expect(f.requests).toHaveLength(2);
  });

  it.each(['pagehide', 'hidden'] as const)('simulated %s/bfcache return clears DOM synchronously, discards late work and never auto-resends', async lifecycle => {
    const f = confirmationFixture();
    await f.fill();
    const fields = f.inputs();
    await f.user.click(screen.getByRole('button', { name: copy.en.confirmation.confirm }));
    await f.reply(0, confirmationCsrf());
    const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
    act(() => {
      if (lifecycle === 'pagehide') window.dispatchEvent(new Event('pagehide'));
      else { visibility.mockReturnValue('hidden'); document.dispatchEvent(new Event('visibilitychange')); }
      expect(fields.email.value).toBe('');
      expect(fields.code.value).toBe('');
      expect(f.requests[1].init?.signal?.aborted).toBe(true);
    });
    await f.reply(1, Response.json({ result: 'confirmed' }));
    act(() => {
      visibility.mockReturnValue('visible');
      if (lifecycle === 'pagehide') {
        const pageshow = new Event('pageshow');
        Object.defineProperty(pageshow, 'persisted', { value: true });
        window.dispatchEvent(pageshow);
      } else document.dispatchEvent(new Event('visibilitychange'));
    });
    expect(fields.email).toHaveValue('');
    expect(fields.code).toHaveValue('');
    expect(screen.queryByText(copy.en.confirmation.result.CONFIRMED)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: copy.en.confirmation.confirm })).toBeEnabled();
    expect(f.requests).toHaveLength(2);
  });

  it('account phase/owner changes clear form, cancel pending work and cannot confer confirmation on the newer account', async () => {
    const f = confirmationFixture();
    await f.fill();
    await f.user.click(screen.getByRole('button', { name: copy.en.confirmation.confirm }));
    await f.reply(0, confirmationCsrf());
    act(() => { f.phase('AUTHENTICATED'); });
    expect(screen.getByRole('status')).toHaveTextContent(copy.en.confirmation.authenticated);
    for (const field of Object.values(f.inputs())) expect(field).toHaveValue('');
    expect(screen.getByRole('button', { name: copy.en.confirmation.confirm })).toBeDisabled();
    await f.reply(1, Response.json({ result: 'confirmed' }));
    expect(screen.queryByText(copy.en.confirmation.result.CONFIRMED)).not.toBeInTheDocument();
    expect(f.store.getState().authentication.user).toEqual(A);
    act(() => { f.phase('ANONYMOUS'); });
    expect(screen.getByRole('button', { name: copy.en.confirmation.confirm })).toBeEnabled();
    expect(f.requests).toHaveLength(2);
    expect(f.logout).not.toHaveBeenCalled();
  });

  it('peer invalidation clears the form and prevents a late CSRF response from starting a POST', async () => {
    const f = confirmationFixture();
    await f.fill();
    await f.user.click(screen.getByRole('button', { name: copy.en.confirmation.confirm }));
    act(() => { f.phase('RECOVERY_REQUIRED', 'PEER_CHANGED'); });
    for (const field of Object.values(f.inputs())) expect(field).toHaveValue('');
    await f.reply(0, confirmationCsrf());
    expect(f.requests).toHaveLength(1);
    expect(screen.queryByText(copy.en.confirmation.result.CONFIRMED)).not.toBeInTheDocument();
    expect(f.recheck).not.toHaveBeenCalled();
    expect(f.recover).not.toHaveBeenCalled();
  });

  it('rapid simulated hide/show in one React batch returns empty and allows only a later explicit action', async () => {
    const f = confirmationFixture();
    await f.fill();
    act(() => { window.dispatchEvent(new Event('pagehide')); window.dispatchEvent(new Event('pageshow')); });
    for (const field of Object.values(f.inputs())) expect(field).toHaveValue('');
    expect(f.fetch).not.toHaveBeenCalled();
    await f.user.type(f.inputs().email, 'NewCase@example.test');
    await f.user.click(screen.getByRole('button', { name: copy.en.confirmation.resend }));
    expect(f.requests.map(request => request.path)).toEqual(['/auth/csrf']);
  });

  it.each(['AUTHENTICATED', 'CHECKING', 'TRANSITION'] as const)('does not submit from local %s UI, even with a forced form event', async phase => {
    const f = confirmationFixture({ phase });
    fireEvent.submit(document.querySelector('.confirmation-form')!);
    await f.user.click(screen.getByRole('button', { name: copy.en.confirmation.resend }));
    expect(f.fetch).not.toHaveBeenCalled();
    expect(f.logout).not.toHaveBeenCalled();
    expect(f.recover).not.toHaveBeenCalled();
  });

  it.each(['RECOVERY_REQUIRED', 'DENIED', 'UNAVAILABLE'] as const)('does not require a live Q/canStart state from local %s; backend anonymous checks stay authoritative', async phase => {
    const f = confirmationFixture({ phase });
    await f.user.type(f.inputs().email, 'Case@example.test');
    await f.user.click(screen.getByRole('button', { name: copy.en.confirmation.resend }));
    expect(f.requests.map(request => request.path)).toEqual(['/auth/csrf']);
    expect(f.recover).not.toHaveBeenCalled();
    expect(f.recheck).not.toHaveBeenCalled();
  });

  it('disconnected transport and build preview with an AuthenticationContext make zero confirmation calls', async () => {
    const f = confirmationFixture({ enabled: false });
    expect(screen.getByRole('status')).toHaveTextContent(copy.en.confirmation.disconnected);
    fireEvent.submit(document.querySelector('.confirmation-form')!);
    expect(f.fetch).not.toHaveBeenCalled();
    f.unmount();
    vi.stubEnv('DEV', false);
    const preview = confirmationFixture({ defaultTransport: true });
    expect(screen.getByRole('status')).toHaveTextContent(copy.en.confirmation.disconnected);
    fireEvent.submit(document.querySelector('.confirmation-form')!);
    expect(preview.fetch).not.toHaveBeenCalled();
  });
});
