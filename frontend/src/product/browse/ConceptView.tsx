import { useParams } from 'react-router';
import { conceptItems } from '../fixtures/conceptItems';
import { RatingBadge } from '../reviews/RatingBadge';
import { NotFoundView } from '../NotFoundView';
import { productCopy } from '../copy';
import { StorefrontBack } from '../StorefrontBack';
import { useProductLocale } from '../useProductLocale';

export function ConceptView() {
  const locale = useProductLocale(), t = productCopy[locale], { id } = useParams();
  const item = conceptItems(locale).find(concept => concept.id === id);
  if (!item) return <NotFoundView />;
  return <div className="product-preview-page"><StorefrontBack /><h1>{item.name}</h1><RatingBadge href={item.href} locale={locale}/>
    {item.photo && <img className="product-concept-detail" src={item.photo} alt={`${item.name} · ${t.concept}`} width="512" height="512" />}
    <p>{item.description}</p></div>;
}
