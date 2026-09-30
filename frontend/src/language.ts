export type Language = 'de' | 'en';
export const LANGUAGE_KEY = 'creastrix.ui.language';
type PreferenceStorage = Pick<Storage, 'getItem' | 'setItem'>;
type StorageAccess = () => PreferenceStorage | undefined;

function browserStorage(): PreferenceStorage | undefined {
  return window.localStorage;
}

export function initialLanguage(
  primaryLanguage: string,
  storage: StorageAccess = browserStorage,
): Language {
  try {
    const saved = storage()?.getItem(LANGUAGE_KEY);
    if (saved === 'de' || saved === 'en') return saved;
  } catch {
    // Storage may be unavailable even when accessing the localStorage getter.
  }
  return /^de(?:-|$)/i.test(primaryLanguage) ? 'de' : 'en';
}

export function rememberLanguage(language: Language, storage: StorageAccess = browserStorage): void {
  try {
    storage()?.setItem(LANGUAGE_KEY, language);
  } catch {
    // The in-memory choice still works; persistence is only a convenience.
  }
}
