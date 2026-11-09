import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { MemoryRouter, useLocation, useNavigate } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import App from '../App';
import { createAppStore } from '../store';
import { catalogCopy } from './catalogCopy';
import { productCopy } from './copy';
import { editionItems } from './editionCatalog';
import { concepts as studioConcepts } from './catalog';
import { productLocaleSelected } from './demoStore';
import { storefrontLocales, type StorefrontLocale } from './locale';

vi.mock('@google/model-viewer', () => ({}));

function HistoryControls() {
  const { pathname, search } = useLocation();
  const navigate = useNavigate();
  return <><output data-testid="catalog-location">{pathname + search}</output>
    <button onClick={() => navigate(-1)}>Browser back</button></>;
}

async function openCatalog(path = '/catalog', locale: StorefrontLocale = 'en') {
  const store = createAppStore('en');
  store.dispatch(productLocaleSelected(locale));
  const view = render(<Provider store={store}><MemoryRouter initialEntries={[path]}>
    <App /><HistoryControls />
  </MemoryRouter></Provider>);
  await screen.findByRole('heading', { level: 1, name: catalogCopy[locale].pageHeading }, { timeout: 5000 });
  return { ...view, user: userEvent.setup() };
}

const main = () => within(screen.getByRole('main'));
function productTargets() {
  return main().queryAllByRole('link').flatMap(link => {
    const target = new URL(link.getAttribute('href')!, 'https://shop.test');
    return target.pathname === '/products/loop' ? [target.searchParams.get('variant') ?? 'product'] : [];
  });
}
const currentURL = () => new URL(screen.getByTestId('catalog-location').textContent!, 'https://shop.test');
const checkbox = (name: string) => main().getByRole('checkbox', { name });
const disclosure = () => main().getByRole('button', { name: catalogCopy.en.filters });
const resetButton = () => main().getByRole('button', { name: catalogCopy.en.reset });

