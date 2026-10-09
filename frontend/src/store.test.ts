import { expect, it, vi } from 'vitest';
import { authenticationFinished, authenticationStarted, createAppStore, languageSelected } from './store';
import { rememberLanguage } from './language';
import type { Owner } from './authBoundary';

const A = { id: '11111111-1111-4111-8111-111111111111', status: 'ACTIVE' } as const;
const B = { id: '22222222-2222-4222-8222-222222222222', status: 'ACTIVE' } as const;
const owner: Owner = { document: 'doc', epoch: 1, request: 1, revision: 'rev' };

it('persists only explicit language, never the in-memory authentication state', () => {
  const persist = vi.fn();
  const store = createAppStore('en', persist);
  expect(persist).not.toHaveBeenCalled();
  expect(store.getState().authentication).toEqual({ owner: null, phase: 'CHECKING', notice: 'NONE', user: null });
  store.dispatch(authenticationStarted({ owner, phase: 'CHECKING', notice: 'NONE' }));
  store.dispatch(authenticationFinished({ owner, result: { phase: 'AUTHENTICATED', notice: 'NONE', user: A } }));
  expect(persist).not.toHaveBeenCalled();
  store.dispatch(languageSelected('de'));
  expect(store.getState().ui.language).toBe('de');
  expect(store.getState().authentication.user).toEqual(A);
  expect(persist).toHaveBeenCalledExactlyOnceWith('de');
  expect(createAppStore('de', persist).getState().authentication.user).toBeNull();
});

it('retains language in memory when preference persistence is unavailable', () => {
  const store = createAppStore('en', language => rememberLanguage(language, () => { throw new Error('Denied'); }));
  store.dispatch(languageSelected('de'));
  expect(store.getState().ui.language).toBe('de');
});

it('a new boundary resets private state immediately while preserving language', () => {
  const store = createAppStore('de', vi.fn());
  store.dispatch(authenticationStarted({ owner, phase: 'CHECKING', notice: 'NONE' }));
  store.dispatch(authenticationFinished({ owner, result: { phase: 'AUTHENTICATED', notice: 'NONE', user: A } }));
  store.dispatch(authenticationStarted({ owner: { ...owner, epoch: 2 }, phase: 'TRANSITION', notice: 'NONE' }));
  expect(store.getState().authentication.user).toBeNull();
  expect(store.getState().ui.language).toBe('de');
});

it('reducer independently rejects stale success, denial, failure and cleanup, retaining positive B', () => {
  const store = createAppStore('en', vi.fn());
  const current = { ...owner, epoch: 2, request: 2, revision: 'new' };
  store.dispatch(authenticationStarted({ owner: current, phase: 'CHECKING', notice: 'NONE' }));
  store.dispatch(authenticationFinished({ owner: current, result: { phase: 'AUTHENTICATED', notice: 'NONE', user: B } }));
  const snapshot = store.getState().authentication;
  for (const phase of ['AUTHENTICATED', 'ANONYMOUS', 'DENIED', 'UNAVAILABLE', 'RECOVERY_REQUIRED'] as const) {
    store.dispatch(authenticationFinished({ owner, result: { phase, notice: 'NONE', user: A } }));
    expect(store.getState().authentication).toEqual(snapshot);
  }
  for (const changed of [{ document: 'other' }, { epoch: 3 }, { request: 3 }, { revision: 'other' }]) {
    store.dispatch(authenticationFinished({ owner: { ...current, ...changed }, result: { phase: 'ANONYMOUS', notice: 'NONE', user: null } }));
    expect(store.getState().authentication.user).toEqual(B);
  }
  store.dispatch(authenticationFinished({ owner: current, result: { phase: 'DENIED', notice: 'SECURITY_DENIED', user: A } }));
  expect(store.getState().authentication.user).toBeNull();
});
