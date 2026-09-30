import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import App from './App';
import { copy } from './i18n';
import { createAppStore } from './store';
import { initialLanguage, LANGUAGE_KEY } from './language';
import type { Language } from './language';

function show(path = '/', language: Language = 'en') {
  const store = createAppStore(language);
  const view = render(<Provider store={store}><MemoryRouter initialEntries={[path]}><App /></MemoryRouter></Provider>);
  return { ...view, store, user: userEvent.setup() };
}

describe('demo routes, not authentication', () => {
  it('navigates from the start to the demo without a sign-in or workspace', async () => {
    const { user, store } = show();
    expect(screen.getByRole('heading', { level: 1, name: copy.en.homeTitle })).toBeVisible();
    await user.click(screen.getByRole('link', { name: copy.en.viewDemo }));
    expect(screen.getByRole('heading', { level: 1, name: copy.en.accountTitle })).toBeVisible();
    expect(screen.getByText(copy.en.workspaceBody)).toBeVisible();
    expect(screen.getByText(copy.en.workspaceChoice)).toBeVisible();
    expect(screen.getByText(copy.en.accessValue)).toBeVisible();
    expect(screen.getByRole('main')).toHaveFocus();
    expect(store.getState()).toEqual({ ui: { language: 'en' } });
    expect(localStorage.length).toBe(0);
  });

  it('shows disabled future methods, no credential inputs, and a separate demo link', async () => {
    const { user } = show('/login');
    expect(screen.getByText(copy.en.loginLead)).toBeVisible();
    for (const method of [copy.en.email, copy.en.google, copy.en.apple]) {
      expect(screen.getByRole('button', { name: method })).toBeDisabled();
    }
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(document.querySelector('input, form')).toBeNull();
    expect(screen.getByText(copy.en.methodsNote)).toBeVisible();
    await user.click(screen.getByRole('link', { name: copy.en.viewDemo }));
    expect(screen.getByText(copy.en.demoNotice)).toBeVisible();
  });

  it.each(['en', 'de'] as const)('supports direct demo routes and fully labelled navigation in %s', language => {
    show('/account', language);
    const t = copy[language];
    expect(screen.getByRole('heading', { level: 1, name: t.accountTitle })).toBeVisible();
    expect(screen.getByText(t.disconnected)).toBeVisible();
    expect(screen.getByText(t.workspaceBoundary)).toBeVisible();
    const nav = screen.getByRole('navigation', { name: t.navigation });
    expect(within(nav).getByRole('link', { name: t.account })).toHaveAttribute('aria-current', 'page');
    expect(document.documentElement.lang).toBe(language);
    expect(document.title).toBe(`${t.account} · Creastrix`);
  });

  it('switches the whole screen, title and accessible labels; restores only language after remount', async () => {
    const { user, unmount } = show('/login');
    await user.click(screen.getByRole('button', { name: 'Deutsch' }));
    expect(screen.getByRole('heading', { level: 1, name: copy.de.loginTitle })).toBeVisible();
    expect(screen.getByRole('group', { name: copy.de.language })).toBeVisible();
    expect(screen.getByRole('button', { name: copy.de.email })).toBeDisabled();
    expect(document.title).toBe(`${copy.de.login} · Creastrix`);
    expect(localStorage.getItem(LANGUAGE_KEY)).toBe('de');
    unmount();
    const { store } = show('/account', initialLanguage('en'));
    expect(store.getState()).toEqual({ ui: { language: 'de' } });
    expect(screen.getByText(copy.de.accessValue)).toBeVisible();
  });

  it('handles an unknown route and offers a working way home', async () => {
    const { user } = show('/missing');
    expect(screen.getByRole('heading', { name: copy.en.notFoundTitle })).toBeVisible();
    await user.click(screen.getByRole('link', { name: copy.en.backHome }));
    expect(screen.getByRole('heading', { name: copy.en.homeTitle })).toBeVisible();
  });

  it.each(['/account/', '/ACCOUNT'])('keeps the title consistent with the router for %s', path => {
    show(path);
    expect(screen.getByRole('heading', { name: copy.en.accountTitle })).toBeVisible();
    expect(document.title).toBe(`${copy.en.account} · Creastrix`);
  });

  it('provides keyboard-reachable navigation and an early skip link', async () => {
    const { user } = show();
    await user.tab();
    expect(screen.getByRole('link', { name: copy.en.skip })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('link', { name: 'Creastrix — Home' })).toHaveFocus();
    await user.tab();
    await user.tab();
    expect(screen.getByRole('link', { name: copy.en.login })).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(screen.getByRole('heading', { name: copy.en.loginTitle })).toBeVisible();
  });

  it('does not make application network requests while navigating the demo', async () => {
    const fetch = vi.spyOn(window, 'fetch');
    const xhr = vi.spyOn(XMLHttpRequest.prototype, 'open');
    const { user } = show('/login');
    await user.click(screen.getByRole('link', { name: copy.en.viewDemo }));
    await user.click(screen.getByRole('button', { name: 'Deutsch' }));
    await user.click(screen.getByRole('link', { name: copy.de.backHome }));
    expect(fetch).not.toHaveBeenCalled();
    expect(xhr).not.toHaveBeenCalled();
  });
});
