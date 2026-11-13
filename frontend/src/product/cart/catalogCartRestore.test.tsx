import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { Provider } from 'react-redux';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeAll, expect, it, vi } from 'vitest';
import { authenticationFinished, authenticationStarted, createAppStore } from '../../store';
import ProductExperience from '../ProductExperience';
import { bindAccountCartReader, type AccountCartAdmission } from './accountCartAdmission';
import * as catalog from '../catalog/localCatalog';
import { isCartSku } from '../cartSku';
import { cartAdded, cartHydrated, cartTotal } from '../demoStore';
import { defaultLettering } from '../studio/design/personalization';
import { createStudioFontLibrary } from '../studio/design/studioFonts';
import { saveDesign, sessionWithDocument } from '../studio/session/designSnapshot';
import { initialStudioComposition } from '../studio/design/studioComposition';
import PersistentStudio from '../studio/session/PersistentStudio';
import * as storage from '../studio/session/studioStorage';
import { unlistedCartNote } from './studioCartCopy';

vi.hoisted(() => { vi.stubEnv('MODE', 'local-catalog'); });
const ids = Array.from({ length: 37 }, (_, i) => 'sample-' + i);
const labels = (text: string) => ({ en: text, de: text, es: text, fr: text, it: text, pl: text, ru: text });
beforeAll(async () => {
  const finish = (id: string, sku: string) => ({ id, sku, name: labels(id), stock: 24, cents: 100,
    previousCents: null, checkTone: 'dark', colors: ['#ffffff'], photos: [], model: null });
  const offer = (id: string, href: string, variants: ReturnType<typeof finish>[]) => ({ id, href, name: id,
    personalizable: id === 'loop', designFeeCents: 0, currency: 'EUR', variants });
  const products = [offer('loop', '/products/loop', ['cobalt', 'coral', 'chalk', 'cobalt-chalk'].map(id => finish(id, id))),
    offer('tide', '/collections/objects/tide', [finish('cobalt', 'tide-cobalt')]),
    ...ids.map(id => offer(id, '/collections/objects/' + id, [finish('standard', id + '-standard')]))];
  const entries = products.map(({ id, href, name }) => ({ id, href, name, kind: 'offer', title: labels(name),
    description: labels(''), material: labels(''), photo: null, thumb: null, categories: [], collectionId: null,
    collectionName: null, editionOrder: null, homeOrder: null, rating: { count: 0, average: null } }));
  vi.spyOn(window, 'fetch').mockResolvedValue(new Response(JSON.stringify({ products, entries, recommendations: [] }),
    { headers: { 'content-type': 'application/json' } }));
  await catalog.loadLocalCatalog();
});
afterEach(() => vi.unstubAllGlobals());

const sketch = (text: string) => ({ selectedId: 'studio-object-7', objects: [{ id: 'studio-object-7', kind: 'text' as const,
  value: { ...defaultLettering('chalk'), text } }] });
const creative = { drafts: {}, applied: {}, customFonts: [] };

