import { Link } from 'react-router';
import { productCopy } from '../copy';
import { Icon } from '../Icon';
import { useProductLocale } from '../useProductLocale';

export function ProductBack({ to }: { to: string }) {
  const t = productCopy[useProductLocale()];
  return <Link className="product-secondary product-review-return" to={to}><span className="product-flip"><Icon name="arrow" /></span>{t.backToProduct}</Link>;
}
