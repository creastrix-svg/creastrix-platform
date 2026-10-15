import { useState, useSyncExternalStore } from 'react';

export type Appearance = 'light' | 'dark' | 'system';
const currentSystem = () => window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false;
const subscribe = (listener: () => void) => {
  const query = window.matchMedia?.('(prefers-color-scheme: dark)');
  query?.addEventListener?.('change', listener);
  return () => query?.removeEventListener?.('change', listener);
};
export function useAppearance() {
  const [appearance, setAppearance] = useState<Appearance>('light');
  const darkSystem = useSyncExternalStore(subscribe, currentSystem, () => false);
  return { appearance, setAppearance, theme: appearance === 'system' ? darkSystem ? 'dark' : 'light' : appearance };
}
