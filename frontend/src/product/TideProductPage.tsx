import { useState } from 'react';
import { useAppDispatch, useAppSelector } from '../hooks';
import { variantById } from './catalog';
import { productCopy } from './copy';
import { cartAdded, favoriteToggled, variantCartQuantity } from './demoStore';
import { editionItems } from './editionCatalog';
import { shelfCopy } from './homeShelfCopy';
import { OfferMediaGallery } from './MediaGallery';
import ProductCardLayout from './ProductCardLayout';
import ProductPrice from './ProductPrice';
import ProductPurchasePanel from './ProductPurchasePanel';
import { tideOffer } from './tideOffer';
import { tideDetails } from './tideDetails';
import { useProductLocale } from './useProductLocale';

// An unsubmitted quantity is a local preference, never evidence of stock or a
// cart line. Keep it separate from Loop's existing preference record.
const draftKey = 'creastrix.product.tide.quantity';
function readQuantity() {
  try {
    const value = window.localStorage.getItem(draftKey);
    if (value && value.length <= String(tideOffer.stock).length && /^\d+$/.test(value)) {
      return Math.max(1, Math.min(Number(value), tideOffer.stock));
    }
  } catch { /* The quantity selector also works without browser storage. */ }
  return 1;
}

export default function TideProductPage() {
  const locale = useProductLocale(), t = productCopy[locale];
  const edition = editionItems(locale), item = edition.find(entry => entry.id === tideOffer.id)!;
  const cobalt = variantById('cobalt');
  const inCart = useAppSelector(state => variantCartQuantity(state.productDemo, tideOffer.sku));
  const favorite = useAppSelector(state => state.productDemo.favorites.includes(tideOffer.sku));
  const dispatch = useAppDispatch();
  const [quantity, setQuantity] = useState(readQuantity);
  const updateQuantity = (next: number) => {
    setQuantity(next);
    try { window.localStorage.setItem(draftKey, String(next)); } catch { /* This visit keeps its in-memory choice. */ }
  };
  const reviewList = tideOffer.href + '/reviews';
  return <ProductCardLayout {...tideDetails(locale, item, edition, cobalt.name[locale])} locale={locale} name={item.name} subtitle={item.title}
    category={{ label: shelfCopy[locale].kitchen, href: '/catalog?category=kitchen' }}
    finishes={[{ id: tideOffer.sku, name: cobalt.name[locale], colors: cobalt.colors, checkTone: cobalt.checkTone }]} selected={tideOffer.sku}
    gallery={<OfferMediaGallery sku={tideOffer.sku} finishName={cobalt.name[locale]} locale={locale}
      t={{ ...t, photoAlt: item.name + ' · ' + item.title + ' · ' + item.material }}
      media={{ photos: [tideOffer.photo], model: null }} imageSize={[1024, 1024]} unavailableModelReason={t.modelFailed}
      favorite={favorite} toggleFavorite={() => dispatch(favoriteToggled(tideOffer.sku))} />}
    reviews={{ list: reviewList, write: score => reviewList + '/new?rating=' + score }}
    price={<ProductPrice cents={tideOffer.unitCents} language={locale} t={t} />}
    purchase={<ProductPurchasePanel locale={locale} stock={tideOffer.stock} remaining={tideOffer.stock - inCart}
      unitCents={tideOffer.unitCents} requested={quantity} inCart={inCart} exhaustedLabel={t.allInCart}
      returnTo={tideOffer.href} onQuantity={updateQuantity}
      onAdd={amount => { dispatch(cartAdded({ variant: tideOffer.sku, quantity: amount })); updateQuantity(1); }} />} />;
}
