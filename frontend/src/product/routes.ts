export const editionCollectionPath = '/collections/objects';
export const tideOfferPath = editionCollectionPath + '/tide';

// These are the only routable detail locations that a catalog offer may advertise.
// The URL slug is stable for buyers and need not equal the database primary key.
const offerPath = /^\/(?:products|collections\/objects|preview\/items)\/[a-z0-9]+(?:-[a-z0-9]+)*$/;
export const isCatalogOfferHref = (path: string) => offerPath.test(path);
export const catalogDetailRoutes = ['/products/:id', '/collections/objects/:id', '/preview/items/:id'] as const;

// React Router matches this product case-insensitively and accepts a trailing slash.
export function isTideOfferPath(path: string) {
  return path.replace(/\/+$/, '').toLowerCase() === tideOfferPath;
}

export function tideReviewRoute(path: string): 'list' | 'form' | null {
  const normalized = path.replace(/\/+$/, '').toLowerCase();
  if (normalized === tideOfferPath + '/reviews') return 'list';
  return normalized === tideOfferPath + '/reviews/new' ? 'form' : null;
}

export function isEditionPath(path: string) {
  return path === editionCollectionPath || path.startsWith(editionCollectionPath + '/');
}

export function isCatalogPath(path: string) {
  const normalized = path.replace(/\/+$/, '').toLowerCase();
  return normalized === '/catalog' || normalized === '/preview/collection';
}

export function isStorefrontPath(path: string) {
  const detail = path.replace(/\/+$/, '');
  // "reviews" is itself a valid product slug. Only strip a review suffix
  // after giving the full detail path a chance to match.
  const product = isCatalogOfferHref(detail) || isCatalogOfferHref(detail.replace(/\/reviews(?:\/new)?$/, ''));
  return path === '/' || isCatalogPath(path) || isEditionPath(path) || isTideOfferPath(path) || tideReviewRoute(path) !== null || product || path.startsWith('/products/loop/') || path === '/preview' || path.startsWith('/preview/');
}
