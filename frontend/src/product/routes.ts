export function isProductPath(path: string) {
  return path === '/products/loop' || path.startsWith('/products/loop/') || path === '/preview' || path.startsWith('/preview/');
}
