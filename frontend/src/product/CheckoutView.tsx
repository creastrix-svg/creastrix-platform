import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';
import { useAppSelector } from '../hooks';
import { mediaForVariant, money, photoDescription, swatchBackground, variantById } from './catalog';
import { checkoutCopy } from './checkoutCopy';
import type { CheckoutCopy } from './checkoutCopy';
import { productCopy } from './copy';
import { cartCount, cartTotal } from './demoStore';
import { Icon } from './Icon';
import { useProductLocale } from './useProductLocale';

const fields = ['name', 'email', 'street', 'postalCode', 'city', 'country'] as const;
type Field = typeof fields[number];
const fieldLimits: Record<Field, number> = { name: 120, email: 254, street: 200, postalCode: 32, city: 100, country: 100 };
const emptyDetails: Record<Field, string> = { name: '', email: '', street: '', postalCode: '', city: '', country: '' };

function CheckoutDetails({ t }: { t: CheckoutCopy }) {
  // This component unmounts on route departure or an empty active cart; details never enter Redux or persistence.
  const [details, setDetails] = useState(emptyDetails), [preview, setPreview] = useState(false);
  const previewHeading = useRef<HTMLHeadingElement>(null);
  useEffect(() => { if (preview) previewHeading.current?.focus(); }, [preview]);
  const field = (key: Field) => <label className={'checkout-field' + (key === 'postalCode' || key === 'city' ? '' : ' checkout-field-wide')} key={key}>
    <span>{t[key]}</span><input name={key} type={key === 'email' ? 'email' : 'text'} autoComplete="off" required maxLength={fieldLimits[key]} value={details[key]} onChange={event => {
      const value = event.target.value;
      setDetails(current => ({ ...current, [key]: value })); setPreview(false);
    }} />
  </label>;
  return <div className="checkout-details">
    <section className="checkout-panel" aria-labelledby="checkout-details-title">
      <h2 id="checkout-details-title">{t.detailsTitle}</h2><p id="checkout-required-note">{t.requiredNote}</p>
      <form className="checkout-form" aria-labelledby="checkout-details-title" aria-describedby="checkout-required-note checkout-prototype-note" autoComplete="off" onSubmit={event => {
        event.preventDefault();
        if (event.currentTarget.reportValidity()) setPreview(true);
      }}>
        <fieldset><legend className="checkout-section-heading">{t.contactTitle}</legend><div className="checkout-fields">{field('name')}{field('email')}</div></fieldset>
        <fieldset><legend className="checkout-section-heading">{t.addressTitle}</legend><div className="checkout-fields">{field('street')}{field('postalCode')}{field('city')}{field('country')}</div></fieldset>
        <button className="product-primary checkout-preview-button" type="submit">{t.previewDetails}<Icon name="arrow" /></button>
      </form>
    </section>
    {preview && <section className="checkout-panel checkout-draft" aria-labelledby="checkout-preview-title">
      <h2 id="checkout-preview-title" ref={previewHeading} tabIndex={-1}>{t.previewTitle}</h2><p>{t.previewNote}</p>
      <dl className="checkout-draft-values">{fields.map(key => <div key={key}><dt>{t[key]}</dt><dd>{details[key]}</dd></div>)}</dl>
    </section>}
  </div>;
}

export default function CheckoutView() {
  const language = useProductLocale(), t = checkoutCopy[language], product = productCopy[language];
  const demo = useAppSelector(state => state.productDemo);
  return <div className="product-preview-page checkout-view">
    <Link to="/preview/cart" className="product-back"><span className="product-flip"><Icon name="arrow" /></span>{t.backToCart}</Link>
    <header className="checkout-header"><h1>{t.title}</h1><p className="checkout-prototype-note" id="checkout-prototype-note"><strong>{t.prototypeLabel}</strong> · {t.prototypeNote}</p></header>
    {demo.cart.length === 0 ? <section className="checkout-empty" aria-labelledby="checkout-empty-title"><span className="product-cart-empty-icon"><Icon name="bag" /></span><h2 id="checkout-empty-title">{t.emptyTitle}</h2><p>{t.emptyBody}</p></section> : <div className="checkout-layout">
      <CheckoutDetails t={t} />
      <aside className="checkout-panel checkout-summary" aria-labelledby="checkout-summary-title">
        <h2 id="checkout-summary-title">{t.summaryTitle}</h2>
        <div className="checkout-lines">{demo.cart.map(line => {
          const variant = variantById(line.variant), photo = mediaForVariant(line.variant).photos[0];
          return <article className="checkout-line" key={line.variant} aria-label={'Loop · ' + variant.name[language]}>
            <div className="checkout-photo">{photo ? <img src={photo} alt={photoDescription(product.photoAlt, variant.name[language])} width="88" height="88" /> : <span role="img" aria-label={product.noPhoto} style={{ background: swatchBackground(variant.colors) }} />}</div>
            <div className="checkout-line-copy"><h3>Loop</h3><p>{variant.name[language]}</p>
              <dl className="checkout-line-amount"><div><dt>{product.price}</dt><dd>{money(variant.cents, language)}</dd></div><div><dt>{product.quantity}</dt><dd>{line.quantity}</dd></div><div><dt>{product.cartLineSubtotal}</dt><dd>{money(variant.cents * line.quantity, language)}</dd></div></dl>
            </div>
          </article>;
        })}</div>
        <dl className="checkout-counts"><div><dt>{product.cartLineCount}</dt><dd>{demo.cart.length}</dd></div><div><dt>{product.cartUnitCount}</dt><dd>{cartCount(demo)}</dd></div></dl>
        <dl className="checkout-subtotal"><dt>{product.cartItemsSubtotal}</dt><dd>{money(cartTotal(demo), language)}</dd></dl>
        <p>{t.subtotalNote}</p><p className="checkout-unavailable">{t.servicesUnavailable}</p>
      </aside>
    </div>}
  </div>;
}
