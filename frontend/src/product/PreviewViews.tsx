import { useContext, useId, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router';
import { useAppDispatch, useAppSelector } from '../hooks';
import { concepts, isVariantId, mediaForVariant, photoDescription, swatchBackground, variantById } from './catalog';
import type { VariantId } from './catalog';
import { productCopy } from './copy';
import { favoriteRemoved } from './demoStore';
import ProductPrice from './ProductPrice';
import { Icon } from './Icon';
import { searchCatalog, searchResultSummary } from './search';
import { useProductLocale } from './useProductLocale';
import { StorefrontOrigin } from './storefrontOrigin';
import { isCatalogPath } from './routes';

function Back() {
  const t = productCopy[useProductLocale()], origin = useContext(StorefrontOrigin);
  const collection = isCatalogPath(useLocation().pathname), navigate = useNavigate();
  return <Link to={origin ?? (collection ? '/' : '/catalog')} className="product-back" onClick={event => {
    if (!origin || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault(); navigate(-1);
  }}><span className="product-flip"><Icon name="arrow" /></span>{origin ? t.back : collection ? t.home : t.browseCollection}</Link>;
}
export { Back as StorefrontBack };

export function FavoritesView() {
  const language = useProductLocale(), t = productCopy[language];
  const ids = useAppSelector(state => state.productDemo.favorites), dispatch = useAppDispatch();
  const heading = useRef<HTMLHeadingElement>(null);
  const titlePrefix = useId();
  const links = useRef(new Map<VariantId, HTMLAnchorElement>());
  const [broken, setBroken] = useState<Partial<Record<VariantId, boolean>>>({});
  return <div className="product-preview-page"><Back /><h1 ref={heading} tabIndex={-1}>{t.favorites}</h1>
    {ids.length ? <div className="product-saved-grid">{ids.map((id, index) => {
      const variant = variantById(id), name = variant.name[language], photo = mediaForVariant(id).photos[0];
      const titleId = titlePrefix + '-' + id;
      return <article key={id} className="product-saved-card"><Link aria-labelledby={titleId} to={'/products/loop?variant=' + id} ref={node => { if (node) links.current.set(id, node); else links.current.delete(id); }}>
        {photo && !broken[id] ? <img src={photo} alt={photoDescription(t.photoAlt, name)} width="425" height="283" onError={() => setBroken(previous => ({ ...previous, [id]: true }))} /> :
          <span className="product-saved-swatch" role="img" aria-label={name + ' · ' + t.noPhoto} style={{ background: swatchBackground(variant.colors) }} />}
        <h2 id={titleId}>Loop · {name}</h2></Link><ProductPrice cents={variant.cents} previousCents={variant.previousCents} language={language} t={t} />
        <button type="button" className="product-text-button" aria-label={t.unfavorite + ' · Loop · ' + name} onClick={() => {
          (links.current.get(ids[index + 1] ?? ids[index - 1]) ?? heading.current)?.focus();
          dispatch(favoriteRemoved(id));
        }}>{t.remove}</button></article>;
    })}</div> : <div className="product-empty"><p><strong>{t.emptySaved}</strong></p><p>{t.savedHint}</p></div>}</div>;
}

export function SearchResultsView() {
  const language = useProductLocale(), t = productCopy[language];
  const [params] = useSearchParams();
  const items = searchCatalog(params.get('q') ?? '', language);
  return <div className="product-preview-page"><Back /><h1>{t.results}</h1>
    {items.length ? <div className="product-collection-grid">{items.map(item => <Link key={item.id} className="product-concept-card" to={item.href}>
      <span className="product-concept-image"><img src={item.photo} alt="" loading="lazy" width="512" height="512" /></span>
      <span className="product-concept-copy"><strong>{item.name}</strong><small>{searchResultSummary(item, language)}</small></span>
    </Link>)}</div> : <p className="product-empty">{t.noResults}</p>}
  </div>;
}

export function ConceptView() {
  const t = productCopy[useProductLocale()], { id } = useParams();
  const item = concepts.find(concept => concept.id === id);
  if (!item) return <NotFoundView />;
  return <div className="product-preview-page"><Back /><h1>{item.name}</h1><img className="product-concept-detail" src={`/product/reference-asset-${item.image}.webp`} alt={`${item.name} · ${t.concept}`} width="512" height="512" /><p>{t.conceptNote}</p></div>;
}

export function InfoView({ studio = false }: { studio?: boolean }) {
  const t = productCopy[useProductLocale()], { topic = 'help' } = useParams();
  const info = { help: [t.help, t.helpBody], delivery: [t.delivery, t.deliveryBody], returns: [t.returns, t.returnsBody], contact: [t.contact, t.contactBody], about: [t.about, t.aboutInfo], legal: [t.legal, t.legalBody] };
  if (!studio && !Object.hasOwn(info, topic)) return <NotFoundView />;
  const content = studio ? ['Loop Studio', t.studioBody] : info[topic as keyof typeof info];
  return <div className="product-preview-page product-info-page"><Back /><h1>{content[0]}</h1><p>{content[1]}</p>
    {studio && <Link className="product-text-button" to="/catalog?format=concept">{t.moreStudio}<Icon name="arrow" /></Link>}
    {!studio && topic === 'help' && <a className="product-text-button" href="mailto:support@creastrix.com">support@creastrix.com</a>}
    {!studio && topic === 'contact' && <a className="product-text-button" href="mailto:hello@creastrix.com">hello@creastrix.com</a>}
  </div>;
}

export function ReviewsView() {
  const t = productCopy[useProductLocale()];
  const [params] = useSearchParams(), variant = reviewVariant(params);
  return <div className="product-preview-page"><ProductBack variant={variant} /><h1>{t.reviews}</h1><div className="product-review-empty"><span className="product-stars" aria-hidden="true">{Array.from({ length: 5 }, (_, i) => <Icon key={i} name="star" />)}</span><h2>{t.noReviews}</h2><Link className="product-secondary" to={'/products/loop/reviews/new?variant=' + variant}>{t.writeReview}</Link></div></div>;
}

function reviewVariant(params: URLSearchParams): VariantId {
  const values = params.getAll('variant');
  return values.length === 1 && isVariantId(values[0]) ? values[0] : 'cobalt';
}
function ProductBack({ variant }: { variant: VariantId }) {
  const t = productCopy[useProductLocale()];
  return <Link className="product-secondary product-review-return" to={'/products/loop?variant=' + variant}><span className="product-flip"><Icon name="arrow" /></span>{t.backToProduct}</Link>;
}
export function ReviewFormView() {
  const [params] = useSearchParams(), values = params.getAll('rating');
  const rating = values.length === 1 && /^[1-5]$/.test(values[0]) ? Number(values[0]) : null;
  return <ReviewForm key={params.toString()} initialRating={rating} variant={reviewVariant(params)} />;
}
function ReviewForm({ initialRating, variant }: { initialRating: number | null; variant: VariantId }) {
  const t = productCopy[useProductLocale()], id = useId();
  const [rating, setRating] = useState(initialRating), [text, setText] = useState('');
  return <div className="product-preview-page"><ProductBack variant={variant} /><h1>{t.reviewFormTitle}</h1><p>{t.reviewDemoNote}</p><form className="product-review-form" aria-label={t.reviewFormTitle} onSubmit={event => event.preventDefault()}>
    <fieldset><legend>{t.reviewRating}</legend><div>{[1, 2, 3, 4, 5].map(value => <label key={value}><input type="radio" name={id + '-rating'} value={value} checked={rating === value} onChange={() => setRating(value)} aria-label={`${t.reviewRating}: ${value} ${t.ratingOutOf}`} /><span>{value}</span></label>)}</div></fieldset>
    {rating === null && <p>{t.reviewChoose}</p>}<label htmlFor={id + '-text'}>{t.reviewText}</label><textarea id={id + '-text'} rows={5} maxLength={2000} value={text} onChange={event => setText(event.target.value.slice(0, 2000))} />
    <button className="product-secondary" type="button" disabled>{t.reviewSubmit}</button>
  </form></div>;
}

export function NotFoundView() {
  const t = productCopy[useProductLocale()];
  return <div className="product-preview-page"><Back /><h1>{t.notFound}</h1><p>{t.notFoundBody}</p></div>;
}
