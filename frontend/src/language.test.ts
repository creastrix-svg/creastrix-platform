import { describe, expect, it, vi } from 'vitest';
import { initialLanguage, LANGUAGE_KEY, rememberLanguage } from './language';

describe('UI language preference', () => {
  it.each([['de', 'de'], ['de-DE', 'de'], ['DE-at', 'de'], ['en-GB', 'en'], ['fr-FR', 'en'], ['', 'en'], ['den', 'en']])(
    'uses primary browser language %s → %s without a saved choice', (input, expected) => {
      expect(initialLanguage(input, () => undefined)).toBe(expected);
    },
  );

  it('gives a valid explicit choice precedence over browser language', () => {
    localStorage.setItem(LANGUAGE_KEY, 'en');
    expect(initialLanguage('de-DE')).toBe('en');
    localStorage.setItem(LANGUAGE_KEY, 'de');
    expect(initialLanguage('en')).toBe('de');
  });

  it('ignores an invalid persisted value', () => {
    localStorage.setItem(LANGUAGE_KEY, 'fr');
    expect(initialLanguage('de')).toBe('de');
  });

  it('falls back when storage access or reading is blocked', () => {
    const denied = () => { throw new Error('Storage unavailable'); };
    expect(initialLanguage('de', denied)).toBe('de');
    expect(initialLanguage('fr', () => ({ getItem: denied, setItem: vi.fn() }))).toBe('en');
  });

  it('saves only the language preference', () => {
    rememberLanguage('de');
    expect(localStorage.length).toBe(1);
    expect(localStorage.getItem(LANGUAGE_KEY)).toBe('de');
  });

  it('does not throw when getting storage or persisting is blocked', () => {
    const denied = () => { throw new Error('Storage unavailable'); };
    expect(() => rememberLanguage('de', denied)).not.toThrow();
    expect(() => rememberLanguage('en', () => ({ getItem: vi.fn(), setItem: denied }))).not.toThrow();
  });
});
