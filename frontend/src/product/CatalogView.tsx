import { useContext, useId, useRef, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { mediaForVariant, money, sampleDiscount, swatchBackground, variants } from './catalog';
import { browseCatalog, clearBrowse, facetOptions, toggleFacet } from './catalogBrowse';
import type { FacetKey } from './catalogBrowse';
import { catalogCopy } from './catalogCopy';
import { shelfCopy } from './homeShelfCopy';
import { productCopy } from './copy';
import { Icon } from './Icon';
import { StorefrontBack } from './PreviewViews';
import { isCatalogPath } from './routes';
import { StorefrontOrigin } from './storefrontOrigin';
import { canPersonalize, studioProducts } from './studioProducts';
import { useProductLocale } from './useProductLocale';
import './catalogView.css';

type Finish = (typeof variants)[number];
const facetKeys = ['format', 'custom', 'technique', 'color'] as const;

function CatalogPhoto({ src, missing, eager = false }: { src?: string; missing: string; eager?: boolean }) {
  const [failed, setFailed] = useState<string>();
  return src && src !== failed
    ? <img src={src} alt="" width="600" height="480" loading={eager ? 'eager' : 'lazy'} decoding="async" onError={() => setFailed(src)} />
    : <span className="catalog-photo-missing"><Icon name="image" />{missing}</span>;
}

function LoopCard({ finishes, narrowed }: { finishes: readonly Finish[]; narrowed: boolean }) {
  const locale = useProductLocale(), t = productCopy[locale], copy = catalogCopy[locale], titleId = useId();
  const first = finishes[0], one = finishes.length === 1;
  const href = '/products/loop' + (narrowed ? '?variant=' + first.id : '');
  const prices = finishes.map(f => f.cents), low = Math.min(...prices), high = Math.max(...prices);
  const discount = one ? sampleDiscount(first.cents, first.previousCents) : null;
  const title = studioProducts.loop.name;
  return <article className="catalog-loop">
    <Link to={href} aria-labelledby={titleId} aria-describedby={titleId + '-spec ' + titleId + '-price ' + titleId + '-options'} className="catalog-loop-link">
      <span className="catalog-photo">
        <CatalogPhoto src={mediaForVariant(first.id).photos[0]} missing={t.noPhotoShort} eager />
        {discount !== null && <span className="catalog-discount"><span className="product-sr-only">{t.sampleDiscount}: </span>−{discount}%</span>}
      </span>
      <span className="catalog-loop-info">
        <strong className="catalog-loop-name" id={titleId}>{title}</strong>
        <span className="catalog-card-spec" id={titleId + '-spec'}>{t.subtitle} · {copy.productDetails}</span>
        <span className="catalog-colours" id={titleId + '-options'}>
          <span className="catalog-swatch-set" aria-hidden="true">{finishes.map(f =>
            <i key={f.id} title={f.name[locale]} className="catalog-swatch" style={{ background: swatchBackground(f.colors) }} />)}</span>
          {one ? first.name[locale] : <>{copy.finishes}: {finishes.length}<span className="product-sr-only">: {finishes.map(f => f.name[locale]).join(', ')}</span></>}
        </span>
        <span className="catalog-price-line" id={titleId + '-price'}>
          <span className={discount === null ? undefined : 'catalog-price-sale'}><span className="product-sr-only">{t.currentPrice}: </span>
            {money(low, locale)}{high !== low && <> – {money(high, locale)}</>}</span>
          {discount !== null && first.previousCents !== undefined && <del><span className="product-sr-only">{t.previousPrice}: </span>{money(first.previousCents, locale)}</del>}
        </span>
        {one && <span className="catalog-stock">{t.stock}: {first.stock}</span>}
      </span>
    </Link>
    <div className="catalog-card-details">
      {canPersonalize(studioProducts.loop) && <span className="catalog-custom"><Icon name="spark" />{t.personalizable}</span>}
      <Link className="catalog-reviews" to={'/products/loop/reviews' + (narrowed ? '?variant=' + first.id : '')} aria-label={t.noReviews + ' · ' + title}><Icon name="star" />{t.noReviews}</Link>
    </div>
  </article>;
}

function FilterIcon() {
  return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" aria-hidden="true">
    <path d="M3 7h10m4 0h4M3 17h3m4 0h11" /><circle cx="15" cy="7" r="2" /><circle cx="8" cy="17" r="2" />
  </svg>;
}

export default function CatalogView() {
  const locale = useProductLocale(), t = productCopy[locale], copy = catalogCopy[locale];
  const origin = useContext(StorefrontOrigin), originPath = origin?.split(/[?#]/, 1)[0];
  const hasBack = !!originPath && !isCatalogPath(originPath);
  const location = useLocation(), navigate = useNavigate();
  const params = new URLSearchParams(location.search), state = browseCatalog(params, locale);
  // Filter values travel in shared links; the open panel belongs to just this
  // visit so Back restores the same layout before restoring its scroll position.
  const expanded = location.state?.catalogFiltersOpen === true;
  const saleControl = useRef<HTMLInputElement>(null), panelId = useId();
  const filterTrigger = useRef<HTMLButtonElement>(null);
  const categoryName = state.category === 'personal' ? t.personalizable
    : state.category ? shelfCopy[locale][state.category === 'living' ? 'home' : state.category] : null;
  const facetLabels: Record<FacetKey, Record<string, string>> = {
    format: { ready: copy.ready, 'made-to-order': copy.madeToOrder, file: copy.productionFile, concept: copy.concept },
    custom: { yes: copy.withPersonalization, no: copy.withoutPersonalization },
    technique: { '3d-print': copy.print3d },
    color: Object.fromEntries(variants.map(variant => [variant.id, variant.name[locale]])),
  };
  const disabledReset = state.filterCount === 0 && state.sort === 'featured';
  function update(next: URLSearchParams, open = expanded) {
    navigate({ pathname: location.pathname, search: next.toString(), hash: location.hash }, {
      replace: true, preventScrollReset: true, state: { ...location.state, catalogFiltersOpen: open },
    });
  }
  function pickSingle(key: 'sale' | 'sort' | 'group', value: string | null) {
    const next = new URLSearchParams(params);
    if (value === null) next.delete(key); else next.set(key, value);
    update(next);
  }
  function resetFilters() {
    // The reset control becomes disabled, so return focus to a stable sibling.
    saleControl.current?.focus({ preventScroll: true });
    update(clearBrowse(params));
  }

  return <div className="catalog-page">
    <header className="catalog-heading">
      <div><h1>{copy.pageHeading}</h1><p className="catalog-meta">
        {state.group && <button type="button" className="catalog-group-chip"
          aria-label={`${t.remove}: ${copy.customOrMade}`} onClick={() => {
            filterTrigger.current?.focus({ preventScroll: true });
            pickSingle('group', null);
          }}>{copy.customOrMade}<Icon name="close" /></button>}
        {categoryName && <><span>{categoryName}</span><span aria-hidden="true" className="catalog-separator">/</span></>}
        <span role="status" aria-atomic="true">{copy.results}: <strong>{state.total}</strong></span>
      </p></div>
      {hasBack && <StorefrontBack />}
    </header>
    <div className="catalog-toolbar">
      <button className="catalog-filter-trigger" ref={filterTrigger} type="button" aria-label={copy.filters} aria-describedby={panelId + '-active'} aria-expanded={expanded} aria-controls={panelId}
        onClick={() => update(params, !expanded)}><FilterIcon /><span>{copy.filters}</span>
        <span className="catalog-filter-number" id={panelId + '-active'}><span className="product-sr-only">{copy.activeFilters}: </span>{state.filterCount}</span>
      </button>
      <label className="catalog-sale-control"><input ref={saleControl} type="checkbox" checked={state.sale}
        onChange={event => pickSingle('sale', event.target.checked ? '1' : null)} /><span>{copy.sale}</span></label>
      <button type="button" className="catalog-reset" disabled={disabledReset} onClick={resetFilters}>{copy.reset}</button>
      <label className="catalog-sort"><span>{copy.sort}</span><span className="catalog-select">
        <select aria-label={copy.sort} value={state.sort} onChange={event => pickSingle('sort', event.target.value === 'featured' ? null : event.target.value)}>
          <option value="featured">{copy.featured}</option><option value="price-asc">{copy.priceAsc}</option><option value="price-desc">{copy.priceDesc}</option>
          <option value="stock-desc">{copy.stockDesc}</option><option value="name-asc">{copy.nameAsc}</option>
        </select><Icon name="chevron" />
      </span></label>
    </div>
    <div className="catalog-facet-panel" id={panelId} hidden={!expanded}>
      {facetKeys.map(key => <fieldset key={key}><legend>{copy[key]}</legend>
        {facetOptions[key].map(value => <label key={value} className="catalog-facet-option">
          <input type="checkbox" checked={state.selected[key].includes(value)} aria-label={facetLabels[key][value]} aria-describedby={panelId + '-' + key + '-' + value}
            onChange={() => update(toggleFacet(params, key, value))} />
          <span>{facetLabels[key][value]}</span><small id={panelId + '-' + key + '-' + value}><span className="product-sr-only">{copy.results}: </span>{state.counts[key][value]}</small>
        </label>)}
      </fieldset>)}
      <button className="catalog-show-results" type="button" onClick={() => {
        update(params, false);
        filterTrigger.current?.focus({ preventScroll: true });
        filterTrigger.current?.scrollIntoView?.({ block: 'start', behavior: 'instant' });
      }}>{copy.showResults} ({state.total})<Icon name="arrow" /></button>
    </div>
    {state.finishes.length > 0 && <section className="catalog-finishes" aria-labelledby="catalog-loop">
      <h2 id="catalog-loop">{copy.ready}</h2>
      <LoopCard finishes={state.finishes} narrowed={state.sale || state.selected.color.length > 0} />
    </section>}
    {state.concepts.length > 0 && <section className="catalog-concepts" aria-labelledby="catalog-concepts">
      <h2 id="catalog-concepts">{copy.conceptsTitle}</h2><p>{copy.conceptsIntro}</p>
      <div className="catalog-concept-grid">{state.concepts.map((item, index) =>
        <Link className="catalog-concept" key={item.href} to={item.href}>
          <span className="catalog-photo"><CatalogPhoto src={item.photo} missing={t.noPhotoShort} eager={!state.finishes.length && index < 2} /></span>
          <span className="catalog-concept-name"><strong>{item.name}</strong><Icon name="arrow" /></span>
          <span className="catalog-concept-caption">{item.caption}</span>
        </Link>)}</div>
    </section>}
    {!state.total && <section className="catalog-empty"><Icon name="search" /><h2>{copy.emptyTitle}</h2><p>{copy.emptyHint}</p></section>}
  </div>;
}