it('hides a restored guest-only unlisted row in account mode and keeps it available on return to the guest cart', async () => {
  const store = createAppStore('en');
  const owner = { document: 'page', epoch: 1, request: 1, revision: 'first' };
  store.dispatch(authenticationStarted({ owner, phase: 'CHECKING', notice: 'NONE' }));
  store.dispatch(authenticationFinished({ owner, result: { phase: 'AUTHENTICATED', notice: 'NONE', user: { id: 'buyer', status: 'ACTIVE' } } }));
  const read = vi.fn(), recover = vi.fn();
  const admission = bindAccountCartReader(store, { read, recover }, { isCurrentOwner: () => true });
  const retired = { variant: 'retired-standard', quantity: 3 };
  const original = storage.serializeStudio(initialStudioComposition(), creative, { cart: [], cartSerial: 1 }, [retired]);
  let disk = original;
  vi.stubGlobal('indexedDB', {});
  Object.defineProperty(document, 'fonts', { configurable: true, value: { add: vi.fn(), delete: vi.fn() } });
  vi.stubGlobal('FontFace', class { async load() { return this; } });
  const storageCall = vi.spyOn(storage, 'studioStorage').mockImplementation(async (op, value, expected) => {
    const previous = disk;
    if (op === 'write') {
      if (expected !== previous) throw new Error('STALE_WRITE');
      disk = value!;
    }
    return previous;
  });
  const page = (accountCart?: AccountCartAdmission) => <Provider store={store}><MemoryRouter initialEntries={['/preview/info/help']}>
    <ProductExperience accountCart={accountCart} />
  </MemoryRouter></Provider>;
  const view = render(page(admission));
  try {
    // Exercise the actual persistence gate on a non-cart route; the account cart
    // route deliberately bypasses it and would not expose the old failure.
    await waitFor(() => expect(storageCall.mock.calls.some(([op]) => op === 'write')).toBe(true));
    expect(screen.queryByText(unlistedCartNote.en)).not.toBeInTheDocument();
    expect(JSON.parse(disk).lines).toEqual([retired]);
    expect(read).not.toHaveBeenCalled();
    view.rerender(page());
    expect(await screen.findByText(unlistedCartNote.en)).toBeVisible();
    expect(JSON.parse(disk).lines).toEqual([retired]);
  } finally {
    view.unmount();
    admission.dispose();
  }
});
const fonts = () => {
  const library = createStudioFontLibrary();
  vi.spyOn(library, 'loadBuiltin').mockImplementation(async id => ({ id, family: id, label: id }));
  return library;
};

it('round-trips 64 and 65 distinct stocked lines with their designs, serial, and a further product draft', async () => {
  const store = createAppStore('en');
  for (const id of ids) {
    const sku = id + '-standard';
    if (!isCartSku(sku)) throw new Error('Fixture SKU is not in the catalog');
    store.dispatch(cartAdded({ variant: sku, quantity: 1 }));
  }
  store.dispatch(cartAdded({ variant: 'tide-cobalt', quantity: 1 }));
  for (let i = 0; i < 26; i++) store.dispatch(cartAdded({ variant: i < 23 ? 'cobalt' : 'chalk',
    quantity: 1, design: saveDesign(sketch('Design ' + i)) }));
  const state = store.getState().productDemo, composition = sessionWithDocument('coral', sketch('First draft'));
  expect(state.cart).toHaveLength(64);
  const library = fonts(), at64 = await storage.restoreStudio(storage.serializeStudio(composition, creative, state), library);
  expect(at64.lines).toEqual(state.cart);
  const fresh = createAppStore('en');
  fresh.dispatch(cartHydrated(at64));
  fresh.dispatch(cartAdded({ variant: 'chalk', quantity: 1, design: saveDesign(sketch('One more design')) }));
  const updated = fresh.getState().productDemo;
  expect(updated.cart).toHaveLength(65);
  const raw = storage.serializeStudio({ ...at64.composition, applied: { chalk: sketch('Next draft') } }, creative, updated);
  const at65 = await storage.restoreStudio(raw, library);
  expect(at65.lines).toEqual(updated.cart);
  expect(at65.serial).toBe(updated.cartSerial);
  expect(at65.composition.histories.coral?.present).toEqual(sketch('First draft'));
  expect(at65.composition.applied.chalk).toEqual(sketch('Next draft'));
  const invalid = JSON.parse(raw);
  invalid.lines.at(-1).quantity = 1_000_000; // Other designs of this SKU already consume units.
  await expect(storage.restoreStudio(JSON.stringify(invalid), library)).rejects.toThrow('STUDIO_STORAGE_STOCK');
  library.dispose();
});

