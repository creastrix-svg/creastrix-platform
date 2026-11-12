import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { MemoryRouter } from 'react-router';
import { expect, it, vi } from 'vitest';
import { createAppStore } from '../../store';
import * as catalog from '../catalog/localCatalog';
import ProductExperience from '../ProductExperience';
import { productCopy } from '../copy';
import { RatingBadge } from './RatingBadge';
import { ratingText } from './ratingText';

it('does not present missing reviews as a zero-star rating', () => {
  const { container } = render(<RatingBadge href="/preview/items/loop-mini" locale="ru" />);
  expect(container).toBeEmptyDOMElement();
  expect(ratingText({ count: 0, average: null }, 'ru')).toBe(productCopy.ru.noReviews);
  expect(ratingText({ count: 12, average: 4.5 }, 'ru')).toBe('4,5 / 5 · Оценок: 12');
});

it('shows the same item rating on the offer card and review page, separate from the editable star choices', async () => {
  vi.spyOn(catalog, 'ratingForPath').mockImplementation(path =>
    path.split('?')[0] === '/products/loop' ? {count: 3, average: 4.3} : {count: 0, average: null});
  const user = userEvent.setup();
  render(<Provider store={createAppStore('en')}><MemoryRouter initialEntries={['/products/loop?variant=coral']}>
    <ProductExperience />
  </MemoryRouter></Provider>);
  expect(screen.getByRole('link', {name: 'Personalise'})).toHaveAttribute('href', '/products/loop/personalize?variant=coral');
  const summary = screen.getByRole('link', {name: '4.3 / 5 · Ratings: 3'});
  await user.click(summary);
  expect(screen.getByRole('heading', {name: '4.3 / 5 · Ratings: 3'})).toBeVisible();
  expect(screen.queryByText(productCopy.en.noReviews)).not.toBeInTheDocument();
});
