import { useId, useLayoutEffect, useRef, useState } from 'react';
import { useStore } from 'react-redux';
import { Link, useLocation } from 'react-router';
import { money } from '../productDisplay';
import { campaignCopy } from './campaignCopy';
import { catalogCopy } from '../browse/catalogCopy';
import { productCopy } from '../copy';
import { editionItems } from '../fixtures/editionCatalog';
import { filterHomeEdition, homeOffer, homeOfferInSection, shelfCatalogHref } from './homeFeatured';
import { shelfCopy, shelfFilters } from './homeShelfCopy';
import type { ShelfFilter } from './homeShelfCopy';
import { Icon } from '../Icon';
import { useProductLocale } from '../useProductLocale';
import { tideOffer } from '../fixtures/tideOffer';
import './homeSpotlight.css';

// Keep a filter when Back restores this history entry, without leaking it to a
// new homepage visit or storing shopping interests across browser sessions.
const filtersByStore = new WeakMap<object, Map<string, ShelfFilter>>();
function rememberFilter(store: object, key: string, choice: ShelfFilter) {
  const previous = filtersByStore.get(store) ?? new Map<string, ShelfFilter>();
  previous.delete(key);
  if (choice !== 'all') previous.set(key, choice);
  if (previous.size > 24) previous.delete(previous.keys().next().value!);
  filtersByStore.set(store, previous);
}

function FeaturePhoto({ src, srcSet, sizes, unavailable }: { src: string; srcSet?: string; sizes?: string; unavailable: string }) {
  const [failed, setFailed] = useState(false);
  return !src || failed ? <span className="home-featured-fallback"><Icon name="image"/>{unavailable}</span>
    : <img src={src} srcSet={srcSet} sizes={sizes} alt="" loading="lazy" decoding="async" width="480" height="480" onError={() => setFailed(true)}/>;
}

function Shelf({ entryKey }: { entryKey: string }) {
  const locale = useProductLocale(), copy = productCopy[locale], h = shelfCopy[locale];
  const store = useStore(), id = useId(), items = editionItems(locale), campaign = campaignCopy[locale];
  const [filter, setFilter] = useState<ShelfFilter>(() => filtersByStore.get(store)?.get(entryKey) ?? 'all');
  const selectedButton = useRef<HTMLButtonElement>(null);
  const toolbar = useRef<HTMLDivElement>(null), grid = useRef<HTMLUListElement>(null);
  function chooseFilter(choice: ShelfFilter) {
    if (choice === filter) return;
    const bar = toolbar.current?.getBoundingClientRect(), items = grid.current?.getBoundingClientRect();
    // If the buyer is deep in the old results, start the new section just below
    // the docked controls. Leave the page alone while its first row is visible.
    if (bar && items && items.top < bar.bottom - 1) {
      window.scrollBy({ top: items.top - bar.bottom, behavior: 'instant' });
    }
    setFilter(choice); rememberFilter(store, entryKey, choice);
  }
  useLayoutEffect(() => {
    const button = selectedButton.current, strip = button?.parentElement;
    if (!button || !strip) return;
    const reveal = () => {
      if (strip.scrollWidth <= strip.clientWidth) return;
      // Only move the strip. A keyboard user may be exploring a choice other than the selected one.
      const focus = document.activeElement;
      const target = focus instanceof HTMLButtonElement && focus.parentElement === strip ? focus : button;
      const choice = target.getBoundingClientRect(), bounds = strip.getBoundingClientRect();
      if (choice.left < bounds.left) strip.scrollLeft += choice.left - bounds.left;
      else if (choice.right > bounds.right) strip.scrollLeft += choice.right - bounds.right;
    };
    reveal();
    if (typeof ResizeObserver === 'undefined') {
      window.addEventListener('resize', reveal);
      return () => window.removeEventListener('resize', reveal);
    }
    const observer = new ResizeObserver(reveal);
    observer.observe(strip);
    return () => observer.disconnect();
  }, [filter, locale]);
  const filtered = filterHomeEdition(items, filter);
  const offer = homeOfferInSection(filter) ? homeOffer() : null;
  return <section className="home-selection" aria-labelledby={id + '-heading'}>
    <h2 className="home-section-title" id={id + '-heading'}>{campaign.featured}</h2>
    <div className="home-shelf-toolbar" ref={toolbar}>
      <div className="home-shelf-filters" role="group" aria-label={h.types}>{shelfFilters.map(choice =>
        <button key={choice} type="button" ref={choice === filter ? selectedButton : undefined} aria-pressed={choice === filter} aria-controls={id + '-items'} onClick={() => chooseFilter(choice)}>{h[choice]}</button>)}</div>
      <Link className="home-all" to={shelfCatalogHref(filter)}>{campaign.seeSelection}<Icon name="arrow"/></Link>
    </div>
    <span className="product-sr-only" role="status">{h.selected}: <strong>{filtered.length + (offer ? 1 : 0)}</strong></span>
    <ul className="home-featured" id={id + '-items'} role="list" ref={grid}>
      {offer && <li className="home-featured-tile">
        <Link to={offer.href} className="home-featured-card">
          <span className="home-featured-media"><FeaturePhoto src={offer.photo} unavailable={copy.noPhotoShort}/></span>
          <span className="home-featured-caption">
            <span className="home-featured-title"><strong>{offer.name}</strong><span>{copy.subtitle}</span></span>
            <span className="home-featured-material">{catalogCopy[locale].finishes}: {offer.finishCount}</span>
            <span className="home-offer-end"><strong>{money(offer.minPrice, locale)}{offer.maxPrice !== offer.minPrice && <> – {money(offer.maxPrice, locale)}</>}</strong>
              <span className="home-offer-action">{h.shop}<Icon name="arrow"/></span></span>
          </span>
        </Link>
      </li>}
      {filtered.map(item => {
      const title = id + '-' + item.id, material = title + '-material', status = title + '-status';
      return <li key={item.id} className="home-featured-tile">
        <Link to={item.href} className="home-featured-card" aria-labelledby={title} aria-describedby={material + ' ' + status}>
          <span className="home-featured-media"><FeaturePhoto src={item.thumb} srcSet={item.thumb + ' 480w, ' + item.photo + ' 1024w'}
            sizes="(min-width: 1600px) 370px, (min-width: 1024px) 24vw, (min-width: 780px) 32vw, 48vw" unavailable={copy.noPhotoShort}/></span>
          <span className="home-featured-caption">
            <span className="home-featured-title" id={title}><strong>{item.name}</strong><span>{item.title}</span></span>
            <span className="home-featured-material" id={material}>{item.material}</span>
            {item.id === tideOffer.id
              ? <span className="home-offer-end" id={status}><strong>{money(tideOffer.unitCents, locale)}</strong><span className="home-offer-action">{h.shop}<Icon name="arrow"/></span></span>
              : <small className="home-featured-status" id={status}>{h.preview}</small>}
          </span>
        </Link>
      </li>;
    })}</ul>
  </section>;
}

export default function HomeSpotlight() {
  const { key } = useLocation();
  return <Shelf key={key} entryKey={key}/>;
}
