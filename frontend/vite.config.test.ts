import { describe, expect, it } from 'vitest';
import { AUTH_PROXY_PATTERN, authProxy, catalogProxyPattern, frontendConfig } from './vite.config';
const config = frontendConfig('development');

describe('fixed development transport configuration, not a runtime proxy proof', () => {
  const routed = new RegExp(AUTH_PROXY_PATTERN);
  it.each(['/auth/csrf', '/auth/login', '/auth/logout', '/auth/unknown', '/auth', '/auth?x=1',
    '/api/me', '/api/unknown', '/oauth2/authorization/auth0', '/login/oauth2/code/auth0?code=test&state=test',
    '/%61uth/login', '/a%75th/unknown', '/%61%70%69/me', '/%6fauth2/unknown', '/%6Fauth2/unknown',
    '/l%6fgin/oauth2/code/auth0', '/l%6Fgin/%6Fauth2/code/auth0'])('routes namespace %s before SPA fallback', path => {
    expect(routed.test(path)).toBe(true);
  });
  it.each(['/oauth2/authorization/cognito', '/login/oauth2/code/cognito?code=test&state=test',
    '/%6fauth2/authorization/cognito', '/login/%6fauth2/code/cognito', '/login/oauth2/code/%63ognito'])(
    'preserves Cognito namespace %s without provider-specific proxy rewriting', path => {
    expect(routed.test(path)).toBe(true);
    expect(authProxy).not.toHaveProperty('rewrite');
  });
  it.each(['/', '/login', '/login?auth=failed', '/account', '/login/other', '/authority', '/apiculture',
    '/oauth20', '/login/oauth20', '/AUTH/login', '/%2561uth/login'])('does not treat %s as the accepted namespace', path => {
    expect(routed.test(path)).toBe(false);
  });
  it('pins loopback/port/target, preserves headers/cookies/navigation and disconnects preview', () => {
    expect(config.server).toEqual({ host: '127.0.0.1', port: 3000, strictPort: true, proxy: { [AUTH_PROXY_PATTERN]: authProxy } });
    expect(authProxy).toEqual({ target: 'http://127.0.0.1:8080', changeOrigin: false, followRedirects: false,
      cookieDomainRewrite: false, cookiePathRewrite: false, xfwd: false });
    expect(config.preview).toEqual({ host: '127.0.0.1', port: 3000, strictPort: true, proxy: {} });
    expect(authProxy).not.toHaveProperty('rewrite');
    expect(authProxy).not.toHaveProperty('headers');
    expect(authProxy).not.toHaveProperty('configure');
  });
  it('confines the dedicated catalog mode to loopback with no account transport', () => {
    const config = frontendConfig('local-catalog'), route = new RegExp(catalogProxyPattern);
    expect(config.server.proxy).toEqual(config.preview.proxy);
    expect(Object.keys(config.server.proxy)).toEqual([catalogProxyPattern]);
    expect(config.server).toMatchObject({host:'127.0.0.1',port:4174,strictPort:true});
    expect(config.build.outDir).toBe('dist-local-catalog');
    expect(route.test('/api/local-catalog/products')).toBe(true);
    for (const path of ['/api/me','/auth/login','/api/local-catalog/products/extra','/api/local-catalog/private'])
      expect(route.test(path)).toBe(false);
  });
});
