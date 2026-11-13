import {Link} from 'react-router';
import {productCopy} from '../copy';
import {useProductLocale} from '../useProductLocale';

const message={
  en:'This saved design is no longer available to apply. It remains in your cart.',
  de:'Dieses gespeicherte Design kann nicht mehr angewendet werden. Es bleibt in deinem Warenkorb.',
  ru:'Этот сохранённый дизайн больше нельзя применить. Он остался в вашей корзине.',
  pl:'Tego zapisanego projektu nie można już zastosować. Pozostaje on w koszyku.',
  es:'Este diseño guardado ya no se puede aplicar. Sigue en tu cesta.',
  fr:'Cette création enregistrée ne peut plus être appliquée. Elle reste dans votre panier.',
  it:'Questo design salvato non può più essere applicato. Rimane nel tuo carrello.',
};
/** A purchased configuration is never an ordinary plain-product deep link. */
export function PurchasedDesignUnavailable(){
  const locale=useProductLocale();
  return <section><p role="status">{message[locale]}</p><Link to="/preview/cart" className="product-back">{productCopy[locale].viewCart}</Link></section>;
}
