import {Link,useLocation} from 'react-router';
import {Icon} from '../Icon';
import type {AccountCartAdmission} from './accountCartAdmission';
import {useAccountCartAccess} from './useAccountCartAccess';
import {catalogReady} from '../catalog/localCatalog';
import {cartReturnOfferPath} from './cartOfferView';

/** An unknown count is not zero and must never be filled from the guest store. */
export function AccountCartBadge({admission,label}:{admission:AccountCartAdmission;label:string}){
  const {state}=useAccountCartAccess(admission.access),location=useLocation();
  const count=state.phase==='ready'?state.cart.reduce((sum,line)=>sum+(line.removed?0n:BigInt(line.quantity)),0n):0n;
  const back=catalogReady()?cartReturnOfferPath(location.pathname+location.search):undefined;
  return <Link className="product-icon-button" aria-label={label} to="/preview/cart" state={back?{cartReturnTo:back}:undefined}>
    <Icon name="bag"/>{count>0n && <span className="product-count">{String(count)}</span>}</Link>;
}
