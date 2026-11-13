import {createContext,useContext} from 'react';
import {useAppDispatch,useAppSelector} from '../../hooks';
import type {CartSku} from '../cartSku';
import {favoriteRemoved,favoriteToggled} from '../demoStore';
import {sharedCatalogMode} from '../catalog/permanentCatalog';
import {isCartSku} from '../cartSku';
import type {AccountCartAdmission} from '../cart/accountCartAdmission';

export type Shelf={
  ids:readonly string[];busy:boolean;loading:boolean;problem:boolean;importProblem?:boolean;terminal?:boolean;reauth?:AccountCartAdmission;private?:boolean;
  set:(sku:string,saved:boolean)=>void;retry:()=>void;
};
export const unavailableShelf:Shelf={ids:[],busy:true,loading:true,problem:false,set:()=>{},retry:()=>{}};
export const FavoriteShelfContext=createContext<Shelf|null>(null);

/** Only the unconnected preview uses Redux/localStorage. Under the shared mode
 * an unavailable account source must never fall back to the guest's list. */
export function useFavoriteShelf():Shelf{
  const connected=useContext(FavoriteShelfContext),ids=useAppSelector(s=>s.productDemo.favorites),dispatch=useAppDispatch();
  if(sharedCatalogMode)return connected??unavailableShelf;
  return {ids,busy:false,loading:false,problem:false,retry:()=>{},set(sku,saved){
    if(!isCartSku(sku)||ids.includes(sku)===saved)return;
    dispatch(saved?favoriteToggled(sku):favoriteRemoved(sku));
  }};
}
export function useFavoriteHeart(sku:CartSku){
  const shelf=useFavoriteShelf(),favorite=shelf.ids.includes(sku);
  return {favorite,favoriteDisabled:shelf.busy||shelf.loading,favoritePrivate:shelf.private,
    toggleFavorite:()=>shelf.set(sku,!favorite)};
}
