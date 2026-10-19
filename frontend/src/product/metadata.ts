import { useEffect } from 'react';
import { useLocation } from 'react-router';
import type { ProductCopy } from './copy';
import { concepts } from './catalog';
import type { StorefrontLocale } from './locale';
import { checkoutCopy } from './checkoutCopy';

export function useProductMetadata(t: ProductCopy, locale: StorefrontLocale) {
  const { pathname: rawPath, search } = useLocation();
  const pathname = rawPath.replace(/\/+$/, '') || '/';
  const info = { help: [t.help, t.helpBody], delivery: [t.delivery, t.deliveryBody], returns: [t.returns, t.returnsBody], contact: [t.contact, t.contactBody], about: [t.about, t.aboutInfo], legal: [t.legal, t.legalBody] };
  let title = t.notFound, description = t.info;
  if (pathname.replace(/\/$/, '') === '/products/loop') { title = `Loop · ${t.subtitle}`; description = t.descriptionIntro; }
  else if (pathname === '/products/loop/reviews') { title = t.reviews; description = t.reviewsBody; }
  else if (pathname === '/products/loop/reviews/new') { title = t.reviewFormTitle; description = t.reviewDemoNote; }
  else if (pathname === '/preview/cart') { title = t.cart; description = t.cartBoundary; }
  else if (pathname === '/preview/checkout') { title = checkoutCopy[locale].title; description = checkoutCopy[locale].prototypeNote; }
  else if (pathname === '/preview/saved') { title = t.favorites; description = t.savedNote; }
  else if (pathname === '/preview/collection') { title = t.collection; description = t.searchNote; }
  else if (pathname === '/preview/studio') { title = 'Loop Studio'; description = t.studioBody; }
  else if (pathname === '/preview/search') { const query = new URLSearchParams(search).get('q')?.slice(0, 100).trim(); title = t.results + (query ? ` · ${query}` : ''); description = t.searchNote; }
  else if (pathname.startsWith('/preview/info/')) { const topic = pathname.slice('/preview/info/'.length); if (Object.hasOwn(info, topic)) [title, description] = info[topic as keyof typeof info]; }
  else if (pathname.startsWith('/preview/items/')) { const item = concepts.find(item => item.id === pathname.slice('/preview/items/'.length)); if (item) { title = `${item.name} · ${t.concept}`; description = t.conceptNote; } }
  useEffect(() => {
    document.documentElement.lang = locale;
    document.title = title + ' · Creastrix';
    const previous = document.head.querySelector<HTMLMetaElement>('meta[name="description"]');
    const element = previous ?? document.createElement('meta');
    const content = previous?.getAttribute('content');
    element.name = 'description'; element.content = description;
    if (!previous) document.head.append(element);
    return () => { if (!previous) element.remove(); else if (content === null) element.removeAttribute('content'); else element.content = content!; };
  }, [title, description, locale]);
}