it('keeps delisted merchandise and drafts across hydration and subsequent writes; only explicit removal forgets it', async () => {
  const sku = 'sample-0-standard';
  if (!isCartSku(sku)) throw new Error('Fixture SKU is not in the catalog');
  const purchased = createAppStore('en');
  purchased.dispatch(cartAdded({ variant: sku, quantity: 2 }));
  purchased.dispatch(cartAdded({ variant: 'coral', quantity: 1, design: saveDesign(sketch('Cart design')) }));
  const active = purchased.getState().productDemo.cart[1], composition = sessionWithDocument('chalk', sketch('My draft'));
  let disk = storage.serializeStudio(composition, creative, purchased.getState().productDemo);
  const lookup = catalog.connectedSku;
  const current = vi.spyOn(catalog, 'connectedSku').mockImplementation(key => key === sku ? undefined : lookup(key));
  vi.stubGlobal('indexedDB', {});
  Object.defineProperty(document, 'fonts', { configurable: true, value: { add: vi.fn(), delete: vi.fn() } });
  vi.stubGlobal('FontFace', class { async load() { return this; } });
  vi.spyOn(storage, 'studioStorage').mockImplementation(async (op, value, expected) => {
    const previous = disk;
    if (op === 'write') { if (expected !== previous) throw new Error('STALE_WRITE'); disk = value!; }
    return previous;
  });
  const show = (store: ReturnType<typeof createAppStore>) => render(<Provider store={store}><MemoryRouter initialEntries={['/preview/cart']}>
    <PersistentStudio><span>Cart ready</span></PersistentStudio>
  </MemoryRouter></Provider>);
  const store = createAppStore('en'), page = show(store);
  expect(await screen.findByText(unlistedCartNote.en)).toBeVisible();
  expect(screen.getByText(sku + ' × 2')).toBeVisible();
  expect(store.getState().productDemo.cart).toEqual([active]);
  expect(cartTotal(store.getState().productDemo)).toBe(100);
  act(() => { store.dispatch(cartAdded({ variant: 'tide-cobalt', quantity: 1 })); });
  const kept = { variant: sku, quantity: 2 };
  await waitFor(() => expect(JSON.parse(disk).lines).toEqual([active, { variant: 'tide-cobalt', quantity: 1 }, kept]));
  const retained = disk;
  page.unmount();
  const fresh = createAppStore('en'); show(fresh);
  fireEvent.click(await screen.findByRole('button', { name: 'Remove · ' + sku }));
  await waitFor(() => expect(JSON.parse(disk).lines).toEqual([active, { variant: 'tide-cobalt', quantity: 1 }]));
  expect(screen.queryByText(unlistedCartNote.en)).toBeNull();
  expect(JSON.parse(disk).drafts.chalk.objects).toEqual(sketch('My draft').objects);
  current.mockRestore();
  const library = fonts(), relisted = await storage.restoreStudio(retained, library);
  expect(relisted.lines).toContainEqual(kept);
  expect(relisted.unavailableLines).toEqual([]);
  library.dispose();
});

it.each([
  [{ variant: '../missing', quantity: 1 }], [{ variant: 'design-1', quantity: 1 }],
  [{ variant: 'old-standard', quantity: 0 }], [{ variant: 'old-standard', quantity: 1_000_001 }],
  [{ variant: 'old-standard', quantity: 1, design: { objects: [] } }],
  [{ variant: 'old-standard', quantity: 1, id: 'design-1' }],
  [{ variant: 'old-standard', quantity: 1 }, { variant: 'old-standard', quantity: 2 }],
].map(lines => ({ lines })))('does not treat a malformed or duplicate row as delisted merchandise ($lines)', async ({ lines }) => {
  const raw = JSON.parse(storage.serializeStudio(sessionWithDocument('chalk', sketch('Draft')), creative, { cart: [], cartSerial: 1 }));
  const library = fonts();
  await expect(storage.restoreStudio(JSON.stringify({ ...raw, lines }), library)).rejects.toThrow();
  library.dispose();
});
