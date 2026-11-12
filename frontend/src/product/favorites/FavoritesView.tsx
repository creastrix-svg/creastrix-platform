import { useId, useRef, useState } from 'react';
import { Link } from 'react-router';
import { useAppDispatch, useAppSelector } from '../../hooks';
import { cartOfferView } from '../cart/cartOfferView';
import { catalogCopy } from '../browse/catalogCopy';
import { isVariantId } from '../fixtures/loop';
import type { CartSku } from '../cartSku';
import { productCopy } from '../copy';
import { cartLinePrice, favoriteRemoved } from '../demoStore';
import ProductPrice from '../ProductPrice';
import { useProductLocale } from '../useProductLocale';
import { StorefrontBack } from '../StorefrontBack';

export function FavoritesView() {
  const language = useProductLocale(), t = productCopy[language];
  const ids = useAppSelector(state => state.productDemo.favorites), dispatch = useAppDispatch();
  const heading = useRef<HTMLHeadingElement>(null);
  const titlePrefix = useId();
  const links = useRef(new Map<CartSku, HTMLAnchorElement>());
  const [broken, setBroken] = useState<Partial<Record<CartSku, boolean>>>({});
  return <div className="product-preview-page"><StorefrontBack /><h1 ref={heading} tabIndex={-1}>{t.favorites}</h1>
    {ids.length ? <div className="product-saved-grid">{ids.map((id, index) => {
      const offer = cartOfferView(id, language), { photo, detail } = offer;
      const label = offer.name + ' · ' + detail;
      const titleId = titlePrefix + '-' + id;
      return <article key={id} className="product-saved-card"><Link aria-labelledby={titleId} to={offer.href} ref={node => { if (node) links.current.set(id, node); else links.current.delete(id); }}>
        {photo && !broken[id] ? <img src={photo} alt={offer.alt} width="425" height="283" onError={() => setBroken(previous => ({ ...previous, [id]: true }))} /> :
          <span className="product-saved-swatch" role="img" aria-label={detail + ' · ' + t.noPhoto} style={{ background: offer.swatch }} />}
        <h2 id={titleId}>{label}</h2></Link>
        {offer.finish !== detail && <small>{offer.finish}</small>}
        <ProductPrice cents={cartLinePrice({variant:id}).baseCents} previousCents={offer.previousCents} language={language} t={t} baseNote={isVariantId(id) ? undefined : catalogCopy[language].ready} />
        <button type="button" className="product-text-button" aria-label={t.unfavorite + ' · ' + label} onClick={() => {
          (links.current.get(ids[index + 1] ?? ids[index - 1]) ?? heading.current)?.focus();
          dispatch(favoriteRemoved(id));
        }}>{t.remove}</button></article>;
    })}</div> : <div className="product-empty"><p><strong>{t.emptySaved}</strong></p><p>{t.savedHint}</p></div>}</div>;
}
