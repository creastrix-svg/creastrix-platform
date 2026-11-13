import {createContext,useContext} from 'react';
import type {CompositionAction,CompositionSession} from '../design/studioComposition';

/** Each published SKU has its own private composition. The projection slot
 * inside a surface template is independent of the actual commerce identity. */
export const AccountSurfaceScope=createContext<{
  states:ReadonlyMap<string,CompositionSession>;dispatch:(sku:string,action:CompositionAction)=>void;
}|null>(null);
export const useAccountSurfaceScope=()=>useContext(AccountSurfaceScope);
