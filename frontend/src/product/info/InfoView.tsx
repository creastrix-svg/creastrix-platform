import { Link, useParams } from 'react-router';
import { productCopy } from '../copy';
import { Icon } from '../Icon';
import { NotFoundView } from '../NotFoundView';
import { StorefrontBack } from '../StorefrontBack';
import { useProductLocale } from '../useProductLocale';

export function InfoView({ studio = false }: { studio?: boolean }) {
  const t = productCopy[useProductLocale()], { topic = 'help' } = useParams();
  const info = { help: [t.help, t.helpBody], delivery: [t.delivery, t.deliveryBody], returns: [t.returns, t.returnsBody], contact: [t.contact, t.contactBody], about: [t.about, t.aboutInfo], legal: [t.legal, t.legalBody] };
  if (!studio && !Object.hasOwn(info, topic)) return <NotFoundView />;
  const content = studio ? ['Loop Studio', t.studioBody] : info[topic as keyof typeof info];
  return <div className="product-preview-page product-info-page"><StorefrontBack /><h1>{content[0]}</h1><p>{content[1]}</p>
    {studio && <Link className="product-text-button" to="/catalog?format=concept">{t.moreStudio}<Icon name="arrow" /></Link>}
    {!studio && topic === 'help' && <a className="product-text-button" href="mailto:support@creastrix.com">support@creastrix.com</a>}
    {!studio && topic === 'contact' && <a className="product-text-button" href="mailto:hello@creastrix.com">hello@creastrix.com</a>}
  </div>;
}
