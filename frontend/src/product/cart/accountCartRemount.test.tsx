import { StrictMode, useLayoutEffect } from 'react';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { Link, MemoryRouter, Route, Routes } from 'react-router';
import { expect, it, vi } from 'vitest';
import { createAuthentication } from '../../authentication';
import { AUTH_BOUNDARY_KEY } from '../../authBoundary';
import { authenticationFinished, authenticationStarted, createAppStore } from '../../store';
import AccountCartView from './AccountCartView';
import { bindAccountCartReader } from './accountCartAdmission';

it.each([false, true])('reopens cached rows only after a fresh display admission (peer changed: %s)', async changed => {
  const store = createAppStore('en', vi.fn()), shared = new Map<string, string>();
  const userId = '44444444-4444-4444-8444-444444444444';
  const auth = createAuthentication({
    enabled: true, nonce: () => crypto.randomUUID(),
    fetch: async () => Response.json({ id: userId, status: 'ACTIVE' }),
    storage: () => ({ getItem: key => shared.get(key) ?? null, setItem: (key, value) => { shared.set(key, value); } }),
    closePrivate: vi.fn(), openPrivate: vi.fn(),
    started: (owner, phase, notice) => { store.dispatch(authenticationStarted({ owner, phase, notice })); },
    finished: (owner, result) => { store.dispatch(authenticationFinished({ owner, result })); },
  });
  await auth.start();
  const name = 'Account-only item';
  const read = vi.fn(async () => ({ kind: 'ready' as const, ownerId: userId,
    cart: [{lineId: 'one', offerLabel: name, variantLabel: 'Blue', designLabel: null, quantity: '3'}] }));
  const recover = vi.fn(), admission = bindAccountCartReader(store, { read, recover }, auth);
  const cachedRowsAtMount: boolean[] = [];
  function CartRoute() {
    // Observe the commit itself; a subsequent passive effect hiding the row is too late.
    useLayoutEffect(() => { cachedRowsAtMount.push(!!document.querySelector('[data-private-account]')); }, []);
    return <AccountCartView admission={admission} />;
  }
  const view = render(<StrictMode><Provider store={store}><MemoryRouter initialEntries={['/preview/cart']}>
    <nav><Link to="/catalog">Catalogue</Link><Link to="/preview/cart">Open cart</Link></nav>
    <Routes><Route path="/catalog" element={<p>Browse products</p>} /><Route path="/preview/cart" element={<CartRoute />} /></Routes>
  </MemoryRouter></Provider></StrictMode>);
  try {
    const user = userEvent.setup();
    expect(await screen.findByText(name)).toBeVisible();
    await user.click(screen.getByRole('link', { name: 'Catalogue' }));
    expect(screen.queryByText(name)).toBeNull();
    cachedRowsAtMount.length = 0;
    if (changed) shared.set(AUTH_BOUNDARY_KEY, JSON.stringify({nonce: 'new-peer', phase: 'transition'}));
    expect(store.getState().authentication.phase).toBe('AUTHENTICATED');
    await user.click(screen.getByRole('link', { name: 'Open cart' }));
    if (changed) {
      expect(cachedRowsAtMount).not.toContain(true);
      expect(store.getState().authentication.notice).toBe('PEER_CHANGED');
      expect(screen.queryByText(name)).toBeNull();
      expect(screen.getByText('Your cart is temporarily unavailable.')).toBeVisible();
    } else expect(await screen.findByText(name)).toBeVisible();
    expect(read).toHaveBeenCalledTimes(1);
    expect(recover).not.toHaveBeenCalled();
  } finally {
    view.unmount();
    act(() => { admission.dispose(); auth.dispose(); });
  }
});
