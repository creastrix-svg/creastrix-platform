import { useLocation, useSearchParams } from 'react-router';
import { connectedOffer, entryForPath } from '../catalog/localCatalog';
import { NotFoundView } from '../NotFoundView';
import { ReviewSummary } from './ReviewsView';
import { ReviewForm } from './ReviewFormView';

export default function ConnectedReviewView({write = false}: {write?: boolean}) {
  const {pathname} = useLocation(), [params] = useSearchParams();
  const entry = entryForPath(pathname.replace(/\/reviews(?:\/new)?\/?$/i, ''));
  const offer = entry && connectedOffer(entry.id);
  if (!offer || !entry || entry.kind !== 'offer') return <NotFoundView/>;
  const requested = params.getAll('variant'), variant = requested.length === 1 && offer.variants.find(v => v.id === requested[0]);
  const query = variant ? '?variant=' + encodeURIComponent(variant.id) : '';
  const links = {product: entry.href + query, form: entry.href + '/reviews/new' + query};
  const scores = params.getAll('rating'), initial = scores.length === 1 && /^[1-5]$/.test(scores[0]) ? Number(scores[0]) : null;
  return write ? <ReviewForm key={links.product + ':' + initial} initialRating={initial} productHref={links.product}/>
    : <ReviewSummary links={links} name={entry.name}/>;
}
