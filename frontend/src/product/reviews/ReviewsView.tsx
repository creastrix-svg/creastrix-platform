import { Link, useSearchParams } from 'react-router';
import { productCopy } from '../copy';
import { Icon } from '../Icon';
import { useProductLocale } from '../useProductLocale';
import { ProductBack } from './ProductBack';
import { reviewLinks } from './reviewNavigation';
import type { ReviewProduct } from './reviewNavigation';

export function ReviewsView({ product = 'loop' }: { product?: ReviewProduct }) {
  const t = productCopy[useProductLocale()];
  const [params] = useSearchParams(), links = reviewLinks(product, params);
  return <div className="product-preview-page"><ProductBack to={links.product} /><h1>{t.reviews.replace('{product}', product === 'loop' ? 'Loop' : 'Tide')}</h1><div className="product-review-empty"><span className="product-stars" aria-hidden="true">{Array.from({ length: 5 }, (_, i) => <Icon key={i} name="star" />)}</span><h2>{t.noReviews}</h2><Link className="product-secondary" to={links.form}>{t.writeReview}</Link></div></div>;
}
