import { useSyncExternalStore } from 'react';
import { Link } from 'react-router';
import { sameOwner } from '../../authBoundary';
import { useAppSelector } from '../../hooks';
import ProductExperience from '../ProductExperience';
import type { LocalCartSession } from './localCartSession';
import { copy } from '../../i18n';

/** Local development entry only. No transport is installed for an unverified
 * identity. In particular, checking/unavailable must not render a guest cart
 * in place of an account result while login state is being resolved. */
export default function LocalAccountStorefront({ session }: { session: LocalCartSession }) {
  const auth = useAppSelector(state => state.authentication);
  const t = copy[useAppSelector(state => state.ui.language)];
  const admission = useSyncExternalStore(session.subscribe, session.getSnapshot);
  if (auth.phase === 'ANONYMOUS') return <ProductExperience />;
  if (auth.phase === 'AUTHENTICATED' && admission && auth.user?.id === admission.userId && sameOwner(auth.owner, admission.owner))
    return <ProductExperience accountCart={admission} />;
  return <main><p role="status">{t.status[auth.phase]}</p><Link to="/login">{t.login}</Link></main>;
}
