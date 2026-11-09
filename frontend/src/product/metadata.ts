import { useEffect } from 'react';
import { useLocation } from 'react-router';
import type { ProductCopy } from './copy';
import { concepts } from './catalog';
import type { StorefrontLocale } from './locale';
import { checkoutCopy } from './checkoutCopy';
import { personalizationCopy } from './personalizationCopy';
import { studioZoneCopy } from './studioZoneCopy';
import { catalogCopy } from './catalogCopy';
import { homeCopy } from './homeCopy';
import { isCatalogPath, isTideOfferPath, tideOfferPath, tideReviewRoute } from './routes';
import { editionCollectionPath, editionCopy, editionItems } from './editionCatalog';

export function useProductMetadata(t: ProductCopy, locale: StorefrontLocale) {
  const { pathname: rawPath, search } = useLocation();
  const pathname = isTideOfferPath(rawPath) ? tideOfferPath : rawPath.replace(/\/+$/, '') || '/';
  const tideReviews = tideReviewRoute(pathname);
  const info = { help: [t.help, t.helpBody], delivery: [t.delivery, t.deliveryBody], returns: [t.returns, t.returnsBody], contact: [t.contact, t.contactBody], about: [t.about, t.aboutInfo], legal: [t.legal, t.legalBody] };
  let title = t.notFound, description = t.notFoundBody;
  if (pathname === '/') { title = homeCopy[locale].title; description = homeCopy[locale].description; }
  else if (pathname === editionCollectionPath) { title = editionCopy[locale].title; description = editionCopy[locale].intro; }
  else if (tideReviews === 'list') { title = t.reviews.replace('{product}', 'Tide'); description = t.noReviews; }
  else if (tideReviews === 'form') { title = t.reviewFormTitle + ' · Tide'; description = t.reviewDemoNote; }
  else if (pathname.startsWith(editionCollectionPath + '/')) {
    const item = editionItems(locale).find(entry => entry.href === pathname);
    if (item) { title = item.name + ' · ' + item.title; description = item.description; }
  }
  else if (pathname === '/products/loop') { title = `Loop · ${t.subtitle}`; description = t.descriptionIntro; }
  else if (pathname === '/products/loop/reviews') { title = t.reviews.replace('{product}', 'Loop'); description = t.reviewsBody; }
  else if (pathname === '/products/loop/reviews/new') { title = t.reviewFormTitle; description = t.reviewDemoNote; }
  else if (pathname === '/products/loop/personalize') {
    const products = new URLSearchParams(search).getAll('product');
    const product = products.length === 1 && products[0] === 'lamp' ? 'Luma' : 'Loop';
    title = personalizationCopy[locale].eyebrow + ' · ' + product;
    description = studioZoneCopy[locale].intro;
  }
  else if (pathname === '/preview/cart') { title = t.cart; description = t.cartBoundary; }
  else if (pathname === '/preview/checkout') { title = checkoutCopy[locale].title; description = checkoutCopy[locale].prototypeNote; }
  else if (pathname === '/preview/saved') { title = t.favorites; description = t.savedNote; }
  else if (isCatalogPath(pathname)) { title = catalogCopy[locale].title; description = catalogCopy[locale].intro; }
  else if (pathname === '/preview/studio') { title = 'Loop Studio'; description = t.studioBody; }
  else if (pathname === '/preview/search') { const query = new URLSearchParams(search).get('q')?.slice(0, 100).trim(); title = t.results + (query ? ` · ${query}` : ''); description = t.searchNote; }
  else if (pathname.startsWith('/preview/info/')) { const topic = pathname.slice('/preview/info/'.length); if (Object.hasOwn(info, topic)) [title, description] = info[topic as keyof typeof info]; }
  else if (pathname.startsWith('/preview/items/')) { const item = concepts.find(item => item.id === pathname.slice('/preview/items/'.length)); if (item) { title = `${item.name} · ${t.concept}`; description = t.conceptNote; } }
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
