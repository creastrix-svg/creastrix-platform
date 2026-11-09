export const editionCollectionPath = '/collections/objects';

export function isEditionPath(path: string) {
  return path === editionCollectionPath || path.startsWith(editionCollectionPath + '/');
}

export function isCatalogPath(path: string) {
  const normalized = path.replace(/\/+$/, '');
  return normalized === '/catalog' || normalized === '/preview/collection';
}

export function isStorefrontPath(path: string) {
  return isCatalogPath(path) || isEditionPath(path) || path === '/products/loop' || path.startsWith('/products/loop/') || path === '/preview' || path.startsWith('/preview/');
}
