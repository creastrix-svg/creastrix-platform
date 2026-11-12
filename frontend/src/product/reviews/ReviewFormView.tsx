import { useId, useState } from 'react';
import { useSearchParams } from 'react-router';
import { productCopy } from '../copy';
import { useProductLocale } from '../useProductLocale';
import { ProductBack } from './ProductBack';
import { reviewLinks } from './reviewNavigation';
import type { ReviewProduct } from './reviewNavigation';

export function ReviewFormView({ product = 'loop' }: { product?: ReviewProduct }) {
  const [params] = useSearchParams(), values = params.getAll('rating');
  const rating = values.length === 1 && /^[1-5]$/.test(values[0]) ? Number(values[0]) : null;
  return <ReviewForm key={product + ':' + params.toString()} initialRating={rating} productHref={reviewLinks(product, params).product} />;
}
export function ReviewForm({ initialRating, productHref }: { initialRating: number | null; productHref: string }) {
  const t = productCopy[useProductLocale()], id = useId();
  const [rating, setRating] = useState(initialRating), [text, setText] = useState('');
  return <div className="product-preview-page"><ProductBack to={productHref} /><h1>{t.reviewFormTitle}</h1><p>{t.reviewDemoNote}</p><form className="product-review-form" aria-label={t.reviewFormTitle} onSubmit={event => event.preventDefault()}>
    <fieldset><legend>{t.reviewRating}</legend><div>{[1, 2, 3, 4, 5].map(value => <label key={value}><input type="radio" name={id + '-rating'} value={value} checked={rating === value} onChange={() => setRating(value)} aria-label={`${t.reviewRating}: ${value} ${t.ratingOutOf}`} /><span>{value}</span></label>)}</div></fieldset>
    {rating === null && <p>{t.reviewChoose}</p>}<label htmlFor={id + '-text'}>{t.reviewText}</label><textarea id={id + '-text'} rows={5} maxLength={2000} value={text} onChange={event => setText(event.target.value.slice(0, 2000))} />
    <button className="product-secondary" type="button" disabled>{t.reviewSubmit}</button>
  </form></div>;
}
