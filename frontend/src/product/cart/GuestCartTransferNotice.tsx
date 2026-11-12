import { useSyncExternalStore } from 'react';
import { useAppSelector } from '../../hooks';
import type { LocalCartSession } from './localCartSession';

const labels = {
  en:{syncing:'Syncing your guest cart…',applied:'Guest cart synced.',pending:'Guest cart sync is pending. Your items are kept locally.',
    binding:'Open your account cart to restore access before syncing guest items.',
    unsupported:'This transfer contains an unsupported item, image or font. Its items are kept locally; none were added to your account cart.',
    retired:'This guest import was retired without adding its items. The local batch is retained.',retry:'Retry guest import'},
  de:{syncing:'Gastwarenkorb wird übertragen…',applied:'Gastwarenkorb übertragen.',pending:'Übertragung ausstehend. Deine Artikel bleiben lokal gespeichert.',
    binding:'Öffne zuerst den Kontowarenkorb, um den Zugriff wiederherzustellen.',
    unsupported:'Diese Übertragung enthält einen nicht unterstützten Artikel, ein Bild oder eine Schrift. Alle Artikel dieser Übertragung bleiben lokal; keiner wurde zum Kontowarenkorb hinzugefügt.',
    retired:'Die Übertragung wurde ohne Gutschrift beendet. Die Artikel bleiben lokal gespeichert.',retry:'Übertragung erneut versuchen'},
};
export default function GuestCartTransferNotice({session}:{session:LocalCartSession}) {
  const locale = useAppSelector(store => store.ui.language);
  const state = useSyncExternalStore(session.subscribe,session.getTransferSnapshot);
  if (state === 'idle') return null;
  const t = labels[locale];
  return <aside data-private-account className="studio-local-status"><p role="status">{t[state]}</p>
    {(state === 'pending' || state === 'binding') && <button type="button" onClick={session.retryTransfer}>{t.retry}</button>}</aside>;
}
