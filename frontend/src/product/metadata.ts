import { useEffect } from 'react';
import { useLocation } from 'react-router';
import type { ProductCopy } from './copy';
import { loopName } from './fixtures/loop';
import { conceptItems } from './fixtures/conceptItems';
import { entryForId, entryForPath } from './catalog/localCatalog';
import { tideOffer } from './fixtures/tideOffer';
import { ratingForPath } from './catalog/localCatalog';
import { ratingText } from './reviews/ratingText';
import type { StorefrontLocale } from './locale';
import { checkoutCopy } from './checkout/checkoutCopy';
import { personalizationCopy } from './studio/personalizationCopy';
import { studioZoneCopy } from './studio/studioZoneCopy';
import { catalogCopy } from './browse/catalogCopy';
import {designLibraryCopy} from './designs/designLibraryCopy';
import {isDesignLibraryPath} from './routes';
import { homeCopy } from './home/homeCopy';
import { isCatalogOfferHref, isCatalogPath, isTideOfferPath, tideOfferPath, tideReviewRoute } from './routes';
import { editionCollectionPath, editionCopy, editionItem } from './fixtures/editionCatalog';

export function useProductMetadata(t: ProductCopy, locale: StorefrontLocale, accountCart = false) {
  const { pathname: rawPath, search } = useLocation();
  const pathname = isTideOfferPath(rawPath) ? tideOfferPath : rawPath.replace(/\/+$/, '') || '/';
  const tideReviews = tideReviewRoute(pathname);
  const entry = isCatalogOfferHref(pathname) ? entryForPath(pathname) : undefined;
  const reviewMatch = pathname.match(/^(.*)\/reviews(\/new)?$/);
  const reviewed = reviewMatch && entryForPath(reviewMatch[1]);
  const info = { help: [t.help, t.helpBody], delivery: [t.delivery, t.deliveryBody], returns: [t.returns, t.returnsBody], contact: [t.contact, t.contactBody], about: [t.about, t.aboutInfo], legal: [t.legal, t.legalBody] };
  let title = t.notFound, description = t.notFoundBody;
  if (pathname === '/') { title = homeCopy[locale].title; description = homeCopy[locale].description; }
  else if(isDesignLibraryPath(pathname)){title=designLibraryCopy[locale].title;description='';}
  else if (pathname === editionCollectionPath) { title = editionCopy[locale].title; description = editionCopy[locale].intro; }
  else if (entry?.kind === 'offer') { title = entry.name + ' · ' + entry.title[locale]; description = entry.description[locale]; }
  else if (reviewed?.kind === 'offer') {
    title = reviewMatch?.[2] ? t.reviewFormTitle + ' · ' + reviewed.name : t.reviews.replace('{product}', reviewed.name);
    description = reviewMatch?.[2] ? t.reviewDemoNote : ratingText(reviewed.rating, locale);
  }
  else if (tideReviews === 'list') { title = t.reviews.replace('{product}', tideOffer.name); description = ratingText(ratingForPath(tideOfferPath), locale); }
  else if (tideReviews === 'form') { title = t.reviewFormTitle + ' · ' + tideOffer.name; description = t.reviewDemoNote; }
  else if (pathname.startsWith(editionCollectionPath + '/')) {
    const candidate = editionItem(pathname.slice(editionCollectionPath.length + 1), locale);
    const item = candidate?.href === pathname ? candidate : undefined;
    if (item) { title = item.name + ' · ' + item.title; description = item.description; }
  }
  else if (pathname === '/products/loop') { const entry = entryForId('loop'); title = loopName() + ' · ' + (entry?.title[locale] ?? t.subtitle); description = entry?.description[locale] ?? t.descriptionIntro; }
  else if (pathname === '/products/loop/reviews') { title = t.reviews.replace('{product}', loopName()); description = t.reviewsBody; }
  else if (pathname === '/products/loop/reviews/new') { title = t.reviewFormTitle; description = t.reviewDemoNote; }
  else if (pathname === '/products/loop/personalize') {
    const products = new URLSearchParams(search).getAll('product');
    const product = products.length === 1 && products[0] === 'lamp' ? 'Luma' : loopName();
    title = personalizationCopy[locale].eyebrow + ' · ' + product;
    description = studioZoneCopy[locale].intro;
  }
  else if (accountCart && (pathname.toLowerCase() === '/preview/cart' || pathname.toLowerCase() === '/preview/checkout')) {
    title = t.cart; description = t.viewCart;
  }
  else if (pathname === '/preview/cart') { title = t.cart; description = t.cartBoundary; }
  else if (pathname === '/preview/checkout') { title = checkoutCopy[locale].title; description = checkoutCopy[locale].prototypeNote; }
  else if (pathname === '/preview/saved') { title = t.favorites; description = t.savedNote; }
  else if (isCatalogPath(pathname)) { title = catalogCopy[locale].title; description = catalogCopy[locale].intro; }
  else if (pathname === '/preview/studio') { title = 'Loop Studio'; description = t.studioBody; }
  else if (pathname === '/preview/search') { const query = new URLSearchParams(search).get('q')?.slice(0, 100).trim(); title = t.results + (query ? ` · ${query}` : ''); description = t.searchNote; }
  else if (pathname.startsWith('/preview/info/')) { const topic = pathname.slice('/preview/info/'.length); if (Object.hasOwn(info, topic)) [title, description] = info[topic as keyof typeof info]; }
  else if (pathname.startsWith('/preview/items/')) {
    const entry = entryForPath(pathname);
    if (entry) { title = entry.name + ' · ' + entry.title[locale]; description = entry.description[locale]; }
    else { const item = conceptItems(locale).find(item => item.href === pathname); if (item) { title = `${item.name} · ${t.concept}`; description = item.description; } }
  }
  const pageTitle = pathname === '/preview/info/about' ? title : title + ' · Creastrix';
  useEffect(() => {
    document.documentElement.lang = locale;
    document.title = pageTitle;
    const previous = document.head.querySelector<HTMLMetaElement>('meta[name="description"]');
    const element = previous ?? document.createElement('meta');
    const content = previous?.getAttribute('content');
    element.name = 'description'; element.content = description;
    if (!previous) document.head.append(element);
    return () => { if (!previous) element.remove(); else if (content === null) element.removeAttribute('content'); else element.content = content!; };
  }, [pageTitle, description, locale]);
}
