import { useContext } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { productCopy } from './copy';
import { Icon } from './Icon';
import { useProductLocale } from './useProductLocale';
import { StorefrontOrigin } from './storefrontOrigin';
import { isCatalogPath } from './routes';

function Back() {
  const t = productCopy[useProductLocale()], origin = useContext(StorefrontOrigin);
  const collection = isCatalogPath(useLocation().pathname), navigate = useNavigate();
  return <Link to={origin ?? (collection ? '/' : '/catalog')} className="product-back" onClick={event => {
    if (!origin || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault(); navigate(-1);
  }}><span className="product-flip"><Icon name="arrow" /></span>{origin ? t.back : collection ? t.home : t.browseCollection}</Link>;
}
export { Back as StorefrontBack };
