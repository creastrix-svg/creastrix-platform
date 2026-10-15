import { useId, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router';
import { useAppDispatch, useAppSelector } from '../hooks';
import { concepts, isVariantId, mediaForVariant, money, photoDescription, photos, swatchBackground, variantById } from './catalog';
import type { VariantId } from './catalog';
import { productCopy } from './copy';
import { cartLineRemoved, cartQuantityChanged, cartTotal, favoriteRemoved } from './demoStore';
import { Icon } from './Icon';
import QuantityInput from './QuantityInput';
import { ConceptCard } from './Carousel';
import { searchExamples } from './search';
import { useProductLocale } from './useProductLocale';

function Back({ productReturn = false }: { productReturn?: boolean }) {
  const t = productCopy[useProductLocale()];
  const selected = useAppSelector(state => state.productDemo.selectedVariant);
  return <Link to={'/products/loop?variant=' + selected} state={productReturn ? { productReturn: true } : null} className="product-back"><span className="product-flip"><Icon name="arrow" /></span>{t.back}</Link>;
}

export function CartView() {
  const language = useProductLocale(), t = productCopy[language];
  const demo = useAppSelector(state => state.productDemo), dispatch = useAppDispatch();
  return <div className="product-preview-page"><Back productReturn /><h1>{t.cart}</h1>
    {demo.cart.length === 0 ? <p className="product-empty">{t.emptyCart}</p> : <div className="product-cart-layout"><div>
      {demo.cart.map(line => { const variant = variantById(line.variant); return <article className="product-cart-line" key={line.variant} aria-label={'Loop · ' + variant.name[language]}>
        {mediaForVariant(line.variant).photos[0] ? <img src={mediaForVariant(line.variant).photos[0]} alt={photoDescription(t.photoAlt, variant.name[language])} width="120" height="96" /> : <span className="product-cart-swatch" style={{ background: swatchBackground(variant.colors) }} aria-label={t.noPhoto} />}
        <div className="product-cart-line-copy"><Link to={'/products/loop?variant=' + line.variant}>Loop</Link><span>{variant.name[language]}</span><button type="button" className="product-text-button" onClick={() => dispatch(cartLineRemoved(line.variant))} aria-label={`${t.remove} · ${variant.name[language]}`}>{t.remove}</button></div>
        <QuantityInput label={`${t.quantity} · ${variant.name[language]}`} value={line.quantity} max={variant.stock} onChange={quantity => dispatch(cartQuantityChanged({ variant: line.variant, quantity }))} t={t} />
        <strong className="product-cart-price">{money(variant.cents * line.quantity, language)}</strong>
      </article>; })}
    </div><aside className="product-cart-summary"><span>{t.total}</span><strong>{money(cartTotal(demo), language)}</strong><Link className="product-primary" state={{ productReturn: true }} to={'/products/loop?variant=' + demo.selectedVariant}>{t.continue}<Icon name="arrow" /></Link></aside></div>}
  </div>;
}

export function FavoritesView() {
  const language = useProductLocale(), t = productCopy[language];
  const favorite = useAppSelector(state => state.productDemo.favorite), dispatch = useAppDispatch();
  const variant = favorite === null ? null : variantById(favorite);
  return <div className="product-preview-page"><Back /><h1>{t.favorites}</h1>{variant ? <article className="product-saved-card"><Link to={'/products/loop?variant=' + variant.id}>
    {mediaForVariant(variant.id).photos[0] ? <img src={mediaForVariant(variant.id).photos[0]} alt={photoDescription(t.photoAlt, variant.name[language])} width="425" height="283" /> : <span className="product-saved-swatch" role="img" aria-label={variant.name[language] + ' · ' + t.noPhoto} style={{ background: swatchBackground(variant.colors) }} />}
    <h2>Loop · {variant.name[language]}</h2></Link><p><span className="product-sr-only">{t.currentPrice}: </span>{money(variant.cents, language)}</p><button type="button" className="product-text-button" onClick={() => dispatch(favoriteRemoved())}>{t.remove}</button></article> : <p className="product-empty">{t.emptySaved}</p>}</div>;
}

export function CollectionView({ search = false }: { search?: boolean }) {
  const language = useProductLocale(), t = productCopy[language];
  const [params] = useSearchParams();
  const query = search ? (params.get('q') ?? '').slice(0, 100).trim().toLocaleLowerCase() : '';
  const ranked = searchExamples(query, language, 7);
  const items = search ? ranked.filter(item => item.id !== 'loop') : concepts;
  const loop = !search || ranked.some(item => item.id === 'loop');
  return <div className="product-preview-page"><Back /><h1>{search ? t.results : t.collection}</h1>
    {items.length || loop ? <div className="product-collection-grid">{loop && <Link to="/products/loop" className="product-concept-card"><span className="product-concept-image"><img src={photos[0]} alt={t.photoAlt} width="850" height="567" /></span><span className="product-concept-copy"><strong>Loop</strong><small>{t.subtitle}</small></span></Link>}{items.map(item => <ConceptCard key={item.id} item={item} t={t} />)}</div> : <p className="product-empty">{t.noResults}</p>}
  </div>;
}

export function ConceptView() {
  const t = productCopy[useProductLocale()], { id } = useParams();
  const item = concepts.find(concept => concept.id === id);
  return <div className="product-preview-page"><Back /><h1>{item?.name ?? t.notFound}</h1>{item && <><img className="product-concept-detail" src={`/product/reference-asset-${item.image}.webp`} alt={`${item.name} · ${t.concept}`} width="512" height="512" /><p>{t.studioBody}</p></>}</div>;
}

export function InfoView({ studio = false }: { studio?: boolean }) {
  const t = productCopy[useProductLocale()], { topic = 'help' } = useParams();
  const info = { help: [t.help, t.helpBody], delivery: [t.delivery, t.deliveryBody], returns: [t.returns, t.returnsBody], contact: [t.contact, t.contactBody], about: [t.about, t.aboutInfo], legal: [t.legal, t.legalBody] };
  if (!studio && !Object.hasOwn(info, topic)) return <NotFoundView />;
  const content = studio ? ['Loop Studio', t.studioBody] : info[topic as keyof typeof info];
  return <div className="product-preview-page product-info-page"><Back /><span className="product-label">{t.info}</span><h1>{content[0]}</h1><p>{content[1]}</p>{studio && <Link className="product-text-button" to="/preview/collection">{t.collection}<Icon name="arrow" /></Link>}</div>;
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
  return <div className="product-preview-page"><Back /><h1>{t.notFound}</h1><p>{t.info}</p></div>;
}
