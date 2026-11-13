import react from '@vitejs/plugin-react';
import { availableParallelism } from 'node:os';
import type { IncomingMessage } from 'node:http';
import { defineConfig } from 'vitest/config';
import { mediaNamespace, publishedPhotoId } from './src/product/catalog/photoRenditions.ts';

// Route namespace letters (including accepted percent encodings) without rewriting URL/query.
// Segment boundaries keep /login a React page and unknown backend routes out of SPA fallback.
const segment = (value: string) => [...value].map(letter => '(?:' + letter + '|%'
  + letter.charCodeAt(0).toString(16).replace(/[a-f]/g, hex => '[' + hex + hex.toUpperCase() + ']') + ')').join('');
export const AUTH_PROXY_PATTERN = '^/(?:' + [segment('auth'), segment('api'), segment('oauth2'),
  segment('login') + '/' + segment('oauth2')].join('|') + ')(?:/|\\?|$)';
export const authProxy = {
  target: 'http://127.0.0.1:8080', changeOrigin: false, followRedirects: false,
  cookieDomainRewrite: false as const, cookiePathRewrite: false as const, xfwd: false,
};

export const catalogProxyPattern = '^/api/local-catalog/products(?:\\?.*)?$';
const catalogTarget = { target: 'http://127.0.0.1:8081', changeOrigin: false, followRedirects: false, xfwd: false };
export const mediaProxyPattern = '^' + mediaNamespace;
const stripCredentials = (req: Pick<IncomingMessage,'headers'>) => {
  delete req.headers.cookie;
  delete req.headers.authorization;
  delete req.headers['proxy-authorization'];
};
export const publicCatalogProxyGate = (req: Pick<IncomingMessage,'method'|'headers'>) => {
  if (req.method !== 'GET') return false as const;
  stripCredentials(req);
};
// Match the namespace so an unknown rendition returns 404, not the SPA HTML.
// Vite's bypass=false rejects at the frontend; only a public derivative is forwarded.
export const publicMediaProxyGate = (req: Pick<IncomingMessage,'method' | 'url' | 'headers'>) => {
  if ((req.method !== 'GET' && req.method !== 'HEAD')
    || !(publishedPhotoId(req.url, 'thumb') || publishedPhotoId(req.url, 'detail'))) return false as const;
  // Images do not need browser credentials. Remove them before Vite constructs the upstream request.
  stripCredentials(req);
};
const localCatalogProxy = {
  [catalogProxyPattern]: { ...catalogTarget, bypass: publicCatalogProxyGate },
  [mediaProxyPattern]: { ...catalogTarget, bypass: publicMediaProxyGate },
};
export const frontendConfig = (mode: string) => ({
  plugins: [react()],
  build: { outDir: mode === 'local-catalog' ? 'dist-local-catalog' : 'dist' },
  server: { host: '127.0.0.1', port: mode === 'local-catalog' ? 4174 : ['account-cart-local','shared-cart-local'].includes(mode) ? 4317 : 3000, strictPort: true,
    // The public endpoints must precede the broad account API prefix: only the account server receives browser credentials.
    proxy: mode === 'shared-cart-local' ? { ...localCatalogProxy, [AUTH_PROXY_PATTERN]: { ...authProxy, target: 'http://127.0.0.1:8191' } }
      : mode === 'local-catalog' ? localCatalogProxy : { [AUTH_PROXY_PATTERN]: mode === 'account-cart-local'
      ? { ...authProxy, target: 'http://127.0.0.1:8191' } : authProxy } },
  preview: { host: '127.0.0.1', port: mode === 'local-catalog' ? 4174 : 3000, strictPort: true,
    proxy: mode === 'local-catalog' ? localCatalogProxy : {} },
  test: {
    environment: 'happy-dom',
    // The studio and gallery tests mount full pages. Bound concurrent DOMs
    // rather than increasing interaction timeouts on busy developer machines.
    maxWorkers: Math.min(3, availableParallelism()),
    setupFiles: ['./src/test/setup.ts'],
    restoreMocks: true,
    clearMocks: true,
  },
});
export default defineConfig(({mode}) => frontendConfig(mode));
