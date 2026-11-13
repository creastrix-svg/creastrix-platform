import { useSyncExternalStore } from 'react';
import { useAppSelector } from '../../hooks';
import type { LocalCartSession } from './localCartSession';

const labels = {
  en:{syncing:'Syncing your guest cart…',applied:'Guest cart synced.',
    binding:'Open your account cart to restore access before syncing guest items.',
    unsupported:'This transfer contains an unsupported item, image or font. Its items are kept locally; none were added to your account cart.',
    retired:'This guest import was retired without adding its items. The local batch is retained.'},
  de:{syncing:'Gastwarenkorb wird übertragen…',applied:'Gastwarenkorb übertragen.',
    binding:'Öffne zuerst den Kontowarenkorb, um den Zugriff wiederherzustellen.',
    unsupported:'Diese Übertragung enthält einen nicht unterstützten Artikel, ein Bild oder eine Schrift. Alle Artikel dieser Übertragung bleiben lokal; keiner wurde zum Kontowarenkorb hinzugefügt.',
    retired:'Die Übertragung wurde ohne Gutschrift beendet. Die Artikel bleiben lokal gespeichert.'},
};
export default function GuestCartTransferNotice({session}:{session:LocalCartSession}) {
  const locale = useAppSelector(store => store.ui.language);
  const state = useSyncExternalStore(session.subscribe,session.getTransferSnapshot);
  // A pending import is retained by the transfer coordinator. It is not a
  // shopping action or a reason to show a technical retry banner to the buyer.
  if (state === 'idle' || state === 'pending') return null;
  const t = labels[locale];
  return <aside data-private-account className="studio-local-status"><p role="status">{t[state]}</p></aside>;
}
