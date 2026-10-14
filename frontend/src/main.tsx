import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { Provider } from 'react-redux';
import { BrowserRouter } from 'react-router';
import App from './App';
import { initialLanguage } from './language';
import { createAppStore } from './store';
import { authenticationFinished, authenticationStarted } from './store';
import { AuthenticationContext, createAuthentication } from './authentication';
import { bindAuthenticationLifecycle } from './authenticationLifecycle';
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
const lifecycle = bindAuthenticationLifecycle(authentication);
if (import.meta.hot) import.meta.hot.dispose(lifecycle.dispose);

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Provider store={store}>
      <AuthenticationContext value={authentication}><BrowserRouter><App /></BrowserRouter></AuthenticationContext>
    </Provider>
  </StrictMode>,
);
