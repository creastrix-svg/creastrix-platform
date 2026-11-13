import {useContext} from 'react';
import {Link,useLocation} from 'react-router';
import {AuthenticationContext,type Phase} from '../../authentication';
import {catalogCopy} from '../browse/catalogCopy';
import CatalogView from '../browse/CatalogView';
import {productCopy} from '../copy';
import {useAppearance} from '../appearance';
import LoadingFrame from '../../LoadingFrame';
import {money} from '../productDisplay';
import {useProductLocale} from '../useProductLocale';
import {isCatalogOfferHref,isCatalogPath} from '../routes';
import ConnectedCatalog from './ConnectedCatalog';
import {entryForPath,connectedOffer,connectedOffers} from './localCatalog';
import {sharedCatalogMode} from './permanentCatalog';
import type {StorefrontLocale} from '../locale';
import '../product.css';
import './readOnlyStorefront.css';

const notices:Record<StorefrontLocale,readonly [string,string,string]>={
  en:['Loading…','Shopping tools are temporarily unavailable.','Try again'],
  de:['Lädt…','Einkaufsfunktionen sind vorübergehend nicht verfügbar.','Erneut versuchen'],
  ru:['Загружаем…','Покупки временно недоступны.','Повторить'],
  fr:['Chargement…','Les achats sont momentanément indisponibles.','Réessayer'],
  es:['Cargando…','Las compras no están disponibles en este momento.','Reintentar'],
  it:['Caricamento…','Gli acquisti non sono disponibili al momento.','Riprova'],
  pl:['Ładowanie…','Zakupy są chwilowo niedostępne.','Spróbuj ponownie'],
};

/** No personal rows, user-supplied text, guest storage or shopping commands.
 * A private/cart URL is never interpreted as a product selection. Only public
 * catalog URLs can choose the preview. The normal route stays intact for Retry. */
function BrowsePublic(){
  const locale=useProductLocale(),t=productCopy[locale],copy=catalogCopy[locale],{pathname,search}=useLocation();
  if(isCatalogPath(pathname)||pathname==='/')return <main className="product-width"><CatalogView/></main>;
  const path=pathname.replace(/\/$/,''),reviewParent=path.match(/^(.*)\/reviews(?:\/new)?$/)?.[1];
  const entry=entryForPath(path)??(reviewParent&&isCatalogOfferHref(reviewParent)?entryForPath(reviewParent):undefined);
  const product=entry?.kind==='offer'?connectedOffer(entry.id):undefined;
  const selected=new URLSearchParams(search).getAll('variant');
  const variant=selected.length===0?product?.variants[0]:selected.length===1?product?.variants.find(v=>v.id===selected[0]):undefined;
  if(product&&entry)return <main className="product-width public-product">
    {variant?.photos[0]&&<img src={variant.photos[0]} width="640" height="440" alt={product.name}/>}
    <section><h1>{product.name}</h1><p>{entry.title[locale]}</p><p>{entry.description[locale]}</p>
      {variant&&<p>{variant.name[locale]} · <strong>{money(variant.cents,locale)}</strong></p>}
      <Link to="/catalog" className="product-secondary">{t.browseCollection}</Link></section>
  </main>;
  const offers=connectedOffers()?.slice(0,4)??[];
  return <main className="product-width public-picks"><h1>{copy.title}</h1>
    <div className="public-picks-grid">{offers.map(o=><Link key={o.id} to={o.href}>
      {o.variants[0]?.photos[0]&&<img src={o.variants[0].photos[0]} width="240" height="160" alt="" loading="lazy"/>}<strong>{o.name}</strong>
    </Link>)}</div><Link className="product-secondary" to="/catalog">{t.browseCollection}</Link></main>;
}
const accountAccess:Record<StorefrontLocale,string>={
  en:'Recover account access',de:'Kontozugang wiederherstellen',ru:'Восстановить доступ',
  fr:'Rétablir l’accès au compte',es:'Recuperar el acceso a la cuenta',
  it:'Recupera l’accesso all’account',pl:'Odzyskaj dostęp do konta',
};
export default function ReadOnlyStorefront({phase}:{phase:Phase}){
  const runtime=useContext(AuthenticationContext),locale=useProductLocale(),n=notices[locale],{theme}=useAppearance();
  const {pathname,search}=useLocation(),params=new URLSearchParams(search);
  const personal=!sharedCatalogMode||['/preview/cart','/preview/checkout','/preview/saved','/account/designs'].includes(pathname.replace(/\/$/,''))||
    pathname.endsWith('/personalize')||['cartLine','accountLine','savedDesign','personalize','edit'].some(k=>params.has(k));
  if(phase==='CHECKING'||phase==='TRANSITION')return <LoadingFrame label={n[0]}/>;
  const requiresRecovery=phase==='DENIED'||phase==='RECOVERY_REQUIRED';
  return <div className="product-storefront" data-theme={theme}>
    <header className="product-width public-storefront-bar"><span className="product-logo">
      <img src="/brand/creastrix-logo-horizontal-black.svg" width="174" height="44" alt="Creastrix"/></span>
      <div className="public-storefront-actions">{sharedCatalogMode&&<Link to="/catalog">{catalogCopy[locale].title}</Link>}
        {requiresRecovery?<Link to="/login">{accountAccess[locale]}</Link>:
          <button type="button" className="product-text-button" disabled={!runtime} onClick={()=>void runtime?.recheck()}>{n[2]}</button>}
      </div></header>
    {personal&&<p className="product-width public-storefront-notice" role="status">{n[1]}</p>}
    {sharedCatalogMode&&<ConnectedCatalog><BrowsePublic/></ConnectedCatalog>}
  </div>;
}
