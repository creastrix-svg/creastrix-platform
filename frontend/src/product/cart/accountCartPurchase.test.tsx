import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { Provider } from 'react-redux';
import { beforeAll, expect, it, vi } from 'vitest';
import { createAppStore, authenticationStarted, authenticationFinished } from '../../store';
import { sameOwner } from '../../authBoundary';
import ProductExperience from '../ProductExperience';
import { loadLocalCatalog } from '../catalog/localCatalog';
import { productCopy } from '../copy';
import { cartAdded } from '../demoStore';
import { defaultLettering } from '../studio/design/personalization';
import { saveDesign } from '../studio/session/designSnapshot';
import { bindAccountCartReader, type AccountCartAdmission } from './accountCartAdmission';

vi.hoisted(() => { vi.stubEnv('MODE', 'local-catalog'); });
vi.mock('@google/model-viewer', () => ({}));

const products = [
  { id: 'loop', href: '/products/loop', sku: 'cobalt' },
  { id: 'tide', href: '/collections/objects/tide', sku: 'tide-cobalt' },
  { id: 'pot', href: '/products/pot', sku: 'pot-standard' },
  { id: 'halo', href: '/collections/objects/halo', sku: 'halo-standard' },
  { id: 'tray', href: '/preview/items/tray', sku: 'tray-standard' },
];
const t = productCopy.en;
beforeAll(async () => {
  const labels = (s: string) => ({ en: s, de: s, pl: s, ru: s, es: s, it: s, fr: s });
  const payload = {
    products: products.map(({ id, href, sku }) => ({ id, href, name: id, currency: 'EUR', personalizable: id === 'loop',
      designFeeCents: 0, variants: (id === 'loop' ? ['cobalt', 'coral', 'chalk', 'cobalt-chalk'] : [id === 'tide' ? 'cobalt' : 'standard'])
        .map(v => ({ id: v, sku: id === 'loop' ? v : sku, name: labels(v), cents: 100, previousCents: null,
          stock: 5, colors: ['#333333'], checkTone: 'light', photos: ['/product/reference-asset-01.webp'], model: null })) })),
    entries: products.map(({ id, href }) => ({ id, href, name: id, kind: 'offer', title: labels(id), material: labels('clay'),
      description: labels(''), categories: ['living'], homeOrder: null, editionOrder: null, collectionId: null,
      collectionName: null, rating: { count: 0, average: null }, photo: null, thumb: null })),
    recommendations: [],
  };
  const request = vi.spyOn(window, 'fetch').mockResolvedValue(new Response(JSON.stringify(payload), { headers: { 'content-type': 'application/json' } }));
  try { await loadLocalCatalog(); } finally { request.mockRestore(); }
});

function setup() {
  const store = createAppStore('en');
  const owner = { document: 'page', epoch: 1, request: 1, revision: 'test' };
  store.dispatch(authenticationStarted({ owner, phase: 'CHECKING', notice: 'NONE' }));
  store.dispatch(authenticationFinished({ owner, result: { phase: 'AUTHENTICATED', notice: 'NONE', user: { id: 'reader', status: 'ACTIVE' } } }));
  const accountCart = bindAccountCartReader(store, {
    read: async () => ({ kind: 'ready', ownerId: 'reader', cart: [] }),
    recover: vi.fn(),
  }, { isCurrentOwner: candidate => sameOwner(candidate, owner) });
  const page = (path: string, admission?: AccountCartAdmission) => <Provider store={store}><MemoryRouter initialEntries={[path]}>
    <ProductExperience accountCart={admission} />
  </MemoryRouter></Provider>;
  return { page, store, accountCart };
}

it.each(products)('disables guest addition and confirmation on the account surface at $href', async ({ href, sku }) => {
  const { store, page, accountCart } = setup();
  const { rerender } = render(page(href));
  const user = userEvent.setup();
  const add = await screen.findByRole('button', { name: t.add });
  expect(add).toBeEnabled();
  await user.click(add);
  const before = store.getState().productDemo.cart;
  expect(before).toEqual([{ variant: sku, quantity: 1 }]);
  const confirmed = t.alreadyInCart.replace('{n}', '1');
  expect(screen.getByText(confirmed)).toBeVisible();

  rerender(page(href, accountCart));
  const blocked = screen.getByRole('button', { name: t.add });
  expect(blocked).toBeDisabled();
  expect(blocked).toHaveAccessibleDescription(/read-only/);
  expect(screen.queryByText(confirmed)).not.toBeInTheDocument();
  await user.click(blocked);
  expect(store.getState().productDemo.cart).toEqual(before);
  await user.click(within(screen.getByRole('region', { name: t.add })).getByRole('link', { name: t.viewCart }));
  expect(await screen.findByText(t.emptyCart)).toBeVisible();
  expect(store.getState().productDemo.cart).toEqual(before);
  act(() => accountCart.dispose());
});

it.each(['?variant=cobalt&cartLine=design-1', '/personalize?variant=cobalt&edit=design-1'])(
  'does not bind an old guest personalisation URL to the account cart: %s', async suffix => {
    const { store, page, accountCart } = setup();
    const design = saveDesign({ selectedId: null, objects: [{ id: 'layer', kind: 'text', value: { ...defaultLettering('cobalt'), text: 'Guest only' } }] });
    store.dispatch(cartAdded({ variant: 'cobalt', quantity: 2, design }));
    const before = store.getState().productDemo.cart;
    expect(before[0]).toMatchObject({ id: 'design-1', quantity: 2, design });
    render(page('/products/loop' + suffix, accountCart));
    const main = within(await screen.findByRole('main'));
    expect(main.getByText(/account cart is read-only/)).toBeVisible();
    expect(main.queryByRole('button')).not.toBeInTheDocument();
    await userEvent.click(main.getByRole('link', { name: t.viewCart }));
    expect(await screen.findByText(t.emptyCart)).toBeVisible();
    expect(store.getState().productDemo.cart).toEqual(before);
    act(() => accountCart.dispose());
  },
);
