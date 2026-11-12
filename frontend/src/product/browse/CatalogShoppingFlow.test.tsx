import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { expect, it } from 'vitest';
import { createAppStore } from '../../store';
import ProductExperience from '../ProductExperience';
import { catalogCopy } from './catalogCopy';
import { productCopy } from '../copy';
import { productLocaleSelected } from '../demoStore';
import { homeCopy } from '../home/homeCopy';
import { tideOffer } from '../fixtures/tideOffer';

const c = catalogCopy.en, t = productCopy.en, home = homeCopy.en;
const main = () => within(screen.getByRole('main'));
function scrollPage(top: number) {
  window.scrollTo({ top, behavior: 'instant' });
  fireEvent.scroll(window);
}

it.each([
  { name: 'Loop', department: home.personal, search: '?custom=yes', categoryFilter: c.withPersonalization, filter: c.sale, product: '/products/loop?variant=chalk', finish: 'Chalk' },
  { name: 'Tide', department: home.ready, search: '?format=ready', categoryFilter: c.ready, filter: c.withoutPersonalization, product: tideOffer.href, finish: 'Cobalt blue' },
])('restores the category, filters and scroll on Back from the $name shopping flow', async ({ name, department, search, categoryFilter, filter, product, finish }) => {
  const store = createAppStore('en'), user = userEvent.setup();
  store.dispatch(productLocaleSelected('en'));
  const router = createMemoryRouter([{ path: '*', element: <ProductExperience /> }], { initialEntries: [{ pathname: '/', key: 'home-' + name }] });
  render(<Provider store={store}><RouterProvider router={router} /></Provider>);
  const url = () => router.state.location.pathname + router.state.location.search;
  const back = () => act(async () => { await router.navigate(-1); });
  const departments = () => main().getByRole('navigation', { name: home.headline });
  const category = within(departments()).getByRole('link', { name: new RegExp('^' + department) });
  expect(category).toHaveAttribute('href', '/catalog' + search);
  scrollPage(510);
  await user.click(category);
  expect(url()).toBe('/catalog' + search);

  await user.click(main().getByRole('button', { name: c.filters }));
  await user.click(main().getByRole('checkbox', { name: filter }));
  await user.selectOptions(main().getByRole('combobox', { name: c.sort }), 'price-asc');
  const catalogURL = url();
  const result = main().getByRole('link', { name });
  expect(result).toHaveAttribute('href', product);
  scrollPage(360);
  await user.click(result);
  expect(url()).toBe(product);
  expect(window.scrollY).toBe(0);
  expect(main().getByRole('button', { name: finish })).toHaveAttribute('aria-pressed', 'true');
  await user.click(main().getByRole('button', { name: t.add }));
  scrollPage(225);
  await user.click(main().getByRole('link', { name: t.viewCart }));
  expect(url()).toBe('/preview/cart');
  expect(window.scrollY).toBe(0);
  const line = main().getByRole('article', { name: new RegExp('^' + name + ' · ') });
  expect(within(line).getByRole('link', { name })).toHaveAttribute('href', name === 'Loop' ? product + '&cartLine=chalk' : product);
  expect(main().getByRole('link', { name: t.continue })).toHaveAttribute('href', product);

  await back();
  expect(url()).toBe(product);
  expect(window.scrollY).toBe(225);
  expect(main().getByRole('link', { name: c.title })).toHaveAttribute('href', catalogURL);
  await back();
  expect(url()).toBe(catalogURL);
  expect(main().getByRole('button', { name: c.filters })).toHaveAttribute('aria-expanded', 'true');
  expect(main().getByRole('checkbox', { name: categoryFilter })).toBeChecked();
  expect(main().getByRole('checkbox', { name: filter })).toBeChecked();
  expect(main().getByRole('combobox', { name: c.sort })).toHaveValue('price-asc');
  expect(main().getByRole('link', { name })).toHaveAttribute('href', product);
  expect(window.scrollY).toBe(360);
  await back();
  expect(url()).toBe('/');
  expect(departments()).toBeVisible();
  expect(window.scrollY).toBe(510);
});
