export const editionCollectionPath = '/collections/objects';
export const tideOfferPath = editionCollectionPath + '/tide';

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
  const normalized = path.replace(/\/+$/, '');
  return normalized === '/catalog' || normalized === '/preview/collection';
}

export function isStorefrontPath(path: string) {
  return path === '/' || isCatalogPath(path) || isEditionPath(path) || isTideOfferPath(path) || tideReviewRoute(path) !== null || path === '/products/loop' || path.startsWith('/products/loop/') || path === '/preview' || path.startsWith('/preview/');
}
