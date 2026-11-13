import {useContext,useEffect,useMemo,useSyncExternalStore,type ReactNode} from 'react';
import {AuthenticationContext} from '../../authentication';
import {useStore} from 'react-redux';
import {useAppSelector} from '../../hooks';
import type {RootState} from '../../store';
import {sameOwner} from '../../authBoundary';
import type {LocalCartSession} from '../cart/localCartSession';
import {FavoriteShelfContext,unavailableShelf} from './favoriteShelf';
import {accountFavoriteSession,guestFavoriteSession,createFavoriteJournal} from './favoriteSession';

const blocked={start:()=>()=>{},subscribe:()=>()=>{},snapshot:()=>unavailableShelf};

/** Keep the provider above routing: a successful hosted sign-in lands on the
 * account page. Guest transfer must not depend on opening the favorites page. */
export default function SharedFavoriteProvider({session,children}:{session:LocalCartSession;children:ReactNode}){
  const auth=useAppSelector(s=>s.authentication),store=useStore<RootState>();
  const authRuntime=useContext(AuthenticationContext),active=useSyncExternalStore(session.subscribe,session.getSnapshot);
  const journalFor=useMemo(()=>createFavoriteJournal(),[]);
  // Maintain an uncertain click across a refreshed transport for the same
  // buyer. A different login or browser document gets a distinct journal.
  const source=useMemo(()=>{
    if(auth.phase==='ANONYMOUS')return guestFavoriteSession(()=>{
      const now=store.getState().authentication;
      return now.phase==='ANONYMOUS'&&!!auth.owner&&sameOwner(now.owner,auth.owner)&&authRuntime?.isCurrentOwner(auth.owner)===true;
    });
    if(auth.phase==='AUTHENTICATED'&&active&&auth.user?.id===active.userId&&sameOwner(auth.owner,active.owner)){
      const scope=JSON.stringify([active.userId,active.owner.document,active.owner.revision]);
      return accountFavoriteSession(active,window.fetch.bind(window),journalFor(scope));
    }
    return blocked;
  },[auth,active,store,authRuntime,journalFor]);
  const shelf=useSyncExternalStore(source.subscribe,source.snapshot);
  useEffect(()=>source.start(),[source]);
  return <FavoriteShelfContext.Provider value={shelf}>{children}</FavoriteShelfContext.Provider>;
}
