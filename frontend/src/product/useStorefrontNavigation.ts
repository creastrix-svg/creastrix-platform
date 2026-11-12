import { useLayoutEffect, useRef } from 'react';
import type { RefObject } from 'react';
import { useLocation, useNavigationType } from 'react-router';
import { isVariantId } from './fixtures/loop';
import { isCartSku, type CartSku } from './cartSku';
import { connectedOffer, entryForPath } from './catalog/localCatalog';
import { tideOffer } from './fixtures/tideOffer';
import { isCatalogPath, isTideOfferPath } from './routes';
import { catalogQueryKeys } from './browse/catalogBrowse';

// Bounded, document-memory scroll history; not account data or persistent browser identity.
type ProductDeparture = { left: number; top: number; variant: CartSku };
const positions = new Map<string, { left: number; top: number; product?: CartSku; returnTo?: ProductDeparture }>();
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
    const route = location.pathname.replace(/\/+$/, ''), oldRoute = old?.pathname.replace(/\/+$/, '');
    const loopPage = route.toLowerCase() === '/products/loop', tidePage = isTideOfferPath(location.pathname);
    const entry = entryForPath(route), offer = entry?.kind === 'offer' ? connectedOffer(entry.id) : undefined;
    const productPage = loopPage || tidePage || !!offer;
    const currentSku = (): CartSku | undefined => {
      if (tidePage) return tideOffer.sku;
      if (!loopPage && offer) {
        const requested = new URLSearchParams(location.search).get('variant');
        const sku = (offer.variants.find(v => v.id === requested) ?? offer.variants[0]).sku;
        return isCartSku(sku) ? sku : undefined;
      }
      const variant = loopPage ? main.current?.querySelector<HTMLElement>('[data-product-variant]')?.dataset.productVariant : undefined;
      return isVariantId(variant) ? variant : undefined;
    };
    const cartPage = route === '/preview/cart' || route === '/products/loop/personalize';
    // Carry the product departure through the local cart/checkout loop, not unrelated routes.
    const checkoutLoop = [route, oldRoute].every(path => path === '/preview/cart' || path === '/preview/checkout');
    const returnTo = cartPage && oldPoint?.product ? { left: oldPoint.left, top: oldPoint.top, variant: oldPoint.product }
      : positions.get(location.key)?.returnTo ?? (checkoutLoop ? oldPoint?.returnTo : undefined);
    const explicitReturn = action === 'PUSH' && (oldRoute === '/preview/cart' || oldRoute === '/products/loop/personalize') && productPage && !location.hash && location.state?.productReturn === true;
    const departure = oldPoint?.returnTo;
    const saved = action === 'POP' ? positions.get(location.key) : explicitReturn && departure && departure.variant === currentSku() ? departure : undefined;
    const sameQueryExcept = (keys: readonly string[]) => {
      const rest = (query: string) => { const params = new URLSearchParams(query); keys.forEach(key => params.delete(key)); return params.toString(); };
      return !!old && rest(old.search) === rest(location.search);
    };
    // In-page picks update a shareable URL without moving focus or jumping to the top.
    const selectionOnly = action === 'REPLACE' && old !== null && location.pathname === old.pathname && location.hash === old.hash
      && ((productPage && sameQueryExcept(loopPage ? ['variant', 'cartLine'] : ['variant'])) || (isCatalogPath(route) && sameQueryExcept(catalogQueryKeys)));
    previous.current = { key: location.key, pathname: location.pathname, search: location.search, hash: location.hash };
    if (!selectionOnly) {
      // A client-side link from the account shell mounts a fresh storefront.
      // Give it the same focus/scroll transition as an in-store link, but leave a direct entry alone.
      const fromOtherShell = old === null && action !== 'POP';
      if (changed || saved || fromOtherShell) main.current?.focus({ preventScroll: true });
      if (location.hash === '#product-main') main.current?.scrollIntoView({ block: 'start', behavior: 'instant' });
      else if (changed || saved || fromOtherShell) window.scrollTo({ left: saved?.left ?? 0, top: saved?.top ?? 0, behavior: 'instant' });
    }
    const remember = () => {
      positions.delete(location.key);
      positions.set(location.key, { top: window.scrollY, left: window.scrollX, product: currentSku(), returnTo });
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
