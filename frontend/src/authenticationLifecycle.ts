import type { Authentication } from './authentication';
import { AUTH_BOUNDARY_KEY } from './authBoundary';

// Document lifecycle stays outside StrictMode replay; completion never republishes intent.
export function bindAuthenticationLifecycle(authentication: Authentication) {
  const peerChanged = (event: StorageEvent) => { if (event.key === AUTH_BOUNDARY_KEY || event.key === null) authentication.reconcile(); };
  const suspend = () => authentication.suspend();
  const resume = () => { if (document.visibilityState !== 'hidden') void authentication.resume(); };
  const visibilityChanged = () => {
    if (document.visibilityState === 'hidden') authentication.suspend();
    else resume();
  };
  window.addEventListener('storage', peerChanged);
  window.addEventListener('pagehide', suspend);
  window.addEventListener('pageshow', resume);
  window.addEventListener('focus', resume);
  document.addEventListener('visibilitychange', visibilityChanged);
  const started = authentication.start();
  if (document.visibilityState === 'hidden') authentication.suspend();
  return { started, dispose() {
    authentication.dispose();
    window.removeEventListener('storage', peerChanged);
    window.removeEventListener('pagehide', suspend);
    window.removeEventListener('pageshow', resume);
    window.removeEventListener('focus', resume);
    document.removeEventListener('visibilitychange', visibilityChanged);
  } };
}
