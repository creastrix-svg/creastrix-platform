import {useCallback,useMemo,type PropsWithChildren} from 'react';
import {initialStudioComposition,type CompositionAction} from '../design/studioComposition';
import {useAccountSurfaceScope} from './accountSurfaceScope';
import {PersonalizationSession} from './personalizationSession';

export function CatalogSurfaceSession({sku,children}:PropsWithChildren<{sku:string}>){
  const scope=useAccountSurfaceScope(),current=scope?.states.get(sku),send=scope?.dispatch;
  const state=useMemo(()=>current??initialStudioComposition(),[current]);
  const dispatch=useCallback((action:CompositionAction)=>send?.(sku,action),[sku,send]);
  if(!scope)return null;
  return <PersonalizationSession.Provider value={{state,dispatch}}>{children}</PersonalizationSession.Provider>;
}
