import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { Provider } from 'react-redux';
import { BrowserRouter } from 'react-router';
import App from './App';
import { initialLanguage } from './language';
import { createAppStore } from './store';
import { authenticationFinished, authenticationStarted } from './store';
import { AuthenticationContext, createAuthentication } from './authentication';
import { AUTH_BOUNDARY_KEY } from './authBoundary';
import './styles.css';

const store = createAppStore(initialLanguage(navigator.languages[0] ?? navigator.language));
const authentication = createAuthentication({
  enabled: import.meta.env.DEV && window.location.origin === 'http://localhost:3000',
  fetch: window.fetch.bind(window), storage: () => window.localStorage, nonce: () => crypto.randomUUID(),
  closePrivate: () => {
    document.documentElement.dataset.authPrivate = 'closed';
    document.querySelectorAll<HTMLInputElement>('input[data-auth-csrf]').forEach(input => { input.value = ''; });
  },
  openPrivate: () => { document.documentElement.dataset.authPrivate = 'open'; },
  started: (owner, phase, notice) => { store.dispatch(authenticationStarted({ owner, phase, notice })); },
  finished: (owner, result) => { store.dispatch(authenticationFinished({ owner, result })); },
});
// Document lifecycle is outside StrictMode replay. Peer events invalidate; they never log in a tab.
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
if (import.meta.hot) import.meta.hot.dispose(() => {
  authentication.dispose();
  window.removeEventListener('storage', peerChanged);
  window.removeEventListener('pagehide', suspend);
  window.removeEventListener('pageshow', resume);
  window.removeEventListener('focus', resume);
  document.removeEventListener('visibilitychange', visibilityChanged);
});
void authentication.start();
if (document.visibilityState === 'hidden') authentication.suspend();

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Provider store={store}>
      <AuthenticationContext value={authentication}><BrowserRouter><App /></BrowserRouter></AuthenticationContext>
    </Provider>
  </StrictMode>,
);
