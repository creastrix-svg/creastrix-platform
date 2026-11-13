import { useSyncExternalStore } from 'react';
import { Link } from 'react-router';
import { sameOwner } from '../../authBoundary';
import { useAppSelector } from '../../hooks';
import ProductExperience from '../ProductExperience';
import type { LocalCartSession } from './localCartSession';
import { copy } from '../../i18n';
import {StudioChooserContext} from '../studio/session/studioChooserContext';

/** Local development entry only. No transport is installed for an unverified
 * identity. In particular, checking/unavailable must not render a guest cart
 * in place of an account result while login state is being resolved. */
export default function LocalAccountStorefront({ session }: { session: LocalCartSession }) {
  const auth = useAppSelector(state => state.authentication);
  const t = copy[useAppSelector(state => state.ui.language)];
  const admission = useSyncExternalStore(session.subscribe, session.getSnapshot);
  const continuity=session.studioChooser;
  const held=auth.phase==='CHECKING'?continuity.held():null;
  const admitted=auth.phase==='AUTHENTICATED' && admission && auth.user?.id===admission.userId && sameOwner(auth.owner,admission.owner);
  const show=auth.phase==='ANONYMOUS'||!!admitted;
  // Retain the native input node, not the old admission. While CHECKING the
  // curtain is closed, this tree is hidden and inert, its old guards are retired,
  // and the picker cannot publish until the fresh session has been verified.
  return <>{(show||held)&&<StudioChooserContext.Provider value={continuity.chooser}>
    <div hidden={!show} inert={!show} style={show?{display:'contents'}:undefined}>
      <ProductExperience key={held?.key??continuity.key()} accountCart={(held?held.account:admitted?admission:null)??undefined}
        studioKey={held?.key??continuity.key()}/>
    </div></StudioChooserContext.Provider>}
    {!show&&<main><p role="status">{t.status[auth.phase]}</p><Link to="/login">{t.login}</Link></main>}
  </>;
}
