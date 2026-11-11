import { Link, useSearchParams } from 'react-router';
import { productCopy } from '../copy';
import { searchCatalog, searchResultSummary } from '../search';
import { StorefrontBack } from '../StorefrontBack';
import { useProductLocale } from '../useProductLocale';

export function SearchResultsView() {
  const language = useProductLocale(), t = productCopy[language];
  const [params] = useSearchParams();
  const items = searchCatalog(params.get('q') ?? '', language);
  return <div className="product-preview-page"><StorefrontBack /><h1>{t.results}</h1>
    {items.length ? <div className="product-collection-grid">{items.map(item => <Link key={item.id} className="product-concept-card" to={item.href}>
      <span className="product-concept-image"><img src={item.photo} alt="" loading="lazy" width="512" height="512" /></span>
      <span className="product-concept-copy"><strong>{item.name}</strong><small>{searchResultSummary(item, language)}</small></span>
    </Link>)}</div> : <p className="product-empty">{t.noResults}</p>}
  </div>;
}
