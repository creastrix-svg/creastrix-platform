import type {Shelf} from './favoriteShelf';
import {useProductLocale} from '../useProductLocale';
import {shelfCopy} from './favoriteCopy';
import {CartSignInAgain} from '../cart/CartAccessAction';
export function FavoriteStatus({shelf,detail=false}:{shelf:Shelf;detail?:boolean}){
  const t=shelfCopy[useProductLocale()];
  if(shelf.reauth)return detail?<div data-private-account><CartSignInAgain admission={shelf.reauth}/></div>:null;
  if(detail&&shelf.terminal&&!shelf.problem)return <p role="status" data-private-account>{t[5]}</p>;
  return shelf.problem||shelf.importProblem?<p className="product-width" role="status" data-private-account={shelf.private||undefined}>
    {shelf.problem?t[1]:t[2]} <button type="button" className="product-text-button" onClick={shelf.retry}>{t[3]}</button></p>:null;
}
