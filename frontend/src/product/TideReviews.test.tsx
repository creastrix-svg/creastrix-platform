import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { MemoryRouter } from 'react-router';
import { expect, it, vi } from 'vitest';
import { createAppStore } from '../store';
import { productCopy } from './copy';
import ProductExperience from './ProductExperience';
import { tideOfferPath } from './routes';

vi.mock('@google/model-viewer', () => ({}));
const t = productCopy.en;
function open(path: string) {
  const store = createAppStore('en', vi.fn());
  render(<Provider store={store}><MemoryRouter initialEntries={[path]}><ProductExperience /></MemoryRouter></Provider>);
  return userEvent.setup();
}

it('opens the empty Tide review list and composer, with both return links pointing to the cup', async () => {
  const user = open(tideOfferPath + '/reviews');
  expect(screen.getByRole('heading', { level: 1, name: 'Tide reviews' })).toBeVisible();
  expect(document.title).toBe('Tide reviews · Creastrix');
  expect(screen.getByRole('heading', { name: t.noReviews })).toBeVisible();
  expect(screen.getByRole('link', { name: t.backToProduct })).toHaveAttribute('href', tideOfferPath);
  const write = screen.getByRole('link', { name: t.writeReview });
  expect(write).toHaveAttribute('href', tideOfferPath + '/reviews/new');
  await user.click(write);
  expect(screen.getAllByRole('radio').every(radio => !(radio as HTMLInputElement).checked)).toBe(true);
  expect(screen.getByRole('button', { name: t.reviewSubmit })).toBeDisabled();
  await user.click(screen.getByRole('link', { name: t.backToProduct }));
  expect(screen.getByRole('heading', { level: 1, name: 'Tide' })).toBeVisible();
});

it('opens a preselected Tide rating without publishing a review or applying a Loop variant', async () => {
  const user = open(tideOfferPath + '/reviews/new?rating=4&variant=chalk');
  expect(screen.getByRole('radio', { name: t.reviewRating + ': 4 ' + t.ratingOutOf })).toBeChecked();
  await user.type(screen.getByRole('textbox', { name: t.reviewText }), 'Tide');
  expect(screen.getByRole('button', { name: t.reviewSubmit })).toBeDisabled();
  expect(screen.getByRole('link', { name: t.backToProduct })).toHaveAttribute('href', tideOfferPath);
});
