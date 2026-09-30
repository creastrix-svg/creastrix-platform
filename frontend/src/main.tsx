import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { Provider } from 'react-redux';
import { BrowserRouter } from 'react-router';
import App from './App';
import { initialLanguage } from './language';
import { createAppStore } from './store';
import './styles.css';

const store = createAppStore(initialLanguage(navigator.languages[0] ?? navigator.language));

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Provider store={store}>
      <BrowserRouter><App /></BrowserRouter>
    </Provider>
  </StrictMode>,
);
