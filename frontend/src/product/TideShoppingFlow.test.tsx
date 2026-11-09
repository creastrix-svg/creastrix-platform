import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { MemoryRouter } from 'react-router';
import { expect, it } from 'vitest';
import { createAppStore } from '../store';
import ProductExperience from './ProductExperience';
import { cartCount, cartTotal, productLocaleSelected } from './demoStore';
import { productCopy } from './copy';
import { money } from './catalog';
import { shelfCopy } from './homeShelfCopy';
import { catalogCopy } from './catalogCopy';
import { variantById } from './catalog';
import { tideOffer } from './tideOffer';
import { isStorefrontPath } from './routes';

it('takes a buyer from Gifts to two Tide cups, back to the same cup, and to it again from live search', async () => {
  const store = createAppStore('en'), user = userEvent.setup(), t = productCopy.en;
  store.dispatch(productLocaleSelected('en'));
  render(<Provider store={store}><MemoryRouter initialEntries={['/']}><ProductExperience/></MemoryRouter></Provider>);
  const main = () => within(screen.getByRole('main'));
  await user.click(main().getByRole('button', {name:shelfCopy.en.gifts}));
  const tile = main().getByRole('link', { name:/^Tide / });
  expect(tile).toHaveAttribute('href', tideOffer.href);
  await user.click(tile);
  expect(main().getByRole('heading', {level:1, name:/Tide/})).toBeVisible();
  await user.click(main().getByRole('button', {name:new RegExp(t.increase)}));
  await user.click(main().getByRole('button', {name:t.add}));
  expect(cartCount(store.getState().productDemo)).toBe(2);
  expect(cartTotal(store.getState().productDemo)).toBe(3600);
  await user.click(screen.getByRole('link', {name:t.cart}));
  const cup = main().getByRole('article', {name:/Tide/});
  expect(cup).toHaveTextContent(money(3600,'en').replace(/\s/gu, ' '));
  await user.click(main().getByRole('link', {name:t.continue}));
  expect(main().getByRole('heading', {level:1, name:/Tide/})).toBeVisible();
  await user.type(screen.getByRole('combobox',{name:t.search}), 'Tide');
  const suggestion = screen.getByRole('option',{name:/Tide/});
  expect(suggestion).toHaveTextContent(money(1800,'en').replace(/\s/gu, ' '));
  expect(suggestion).toHaveAttribute('href', tideOffer.href);
});

it('recognises a shared Tide URL with a trailing slash and displays its product title', () => {
  const path = '/Collections/Objects/TIDE/';
  expect(isStorefrontPath(path)).toBe(true);
  render(<Provider store={createAppStore('en')}><MemoryRouter initialEntries={[path]}><ProductExperience/></MemoryRouter></Provider>);
  expect(screen.getByRole('heading',{level:1,name:/Tide/})).toBeVisible();
  expect(document.title).toMatch(/^Tide · /);
});

it('saves the cup with its own finish, price and URL instead of Loop’s lettering option', async () => {
  const user = userEvent.setup(), store = createAppStore('en'), t = productCopy.en;
  store.dispatch(productLocaleSelected('en'));
  render(<Provider store={store}><MemoryRouter initialEntries={[tideOffer.href]}><ProductExperience/></MemoryRouter></Provider>);
  await user.click(screen.getByRole('button', {name:t.favorite}));
  await user.click(screen.getByRole('link', {name:t.favorites + ' · 1'}));
  const card = within(screen.getByRole('article'));
  expect(card.getByRole('link',{name:/Tide/})).toHaveAttribute('href', tideOffer.href);
  expect(card.getByText(variantById('cobalt').name.en)).toBeVisible();
  expect(card.getByText(catalogCopy.en.ready)).toBeVisible();
  expect(card.queryByText(t.without)).toBeNull();
  expect(card.getByText(money(tideOffer.unitCents,'en'))).toBeVisible();
});
