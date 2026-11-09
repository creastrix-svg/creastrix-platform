import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { Link, MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router';
import { expect, it, vi } from 'vitest';
import { createAppStore } from '../store';
import { money, variants } from './catalog';
import { catalogCopy } from './catalogCopy';
import { productCopy } from './copy';
import { productLocaleSelected, productVariantSelected } from './demoStore';
import { editionItems } from './editionCatalog';
import HomeSpotlight from './HomeSpotlight';
import { shelfCopy } from './homeShelfCopy';
import { storefrontLocales } from './locale';
import type { StorefrontLocale } from './locale';

function Destination() {
  const { pathname, search } = useLocation(), navigate = useNavigate();
  return <><output>{pathname + search}</output><button type="button" onClick={() => navigate(-1)}>Back</button><Link to="/">New home entry</Link></>;
}
function mount(locale: StorefrontLocale = 'en') {
  const store = createAppStore('en'), user = userEvent.setup();
  store.dispatch(productLocaleSelected(locale));
  store.dispatch(productVariantSelected('chalk'));
  render(<Provider store={store}><MemoryRouter><Routes><Route path="/" element={<HomeSpotlight/>}/>
    <Route path="*" element={<Destination/>}/></Routes></MemoryRouter></Provider>);
  return { user, store, copy: shelfCopy[locale] };
}
it.each(storefrontLocales)('leads with a priced offer and labels 31 distinct editorial designs in %s', locale => {
  mount(locale);
  const entries = editionItems(locale).filter(item => item.id !== 'drift'), shelf = within(screen.getByRole('list'));
  const available = variants.filter(item => item.stock > 0), prices = available.map(item => item.cents);
  const href = '/products/loop';
  const tiles = shelf.getAllByRole('listitem');
  expect(tiles).toHaveLength(32);
  expect(shelf.queryByRole('button')).toBeNull();
  expect(shelf.getAllByRole('link').map(link => link.getAttribute('href'))).toEqual([href, ...entries.map(item => item.href)]);
  expect(screen.getByRole('status')).toHaveTextContent(shelfCopy[locale].selected + ': 32');
  for (const [index, item] of entries.entries()) {
    const tile = within(tiles[index + 1]);
    const link = tile.getByRole('link', { name: item.name + ' ' + item.title });
    expect(link).toHaveAccessibleDescription(item.material + ' ' + shelfCopy[locale].preview);
    expect(within(link).getByText(shelfCopy[locale].preview)).toBeVisible();
    expect(within(link).queryByText(/€/)).toBeNull();
    expect(link.querySelector('img')).toHaveAttribute('src', item.thumb);
    expect(link.querySelector('img')).toHaveAttribute('loading', 'lazy');
    expect(tile.getAllByRole('link')).toHaveLength(1);
  }
  const offer = shelf.getByRole('link', { name: /Loop/ });
  expect(within(offer).getByText(shelfCopy[locale].shop)).toBeVisible();
  expect(within(offer).queryByText(shelfCopy[locale].preview)).toBeNull();
  expect(offer).toHaveTextContent(catalogCopy[locale].finishes + ': ' + available.length);
  expect(offer).toHaveTextContent((money(Math.min(...prices), locale) + ' – ' + money(Math.max(...prices), locale)).replace(/\s/gu, ' '));
});
it('counts the visible tiles and shows the stocked product in the applicable sections', async () => {
  const { user, copy } = mount();
  const shelf = within(screen.getByRole('list'));
  for (const [filter, count, withOffer] of [[copy.home, 19, true], [copy.desk, 8, false], [copy.carry, 11, false], [copy.all, 32, true]] as const) {
    await user.click(screen.getByRole('button', { name: filter }));
    expect(shelf.getAllByRole('listitem')).toHaveLength(count);
    expect(screen.getByRole('status')).toHaveTextContent(copy.selected + ': ' + count);
    expect(Boolean(shelf.queryByRole('link', { name: /Loop/ }))).toBe(withOffer);
  }
  await user.click(shelf.getByRole('link', { name: /Loop/ }));
  expect(screen.getByRole('status').textContent).toBe('/products/loop');
});
it('filters the shown objects, restores the shelf on Back, and resets it for a fresh homepage visit', async () => {
  const { user, store, copy } = mount();
  const before = store.getState().productDemo;
  await user.click(screen.getByRole('button', { name: copy.carry }));
  expect(screen.getByRole('status')).toHaveTextContent('11');
  const carryLinks = within(screen.getByRole('list')).getAllByRole('link').map(link => link.getAttribute('href'));
  expect(carryLinks).toHaveLength(11);
  expect(carryLinks).toEqual(expect.arrayContaining(['/collections/objects/arc', '/collections/objects/fold', '/collections/objects/trail']));
  expect(carryLinks).not.toContain('/collections/objects/moss');
  await user.click(screen.getByRole('link', { name: /^Arc / }));
  expect(screen.getByRole('status')).toHaveTextContent('/collections/objects/arc');
  await user.click(screen.getByRole('button', {name:'Back'}));
  expect(screen.getByRole('button', { name: copy.carry })).toHaveAttribute('aria-pressed','true');
  expect(screen.getAllByRole('listitem')).toHaveLength(11);
  await user.click(screen.getByRole('link', { name: /^Fold / }));
  await user.click(screen.getByRole('link', {name:'New home entry'}));
  expect(screen.getByRole('button', { name: copy.all })).toHaveAttribute('aria-pressed','true');
  expect(screen.getAllByRole('listitem')).toHaveLength(32);
  expect(store.getState().productDemo).toBe(before);
});
it('opens a design directly with Enter, and restores the filter on Back', async () => {
  const { user, copy } = mount('ru');
  await user.click(screen.getByRole('button', { name: copy.desk }));
  const link = screen.getByRole('link', { name: /^Tempo / });
  link.focus();
  await user.keyboard('{Enter}');
  expect(screen.getByRole('status').textContent).toBe('/collections/objects/tempo');
  await user.click(screen.getByRole('button', {name:'Back'}));
  expect(screen.getByRole('button', { name: copy.desk })).toHaveAttribute('aria-pressed','true');
});
it('keeps the selected or keyboard-focused choice visible after a resize without snapping on a swipe', async () => {
  let resized = () => {};
  vi.stubGlobal('ResizeObserver', class {
    constructor(callback: () => void) { resized = callback; }
    observe() {}
    disconnect() {}
  });
  try {
    const { user, copy } = mount();
    const decor = screen.getByRole('button', { name: copy.decor });
    const gifts = screen.getByRole('button', { name: copy.gifts });
    const strip = screen.getByRole('group', { name: copy.types });
    await user.click(decor);
    decor.blur();
    Object.defineProperties(strip, { scrollWidth: { value: 450 }, clientWidth: { value: 284 } });
    vi.spyOn(strip, 'getBoundingClientRect').mockReturnValue(new DOMRect(18, 0, 284, 44));
    vi.spyOn(decor, 'getBoundingClientRect').mockReturnValue(new DOMRect(318, 0, 54, 44));
    resized();
    expect(strip.scrollLeft).toBe(70);
    strip.scrollLeft = 31;
    fireEvent.scroll(strip);
    expect(strip.scrollLeft).toBe(31);
    gifts.focus();
    vi.spyOn(gifts, 'getBoundingClientRect').mockReturnValue(new DOMRect(-2, 0, 55, 44));
    resized();
    expect(strip.scrollLeft).toBe(11);
    expect(gifts).toHaveFocus();
    expect(decor).toHaveAttribute('aria-pressed', 'true');
  } finally {
    vi.unstubAllGlobals();
  }
});
it('navigates directly from a product photograph to its own full detail', async () => {
  const { user } = mount();
  await user.click(screen.getByRole('link', { name: /^Tide / }).querySelector('img')!);
  expect(screen.getByRole('status').textContent).toBe('/collections/objects/tide');
});
it('starts a different section below the docked controls without moving an undocked shelf or reselecting', async () => {
  const { user, copy } = mount();
  const group = screen.getByRole('group', { name: copy.types });
  const bar = group.parentElement!, grid = screen.getByRole('list');
  const move = vi.spyOn(window, 'scrollBy').mockImplementation(() => {});
  try {
    vi.spyOn(bar, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 88, 600, 55));
    const items = vi.spyOn(grid, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 155, 600, 1600));
    await user.click(screen.getByRole('button', { name: copy.gifts }));
    expect(move).not.toHaveBeenCalled();
    items.mockReturnValue(new DOMRect(0, -601, 600, 1600));
    await user.click(screen.getByRole('button', { name: copy.gifts }));
    expect(move).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: copy.desk }));
    expect(move).toHaveBeenCalledExactlyOnceWith({ top: -744, behavior: 'instant' });
  } finally { move.mockRestore(); }
});
it('keeps the direct design link usable after its image fails', async () => {
  const { user } = mount();
  const link = screen.getByRole('link', { name: /^Halo / });
  fireEvent.error(link.querySelector('img')!);
  expect(within(link).getByText(productCopy.en.noPhotoShort)).toBeVisible();
  await user.click(link);
  expect(screen.getByRole('status').textContent).toBe('/collections/objects/halo');
});
