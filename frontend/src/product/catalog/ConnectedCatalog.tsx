import { useEffect, useState, type ReactNode } from 'react';
import { catalogReady, loadLocalCatalog } from './localCatalog';
import { useProductLocale } from '../useProductLocale';

const messages = {
  en: ['Loading catalog…', 'The catalog could not be loaded.', 'Try again'],
  de: ['Katalog wird geladen…', 'Der Katalog konnte nicht geladen werden.', 'Erneut versuchen'],
  ru: ['Загружаем каталог…', 'Не удалось загрузить каталог.', 'Повторить'],
  pl: ['Ładowanie katalogu…', 'Nie udało się wczytać katalogu.', 'Spróbuj ponownie'],
  fr: ['Chargement du catalogue…', 'Impossible de charger le catalogue.', 'Réessayer'],
  es: ['Cargando el catálogo…', 'No se pudo cargar el catálogo.', 'Reintentar'],
  it: ['Caricamento del catalogo…', 'Impossibile caricare il catalogo.', 'Riprova'],
} as const;

/** Live reads gate only the storefront, never the account or its lifecycle.
 * The initial load is shared across StrictMode mount/cleanup/remount. */
export default function ConnectedCatalog({children}: {children: ReactNode}) {
  const [phase, setPhase] = useState<'pending'|'ready'|'failed'>(() => catalogReady() ? 'ready' : 'pending');
  const labels = messages[useProductLocale()];
  useEffect(() => {
    if (phase !== 'pending') return;
    let active = true;
    void loadLocalCatalog().then(() => { if (active) setPhase('ready'); }, () => { if (active) setPhase('failed'); });
    return () => { active = false; };
  }, [phase]);
  if (phase === 'ready') return children;
  return <main className="product-width">{phase === 'pending' ? <p role="status">{labels[0]}</p> :
    <><p role="alert">{labels[1]}</p><button type="button" className="product-secondary" onClick={() => setPhase('pending')}>{labels[2]}</button></>}</main>;
}
