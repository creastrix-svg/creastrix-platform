import { productCopy } from './copy';
import { StorefrontBack } from './StorefrontBack';
import { useProductLocale } from './useProductLocale';

export function NotFoundView() {
  const t = productCopy[useProductLocale()];
  return <div className="product-preview-page"><StorefrontBack /><h1>{t.notFound}</h1><p>{t.notFoundBody}</p></div>;
}
