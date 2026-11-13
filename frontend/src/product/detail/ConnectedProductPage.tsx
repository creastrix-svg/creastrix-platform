import { useContext, useMemo, useState } from 'react';
import { Link,useLocation, useSearchParams } from 'react-router';
import { useAppDispatch, useAppSelector } from '../../hooks';
import { connectedOffer, entryForPath, type CatalogProduct, type CatalogVariant } from '../catalog/localCatalog';
import type { CatalogEntry, Rail } from '../catalog/catalogEntries';
import { isCartSku, type CartSku } from '../cartSku';
import { cartAdded, variantCartQuantity } from '../demoStore';
import {useFavoriteHeart} from '../favorites/favoriteShelf';
import { productCopy } from '../copy';
import { catalogCopy } from '../browse/catalogCopy';
import { departmentForSlug } from '../home/homeFeatured';
import { shelfCopy } from '../home/homeShelfCopy';
import { OfferMediaGallery } from '../media/MediaGallery';
import { NotFoundView } from '../NotFoundView';
import { useProductLocale } from '../useProductLocale';
import ProductPrice from '../ProductPrice';
import { railItems, sellerDestination } from './carouselContent';
import ProductCardLayout from './ProductCardLayout';
import ProductPurchasePanel, { ReadOnlyCartNotice } from './ProductPurchasePanel';
import { accountProductLabels } from '../cart/accountProductLabels';
import { AccountPurchaseContext } from '../cart/accountPurchaseContext';
import { AccountOfferPurchase } from '../cart/AccountOfferPurchase';
import { permanentCatalogMode,sharedCatalogMode } from '../catalog/permanentCatalog';
import {CatalogSurfaceSession} from '../studio/session/CatalogSurfaceSession';
import {usePersonalizationSession} from '../studio/session/personalizationSession';
import {PublishedSurfaceEditor} from '../studio/PersonalizationView';
import {saveDesign,visibleDesign,type SavedDesign} from '../studio/session/designSnapshot';
import {OpenPurchasedDesign} from '../cart/OpenPurchasedDesign';
import {PurchasedDesignUnavailable} from '../cart/PurchasedDesignUnavailable';
import {MissingLibraryDesign} from '../designs/LibraryStudio';

/** Both historical URL families resolve through catalog identity; neither the
 * layout nor the purchase action needs a new component for the next item. */
export default function ConnectedProductPage() {
  const entry = entryForPath(useLocation().pathname);
  const account=useContext(AccountPurchaseContext);
  const [params, setParams] = useSearchParams();
  const offer = entry && connectedOffer(entry.id);
  const opening=sharedCatalogMode && params.has('accountLine');
  const saved=params.has('savedDesign');
  if(saved && (!sharedCatalogMode||!account||!offer?.personalizable||params.has('accountLine')||params.has('cartLine')||
    params.getAll('variant').length!==1||params.getAll('personalize').length!==1||params.get('personalize')!=='surface'))
    return <MissingLibraryDesign/>;
  if (!entry || entry.kind !== 'offer' || !offer) return opening?<PurchasedDesignUnavailable/>:<NotFoundView/>;
  const variant = offer.variants.find(v => v.id === params.get('variant')) ?? offer.variants[0];
  if(saved && (variant?.id!==params.get('variant')||variant?.studioFormat!=='surface-print-v1'||variant?.templateRevision!=='2'))
    return <MissingLibraryDesign/>;
  if (!variant || !isCartSku(variant.sku)) return opening?<PurchasedDesignUnavailable/>:<NotFoundView/>;
  if(opening && (!account || params.getAll('variant').length!==1 || params.get('variant')!==variant.id ||
      variant.studioFormat!=='surface-print-v1'||variant.templateRevision!=='2'))return <PurchasedDesignUnavailable/>;
  const props={entry,offer,variant,sku:variant.sku,select:(id:string)=>{
    const next=new URLSearchParams(params);next.set('variant',id);next.delete('accountLine');next.delete('personalize');
    setParams(next,{replace:true,preventScrollReset:true});
  }};
  if(sharedCatalogMode && account && offer.personalizable && variant.studioFormat==='surface-print-v1'){
    const open=params.getAll('accountLine');
    return <CatalogSurfaceSession key={variant.sku} sku={variant.sku}>{open.length===1
      ? <OpenPurchasedDesign key={open[0]} admission={account} lineId={open[0]} sku={variant.sku} variant="cobalt" variantId={variant.id} href={offer.href}>
          <SurfaceOfferCard {...props}/></OpenPurchasedDesign>
      : open.length ? <NotFoundView/> : params.get('personalize')==='surface' ? <PublishedSurfaceEditor offer={offer} finish={variant}/>
      : <SurfaceOfferCard {...props}/>}</CatalogSurfaceSession>;
  }
  if(opening)return <PurchasedDesignUnavailable/>;
  if(sharedCatalogMode && params.has('personalize'))return <NotFoundView/>;
  return <OfferCard key={variant.sku} {...props}/>;
}

