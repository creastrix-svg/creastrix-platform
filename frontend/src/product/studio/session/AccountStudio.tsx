import {useCallback,useReducer,type PropsWithChildren} from 'react';
import {compositionReducer,initialStudioComposition,type CompositionAction,type CompositionSession} from '../design/studioComposition';
import {PersonalizationSession} from './personalizationSession';
import {StudioCreativeProvider} from './studioCreativeSession';
import {AccountSurfaceScope} from './accountSurfaceScope';

/** Never restore a guest draft into an account editor. The verified owner
 * keys this provider; a new admission starts with empty private layers/fonts. */
export function AccountStudio({children}:PropsWithChildren){
  const [state,dispatch]=useReducer(compositionReducer,undefined,initialStudioComposition);
  const [states,update]=useReducer((prev:ReadonlyMap<string,CompositionSession>,{sku,action}:{sku:string;action:CompositionAction})=>{
    const current=prev.get(sku)??initialStudioComposition(),next=compositionReducer(current,action);
    return next===current?prev:new Map(prev).set(sku,next);
  },new Map<string,CompositionSession>());
  const updateSku=useCallback((sku:string,action:CompositionAction)=>update({sku,action}),[]);
  return <AccountSurfaceScope.Provider value={{states,dispatch:updateSku}}><PersonalizationSession.Provider value={{state,dispatch}}>
    <StudioCreativeProvider><div data-private-account>{children}</div></StudioCreativeProvider>
  </PersonalizationSession.Provider></AccountSurfaceScope.Provider>;
}
