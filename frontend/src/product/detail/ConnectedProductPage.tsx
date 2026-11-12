import { useState } from 'react';
import { useLocation, useSearchParams } from 'react-router';
import { useAppDispatch, useAppSelector } from '../../hooks';
import { connectedOffer, entryForPath, type CatalogProduct, type CatalogVariant } from '../catalog/localCatalog';
import type { CatalogEntry, Rail } from '../catalog/catalogEntries';
import { isCartSku, type CartSku } from '../cartSku';
import { cartAdded, favoriteToggled, variantCartQuantity } from '../demoStore';
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
import ProductPurchasePanel from './ProductPurchasePanel';
import { accountProductLabels } from '../cart/accountProductLabels';

/** Both historical URL families resolve through catalog identity; neither the
 * layout nor the purchase action needs a new component for the next item. */
export default function ConnectedProductPage() {
  const entry = entryForPath(useLocation().pathname);
  const [params, setParams] = useSearchParams();
  const offer = entry && connectedOffer(entry.id);
  if (!entry || entry.kind !== 'offer' || !offer) return <NotFoundView/>;
  const variant = offer.variants.find(v => v.id === params.get('variant')) ?? offer.variants[0];
  if (!variant || !isCartSku(variant.sku)) return <NotFoundView/>;
  return <OfferCard key={variant.sku} entry={entry} offer={offer} variant={variant} sku={variant.sku}
    select={id => { const next = new URLSearchParams(params); next.set('variant', id); setParams(next, {replace:true, preventScrollReset:true}); }}/>;
}

function OfferCard({entry, offer, variant, sku, select}: {
  entry: CatalogEntry; offer: CatalogProduct; variant: CatalogVariant; sku: CartSku; select: (id:string) => void;
}) {
  const locale = useProductLocale(), t = productCopy[locale], c = catalogCopy[locale];
  const dispatch = useAppDispatch();
  const favorite = useAppSelector(state => state.productDemo.favorites.includes(sku));
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
      favorite={favorite} toggleFavorite={() => dispatch(favoriteToggled(sku))}/>}
    reviews={{list:list + (query ? '?' + query : ''), write: score => list + '/new?' + (query ? query + '&' : '') + 'rating=' + score}}
    price={<ProductPrice cents={variant.cents} previousCents={variant.previousCents} language={locale} t={t}/>}
    purchase={<ProductPurchasePanel locale={locale} stock={variant.stock} remaining={variant.stock - inCart}
      unitCents={variant.cents} requested={quantity} onQuantity={changeQuantity} inCart={inCart} returnTo={returnTo}
      exhaustedLabel={t.allInCart} onAdd={amount => { dispatch(cartAdded({variant:sku, quantity:amount})); changeQuantity(1); }}/>}
    features={[material || title, finish, c.ready]} about={{title:t.specsTitle, body:entry.description[locale], facts}}
    description={{intro:title, sections:[], facts:[[t.specsModel, entry.name], ...facts]}}
    seller={{name:'Creastrix', byline:t.seller, href:'/preview/info/about', label:t.about}}
    recommendations={{studio:rail('studio'), related:rail('related'), section:rail('section')}}/>;
}
