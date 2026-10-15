import { useLayoutEffect, useRef } from 'react';
import type { RefObject } from 'react';
import { useLocation, useNavigationType } from 'react-router';
import { isVariantId } from './catalog';
import type { VariantId } from './catalog';

// Bounded, document-memory scroll history; not account data or persistent browser identity.
type ProductDeparture = { left: number; top: number; variant: VariantId };
const positions = new Map<string, { left: number; top: number; product?: VariantId; returnTo?: ProductDeparture }>();
export function useStorefrontNavigation(main: RefObject<HTMLElement | null>) {
  const location = useLocation(), action = useNavigationType();
  const previous = useRef<{ key: string; pathname: string; search: string; hash: string } | null>(null);
  useLayoutEffect(() => {
    const prior = window.history.scrollRestoration;
    window.history.scrollRestoration = 'manual';
    return () => { window.history.scrollRestoration = prior; };
  }, []);
  useLayoutEffect(() => {
    const old = previous.current, changed = old !== null && old.key !== location.key;
    const oldPoint = old ? positions.get(old.key) : undefined;
    const productPage = location.pathname.replace(/\/+$/, '') === '/products/loop';
    const cartPage = location.pathname.replace(/\/+$/, '') === '/preview/cart';
    const returnTo = cartPage && oldPoint?.product ? { left: oldPoint.left, top: oldPoint.top, variant: oldPoint.product } : positions.get(location.key)?.returnTo;
    const selected = main.current?.querySelector<HTMLElement>('[data-product-variant]')?.dataset.productVariant;
    const explicitReturn = action === 'PUSH' && old?.pathname.replace(/\/+$/, '') === '/preview/cart' && productPage && !location.hash && location.state?.productReturn === true;
    const departure = oldPoint?.returnTo;
    const saved = action === 'POP' ? positions.get(location.key) : explicitReturn && departure && departure.variant === selected ? departure : undefined;
    const withoutVariant = (search: string) => { const params = new URLSearchParams(search); params.delete('variant'); return params.toString(); };
    const selectionOnly = action === 'REPLACE' && old !== null && old.pathname.replace(/\/+$/, '') === '/products/loop' && location.pathname === old.pathname && location.hash === old.hash && withoutVariant(location.search) === withoutVariant(old.search);
    previous.current = { key: location.key, pathname: location.pathname, search: location.search, hash: location.hash };
    if (!selectionOnly) {
      if (changed || saved) main.current?.focus({ preventScroll: true });
      if (location.hash === '#product-main') main.current?.scrollIntoView({ block: 'start', behavior: 'instant' });
      else if (changed || saved) window.scrollTo({ left: saved?.left ?? 0, top: saved?.top ?? 0, behavior: 'instant' });
    }
    const remember = () => {
      positions.delete(location.key);
      const variant = main.current?.querySelector<HTMLElement>('[data-product-variant]')?.dataset.productVariant;
      positions.set(location.key, { top: window.scrollY, left: window.scrollX, product: productPage && isVariantId(variant) ? variant : undefined, returnTo });
      while (positions.size > 60) positions.delete(positions.keys().next().value!);
    };
    if (selectionOnly) positions.delete(old.key);
    remember();
    const capture = (event: Event) => {
      const shell = main.current?.closest('.product-storefront');
      if (event.target instanceof Node && shell?.contains(event.target)) remember();
    };
    const keyCapture = (event: KeyboardEvent) => { if (event.key === 'Enter') capture(event); };
    window.addEventListener('scroll', remember, { passive: true });
    document.addEventListener('click', capture, true);
    document.addEventListener('submit', capture, true);
    document.addEventListener('keydown', keyCapture, true);
    return () => {
      // The next route's shorter DOM may already clamp scroll during this cleanup.
      window.removeEventListener('scroll', remember);
      document.removeEventListener('click', capture, true);
      document.removeEventListener('submit', capture, true);
      document.removeEventListener('keydown', keyCapture, true);
    };
  }, [location.key, location.hash, location.pathname, location.search, location.state, action, main]);
}
