import { Link, useSearchParams } from 'react-router';
import { productCopy } from '../copy';
import { Icon } from '../Icon';
import { useProductLocale } from '../useProductLocale';
import { ProductBack } from './ProductBack';
import { reviewLinks } from './reviewNavigation';
import type { ReviewProduct } from './reviewNavigation';
import { loopName } from '../fixtures/loop';
import { tideOffer } from '../fixtures/tideOffer';
import { ratingForPath } from '../catalog/localCatalog';
import { ratingText } from './ratingText';

export function ReviewsView({ product = 'loop' }: { product?: ReviewProduct }) {
  const [params] = useSearchParams(), links = reviewLinks(product, params);
  return <ReviewSummary name={product === 'loop' ? loopName() : tideOffer.name} links={links}/>;
}

export function ReviewSummary({name, links}: {name:string; links:{product:string; form:string}}) {
  const locale = useProductLocale(), t = productCopy[locale];
  const rating = ratingForPath(links.product);
  return <div className="product-preview-page"><ProductBack to={links.product} /><h1>{t.reviews.replace('{product}', name)}</h1><div className="product-review-empty"><span className="product-stars" aria-hidden="true">{Array.from({ length: 5 }, (_, i) => <span data-rated={i < Math.round(rating.average ?? 0)} key={i}><Icon name="star" /></span>)}</span><h2>{ratingText(rating, locale)}</h2><Link className="product-secondary" to={links.form}>{t.writeReview}</Link></div></div>;
}
