import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { StrictMode } from 'react';
import { Provider } from 'react-redux';
import { MemoryRouter } from 'react-router';
import { afterEach, expect, it, vi } from 'vitest';
import { authenticationFinished, authenticationStarted, createAppStore } from '../../store';
import ProductExperience from '../ProductExperience';
import { catalogReady, connectedMode } from '../catalog/localCatalog';
import { productCopy } from '../copy';
import { PRODUCT_PREFERENCES_KEY } from '../preferences';
import { bindAccountCartReader } from './accountCartAdmission';

vi.hoisted(() => { vi.stubEnv('MODE', 'local-catalog'); });
afterEach(() => vi.restoreAllMocks());

it.each(['/preview/cart', '/preview/checkout'])('opens %s with cold connected catalog, including navigation and metadata', async path => {
  expect(connectedMode).toBe(true);
  expect(catalogReady()).toBe(false);
  const http = vi.spyOn(window, 'fetch').mockRejectedValue(new Error('No catalog available'));
  const guestPreferences = window.localStorage.getItem(PRODUCT_PREFERENCES_KEY);
  const store = createAppStore('en', vi.fn());
  const owner = { document: 'page', epoch: 1, request: 1, revision: 'first' };
  store.dispatch(authenticationStarted({ owner, phase: 'CHECKING', notice: 'NONE' }));
  store.dispatch(authenticationFinished({ owner, result: { phase: 'AUTHENTICATED', notice: 'NONE', user: { id: 'reader', status: 'ACTIVE' } } }));
  const read = vi.fn(async () => ({ kind: 'ready' as const, ownerId: 'reader', cart: [] }));
  const admission = bindAccountCartReader(store, { read, recover: vi.fn() }, { isCurrentOwner: () => true });
  const view = render(<StrictMode><Provider store={store}><MemoryRouter initialEntries={[path]}>
    <ProductExperience accountCart={admission} />
  </MemoryRouter></Provider></StrictMode>);
  try {
    const t = productCopy.en, cart = path === '/preview/cart';
    const main = within(screen.getByRole('main'));
    if (cart) expect(await main.findByText(t.emptyCart)).toBeVisible();
    else expect(main.getByRole('link', { name: t.viewCart })).toHaveAttribute('href', '/preview/cart');
    expect(document.title).toBe(t.cart + ' · Creastrix');
    expect(document.head.querySelector('meta[name="description"]')?.getAttribute('content'))
      .toBe(t.viewCart);

    const user = userEvent.setup();
    await user.type(screen.getByRole('combobox', { name: t.search }), 'Tide');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: t.categories }));
    expect(within(screen.getByRole('dialog')).getAllByRole('link').some(link => link.getAttribute('href') === '/catalog')).toBe(true);
    expect(read).toHaveBeenCalledTimes(cart ? 1 : 0);
    expect(http).not.toHaveBeenCalled();
    expect(catalogReady()).toBe(false);
    expect(window.localStorage.getItem(PRODUCT_PREFERENCES_KEY)).toBe(guestPreferences);
  } finally {
    view.unmount();
    admission.dispose();
  }
});
