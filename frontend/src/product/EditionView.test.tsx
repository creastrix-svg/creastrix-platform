import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { MemoryRouter } from 'react-router';
import { expect, it } from 'vitest';
import { createAppStore } from '../store';
import { productCopy } from './copy';
import { concepts } from './catalog';
import { catalogCopy } from './catalogCopy';
import { productLocaleSelected } from './demoStore';
import { editionCollectionPath, editionCopy, editionItems } from './editionCatalog';
import { storefrontLocales } from './locale';
import type { StorefrontLocale } from './locale';
import ProductExperience from './ProductExperience';
import { isStorefrontPath } from './routes';
import { searchCatalog, searchResultSummary } from './search';

function open(path: string, locale: StorefrontLocale = 'en') {
  const store = createAppStore('en');
  store.dispatch(productLocaleSelected(locale));
  const user = userEvent.setup();
  render(<Provider store={store}><MemoryRouter initialEntries={[path]}><ProductExperience /></MemoryRouter></Provider>);
  return { user, main: within(screen.getByRole('main')) };
}

it.each(storefrontLocales)('shows 32 distinct preview designs with working detail links and no commerce claims in %s', locale => {
  const { main } = open(editionCollectionPath, locale);
  expect(main.getByRole('heading', { level: 1, name: editionCopy[locale].title })).toBeVisible();
  const items = editionItems(locale);
  expect(items.map(i => i.id)).toEqual(['halo','arc','tide','folio','dune','iris','kestrel','moss','fold','prism','loom','rook','cove','flute','dot','tempo','lunar','wave','reed','pin','bend','linea','hex','trail','stripe','orbit','ridge','nook','coil','pebble','rail','drift']);
  const links = within(main.getByRole('list')).getAllByRole('link');
  expect(links.map(link => link.getAttribute('href'))).toEqual(items.map(i => i.href));
  for (const [position, item] of items.entries()) {
    expect(links[position]).toHaveAccessibleName([item.name,item.title,item.material].join(' '));
    expect(links[position].querySelector('img')).toHaveAttribute('src', item.thumb);
    const result = searchCatalog(item.name, locale, 1)[0];
    expect(result).toMatchObject({ href: item.href, photo: item.thumb, kind: 'edition' });
    expect(searchResultSummary(result, locale)).toContain(editionCopy[locale].status);
    expect(result.cents).toBeUndefined();
    expect(searchCatalog(item.material, locale).some(match => match.href === item.href)).toBe(true);
  }
  expect(main.queryByRole('button')).toBeNull();
  expect(document.title).toBe(editionCopy[locale].title + ' · Creastrix');
  expect(main.getByRole('link',{name:productCopy[locale].home})).toHaveAttribute('href','/');
});

it.each(editionItems('en'))('opens deep link $href with material, purchase status and canonical collection return', item => {
  const { main } = open(item.href);
  expect(main.getByRole('heading', { level: 1, name: item.name })).toBeVisible();
  expect(main.getByText(item.description)).toBeVisible();
  expect(main.getByText(editionCopy.en.note)).toBeVisible();
  expect(main.getByRole('link',{name:editionCopy.en.index})).toHaveAttribute('href',editionCollectionPath);
  expect(main.getByRole('img', { name: item.name + ' · ' + item.title + ' · ' + item.material })).toHaveAttribute('src', item.photo);
  const related = within(main.getByRole('list')).getAllByRole('link');
  expect(related).toHaveLength(4);
  expect(related.map(link => link.getAttribute('href'))).not.toContain(item.href);
  expect(main.queryByRole('button')).toBeNull();
  expect(document.title).toBe(item.name + ' · ' + item.title + ' · Creastrix');
});

it('returns to the actual referring collection; unknown design URLs do not resolve to another item', async () => {
  expect(isStorefrontPath(editionCollectionPath + '/halo/')).toBe(true);
  expect(isStorefrontPath(editionCollectionPath + '-archive')).toBe(false);
  const { user, main } = open(editionCollectionPath);
  await user.click(main.getByRole('link',{name:/^Halo /}));
  const back = main.getByRole('link', { name: productCopy.en.back });
  expect(back).toHaveAttribute('href',editionCollectionPath);
  await user.click(back);
  expect(main.getByRole('heading',{name:editionCopy.en.title})).toBeVisible();
});

it('shows a local not-found page for unknown objects', () => {
  const { main } = open(editionCollectionPath + '/no-such-object');
  expect(main.getByRole('heading', { name: productCopy.en.notFound })).toBeVisible();
  expect(document.title).toBe(productCopy.en.notFound + ' · Creastrix');
});

it.each(['/catalog','/catalog?format=concept'])('lists individual designs without an editorial collection masquerading as a listing on %s', path => {
  const { main } = open(path);
  expect(main.queryByRole('link', { name:editionCopy.en.title })).toBeNull();
  const results = main.getByRole('region', { name:catalogCopy.en.conceptsTitle });
  const expectedLinks = [...editionItems('en').map(item => item.href), ...concepts.map(item => '/preview/items/' + item.id)];
  expect(within(results).getAllByRole('link').map(link => link.getAttribute('href'))).toEqual(expectedLinks);
  expect(main.getByRole('status')).toHaveTextContent(catalogCopy.en.results + ': ' + (expectedLinks.length + (path === '/catalog' ? 1 : 0)));
});

it('opens an individual editorial design and returns to the original filtered catalog', async () => {
  const path = '/catalog?format=concept&sort=name-asc';
  const { main, user } = open(path, 'ru');
  const item = editionItems('ru').find(i => i.id === 'halo')!;
  await user.click(main.getByRole('link', { name: item.name + ' ' + item.title }));
  expect(main.getByRole('heading', { level:1, name:item.name })).toBeVisible();
  const back = main.getByRole('link', { name:productCopy.ru.back });
  expect(back).toHaveAttribute('href', path);
  await user.click(back);
  expect(main.getByRole('heading', { level:1, name:catalogCopy.ru.pageHeading })).toBeVisible();
});

it.each(['sale=1','color=chalk','category=gifts','format=ready','technique=3d-print','custom=no'])(
  'does not suggest an unfiltered editorial collection for a concept query narrowed by %s', filter => {
    const { main } = open('/catalog?format=concept&' + filter);
    expect(main.queryByRole('link', { name: editionCopy.en.title })).toBeNull();
  });
