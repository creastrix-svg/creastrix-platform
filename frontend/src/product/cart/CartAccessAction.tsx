import {useContext} from 'react';
import {useNavigate} from 'react-router';
import {AuthenticationContext} from '../../authentication';
import {useCartAccessLabels} from './cartAccessLabels';
import type {AccountCartAdmission} from './accountCartAdmission';


/** This replaces a purchase button only after an explicit storefront refusal.
 * Start the ordinary local sign-in cleanup on click; never rebind a revoked
 * cart claim, import a guest cart, or remember an order to submit after login. */
export function CartSignInAgain({admission}:{admission:AccountCartAdmission}){
  const auth=useContext(AuthenticationContext),navigate=useNavigate(),[label]=useCartAccessLabels();
  return <button type="button" className="product-primary" disabled={!auth} onClick={()=>{
    if(!auth || !admission.access.ownsDisplay())return;
    navigate('/login');
    void auth.recover();
  }}>{label}</button>;
}
