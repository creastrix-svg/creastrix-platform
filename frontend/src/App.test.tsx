import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { StrictMode } from 'react';
import { Provider } from 'react-redux';
import { MemoryRouter } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import App from './App';
import { AuthenticationContext, createAuthentication } from './authentication';
import type { Authentication } from './authentication';
import { copy } from './i18n';
import { authenticationFinished, authenticationStarted, createAppStore } from './store';
import { initialLanguage, LANGUAGE_KEY } from './language';
import type { Language } from './language';

const A = { id: '11111111-1111-4111-8111-111111111111', status: 'ACTIVE' };
const B = { id: '22222222-2222-4222-8222-222222222222', status: 'ACTIVE' };
const controllers: Authentication[] = [];
afterEach(() => { controllers.splice(0).forEach(runtime => runtime.dispose()); delete document.documentElement.dataset.authPrivate; });

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
