import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { expect, it } from 'vitest';
import { createAppStore } from '../store';
import { money } from './productDisplay';
import { productCopy } from './copy';
import { productLocaleSelected } from './demoStore';
import { SearchControl } from './HeaderControls';
import { SearchResultsView } from './browse/SearchResultsView';

it.each([
  ['de', 'Suche'], ['en', 'Search'], ['es', 'Buscar'], ['fr', 'Rechercher'],
  ['it', 'Cerca'], ['pl', 'Szukaj'], ['ru', 'Поиск'],
] as const)('shows a compact %s placeholder without shortening the accessible field label', (locale, placeholder) => {
  const store = createAppStore('en');
  store.dispatch(productLocaleSelected(locale));
  render(<Provider store={store}><MemoryRouter><SearchControl /></MemoryRouter></Provider>);
  expect(screen.getByRole('combobox', { name: productCopy[locale].search })).toHaveAttribute('placeholder', placeholder);
  expect(screen.getByRole('button', { name: productCopy[locale].searchSubmit })).toBeVisible();
});

function CurrentLocation() {
  const location = useLocation();
  return <output>{location.pathname + location.search}</output>;
}

it('offers the same variant, photo and price for a live option and the ranked results page', async () => {
  const user = userEvent.setup();
  render(<Provider store={createAppStore('en')}><MemoryRouter initialEntries={['/catalog']}>
    <SearchControl /><Routes><Route path="/preview/search" element={<SearchResultsView />} /><Route path="*" element={<CurrentLocation />} /></Routes>
  </MemoryRouter></Provider>);
  const input = screen.getByRole('combobox'), price = money(1990, 'en');
  await user.type(input, 'Loop chalk');
  const option = screen.getAllByRole('option')[0];
  expect(option).toHaveAttribute('href', '/products/loop?variant=chalk');
  expect(option).toHaveTextContent(price);
  expect(option.querySelector('img')).toHaveAttribute('src', '/product/loop-chalk-01.webp');
  await user.click(screen.getByRole('button', { name: productCopy.en.searchSubmit }));
  expect(screen.getByRole('heading', { name: productCopy.en.results })).toBeVisible();
  const result = screen.getByRole('link', { name: /Loop · Chalk/ });
  expect(result).toHaveTextContent(price);
  expect(result).toHaveAttribute('href', '/products/loop?variant=chalk');
  expect(result.querySelector('img')).toHaveAttribute('src', '/product/loop-chalk-01.webp');
  await user.clear(input);
  await user.type(input, 'coral{ArrowDown}{Enter}');
  expect(screen.getByRole('status')).toHaveTextContent('/products/loop?variant=coral');
});

it('keeps the suggestion list short without dropping matching products from the full page', async () => {
  const user = userEvent.setup(), store = createAppStore('en');
  store.dispatch(productLocaleSelected('ru'));
  render(<Provider store={store}><MemoryRouter initialEntries={['/catalog']}>
    <SearchControl /><Routes><Route path="/preview/search" element={<SearchResultsView />} /><Route path="*" element={<CurrentLocation />} /></Routes>
  </MemoryRouter></Provider>);
  await user.type(screen.getByRole('combobox'), 'к');
  const suggestions = screen.getAllByRole('option').map(option => option.getAttribute('href'));
  expect(suggestions).toHaveLength(5);
  await user.click(screen.getByRole('button', { name: productCopy.ru.searchSubmit }));
  expect(screen.getByRole('heading', { name: productCopy.ru.results })).toBeVisible();
  const results = screen.getAllByRole('link').map(link => link.getAttribute('href'));
  expect(results).toEqual(expect.arrayContaining([...suggestions, '/preview/items/loop-tall', '/preview/items/loop-duo']));
  expect(new Set(results.filter(href => href?.startsWith('/products/') || href?.startsWith('/preview/items/'))).size).toBeGreaterThan(7);
});
