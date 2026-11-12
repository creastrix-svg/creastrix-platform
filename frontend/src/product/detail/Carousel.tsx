import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { Link } from 'react-router';
import type { CarouselItem, CarouselContent } from './carouselContent';
import type { ProductCopy } from '../copy';
import { Icon } from '../Icon';
import { RatingBadge } from '../reviews/RatingBadge';
import type { StorefrontLocale } from '../locale';
import { OfferPriceRange } from '../catalog/OfferPriceRange';

/** One buyer-facing shelf, preserving the first occurrence in supplied ranking:
 * close alternatives first, followed by candidates from the same section. */
export function LowerRecommendations({similar, section, locale, t}: {
  similar: CarouselContent; section: CarouselContent; locale: StorefrontLocale; t: ProductCopy;
}) {
  const seen = new Set<string>();
  const items = [...similar.items, ...section.items].filter(item => {
    if (seen.has(item.href)) return false;
    seen.add(item.href);
    return true;
  });
  return <Carousel title={t.youMayLike} locale={locale} t={t} content={{items, href:similar.href}} />;
}

function RailCard({ item, locale }: { item: CarouselItem; locale: StorefrontLocale }) {
  return <Link className="product-concept-card" to={item.href}>
    <span className="product-concept-image">{item.photo ? <img src={item.photo} alt="" loading="lazy" width="512" height="512" /> : <Icon name="image" />}</span>
    <span className="product-concept-copy"><strong>{item.name}</strong><small>{item.note}</small>
      <OfferPriceRange href={item.href} locale={locale}/>
      <RatingBadge href={item.href} locale={locale}/></span>
  </Link>;
}

export default function Carousel({ title, mini = false, locale, t, content }: { title: string; mini?: boolean; locale: StorefrontLocale; t: ProductCopy; content: CarouselContent }) {
  const { items, href, collectionName } = content;
  const id = useId();
  const rail = useRef<HTMLUListElement>(null);
  const frame = useRef<HTMLDivElement>(null);
  const [bounds, setBounds] = useState({ start: true, end: true });
  const measure = useCallback(() => {
    const element = rail.current;
    if (!element) return;
    const max = Math.max(0, element.scrollWidth - element.clientWidth);
    setBounds(previous => {
      const next = { start: element.scrollLeft <= 2, end: element.scrollLeft >= max - 2 };
      return previous.start === next.start && previous.end === next.end ? previous : next;
    });
    const image = element.querySelector('.product-concept-image');
    const secondRow = mini ? element.children[1] : null;
    const top = element.getBoundingClientRect().top;
    const midpoint = secondRow ? (secondRow.getBoundingClientRect().bottom - top + 2) / 2 : image ? image.getBoundingClientRect().height / 2 + 2 : 90;
    frame.current?.style.setProperty('--product-arrow-y', `${midpoint}px`);
  }, [mini]);
  useEffect(() => {
    const initial = requestAnimationFrame(measure);
    const observer = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(measure) : null;
    if (rail.current) observer?.observe(rail.current);
    window.addEventListener('resize', measure);
    return () => { cancelAnimationFrame(initial); observer?.disconnect(); window.removeEventListener('resize', measure); };
  }, [measure, items.length]);
  function move(direction: number) {
    const element = rail.current;
    if (!element) return;
    const gap = Number.parseFloat(getComputedStyle(element).columnGap) || 0;
    const max = Math.max(0, element.scrollWidth - element.clientWidth);
    const left = Math.max(0, Math.min(max, element.scrollLeft + direction * (element.clientWidth + gap)));
    element.scrollTo({ left, behavior: window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth' });
  }
  const collectionLabel = collectionName && t.viewCollection.replace('{name}', () => collectionName);
  if (!items.length) {
    if (!mini || !collectionLabel) return null;
    return <Link className="product-text-button" to={href}>{collectionLabel}<Icon name="arrow" /></Link>;
  }
  return <section className={'product-carousel ' + (mini ? 'product-carousel-mini' : 'product-carousel-similar')}
    data-short-row={!mini && items.length <= 2 ? true : undefined} aria-labelledby={id + '-title'}>
    <div className="product-section-heading">{mini ? <h2><Link to={href} aria-labelledby={id + '-title'} aria-describedby={collectionLabel ? id + '-destination' : undefined}>
      <span id={id + '-title'}>{title}</span>
      {collectionLabel && <small id={id + '-destination'}>{collectionLabel}</small>}
      <Icon name="arrow" /></Link></h2> : <h2 id={id + '-title'}>{title}</h2>}</div>
    <div className="product-rail-frame" ref={frame}>
      <ul className="product-rail" ref={rail} id={id} onScroll={measure}>{items.map(item => <li key={item.href}><RailCard item={item} locale={locale} /></li>)}</ul>
      {!(bounds.start && bounds.end) && <>
        <button type="button" className="product-rail-arrow product-rail-prev" aria-label={t.previousProducts} aria-controls={id} disabled={bounds.start} onClick={() => move(-1)}><span className="product-flip"><Icon name="chevron" /></span></button>
        <button type="button" className="product-rail-arrow product-rail-next" aria-label={t.nextProducts} aria-controls={id} disabled={bounds.end} onClick={() => move(1)}><Icon name="chevron" /></button>
      </>}
    </div>
  </section>;
}
