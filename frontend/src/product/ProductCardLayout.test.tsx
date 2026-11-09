import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { MemoryRouter } from 'react-router';
import { expect, it } from 'vitest';
import { createAppStore } from '../store';
import ProductExperience from './ProductExperience';
import { productCopy } from './copy';
import { tideOffer } from './tideOffer';

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
  expect(card.getByRole('region', {name:t.moreStudio})).toContainElement(card.getByRole('link', {name:t.moreStudio}));
  expect(card.getByRole('region', {name:t.related})).toContainElement(card.getByRole('link', {name:t.related}));

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
