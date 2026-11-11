import { useParams } from 'react-router';
import { concepts } from '../fixtures/loop';
import { NotFoundView } from '../NotFoundView';
import { productCopy } from '../copy';
import { StorefrontBack } from '../StorefrontBack';
import { useProductLocale } from '../useProductLocale';

export function ConceptView() {
  const t = productCopy[useProductLocale()], { id } = useParams();
  const item = concepts.find(concept => concept.id === id);
  if (!item) return <NotFoundView />;
  return <div className="product-preview-page"><StorefrontBack /><h1>{item.name}</h1><img className="product-concept-detail" src={`/product/reference-asset-${item.image}.webp`} alt={`${item.name} · ${t.concept}`} width="512" height="512" /><p>{t.conceptNote}</p></div>;
}
