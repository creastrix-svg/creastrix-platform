import { expect, it, vi } from 'vitest';
import { createAppStore, languageSelected } from './store';
import { rememberLanguage } from './language';

it('keeps only shared UI state, persisting explicit language selections', () => {
  const persist = vi.fn();
  const store = createAppStore('en', persist);
  expect(persist).not.toHaveBeenCalled();
  expect(store.getState()).toEqual({ ui: { language: 'en' } });
  store.dispatch(languageSelected('de'));
  expect(store.getState()).toEqual({ ui: { language: 'de' } });
  expect(persist).toHaveBeenCalledExactlyOnceWith('de');
});

it('retains an in-memory selection when persistence is unavailable', () => {
  const store = createAppStore('en', language => rememberLanguage(language, () => { throw new Error('Denied'); }));
  store.dispatch(languageSelected('de'));
  expect(store.getState()).toEqual({ ui: { language: 'de' } });
});
