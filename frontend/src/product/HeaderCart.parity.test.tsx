import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { MemoryRouter } from 'react-router';
import { afterEach, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { createAppStore } from '../store';
import * as catalog from './catalog';
import type { VariantId } from './catalog';
import { cartAdded, cartLineRemoved, cartQuantityChanged, favoriteToggled } from './demoStore';
import { HeaderCart } from './HeaderControls';
import { productCopy } from './copy';
const css = readFileSync('src/product/product.css', 'utf8');

afterEach(() => { vi.unstubAllGlobals(); });

it('keeps only the last five fixture lines in a separate scroll body, with static header and total/CTA outside it', async () => {
  // Test-only long catalogue: no new production products or API are introduced.
  const original = catalog.variantById;
  const fixtures = Array.from({ length: 8 }, (_, index) => ({ ...catalog.variants[0],
    id: `fixture-${index + 1}` as VariantId, cents: 100, stock: 10,
    name: { ...catalog.variants[0].name, en: `Fixture ${index + 1}` },
  }));
  vi.spyOn(catalog, 'variantById').mockImplementation(id => fixtures.find(item => item.id === id) ?? original(id));
  vi.stubGlobal('innerHeight', 320); vi.stubGlobal('innerWidth', 640);
  const store = createAppStore('en');
  fixtures.forEach(item => store.dispatch(cartAdded({ variant: item.id, quantity: 1 })));
  const { container } = render(<Provider store={store}><MemoryRouter><HeaderCart /></MemoryRouter></Provider>);
  const anchor = container.querySelector('.product-cart-anchor')!;
  vi.spyOn(anchor, 'getBoundingClientRect').mockReturnValue(new DOMRect(500, 70, 44, 44));
  const t = productCopy.en, user = userEvent.setup();
  await user.hover(screen.getByRole('link', { name: t.cart }));
  const popup = screen.getByRole('region', { name: t.cartPreview });
  const list = popup.querySelector<HTMLDivElement>('.product-peek-items')!, footer = popup.querySelector<HTMLDivElement>('.product-peek-footer')!;
  expect(list.querySelectorAll('.product-peek-line')).toHaveLength(5);
  expect(list).not.toHaveTextContent('Fixture 1'); expect(list).not.toHaveTextContent('Fixture 3');
  expect(list).toHaveTextContent('Fixture 4'); expect(list).toHaveTextContent('Fixture 8');
  expect(footer.parentElement).toBe(popup);
  expect(footer).toContainElement(within(popup).getByRole('link', { name: t.viewCart }));
  expect(list).not.toContainElement(footer);
  expect(list).not.toContainElement(within(popup).getByRole('heading', { name: t.cart }));
  expect(footer).toHaveTextContent('€8.00');
  expect(popup.style.getPropertyValue('--product-peek-height')).toBe('228px');
  expect(popup.style.getPropertyValue('--product-peek-top')).toBe('80px');
  // Static CSS/DOM evidence is deliberately not a browser geometry claim.
  expect(css).toMatch(/\.product-cart-peek\s*\{[^}]*overflow: hidden/s);
  expect(css).toMatch(/\.product-peek-items\s*\{[^}]*min-height: 0;[^}]*overflow-y: auto/s);
  expect(css).toMatch(/\.product-peek-footer\s*\{[^}]*flex: 0 0 auto/s);
});

it('highlights only an actual add, not mount, quantity edits, removal or favorite changes', async () => {
  const store = createAppStore('en');
  store.dispatch(cartAdded({ variant: 'cobalt', quantity: 1 }));
  store.dispatch(cartAdded({ variant: 'coral', quantity: 1 }));
  const { container } = render(<Provider store={store}><MemoryRouter><HeaderCart /></MemoryRouter></Provider>);
  const initial = container.querySelector('.product-count')!;
  expect(initial).not.toHaveClass('product-count-added');
  act(() => { store.dispatch(cartQuantityChanged({ variant: 'cobalt', quantity: 2 })); store.dispatch(favoriteToggled('cobalt')); });
  expect(container.querySelector('.product-count')).toBe(initial);
  expect(initial).not.toHaveClass('product-count-added');
  act(() => { store.dispatch(cartAdded({ variant: 'cobalt', quantity: 1 })); });
  const added = container.querySelector('.product-count')!;
  expect(added).not.toBe(initial); expect(added).toHaveClass('product-count-added');
  act(() => { store.dispatch(cartQuantityChanged({ variant: 'cobalt', quantity: 4 })); });
  expect(container.querySelector('.product-count')).toBe(added);
  act(() => { store.dispatch(cartLineRemoved('cobalt')); });
  expect(container.querySelector('.product-count')).toBe(added);
  const user = userEvent.setup();
  await user.hover(screen.getByRole('link', { name: productCopy.en.cart }));
  fireEvent.keyDown(screen.getByRole('region', { name: productCopy.en.cartPreview }), { key: 'Escape' });
  expect(screen.queryByRole('region')).not.toBeInTheDocument();
  expect(css).toContain('@media (prefers-reduced-motion: reduce)');
});
