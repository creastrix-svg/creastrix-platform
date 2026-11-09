import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router';
import { expect, it } from 'vitest';
import { createAppStore } from '../store';
import { browseCatalog } from './catalogBrowse';
import { productLocaleSelected } from './demoStore';
import { homeCopy } from './homeCopy';
import { campaignCopy } from './campaignCopy';
import CatalogView from './CatalogView';
import { shelfCopy } from './homeShelfCopy';
import { storefrontLocales } from './locale';
import StorefrontHome from './StorefrontHome';

it.each(storefrontLocales)('gives the hero one catalog destination and provides genuine catalog shortcuts (%s)', locale => {
  const store = createAppStore('en');
  store.dispatch(productLocaleSelected(locale));
  render(<Provider store={store}><MemoryRouter><StorefrontHome/></MemoryRouter></Provider>);
  const h = homeCopy[locale];
  const navigation = screen.getByRole('navigation', { name: h.headline });
  const c = campaignCopy[locale];
  const campaign = within(screen.getByRole('region', { name: c.title }));
  expect(campaign.getByRole('img', { name:c.photoAlt })).toBeVisible();
  expect(campaign.getByRole('link', { name:h.browse })).toHaveAttribute('href','/catalog');
  expect(campaign.getAllByRole('link')).toHaveLength(1);
  expect(campaign.queryByRole('button')).toBeNull();
  expect(screen.getByRole('region',{name:c.featured})).toBeVisible();
  const links = within(navigation).getAllByRole('link');
  expect(links.map(a => a.getAttribute('href'))).toEqual(['/catalog?format=ready','/catalog?custom=yes','/catalog?format=concept']);
  for (const link of links) {
    expect(link).toHaveAccessibleName();
    expect(link.querySelector('img')).toHaveAttribute('alt', '');
    const params = new URLSearchParams(link.getAttribute('href')!.split('?')[1]);
    expect(browseCatalog(params, locale).total).toBeGreaterThan(0);
  }
  expect(screen.getByRole('link', { name: h.browse })).toHaveAttribute('href','/catalog');
});

it('presents the same five catalog entrances in reading order before the popular shelf', () => {
  render(<Provider store={createAppStore('en')}><MemoryRouter><StorefrontHome/></MemoryRouter></Provider>);
  const c = campaignCopy.en;
  const catalog = screen.getByRole('link', { name:homeCopy.en.browse });
  const studio = screen.getByRole('link', { name:new RegExp(c.studioAction) });
  const departments = screen.getByRole('navigation', { name:homeCopy.en.headline });
  const shelf = screen.getByRole('region', { name:c.featured });
  const follows = (a: Node,b: Node) => Boolean(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING);
  expect(studio).toHaveAttribute('href','/catalog?group=custom-or-made');
  expect(follows(catalog,studio) && follows(studio,departments) && follows(departments,shelf)).toBe(true);
  expect(within(shelf).queryByText('Selected by our editors')).not.toBeInTheDocument();
  expect(within(shelf).getByRole('link', { name:c.seeSelection })).toHaveAttribute('href','/catalog');
});

function CatalogWithHistory() {
  const { pathname, search } = useLocation(), navigate = useNavigate();
  return <><CatalogView/><output data-testid="catalog-url">{pathname + search}</output>
    <button onClick={() => navigate(-1)}>Browser back</button></>;
}

function openShop(entry = '/') {
  const store = createAppStore('en');
  store.dispatch(productLocaleSelected('ru'));
  render(<Provider store={store}><MemoryRouter initialEntries={[entry]}><Routes>
    <Route path="/" element={<StorefrontHome/>}/><Route path="/catalog" element={<CatalogWithHistory/>}/>
  </Routes></MemoryRouter></Provider>);
  return userEvent.setup();
}

// Assert public URLs and representative products independently of the URL builder
// and membership table. The full catalog can carry more than the homepage shelf.
it.each([
  ['home', 'living', 'moss', 'fold'], ['carry', 'carry', 'fold', 'moss'],
  ['desk', 'desk', 'folio', 'arc'], ['gifts', 'gifts', 'loop', 'bend'],
  ['kitchen', 'kitchen', 'tide', 'arc'], ['decor', 'decor', 'flute', 'fold'],
] as const)('opens the actual %s collection and restores the choice on Back', async (key, category, yes, no) => {
  const user = openShop(), label = shelfCopy.ru[key];
  const linkFor = (id: string) => id === 'loop' ? '/products/loop' : '/collections/objects/' + id;
  expect(screen.queryByText('Выбор редакции')).not.toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: label }));
  const products = within(screen.getByRole('list')).getAllByRole('link').map(a => a.getAttribute('href'));
  expect(products).toContain(linkFor(yes));
  expect(products).not.toContain(linkFor(no));
  const cta = screen.getByRole('link', { name: 'Посмотреть в каталоге' });
  expect(cta).toHaveAttribute('href', '/catalog?category=' + category);
  expect(screen.getByRole('link', { name: homeCopy.ru.browse })).toHaveAttribute('href','/catalog');
  await user.click(cta);
  expect(screen.getByTestId('catalog-url')).toHaveTextContent('/catalog?category=' + category);
  expect(screen.getByText(label, { exact: true })).toBeVisible();
  const results = screen.getAllByRole('link').map(a => a.getAttribute('href'));
  expect(results).toEqual(expect.arrayContaining(products));
  expect(results).not.toContain(linkFor(no));
  await user.click(screen.getByRole('button', { name: 'Browser back' }));
  expect(screen.getByRole('button', { name: label })).toHaveAttribute('aria-pressed','true');
  expect(screen.getByRole('link', { name: 'Посмотреть в каталоге' })).toHaveAttribute('href', '/catalog?category=' + category);
});

it('opens a shared kitchen URL without a previous homepage selection', () => {
  openShop('/catalog?category=kitchen');
  expect(screen.getByText(shelfCopy.ru.kitchen, { exact: true })).toBeVisible();
  expect(screen.getByRole('link', { name: /^Tide / })).toBeVisible();
  expect(screen.queryByRole('link', { name: /^Arc / })).not.toBeInTheDocument();
});

it('navigates to the complete catalog after switching from a section to All', async () => {
  const user = openShop();
  await user.click(screen.getByRole('button', { name: shelfCopy.ru.desk }));
  await user.click(screen.getByRole('button', { name: shelfCopy.ru.all }));
  const cta = screen.getByRole('link', { name: 'Посмотреть в каталоге' });
  expect(cta).toHaveAttribute('href', '/catalog');
  await user.click(cta);
  expect(screen.getByTestId('catalog-url').textContent).toBe('/catalog');
  expect(screen.getByRole('link', { name: /^Folio / })).toBeVisible();
  expect(screen.getByRole('link', { name: /^Arc / })).toBeVisible();
});
