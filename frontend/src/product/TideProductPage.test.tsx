import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { MemoryRouter } from 'react-router';
import { afterEach, expect, it } from 'vitest';
import { createAppStore } from '../store';
import { money, variantById } from './catalog';
import { productCopy } from './copy';
import { cartAdded, productLocaleSelected } from './demoStore';
import { editionItems } from './editionCatalog';
import { storefrontLocales } from './locale';
import type { StorefrontLocale } from './locale';
import TideProductPage from './TideProductPage';
import { tideOffer } from './tideOffer';

function mount(locale: StorefrontLocale = 'en', store = createAppStore('en')) {
  store.dispatch(productLocaleSelected(locale));
  const view = render(<Provider store={store}><MemoryRouter initialEntries={[tideOffer.href]}><TideProductPage /></MemoryRouter></Provider>);
  return { ...view, store, user: userEvent.setup(), t: productCopy[locale] };
}
afterEach(() => window.localStorage.removeItem('creastrix.product.tide.quantity'));

it.each(storefrontLocales)('presents the actual Tide offer without Loop-only capabilities (%s)', locale => {
  const { t, container } = mount(locale), item = editionItems(locale).find(i => i.id === 'tide')!;
  expect(screen.getByRole('heading', { level: 1, name: 'Tide' })).toBeVisible();
  expect(screen.getByText(item.title, {selector:'.product-subtitle'})).toBeVisible();
  expect(within(container.querySelector<HTMLElement>('.product-about')!).getByText(item.material)).toBeVisible();
  expect(screen.getByText(item.description)).toBeVisible();
  expect(screen.getByText(t.stock + ': 18')).toBeVisible();
  expect(screen.getByText(t.price).closest('.product-price-row')).toHaveTextContent(money(1800, locale).replace(/\s/gu, ' '));
  expect(screen.getByRole('button', { name: t.add })).toBeEnabled();
  const gallery = screen.getByRole('region', { name: t.photo });
  const model = within(gallery).getByRole('button', { name: t.model });
  expect(model).toBeDisabled();
  expect(model).toHaveAccessibleDescription(t.modelFailed);
  expect(within(gallery).getByText(t.modelFailed)).not.toBeVisible();
  expect(within(gallery).getByRole('button', { name: t.photo })).toHaveAttribute('aria-pressed', 'true');
  const finishes = screen.getByRole('group', { name: new RegExp('^' + t.color) });
  const swatches = within(finishes).getAllByRole('button');
  expect(swatches).toHaveLength(1);
  expect(swatches[0]).toHaveAccessibleName(variantById('cobalt').name[locale]);
  expect(swatches[0]).toHaveAttribute('aria-pressed', 'true');
  expect(screen.getByRole('link', { name: t.noReviews })).toHaveAttribute('href', tideOffer.href + '/reviews');
  expect(screen.queryByText(t.personalButton)).toBeNull();
  expect(within(container.querySelector<HTMLElement>('.product-price-row')!).getByText(t.without)).toBeVisible();
  expect(screen.queryByText(/Loop/)).toBeNull();
});

it('keeps all standard product sections and moves the three features right below the gallery', async () => {
  const { user, t, container } = mount();
  const gallery = screen.getByRole('region',{name:t.photo});
  const features = gallery.nextElementSibling;
  expect(features).toHaveClass('product-features');
  expect(features?.children).toHaveLength(3);
  const seller = within(container.querySelector<HTMLElement>('.product-seller')!);
  expect(seller.getByText('Creastrix')).toBeVisible();
  expect(seller.getByRole('link')).toHaveAttribute('href','/preview/info/about');
  for (const rail of [t.moreStudio, t.related]) {
    const section = within(screen.getByRole('region',{name:rail}));
    const links = within(section.getByRole('list')).getAllByRole('link');
    expect(links.length).toBeGreaterThan(1);
    for (const link of links) expect(link.getAttribute('href')).toMatch(/^\/collections\/objects\/(?!tide$)/);
  }
  await user.click(screen.getByText(t.readMore));
  expect(screen.getByRole('heading',{name:t.specsTitle})).toBeVisible();
  await user.click(screen.getByText(t.readLess));
  expect(screen.getByText(t.readMore).closest('details')).not.toHaveAttribute('open');
});

it('uses Tide stock and pricing, preserves an unsubmitted choice and resets only Tide after adding', async () => {
  const store = createAppStore('en');
  store.dispatch(cartAdded({ variant: 'chalk', quantity: 3 }));
  const first = mount('en', store), t = first.t;
  fireEvent.change(screen.getByRole('textbox', { name: t.quantity }), { target: { value: '3' } });
  first.unmount();
  const { user } = mount('en', store), input = screen.getByRole('textbox', { name: t.quantity });
  expect(input).toHaveValue('3');
  expect(screen.getByText('€54.00')).toBeVisible();
  await user.click(screen.getByRole('button', { name: t.add }));
  expect(input).toHaveValue('1');
  expect(screen.getByRole('link', { name: t.viewCart })).toHaveAttribute('href', '/preview/cart');
  expect(screen.getByText('Already in cart: 3')).toBeVisible();
  fireEvent.change(input, { target: { value: '9999' } });
  expect(input).toHaveValue('15');
  await user.click(screen.getByRole('button', { name: t.add }));
  expect(input).toBeDisabled();
  expect(screen.getByRole('button', { name: t.add })).toBeDisabled();
  expect(store.getState().productDemo.cart).toEqual(expect.arrayContaining([
    expect.objectContaining({ variant: 'chalk', quantity: 3 }),
    expect.objectContaining({ variant: 'tide-cobalt', quantity: 18 }),
  ]));
});

it('opens only its own photo at full size and restores keyboard focus on Escape', async () => {
  const { user, t } = mount();
  const open = screen.getByRole('button', { name: t.enlarge });
  expect(open).toBeDisabled();
  fireEvent.load(within(open).getByRole('img'));
  await user.click(open);
  const dialog = screen.getByRole('dialog');
  expect(within(dialog).getByRole('img', { name: /Tide/ })).toHaveAttribute('src', tideOffer.photo);
  const model = within(dialog).getByRole('button', { name: t.model });
  expect(model).toBeDisabled();
  expect(model).toHaveAccessibleDescription(t.modelFailed);
  expect(within(dialog).queryByRole('button', { name: t.next })).toBeNull();
  await user.keyboard('{Escape}');
  expect(screen.queryByRole('dialog')).toBeNull();
  expect(open).toHaveFocus();
});
