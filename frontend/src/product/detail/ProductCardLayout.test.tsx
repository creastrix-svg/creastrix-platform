import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { MemoryRouter } from 'react-router';
import { expect, it } from 'vitest';
import { createAppStore } from '../../store';
import ProductExperience from '../ProductExperience';
import { productCopy } from '../copy';
import { tideOffer } from '../fixtures/tideOffer';
import Carousel from './Carousel';

it.each([
  ['Loop', '/products/loop', true],
  ['Tide', tideOffer.href, false],
] as const)('fills every standard product section for %s', async (name, path, personalized) => {
  const t = productCopy.en, user = userEvent.setup();
  render(<Provider store={createAppStore('en')}><MemoryRouter initialEntries={[path]}><ProductExperience /></MemoryRouter></Provider>);
  const main = screen.getByRole('main'), card = within(main);
  expect(card.getByRole('heading', { level: 1, name })).toBeVisible();
  const sections = [
    '.product-gallery', '.product-information', '.product-rating', '.product-price-row',
    '.product-colors', '.product-purchase', '.product-seller', '.product-features', '.product-about',
  ];
  for (const selector of sections) expect(main.querySelectorAll(selector)).toHaveLength(1);
  expect(main.querySelectorAll('.product-features > div')).toHaveLength(3);
  const sellerLink = within(card.getByRole('region', {name:t.moreSeller})).getByRole('link', {name:t.moreSeller});
  expect(sellerLink).toHaveAttribute('href', name === 'Loop' ? '/catalog?collection=loop' : '/catalog');
  if (name === 'Loop') {
    expect(sellerLink).toHaveAccessibleDescription('View the Loop collection');
    expect(within(card.getByRole('region', {name:t.youMayLike})).getByRole('heading', {name:t.youMayLike})).toBeVisible();
    expect(card.queryByRole('link', {name:t.youMayLike})).toBeNull();
  } else {
    expect(card.queryByRole('region', {name:t.youMayLike})).toBeNull();
  }

  const gallery = main.querySelector('.product-gallery')!;
  const studio = main.querySelector('.product-personalisation');
  if (personalized) {
    expect(studio).toBeInTheDocument();
    expect(within(card.getByRole('region', {name:t.photo})).getByRole('button', {name:t.model})).toBeEnabled();
  } else {
    expect(studio).toBeNull();
    expect(gallery.nextElementSibling).toBe(main.querySelector('.product-features'));
    expect(within(card.getByRole('region', {name:t.photo})).getByRole('button', {name:t.model})).toBeDisabled();
  }
  const details = main.querySelector<HTMLDetailsElement>('.product-description details')!;
  expect(details.open).toBe(false);
  await user.click(within(details).getByText(t.readMore));
  expect(details.open).toBe(true);
  expect(within(details).getByRole('heading', {name:t.specsTitle})).toBeVisible();
  expect(details.querySelectorAll('dl dt').length).toBeGreaterThan(0);
  await user.click(within(details).getByText(t.readLess));
  expect(details.open).toBe(false);
});

it('keeps Loop accessories on its seller shelf and reserves different planters for the similar shelf', () => {
  const t = productCopy.en;
  render(<Provider store={createAppStore('en')}><MemoryRouter initialEntries={['/products/loop']}><ProductExperience /></MemoryRouter></Provider>);
  const targets = (name: string) => {
    const list = within(screen.getByRole('region', { name })).getByRole('list');
    return within(list).getAllByRole('link').map(link => link.getAttribute('href'));
  };
  const collection = targets(t.moreSeller), related = targets(t.youMayLike);
  expect(collection).toEqual([...['tray', 'stand', 'mini', 'wide'].map(key => '/preview/items/loop-' + key), tideOffer.href]);
  expect(related).toEqual(['/preview/items/loop-tall', '/preview/items/loop-duo']);
  expect(new Set([...collection, ...related, '/products/loop']).size).toBe(collection.length + related.length + 1);
});

it('only offers an actual collection link when a seller has no other recommendations', () => {
  const t = productCopy.en;
  const emptyShelf = (collectionName?: string) => <MemoryRouter><Carousel title={t.moreSeller} mini locale="en" t={t}
    content={{items:[], href:collectionName ? '/catalog?collection=loop' : '/catalog', collectionName}} /></MemoryRouter>;
  const {rerender} = render(emptyShelf());
  expect(screen.queryByRole('link')).toBeNull();
  rerender(emptyShelf('Loop'));
  expect(screen.getByRole('link', {name:'View the Loop collection'})).toHaveAttribute('href', '/catalog?collection=loop');
  expect(screen.queryByRole('heading', {name:t.moreSeller})).toBeNull();
  expect(screen.queryByRole('list')).toBeNull();
});