function GuestOfferPurchase({sku,variant,returnTo}:{sku:CartSku;variant:CatalogVariant;returnTo:string}) {
  const locale=useProductLocale(), t=productCopy[locale];
  const dispatch = useAppDispatch();
  const inCart = useAppSelector(state => variantCartQuantity(state.productDemo, sku));
  const draftKey = 'creastrix.product.quantity.' + sku;
  const [quantity, setQuantity] = useState(() => {
    try {
      const stored = localStorage.getItem(draftKey), n = Number(stored);
      if (stored && /^\d+$/.test(stored) && Number.isSafeInteger(n)) return Math.max(1, Math.min(n, variant.stock));
    } catch { /* Storage is optional; buying in the current tab still works. */ }
    return 1;
  });
  function changeQuantity(value: number) {
    setQuantity(value);
    try { localStorage.setItem(draftKey, String(value)); } catch { /* In-memory selection remains available. */ }
  }
  return <ProductPurchasePanel locale={locale} stock={variant.stock} remaining={variant.stock - inCart}
    unitCents={variant.cents} requested={quantity} onQuantity={changeQuantity} inCart={inCart} returnTo={returnTo}
    exhaustedLabel={t.allInCart} onAdd={amount => { dispatch(cartAdded({variant:sku, quantity:amount})); changeQuantity(1); }}/>;
}

type OfferCardProps={
  entry: CatalogEntry; offer: CatalogProduct; variant: CatalogVariant; sku: CartSku; select: (id:string) => void;
};
function SurfaceOfferCard(props:OfferCardProps){
  const {state,dispatch}=usePersonalizationSession(),applied=state.applied.cobalt,locale=useProductLocale(),t=productCopy[locale];
  const design=useMemo(()=>{const value=saveDesign(applied);return visibleDesign(value)?value:undefined;},[applied]);
  const href=props.offer.href+'?variant='+props.variant.id+'&personalize=surface';
  return <OfferCard {...props} design={design} personalization={<section className="product-personalisation">
    <div><strong>{t.personalise}</strong><span>{t.personalIntro}</span></div><Link to={href}>{t.personalButton}</Link>
    {design && <button type="button" className="product-text-button" onClick={()=>dispatch({type:'remove',variant:'cobalt'})}>{t.remove}</button>}
  </section>}/>;
}
function OfferCard({entry, offer, variant, sku, select,design,personalization}: OfferCardProps & {design?:SavedDesign;personalization?:React.ReactNode}) {
  const locale = useProductLocale(), t = productCopy[locale], c = catalogCopy[locale];
  const account=useContext(AccountPurchaseContext);
  const heart=useFavoriteHeart(sku);
  const finish = variant.name[locale], title = entry.title[locale], material = entry.material[locale];
  const categorySlug = entry.categories[0], department = departmentForSlug(categorySlug);
  const category = department ? {label: shelfCopy[locale][department], href: '/catalog?category=' + categorySlug}
    : {label: c.title, href: '/catalog'};
  const facts = [...(material ? [[t.material, material] as const] : []), [t.color, finish] as const,
    ...(offer.templateRevision ? [[accountProductLabels[locale][0], offer.templateRevision] as const] : [])];
  const returnTo = entry.href + (offer.variants.length > 1 ? '?variant=' + variant.id : '');
  const list = entry.href + '/reviews', query = offer.variants.length > 1 ? 'variant=' + variant.id : '';
  const rail = (section: Rail) => ({items: railItems(entry.id, section, locale) ?? [],
    ...(section === 'studio' ? sellerDestination(entry.id) : {href:'/catalog'})});
  return <ProductCardLayout locale={locale} name={entry.name} subtitle={title} category={category}
    finishes={offer.variants.map(v => ({id:v.id, name:v.name[locale], colors:v.colors, checkTone:v.checkTone}))}
    selected={variant.id} urlVariant={sku} onSelect={offer.variants.length > 1 ? select : undefined}
    gallery={<OfferMediaGallery sku={sku} finishName={finish} media={variant} imageSize={[1024,1024]} locale={locale}
      t={{...t, photoAlt: entry.name + ' · ' + title}} unavailableModelReason={variant.model ? undefined : t.modelFailed}
      {...heart}/>}
    reviews={{list:list + (query ? '?' + query : ''), write: score => list + '/new?' + (query ? query + '&' : '') + 'rating=' + score}}
    personalization={personalization}
    price={<ProductPrice cents={variant.cents+(design?offer.designFeeCents:0)} designFeeCents={design?offer.designFeeCents:0}
      hasDesign={!!design} previousCents={variant.previousCents} language={locale} t={t}/>}
    purchase={!account ? <GuestOfferPurchase sku={sku} variant={variant} returnTo={returnTo}/>
      : (permanentCatalogMode && variant.directAddSupported && account.directAdditions
        ? <AccountOfferPurchase key={account.userId+':'+sku} admission={account} writer={account.directAdditions} offerId={offer.id}
          variant={variant} returnTo={returnTo} design={design} unitCents={variant.cents+(design?offer.designFeeCents:0)}/>
        : <ReadOnlyCartNotice purchase/>)}
    features={[material || title, finish, c.ready]} about={{title:t.specsTitle, body:entry.description[locale], facts}}
    description={{intro:title, sections:[], facts:[[t.specsModel, entry.name], ...facts]}}
    seller={{name:'Creastrix', byline:t.seller, href:'/preview/info/about', label:t.about}}
    recommendations={{studio:rail('studio'), related:rail('related'), section:rail('section')}}/>;
}
