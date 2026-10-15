import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { Link } from 'react-router';
import { concepts } from './catalog';
import type { ProductCopy } from './copy';
import { Icon } from './Icon';

export function ConceptCard({ item, t }: { item: typeof concepts[number]; t: ProductCopy }) {
  return <Link className="product-concept-card" to={'/preview/items/' + item.id}>
    <span className="product-concept-image"><img src={`/product/reference-asset-${item.image}.webp`} alt={`${item.name} · ${t.conceptNote}`} loading="lazy" width="512" height="512" /></span>
    <span className="product-concept-copy"><strong>{item.name}</strong><small>{t.concept}</small></span>
  </Link>;
}

export default function Carousel({ title, mini = false, t }: { title: string; mini?: boolean; t: ProductCopy }) {
  const id = useId();
  const rail = useRef<HTMLUListElement>(null);
  const frame = useRef<HTMLDivElement>(null);
  const [bounds, setBounds] = useState({ start: true, end: false });
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
  }, [measure]);
  function move(direction: number) {
    const element = rail.current;
    if (!element) return;
    const gap = Number.parseFloat(getComputedStyle(element).columnGap) || 0;
    const max = Math.max(0, element.scrollWidth - element.clientWidth);
    const left = Math.max(0, Math.min(max, element.scrollLeft + direction * (element.clientWidth + gap)));
    element.scrollTo({ left, behavior: window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth' });
  }
  return <section className={'product-carousel' + (mini ? ' product-carousel-mini' : '')} aria-labelledby={id + '-title'}>
    <div className="product-section-heading"><h2 id={id + '-title'}><Link to="/preview/collection">{title}<Icon name="arrow" /></Link></h2></div>
    <div className="product-rail-frame" ref={frame}>
      <ul className="product-rail" ref={rail} id={id} onScroll={measure}>{concepts.map(item => <li key={item.id}><ConceptCard item={item} t={t} /></li>)}</ul>
      <button type="button" className="product-rail-arrow product-rail-prev" aria-label={t.previousProducts} aria-controls={id} disabled={bounds.start} onClick={() => move(-1)}><span className="product-flip"><Icon name="chevron" /></span></button>
      <button type="button" className="product-rail-arrow product-rail-next" aria-label={t.nextProducts} aria-controls={id} disabled={bounds.end} onClick={() => move(1)}><Icon name="chevron" /></button>
    </div>
  </section>;
}
