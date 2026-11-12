import { StrictMode } from 'react';
import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { MemoryRouter } from 'react-router';
import { expect, it, vi } from 'vitest';
import { sameOwner } from '../../authBoundary';
import { authenticationFinished, authenticationStarted, createAppStore } from '../../store';
import { cartAdded } from '../demoStore';
import { productCopy } from '../copy';
import ProductExperience from '../ProductExperience';
import { bindAccountCartReader, type AccountCartLines } from './accountCartAdmission';
import type { AccountCartTransport } from './useAccountCartAccess';

vi.mock('@google/model-viewer', () => ({}));
const rows: AccountCartLines = [
  { lineId: 'a', offerLabel: 'Ceramic cup', variantLabel: 'Blue', designLabel: null, quantity: '3' },
  { lineId: 'b', offerLabel: 'Reading lamp', variantLabel: 'Amber', designLabel: 'Initials', quantity: '4' },
];

function setup() {
  const store = createAppStore('en', vi.fn());
  const owner = { document: 'page', epoch: 1, request: 1, revision: 'first' };
  store.dispatch(cartAdded({ variant: 'coral', quantity: 2 }));
  const guest = store.getState().productDemo.cart;
  store.dispatch(authenticationStarted({ owner, phase: 'CHECKING', notice: 'NONE' }));
  store.dispatch(authenticationFinished({ owner, result: { phase: 'AUTHENTICATED', notice: 'NONE', user: { id: 'buyer', status: 'ACTIVE' } } }));
  const read = vi.fn<AccountCartTransport<AccountCartLines>['read']>();
  const recover = vi.fn<AccountCartTransport<AccountCartLines>['recover']>();
  const access = bindAccountCartReader(store, { read, recover }, { isCurrentOwner: candidate => sameOwner(candidate, owner) });
  function mount(path = '/preview/cart') {
    return render(<StrictMode><Provider store={store}><MemoryRouter initialEntries={[path]}>
      <ProductExperience accountCart={access} />
    </MemoryRouter></Provider></StrictMode>);
  }
  return { store, owner, guest, read, recover, access, mount };
}

it.each([false, true])('restores the same cart in the storefront without consuming the guest cart (manual: %s)', async manual => {
  const { mount, read, recover, store, guest, access } = setup();
  read.mockResolvedValueOnce({ kind: 'pending', ownerId: 'buyer', ticket: 'original' })
    .mockResolvedValueOnce({ kind: 'ready', ownerId: 'buyer', cart: rows });
  if (manual) recover.mockResolvedValueOnce('retryable');
  recover.mockResolvedValueOnce('recovered');
  mount();
  if (manual) {
    const retry = await screen.findByRole('button', { name: 'Retry' });
    expect(within(screen.getByRole('main')).getByRole('status')).toHaveTextContent('temporarily unavailable');
    expect(screen.queryByText('Ceramic cup')).not.toBeInTheDocument();
    expect(screen.queryByText(productCopy.en.emptyCart, { exact: true })).not.toBeInTheDocument();
    expect(recover).toHaveBeenCalledTimes(1);
    await userEvent.click(retry);
  }
  expect(await screen.findByText('Reading lamp')).toBeVisible();
  expect(screen.getAllByRole('listitem').filter(node => node.closest('[data-private-account]')).map(node => node.textContent))
    .toEqual(['Ceramic cup · Blue × 3', 'Reading lamp · Amber · Initials × 4']);
  expect(recover.mock.calls.map(([ticket]) => ticket)).toEqual(manual ? ['original', 'original'] : ['original']);
  expect(read).toHaveBeenCalledTimes(2);
  expect(store.getState().productDemo.cart).toEqual(guest);
  expect(document.querySelector('[data-local-save]')).toBeNull();
  act(() => access.dispose());
});

it('shows the storefront empty-cart message only after an authorised read returns no lines', async () => {
  const { mount, read, recover, access } = setup();
  let resolveRead!: (result: Awaited<ReturnType<typeof read>>) => void;
  read.mockReturnValueOnce(new Promise(resolve => { resolveRead = resolve; }));
  mount();
  const main = within(screen.getByRole('main'));
  expect(main.getByRole('status')).toHaveTextContent('Opening your cart');
  expect(main.queryByText(productCopy.en.emptyCart, { exact: true })).not.toBeInTheDocument();
  await act(async () => resolveRead({ kind: 'ready', ownerId: 'buyer', cart: [] }));
  expect(main.getByText(productCopy.en.emptyCart, { exact: true })).toBeVisible();
  expect(recover).not.toHaveBeenCalled();
  act(() => access.dispose());
});

it('offers a distinct retry for loading the cart; it reads rows without performing any binding recovery', async () => {
  const { mount, read, recover, store, guest, access } = setup();
  read.mockResolvedValueOnce({ kind: 'read-unavailable' }).mockResolvedValueOnce({kind:'ready',ownerId:'buyer',cart:rows});
  mount();
  const retry = await screen.findByRole('button', { name: 'Retry loading cart' });
  expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
  expect(screen.queryByText(productCopy.en.emptyCart, { exact: true })).not.toBeInTheDocument();
  expect(read).toHaveBeenCalledTimes(1);
  await userEvent.click(retry);
  expect(await screen.findByText('Reading lamp')).toBeVisible();
  expect(read).toHaveBeenCalledTimes(2);
  expect(recover).not.toHaveBeenCalled();
  expect(store.getState().productDemo.cart).toEqual(guest);
  act(() => access.dispose());
});

it('retires the account read on a new authentication generation, even for the same owner', async () => {
  const { mount, read, recover, store, owner } = setup();
  read.mockResolvedValue({ kind: 'ready', ownerId: 'buyer', cart: rows });
  mount();
  expect(await screen.findByText('Ceramic cup')).toBeVisible();
  const next = { ...owner, epoch: 2, request: 2 };
  act(() => {
    store.dispatch(authenticationStarted({ owner: next, phase: 'CHECKING', notice: 'NONE' }));
    store.dispatch(authenticationFinished({ owner: next, result: { phase: 'AUTHENTICATED', notice: 'NONE', user: { id: 'buyer', status: 'ACTIVE' } } }));
  });
  expect(read.mock.calls[0][0].aborted).toBe(true);
  expect(screen.queryByText('Ceramic cup')).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
  expect(screen.getByText('Your cart is temporarily unavailable.')).toBeVisible();
  expect(read).toHaveBeenCalledTimes(1);
  expect(recover).not.toHaveBeenCalled();
});

it('never substitutes the guest mini-cart or checkout for an account preview', async () => {
  const { mount, read, access } = setup();
  mount('/preview/checkout');
  const main = within(await screen.findByRole('main'));
  expect(main.getByRole('link', { name: 'View cart' })).toHaveAttribute('href', '/preview/cart');
  expect(main.queryByText(/coral/i)).not.toBeInTheDocument();
  expect(within(screen.getByRole('banner')).getByRole('link', { name: 'Your cart' }).textContent).toBe('');
  expect(read).not.toHaveBeenCalled();
  act(() => access.dispose());
});
