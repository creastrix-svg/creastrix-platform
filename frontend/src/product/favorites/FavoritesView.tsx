import { useId, useRef, useState } from 'react';
import { Link } from 'react-router';
import {useFavoriteShelf} from './favoriteShelf';
import {shelfCopy} from './favoriteCopy';
import {FavoriteStatus} from './FavoriteStatus';
import { cartOfferView } from '../cart/cartOfferView';
import { catalogCopy } from '../browse/catalogCopy';
import { isVariantId } from '../fixtures/loop';
import {isCartSku} from '../cartSku';
import { productCopy } from '../copy';
import { cartLinePrice } from '../demoStore';
import {connectedSku} from '../catalog/localCatalog';
import {sharedCatalogMode} from '../catalog/permanentCatalog';
import {swatchBackground} from '../productDisplay';
import {thumbnailFor} from '../catalog/photoRenditions';
import type {StorefrontLocale} from '../locale';
import ProductPrice from '../ProductPrice';
import { useProductLocale } from '../useProductLocale';
import { StorefrontBack } from '../StorefrontBack';

function favoriteOffer(id:string,language:StorefrontLocale){
  if(sharedCatalogMode){
    const found=connectedSku(id);
    if(!found)return null;
    const {product,variant}=found,detail=variant.name[language],photo=variant.photos[0];
    return {name:product.name,detail,finish:detail,href:product.href+'?variant='+encodeURIComponent(variant.id),
      photo:photo?thumbnailFor(photo):undefined,alt:product.name+' · '+detail,swatch:swatchBackground(variant.colors),
      baseCents:variant.cents,previousCents:variant.previousCents,baseNote:undefined};
  }
  return isCartSku(id)?{...cartOfferView(id,language),baseCents:cartLinePrice({variant:id}).baseCents,
    baseNote:isVariantId(id)?undefined:catalogCopy[language].ready}:null;
}

export function FavoritesView() {
  const language = useProductLocale(), t = productCopy[language];
  const shelf=useFavoriteShelf(),ids=shelf.ids;
  const heading = useRef<HTMLHeadingElement>(null);
  const titlePrefix = useId();
  const links = useRef(new Map<string, HTMLAnchorElement>());
  const [broken, setBroken] = useState<Partial<Record<string, boolean>>>({});
  return <div className="product-preview-page" data-private-account={shelf.private||undefined}><StorefrontBack /><h1 ref={heading} tabIndex={-1}>{t.favorites}</h1>
    {(shelf.reauth||shelf.terminal)&&<FavoriteStatus shelf={shelf} detail/>}
    {shelf.reauth?null:shelf.loading?<p role="status">{shelfCopy[language][0]}</p>:ids.length ? <div className="product-saved-grid">{ids.map((id, index) => {
      const remove=()=>{(links.current.get(ids[index+1]??ids[index-1])??heading.current)?.focus();shelf.set(id,false);};
      const offer=favoriteOffer(id,language);
      if(!offer)return <article key={id} className="product-saved-card"><h2>{shelfCopy[language][4]}</h2><small>{id}</small><button type="button" className="product-text-button" disabled={shelf.busy} aria-label={t.unfavorite+' · '+id} onClick={remove}>{t.remove}</button></article>;
      const {photo,detail}=offer;
      const label = offer.name + ' · ' + detail;
      const titleId = titlePrefix + '-' + id;
      return <article key={id} className="product-saved-card"><Link aria-labelledby={titleId} to={offer.href} ref={node => { if (node) links.current.set(id, node); else links.current.delete(id); }}>
        {photo && !broken[id] ? <img src={photo} alt={offer.alt} width="425" height="283" onError={() => setBroken(previous => ({ ...previous, [id]: true }))} /> :
          <span className="product-saved-swatch" role="img" aria-label={detail + ' · ' + t.noPhoto} style={{ background: offer.swatch }} />}
        <h2 id={titleId}>{label}</h2></Link>
        {offer.finish !== detail && <small>{offer.finish}</small>}
        <ProductPrice cents={offer.baseCents} previousCents={offer.previousCents} language={language} t={t} baseNote={offer.baseNote} />
        <button type="button" className="product-text-button" aria-label={t.unfavorite + ' · ' + label} disabled={shelf.busy} onClick={remove}>{t.remove}</button></article>;
    })}</div> : shelf.problem?null:<div className="product-empty"><p><strong>{t.emptySaved}</strong></p><p>{t.savedHint}</p></div>}</div>;
}
