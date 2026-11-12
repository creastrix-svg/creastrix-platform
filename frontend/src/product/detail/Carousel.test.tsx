import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { expect, it } from 'vitest';
import { productCopy } from '../copy';
import Carousel, { LowerRecommendations } from './Carousel';

const copy = productCopy.en;
// Deliberately not alphabetical: the supplier of the rail owns its ranking.
const recommendations = ['c', 'f', 'a', 'd', 'b', 'e'].map(id => ({
  name: id, href: '/products/' + id, note: '', photo: '',
}));

function show(count: number, mini = false) {
  render(<MemoryRouter><Carousel title={mini ? copy.moreSeller : copy.youMayLike} locale="en" t={copy} mini={mini}
    content={{items: recommendations.slice(0, count), href: '/catalog'}} /></MemoryRouter>);
}

function destinations() {
  return within(screen.getByRole('list')).getAllByRole('link').map(link => link.getAttribute('href'));
}

it('omits the shelf when there are no recommendations', () => {
  show(0);
  expect(screen.queryByRole('region', {name: copy.youMayLike})).not.toBeInTheDocument();
  expect(screen.queryByRole('list')).not.toBeInTheDocument();
});

it.each([1, 2, 5, 6])('keeps all %i recommendations available in one ordered row', input => {
  show(input);
  expect(destinations()).toEqual(recommendations.slice(0, input).map(item => item.href));
});

it('keeps the seller rail independent', () => {
  show(6, true);
  expect(destinations()).toEqual(recommendations.map(item => item.href));
});

it.each([[0, 0], [0, 6], [1, 1], [2, 6], [6, 5]])('combines %i alternatives and %i section items under one heading', (similar, section) => {
  const neighbors = recommendations.map(item => ({...item, href: item.href + '-section'}));
  const first = recommendations.slice(0, similar), next = neighbors.slice(0, section);
  render(<MemoryRouter><LowerRecommendations locale="ru" t={productCopy.ru}
    similar={{ items:first, href:'/catalog' }}
    section={{ items:[...first.slice(0, 1), ...next], href:'/catalog' }} /></MemoryRouter>);
  const total = similar + section;
  expect(screen.queryAllByRole('heading', {level:2}).map(h => h.textContent)).toEqual(total ? ['Вам также может подойти'] : []);
  expect(screen.queryAllByRole('list')).toHaveLength(total ? 1 : 0);
  if (!total) return;
  expect(destinations()).toEqual([...first, ...next].map(item => item.href));
  if (total <= 2) expect(screen.getByRole('region')).toHaveAttribute('data-short-row', 'true');
});
