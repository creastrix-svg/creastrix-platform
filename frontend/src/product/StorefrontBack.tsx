import { useContext } from 'react';
import { Link, useLocation } from 'react-router';
import { productCopy } from './copy';
import { Icon } from './Icon';
import { useProductLocale } from './useProductLocale';
import { StorefrontOrigin } from './storefrontOrigin';
import { StorefrontHistoryBack } from './storefrontHistoryBack';
import { isCatalogPath } from './routes';

function Back() {
  const t = productCopy[useProductLocale()], origin = useContext(StorefrontOrigin);
  const collection = isCatalogPath(useLocation().pathname), historyBack = useContext(StorefrontHistoryBack);
  return <Link to={origin ?? (collection ? '/' : '/catalog')} className="product-back"
    onClick={event => historyBack(event, origin)}><span className="product-flip"><Icon name="arrow" /></span>{origin ? t.back : collection ? t.home : t.browseCollection}</Link>;
}
export { Back as StorefrontBack };