describe('buyer catalog', () => {
  it.each(['/catalog', '/preview/collection'])('opens %s directly with one shoppable product and separate, unpriced concepts', async path => {
    await openCatalog(path);
    const logo = screen.getByRole('link', { name: productCopy.en.home });
    expect(logo).toHaveAttribute('href', '/');
    expect(within(logo).getByRole('img', { name: 'Creastrix' })).toBeVisible();
    expect(main().queryByRole('link', { name: productCopy.en.home })).not.toBeInTheDocument();
    expect(productTargets()).toEqual(['product']);
    const card = main().getByRole('link', { name: 'Loop' }).closest('article')!;
    expect(card).toHaveTextContent('Colour options: 4');
    expect(card).toHaveTextContent('€19.90 – €28.00');
    expect(card.querySelector('del')).toBeNull();
    expect(card.querySelector('img')).toHaveAttribute('src','/product/reference-asset-01.webp');
    expect(within(card).queryByRole('button')).toBeNull();
    const concepts = main().getByRole('region', { name: catalogCopy.en.conceptsTitle });
    const links = within(concepts).getAllByRole('link');
    expect(links.map(link => link.getAttribute('href'))).toEqual([
      ...editionItems('en').map(item => item.href), ...studioConcepts.map(item => '/preview/items/' + item.id),
    ]);
    expect(concepts).not.toHaveTextContent(/€|EUR/);
    expect(within(concepts).queryByRole('button', { name: productCopy.en.add })).not.toBeInTheDocument();
  });

  it('offers the original page as a contextual back link when the catalog was entered from a product', async () => {
    const { user } = await openCatalog('/catalog?color=coral');
    await user.click(main().getByRole('link', { name: 'Loop' }));
    await user.click(within(screen.getByRole('contentinfo')).getByRole('link', { name: catalogCopy.en.title }));
    const back = main().getByRole('link', { name: productCopy.en.back });
    expect(back).toHaveAttribute('href', '/products/loop?variant=coral');
    await user.click(back);
    expect(currentURL().pathname).toBe('/products/loop');
    expect(currentURL().searchParams.get('variant')).toBe('coral');
  });

  it('shows the exact finish price, previous price, dimensions and actual review state before opening it', async () => {
    await openCatalog('/catalog?sale=1');
    const chalk = main().getByRole('link', { name: 'Loop' }).closest('article')!;
    expect(chalk).toHaveTextContent('€19.90');
    expect(chalk.querySelector('del')).toHaveTextContent('€24.00');
    expect(chalk).toHaveTextContent(/13\s*[×x]\s*12/);
    expect(chalk).toHaveTextContent(productCopy.en.personalizable);
    expect(chalk).toHaveTextContent(productCopy.en.stock + ': 15');
    expect(within(chalk).getByRole('link', { name: /No reviews yet/ })).toHaveAttribute('href', '/products/loop/reviews?variant=chalk');
    expect(chalk).not.toHaveTextContent(/\b5\.0\b|★★★★★/);
  });

  it('keeps a single stable reset control, including while inactive and after the last selection is removed', async () => {
    const { user } = await openCatalog();
    const clear = resetButton(), sale = checkbox(catalogCopy.en.sale);
    expect(clear).toBeVisible();
    expect(clear).toBeDisabled();
    await user.click(sale);
    expect(resetButton()).toBe(clear);
    expect(clear).toBeEnabled();
    await user.click(clear);
    expect(resetButton()).toBe(clear);
    expect(clear).toBeVisible();
    expect(clear).toBeDisabled();
    expect(sale).toHaveFocus();
  });

  it('combines colours as alternatives, intersects other filters and restores the same selection after reopen', async () => {
    const first = await openCatalog(), c = catalogCopy.en;
    const filters = disclosure();
    filters.focus(); await first.user.keyboard('{Enter}');
    expect(filters).toHaveAttribute('aria-expanded', 'true');
    await first.user.click(checkbox('Coral'));
    expect(productTargets()).toEqual(['coral']);
    const chalk = checkbox('Chalk');
    await first.user.click(chalk);
    expect(chalk).toHaveFocus();
    expect(productTargets()).toEqual(['coral']);
    expect(main().getByRole('status')).toHaveTextContent(/\b1\b/);
    expect(main().getByText('Colour options: 2')).toBeVisible();
    await first.user.click(checkbox(c.withPersonalization));
    await first.user.click(checkbox(c.print3d));
    await first.user.click(checkbox(c.sale));
    expect(productTargets()).toEqual(['chalk']);
    const shared = currentURL();
    expect(shared.searchParams.getAll('color').sort()).toEqual(['chalk', 'coral']);
    expect(shared.searchParams.get('custom')).toBe('yes');
    expect(shared.searchParams.get('technique')).toBe('3d-print');
    first.unmount();
    const reopened = await openCatalog(shared.pathname + shared.search);
    expect(productTargets()).toEqual(['chalk']);
    expect(disclosure()).toHaveAttribute('aria-expanded', 'false');
    await reopened.user.click(disclosure());
    for (const name of ['Chalk', 'Coral', c.withPersonalization, c.print3d, c.sale]) expect(checkbox(name)).toBeChecked();
  });

  it('restores the open filter panel on browser Back without expanding it for a new visitor to the same URL', async () => {
    const first = await openCatalog();
    await first.user.click(disclosure());
    await first.user.click(checkbox('Chalk'));
    const shared = currentURL();
    await first.user.click(main().getByRole('link', { name: 'Loop' }));
    expect(currentURL().pathname).toBe('/products/loop');
    await first.user.click(screen.getByRole('button', { name: 'Browser back' }));
    expect(disclosure()).toHaveAttribute('aria-expanded', 'true');
    expect(checkbox('Chalk')).toBeChecked();
    expect(productTargets()).toEqual(['chalk']);
    first.unmount();
    await openCatalog(shared.pathname + shared.search);
    expect(disclosure()).toHaveAttribute('aria-expanded', 'false');
    expect(productTargets()).toEqual(['chalk']);
  });

  it('gives keyboard and screen-reader users the applied count and option result counts', async () => {
    const { user } = await openCatalog(), c = catalogCopy.en;
    await user.click(disclosure());
    expect(disclosure()).toHaveAccessibleDescription(/\b0\b/);
    expect(checkbox(c.ready)).toHaveAccessibleDescription(/\b1\b/);
    expect(checkbox(c.concept)).toHaveAccessibleDescription(c.results + ': ' + (studioConcepts.length + editionItems('en').length));
    await user.click(checkbox('Chalk'));
    expect(disclosure()).toHaveAccessibleDescription(/\b1\b/);
    expect(checkbox(c.ready)).toHaveAccessibleDescription(/\b1\b/);
    expect(checkbox(c.concept)).toHaveAccessibleDescription(/\b0\b/);
  });

  it.each(['file', 'made-to-order'])('distinguishes an unlisted %s from a non-purchasable studio concept and recovers from zero results', async format => {
    const c = catalogCopy.en;
    const { user } = await openCatalog('/catalog?format=' + format);
    expect(productTargets()).toEqual([]);
    expect(main().queryByRole('region', { name: c.conceptsTitle })).not.toBeInTheDocument();
    expect(main().getByText(c.emptyTitle)).toBeVisible();
    await user.click(resetButton());
    expect(main().queryByText(c.emptyTitle)).not.toBeInTheDocument();
    expect(productTargets()).toEqual(['product']);
    expect(main().getByRole('region', { name: c.conceptsTitle })).toBeVisible();
  });

  it('separates ready-made offers from view-only concepts without treating the latter as a production file', async () => {
    const { user } = await openCatalog(), c = catalogCopy.en;
    await user.click(disclosure());
    await user.click(checkbox(c.concept));
    expect(productTargets()).toEqual([]);
    expect(main().getByRole('status')).toHaveTextContent(c.results + ': ' + (studioConcepts.length + editionItems('en').length));
    expect(main().getByRole('region', { name: c.conceptsTitle })).toBeVisible();
    await user.click(checkbox(c.ready));
    expect(productTargets()).toEqual(['product']);
    expect(main().getByRole('status')).toHaveTextContent(c.results + ': ' + (studioConcepts.length + editionItems('en').length + 1));
    expect(main().getByRole('region', { name: c.conceptsTitle })).toBeVisible();
    await user.click(checkbox(c.withPersonalization));
    expect(productTargets()).toEqual(['product']);
    expect(main().queryByRole('region', { name: c.conceptsTitle })).not.toBeInTheDocument();
  });

  it('retains product links through sorting and routes a discount-only result to its actual selling price', async () => {
    const { user } = await openCatalog();
    const sort = main().getByRole('combobox', { name: catalogCopy.en.sort });
    const sale = main().getByRole('checkbox', { name: catalogCopy.en.sale });
    await user.selectOptions(sort, 'price-asc');
    expect(productTargets()).toEqual(['product']);
    expect(currentURL().searchParams.get('sort')).toBe('price-asc');
    await user.selectOptions(sort, 'price-desc');
    expect(productTargets()).toEqual(['product']);
    await user.click(sale);
    expect(productTargets()).toEqual(['chalk']);
    expect(main().queryByRole('region', { name: catalogCopy.en.conceptsTitle })).not.toBeInTheDocument();
    expect(currentURL().searchParams.get('sale')).toBe('1');
    expect(currentURL().searchParams.get('sort')).toBe('price-desc');
    await user.click(main().getByRole('button', { name: catalogCopy.en.reset }));
    expect(sale).not.toBeChecked();
    expect(sale).toHaveFocus();
    expect(sort).toHaveValue('featured');
    expect(currentURL().searchParams.has('sale')).toBe(false);
    expect(currentURL().searchParams.has('sort')).toBe(false);
    expect(productTargets()).toEqual(['product']);
  });

  it('opens the selected colour and restores a shared catalog URL with both controls on browser Back', async () => {
    const { user } = await openCatalog('/catalog?sort=price-asc&sale=1');
    expect(productTargets()).toEqual(['chalk']);
    await user.click(main().getByRole('link', { name: 'Loop' }));
    expect(currentURL().pathname).toBe('/products/loop');
    expect(currentURL().searchParams.get('variant')).toBe('chalk');
    expect(main().getByRole('button', { name: 'Chalk' })).toHaveAttribute('aria-pressed', 'true');
    await user.click(screen.getByRole('button', { name: 'Browser back' }));
    expect(currentURL().pathname).toBe('/catalog');
    expect(main().getByRole('checkbox', { name: catalogCopy.en.sale })).toBeChecked();
    expect(main().getByRole('combobox', { name: catalogCopy.en.sort })).toHaveValue('price-asc');
    expect(productTargets()).toEqual(['chalk']);
  });

  it('clears a shared category without taking keyboard focus away from the filters', async () => {
    const { user } = await openCatalog('/catalog?category=personal&sale=1');
    await user.click(main().getByRole('button', { name: catalogCopy.en.reset }));
    expect(currentURL().search).toBe('');
    expect(main().getByRole('checkbox', { name: catalogCopy.en.sale })).toHaveFocus();
    expect(main().getByRole('region', { name: catalogCopy.en.conceptsTitle })).toBeVisible();
  });

  it('restores the creative collection on Back and can remove just that collection without losing other filters', async () => {
    const { user } = await openCatalog('/catalog?group=custom-or-made&color=coral');
    const chip = () => main().getByRole('button', { name: productCopy.en.remove + ': ' + catalogCopy.en.customOrMade });
    expect(chip()).toBeVisible();
    expect(productTargets()).toEqual(['coral']);
    await user.click(main().getByRole('link', { name: 'Loop' }));
    await user.click(screen.getByRole('button', { name: 'Browser back' }));
    expect(currentURL().searchParams.get('group')).toBe('custom-or-made');
    expect(disclosure()).toHaveAccessibleDescription(/\b2\b/);
    await user.click(chip());
    expect(disclosure()).toHaveFocus();
    expect(currentURL().search).toBe('?color=coral');
    expect(disclosure()).toHaveAccessibleDescription(/\b1\b/);
  });

  it('keeps the confirmed product-page colour on a later generic visit instead of choosing a colour for the buyer', async () => {
    const { user } = await openCatalog();
    const loop = () => main().getByRole('link', { name:'Loop' });
    await user.click(loop());
    await user.click(main().getByRole('button', { name:'Coral' }));
    await user.click(within(screen.getByRole('contentinfo')).getByRole('link', { name:catalogCopy.en.title }));
    expect(loop()).toHaveAttribute('href','/products/loop');
    await user.click(loop());
    expect(main().getByRole('button', { name:'Coral' })).toHaveAttribute('aria-pressed','true');
  });

  it.each(storefrontLocales)('offers named browsing controls and design concepts in storefront locale %s', async locale => {
    const { user } = await openCatalog('/catalog', locale);
    const copy = catalogCopy[locale];
    const footer = within(screen.getByRole('contentinfo'));
    expect(footer.getByRole('link', { name: copy.title })).toHaveAttribute('href', '/catalog');
    expect(footer.getByRole('link', { name: productCopy[locale].favorites })).toHaveAttribute('href', '/preview/saved');
    expect(footer.queryByRole('link', { name: /loop/i })).not.toBeInTheDocument();
    const destinations = footer.getAllByRole('link').map(link => link.getAttribute('href'));
    expect(destinations.some(url => url?.startsWith('/products/') || url === '/preview/studio')).toBe(false);
    expect(new Set(destinations).size).toBe(destinations.length);
    expect(document.documentElement.lang).toBe(locale);
    expect(document.title).toBe(copy.title + ' · Creastrix');
    expect(document.querySelector('meta[name="description"]')).toHaveAttribute('content', copy.intro);
    expect(main().getByRole('combobox', { name: copy.sort })).toBeEnabled();
    expect(main().getByRole('checkbox', { name: copy.sale })).not.toBeChecked();
    expect(main().getByRole('region', { name: copy.conceptsTitle })).toBeVisible();
    await user.click(main().getByRole('button', { name: copy.filters }));
    for (const name of [copy.ready, copy.productionFile, copy.withPersonalization, copy.withoutPersonalization]) {
      expect(checkbox(name)).not.toBeChecked();
    }
  });
});
