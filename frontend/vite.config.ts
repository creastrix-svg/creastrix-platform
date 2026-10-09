import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

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

export default defineConfig({
  plugins: [react()],
  server: { host: '127.0.0.1', port: 3000, strictPort: true, proxy: { [AUTH_PROXY_PATTERN]: authProxy } },
  preview: { host: '127.0.0.1', port: 3000, strictPort: true, proxy: {} },
  test: {
    environment: 'happy-dom',
    setupFiles: ['./src/test/setup.ts'],
    restoreMocks: true,
    clearMocks: true,
  },
});
