import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { Provider } from 'react-redux';
import { Link, MemoryRouter } from 'react-router';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { createAppStore } from '../../../store';
import PersistentStudio from './PersistentStudio';
import { usePersonalizationSession } from './personalizationSession';
import { compositionHistory, initialStudioComposition } from '../design/studioComposition';
import { restoreStudio, serializeStudio, studioStorage } from './studioStorage';
import { defaultLettering } from '../design/personalization';
import { HeaderCart } from '../../HeaderControls';
import { cartAdded } from '../../demoStore';
import { productCopy } from '../../copy';

vi.mock('./studioStorage', async original => ({ ...await original<typeof import('./studioStorage')>(), studioStorage: vi.fn(), serializeStudio: vi.fn(), restoreStudio: vi.fn() }));
function deferred<T>() { let resolve!: (value: T) => void, reject!: (reason: Error) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
function Probe() {
  const { state, dispatch } = usePersonalizationSession();
  return <><span>Editor ready</span>{['A', 'B', 'OVERSIZE'].map(text => <button key={text} onClick={() => dispatch({ type: 'update', variant: 'cobalt', id: 'studio-object-1', object: { id: 'studio-object-1', kind: 'text', value: { ...defaultLettering('cobalt'), text } } })}>{text}</button>)}<div>{JSON.stringify(state.applied)}</div></>;
}
function show() { return render(<Provider store={createAppStore('en')}><MemoryRouter><PersistentStudio><Probe /></PersistentStudio></MemoryRouter></Provider>); }
beforeEach(() => {
  vi.stubGlobal('indexedDB', {});
  vi.mocked(studioStorage).mockReset().mockResolvedValue(null);
  vi.mocked(restoreStudio).mockReset().mockResolvedValue({ composition: initialStudioComposition(), drafts: {}, applied: {}, customFonts: [], lines: [], unavailableLines: [], serial: 1 });
  vi.mocked(serializeStudio).mockReset().mockImplementation(state => {
    const object = compositionHistory(state, 'cobalt').present.objects[0];
    const text = object.kind === 'text' ? object.value.text : '';
    if (text === 'OVERSIZE') throw new Error('LIMIT');
    return text || 'empty';
  });
});
afterEach(() => { vi.unstubAllGlobals(); });
it('keeps the failed-save warning on Home after removing the last unavailable item', async () => {
  const retired = [{variant:'retired-standard', quantity:1}];
  vi.mocked(restoreStudio).mockResolvedValue({composition:initialStudioComposition(), drafts:{}, applied:{},
    customFonts:[], lines:[], unavailableLines:retired, serial:1});
  vi.mocked(serializeStudio).mockImplementation((_composition, _creative, _cart, unlisted) => unlisted?.length ? 'before' : 'after');
  vi.mocked(studioStorage).mockImplementation(async (op, value) => {
    if (op === 'write' && value === 'after') throw new Error('QUOTA');
    return 'before';
  });
  render(<Provider store={createAppStore('en')}><MemoryRouter initialEntries={['/preview/cart']}>
    <PersistentStudio browsingHome={<p>Browse</p>}><Link to="/">Home</Link></PersistentStudio>
  </MemoryRouter></Provider>);
  fireEvent.click(await screen.findByRole('button', {name:'Remove · retired-standard'}));
  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Not saved locally'));
  fireEvent.click(screen.getByRole('link', {name:'Home'}));
  expect(screen.getByRole('status')).toHaveTextContent('Not saved locally');
  expect(studioStorage).toHaveBeenCalledWith('write', 'after', 'before');
});
it.each(['/collections/objects/tide', '/collections/objects/Tide/'])('waits for the cart before allowing purchases on %s', async path => {
  const read = deferred<string | null>();
  vi.mocked(studioStorage).mockReturnValue(read.promise);
  render(<Provider store={createAppStore('en')}><MemoryRouter initialEntries={[path]}>
    <PersistentStudio browsingHome={<span>Read-only browsing</span>}><span>Purchase controls</span></PersistentStudio>
  </MemoryRouter></Provider>);
  expect(screen.queryByText('Read-only browsing')).toBeNull();
  expect(screen.queryByText('Purchase controls')).toBeNull();
  await act(async () => read.resolve(null));
  expect(screen.getByText('Purchase controls')).toBeVisible();
});
it.each(['/', '/collections/objects', '/collections/objects/halo'])('keeps %s readable without exposing a stale cart and preserves a failed restore on navigation', async path => {
  const user = userEvent.setup(), read = deferred<string | null>(), store = createAppStore('en');
  store.dispatch(cartAdded({ variant: 'coral', quantity: 2 }));
  vi.mocked(studioStorage).mockReturnValue(read.promise);
  render(<Provider store={store}><MemoryRouter initialEntries={[path]}>
    <PersistentStudio browsingHome={<><h1>Browse</h1><HeaderCart ready={false} /></>}><Probe /></PersistentStudio>
  </MemoryRouter></Provider>);
  const cart = screen.getByRole('link', { name: productCopy.en.cart });
  expect(cart).toHaveAttribute('href', '/preview/cart');
  expect(cart).not.toHaveTextContent('2');
  await user.hover(cart);
  expect(screen.queryByRole('region', { name: productCopy.en.cartPreview })).toBeNull();
  await act(async () => read.reject(new Error('LOCAL_READ')));
  expect(screen.getByRole('heading', { name: 'Browse' })).toBeVisible();
  expect(screen.queryByRole('alert')).toBeNull();
  await user.click(cart);
  expect(screen.getByRole('alert')).toHaveTextContent('not been overwritten');
  expect(screen.queryByText('Editor ready')).toBeNull();
  expect(vi.mocked(studioStorage).mock.calls.map(call => call[0])).toEqual(['read']);
});

it('hydrates behind the homepage without replacing the shopper’s current control', async () => {
  const read = deferred<string | null>();
  vi.mocked(studioStorage).mockImplementation(operation => operation === 'read' ? read.promise : Promise.resolve(null));
  const search = <input aria-label="Search products" />;
  render(<Provider store={createAppStore('en')}><MemoryRouter initialEntries={['/']}>
    <PersistentStudio browsingHome={<div>{search}<HeaderCart ready={false} /></div>}><div>{search}<HeaderCart /></div></PersistentStudio>
  </MemoryRouter></Provider>);
  const input = screen.getByRole('textbox');
  input.focus(); fireEvent.change(input, { target: { value: 'plant' } });
  await act(async () => read.resolve(null));
  expect(screen.getByRole('textbox')).toBe(input);
  expect(input).toHaveFocus(); expect(input).toHaveValue('plant');
  expect(screen.getByRole('link', { name: productCopy.en.cart })).toHaveAttribute('aria-expanded', 'false');
});
it('reports a failed save for a cart on the homepage without blocking browsing', async () => {
  const store = createAppStore('en');
  store.dispatch(cartAdded({ variant: 'coral', quantity: 1 }));
  vi.mocked(studioStorage).mockImplementation(op => op === 'write' ? Promise.reject(new Error('QUOTA')) : Promise.resolve(null));
  render(<Provider store={store}><MemoryRouter initialEntries={['/']}>
    <PersistentStudio browsingHome={<h1>Browse</h1>}><h1>Browse</h1><HeaderCart /></PersistentStudio>
  </MemoryRouter></Provider>);
  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Not saved locally'));
  expect(screen.getByRole('heading', { name: 'Browse' })).toBeVisible();
  expect(screen.getByRole('link', { name: /cart/i })).toBeVisible();
});
it('never writes an empty snapshot or exposes editing before hydration resolves', async () => {
  const read = deferred<string | null>(); vi.mocked(studioStorage).mockImplementation(operation => operation === 'read' ? read.promise : Promise.resolve(null));
  show(); expect(screen.getByRole('status')).toHaveTextContent('Restoring');
  expect(screen.queryByText('Editor ready')).toBeNull(); expect(studioStorage).toHaveBeenCalledTimes(1);
  await act(async () => read.resolve('retained-copy'));
  expect(screen.getByText('Editor ready')).toBeVisible();
  expect(restoreStudio).toHaveBeenCalledWith('retained-copy', expect.anything(), expect.any(AbortSignal));
  await waitFor(() => expect(studioStorage).toHaveBeenCalledWith('write', 'empty', 'retained-copy'));
});
it('preserves a corrupt stored copy and offers a non-writing memory session', async () => {
  vi.mocked(studioStorage).mockResolvedValue('corrupt'); vi.mocked(restoreStudio).mockRejectedValue(new Error('CORRUPT'));
  show(); expect(await screen.findByRole('alert')).toHaveTextContent('not been overwritten');
  expect(screen.queryByText('Editor ready')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'Continue without local saving' }));
  fireEvent.click(screen.getByRole('button', { name: 'B' }));
  expect(screen.getByText('Editor ready')).toBeVisible();
  expect(vi.mocked(studioStorage).mock.calls.map(call => call[0])).toEqual(['read']);
  expect(screen.getByRole('status')).toHaveTextContent('Not saved locally');
});
it('serializes overlapping saves and never labels the older completed snapshot as the latest saved design', async () => {
  const a = deferred<string | null>(), b = deferred<string | null>();
  vi.mocked(studioStorage).mockImplementation((operation, value) => operation === 'write' && value === 'A' ? a.promise : value === 'B' ? b.promise : Promise.resolve(null));
  show(); await screen.findByText('Editor ready');
  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Saved'));
  fireEvent.click(screen.getByRole('button', { name: 'A' }));
  fireEvent.click(screen.getByRole('button', { name: 'B' }));
  expect(studioStorage).not.toHaveBeenCalledWith('write', 'B', expect.anything());
  await act(async () => a.resolve(null));
  expect(screen.getByRole('status')).toHaveTextContent('Saving locally');
  expect(studioStorage).toHaveBeenLastCalledWith('write', 'B', 'A');
  await act(async () => b.resolve(null));
  expect(screen.getByRole('status')).toHaveTextContent('Saved in this browser');
});
it('retains failure when B cannot serialize even if an earlier write A later succeeds', async () => {
  const a = deferred<string | null>();
  vi.mocked(studioStorage).mockImplementation((_operation, value) => value === 'A' ? a.promise : Promise.resolve(null));
  show(); await screen.findByText('Editor ready'); await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Saved'));
  fireEvent.click(screen.getByRole('button', { name: 'A' }));
  fireEvent.click(screen.getByRole('button', { name: 'OVERSIZE' }));
  expect(screen.getByRole('status')).toHaveTextContent('Not saved locally');
  await act(async () => a.resolve(null));
  expect(screen.getByRole('status')).toHaveTextContent('Not saved locally');
  expect(studioStorage).toHaveBeenLastCalledWith('write', 'A', 'empty');
});
it('reports transaction/quota failure without claiming saved, and keeps the editor usable', async () => {
  vi.mocked(studioStorage).mockImplementation(operation => operation === 'write' ? Promise.reject(new Error('QUOTA')) : Promise.resolve(null));
  show(); await screen.findByText('Editor ready');
  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Not saved locally'));
  fireEvent.click(screen.getByRole('button', { name: 'B' }));
  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Not saved locally'));
  expect(screen.getByText('Editor ready')).toBeVisible();
});
it('drops a late hydration result after unmount and never writes it', async () => {
  const read = deferred<string | null>(); vi.mocked(studioStorage).mockReturnValue(read.promise);
  const view = show(); view.unmount();
  await act(async () => read.resolve('late'));
  expect(restoreStudio).not.toHaveBeenCalled(); expect(studioStorage).toHaveBeenCalledTimes(1);
});
it('keeps the previous committed expected value after A fails, then saves B against it', async () => {
  const a = deferred<string | null>();
  vi.mocked(studioStorage).mockImplementation((operation, value) => operation === 'read' ? Promise.resolve(null) : value === 'A' ? a.promise : Promise.resolve(null));
  show(); await screen.findByText('Editor ready'); await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Saved'));
  fireEvent.click(screen.getByRole('button', { name: 'A' }));
  fireEvent.click(screen.getByRole('button', { name: 'B' }));
  await act(async () => a.reject(new Error('QUOTA')));
  expect(studioStorage).toHaveBeenLastCalledWith('write', 'B', 'empty');
  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Saved in this browser'));
});
it.each(['bitmap', 'font'])('does not publish a partial restore after %s decoding fails', async kind => {
  vi.mocked(studioStorage).mockResolvedValue('retained-copy'); vi.mocked(restoreStudio).mockRejectedValue(new Error(kind));
  show(); expect(await screen.findByRole('alert')).toHaveTextContent('not been overwritten');
  expect(screen.queryByText('Editor ready')).toBeNull();
  expect(vi.mocked(studioStorage).mock.calls.map(call => call[0])).toEqual(['read']);
});
