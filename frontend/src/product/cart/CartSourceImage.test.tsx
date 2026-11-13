import { StrictMode } from 'react';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { expect, it, vi } from 'vitest';
import { authenticationFinished, authenticationStarted, createAppStore } from '../../store';
import { bindAccountCartReader } from './accountCartAdmission';
import { accountProductLabels } from './accountProductLabels';
import CartSourceImage from './CartSourceImage';

const [, label, unavailable] = accountProductLabels.en;
const id = '5a550000-0000-4000-8000-000000000001';
const png = () => new Response(new Uint8Array([137, 80, 78, 71]), {headers: {'content-type': 'image/png'}});
function setup() {
  const store = createAppStore('en', vi.fn());
  const owner = {document: 'page', epoch: 1, request: 1, revision: 'first'};
  store.dispatch(authenticationStarted({owner, phase: 'CHECKING', notice: 'NONE'}));
  store.dispatch(authenticationFinished({owner, result: {phase: 'AUTHENTICATED', notice: 'NONE', user: {id: 'buyer', status: 'ACTIVE'}}}));
  const isCurrentOwner = vi.fn(() => true);
  const admission = bindAccountCartReader(store, {read: vi.fn(), recover: vi.fn()}, {isCurrentOwner});
  const fetcher = vi.spyOn(globalThis, 'fetch');
  const create = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:owned-preview');
  const revoke = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {});
  const view = render(<StrictMode><Provider store={store}><CartSourceImage admission={admission} lineId={id} /></Provider></StrictMode>);
  const click = () => userEvent.click(screen.getByRole('button', {name: label}));
  return { isCurrentOwner, fetcher, create, revoke, view, click, dispose: admission.dispose };
}

it('requests one private image only on click, with same-origin GET; unmount revokes its sole URL', async () => {
  const {fetcher, create, revoke, view, click, dispose} = setup();
  fetcher.mockResolvedValueOnce(png());
  expect(fetcher).not.toHaveBeenCalled();
  expect(create).not.toHaveBeenCalled();
  await click();
  expect(await screen.findByRole('img', {name: label})).toHaveAttribute('src', 'blob:owned-preview');
  expect(fetcher).toHaveBeenCalledTimes(1);
  const [path, init] = fetcher.mock.calls[0];
  expect(path).toBe('/api/account-cart/lines/' + id + '/artwork');
  expect(init?.method ?? 'GET').toBe('GET');
  expect(init).toMatchObject({credentials:'same-origin', cache:'no-store', redirect:'error', headers:{Accept:'image/png'}});
  expect(init?.body).toBeUndefined();
  expect(create).toHaveBeenCalledTimes(1);
  expect(revoke).not.toHaveBeenCalled();
  view.unmount();
  expect(revoke).toHaveBeenCalledExactlyOnceWith('blob:owned-preview');
  dispose();
});

it.each(['changed admission', 'unmounted'] as const)('rejects private bytes completed after %s, without allocating a URL', async stop => {
  const {fetcher, create, revoke, view, click, isCurrentOwner, dispose} = setup();
  let finish!: (blob: Blob) => void;
  const response = png(), body = vi.spyOn(response, 'blob').mockReturnValue(new Promise(resolve => {finish = resolve;}));
  fetcher.mockResolvedValue(response);
  await click();
  await waitFor(() => expect(body).toHaveBeenCalledTimes(1));
  if (stop === 'changed admission') isCurrentOwner.mockReturnValue(false);
  else {
    view.unmount();
    expect(fetcher.mock.calls[0][1]?.signal?.aborted).toBe(true);
  }
  await act(async () => finish(new Blob([new Uint8Array([137,80,78,71])], {type:'image/png'})));
  expect(screen.queryByRole('img')).not.toBeInTheDocument();
  expect(create).not.toHaveBeenCalled();
  expect(revoke).not.toHaveBeenCalled();
  dispose();
});

it.each(['missing', 'ambiguous'])('shows the same generic message for %s artwork, without reading the response body', async detail => {
  const { fetcher, create, click, dispose } = setup();
  const response = new Response('Private server detail: ' + detail, {status:404});
  const body = vi.spyOn(response, 'blob');
  fetcher.mockResolvedValueOnce(response);
  await click();
  expect(await screen.findByRole('status')).toHaveTextContent(label + ': ' + unavailable);
  expect(screen.queryByText(/Private server detail/)).not.toBeInTheDocument();
  expect(screen.queryByRole('img')).not.toBeInTheDocument();
  expect(body).not.toHaveBeenCalled();
  expect(create).not.toHaveBeenCalled();
  dispose();
});
