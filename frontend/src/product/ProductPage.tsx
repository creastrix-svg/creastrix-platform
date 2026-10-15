import { useEffect, useState } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router';
import { useAppDispatch, useAppSelector } from '../hooks';
import { isVariantId, money, photoDescription, swatchBackground, variantById, variants } from './catalog';
import type { VariantId } from './catalog';
import { productCopy } from './copy';
import { cartAdded, favoriteToggled, productDescriptionChanged, productMediaModeSelected, productVariantSelected, purchaseQuantityChanged } from './demoStore';
import { Icon } from './Icon';
import MediaGallery from './MediaGallery';
import QuantityInput from './QuantityInput';
import Carousel from './Carousel';
import Modal from './Modal';
import ProductPrice from './ProductPrice';
import { useProductLocale } from './useProductLocale';

export default function ProductPage() {
  const [params] = useSearchParams(), location = useLocation(), navigate = useNavigate();
  const remembered = useAppSelector(state => state.productDemo.selectedVariant), dispatch = useAppDispatch();
  const requested = params.getAll('variant');
  const variantId = requested.length === 0 ? remembered : requested.length === 1 && isVariantId(requested[0]) ? requested[0] : 'cobalt';
  useEffect(() => { dispatch(productVariantSelected(variantId)); }, [dispatch, variantId]);
  function selectVariant(id: VariantId) {
    const next = new URLSearchParams(params); next.set('variant', id);
    // Replace this selection entry, not the history of genuine route changes.
    navigate({ search: '?' + next.toString(), hash: location.hash }, { replace: true, preventScrollReset: true });
  }
  return <ProductCard variantId={variantId} selectVariant={selectVariant} />;
}

function ProductCard({ variantId, selectVariant }: { variantId: VariantId; selectVariant: (variant: VariantId) => void }) {
  const navigate = useNavigate();
  const language = useProductLocale(), t = productCopy[language];
  const demo = useAppSelector(state => state.productDemo), dispatch = useAppDispatch();
  const [personalising, setPersonalising] = useState(false);
  const [starPreview, setStarPreview] = useState(0);
  const variant = variantById(variantId);
  return <>
    <nav className="product-breadcrumb" aria-label={t.navigation}><Link to="/preview/collection">{t.explore}</Link><Icon name="chevron" /><Link to="/preview/collection">{t.pots}</Link><Icon name="chevron" /><span>Loop</span></nav>
    <div className="product-layout">
      <MediaGallery key={variantId} variant={variantId} initialMode={demo.mediaMode} onModeChange={mode => dispatch(productMediaModeSelected(mode))} favorite={demo.favorite !== null} toggleFavorite={() => dispatch(favoriteToggled(variantId))} t={{ ...t, photoAlt: photoDescription(t.photoAlt, variant.name[language]) }} />
      <section className="product-information" data-product-variant={variantId} aria-label={t.choose}>
        <h1>Loop</h1><p className="product-subtitle">{t.subtitle}</p>
        <div className="product-rating" onBlur={() => setStarPreview(0)} onPointerDown={event => { if (event.pointerType !== 'mouse') setStarPreview(0); }}>
          <span className="product-stars" role="group" aria-label={t.reviewRating} onPointerLeave={() => setStarPreview(0)}>{Array.from({ length: 5 }, (_, i) => <button type="button" key={i} className="product-star-choice" aria-label={`${t.reviewRating}: ${i + 1} ${t.ratingOutOf}`} data-highlighted={i < starPreview} onPointerEnter={event => { if (event.pointerType === 'mouse' && window.matchMedia('(hover: hover) and (pointer: fine)').matches) setStarPreview(i + 1); }} onClick={() => navigate(`/products/loop/reviews/new?rating=${i + 1}&variant=${variantId}`)}><Icon name="star" /></button>)}</span><Link to={'/products/loop/reviews?variant=' + variantId}>{t.noReviews}</Link>
        </div>
        <ProductPrice cents={variant.cents} previousCents={variant.previousCents} language={language} t={t} />
        <fieldset className="product-colors"><legend>{t.color} <strong>{variant.name[language]}</strong></legend><div>
          {variants.map(color => <button type="button" key={color.id} className="product-swatch" data-check-tone={color.checkTone} aria-label={color.name[language]} aria-pressed={variantId === color.id} onClick={() => selectVariant(color.id)}><span style={{ background: swatchBackground(color.colors) }}>{variantId === color.id && <Icon name="check" />}</span></button>)}
        </div></fieldset>
        <PurchaseControls key={variantId} variantId={variantId} />
        <div className="product-seller"><span className="product-studio-mark" aria-hidden="true"><Icon name="store" /></span><div><strong>Loop Studio</strong><small>{t.studioBadge} · {t.seller}</small></div><Link to="/preview/studio" aria-label={t.studioLink}><Icon name="arrow" /></Link></div>
        <Carousel title={t.moreStudio} mini t={t} />
      </section>
      <section className="product-personalisation" aria-label={t.personalise}><div><strong>{t.personalise}</strong><span>{t.personalIntro}</span><small>{t.personalFrom}</small></div><button type="button" onClick={() => setPersonalising(true)}><Icon name="spark" /><span>{t.personalButton}</span><Icon name="arrow" /></button></section>
      <div className="product-features">{t.features.map((feature, index) => <div key={feature}><Icon name={(['cube', 'layers', 'spark'] as const)[index]} /><span>{feature}</span></div>)}</div>
      <section className="product-about"><h2>{t.aboutTitle}</h2><p>{t.aboutBody}</p><dl><div><dt>{t.material}</dt><dd>{t.plastic}</dd></div><div><dt>{t.dimensions}</dt><dd>Ø 13 × 12 cm</dd></div><div><dt>{t.process}</dt><dd>{t.printing}</dd></div></dl></section>
    </div>
    <section className="product-description"><h2>{t.description}</h2><p className="product-description-intro">{t.descriptionIntro}</p><details open={demo.descriptionOpen} onToggle={event => dispatch(productDescriptionChanged(event.currentTarget.open))}><summary><span className="product-summary-label" data-label={t.readMore} data-label-less={t.readLess}><span>{demo.descriptionOpen ? t.readLess : t.readMore}</span></span><Icon name="chevron" /></summary><div className="product-description-content"><div className="product-description-prose">
      <h3>{t.descriptionDesignTitle}</h3><p>{t.descriptionDesignBody}</p><h3>{t.descriptionCustomTitle}</h3><p>{t.descriptionCustomBody}</p><h3>{t.descriptionUseTitle}</h3><p>{t.descriptionUseBody}</p>
    </div><div><h3>{t.specsTitle}</h3><dl className="product-description-specs"><div><dt>{t.specsModel}</dt><dd>Loop</dd></div><div><dt>{t.material}</dt><dd>{t.plastic}</dd></div><div><dt>{t.dimensions}</dt><dd>Ø 13 × 12 cm</dd></div><div><dt>{t.process}</dt><dd>{t.printing}</dd></div><div><dt>{t.specsColors}</dt><dd>{variants.map(color => color.name[language]).join(' · ')}</dd></div><div><dt>{t.specsPersonal}</dt><dd>{t.specsPersonalValue}</dd></div></dl></div></div></details></section>
    <Carousel title={t.related} t={t} />
    {personalising && <Modal title={t.personalise} closeLabel={t.close} onClose={() => setPersonalising(false)}><div className="product-info-dialog"><Icon name="spark" /><p>{t.personalBody}</p></div></Modal>}
  </>;
}

// Only transient add feedback resets on remount; validated quantities stay in public memory.
function PurchaseControls({ variantId }: { variantId: VariantId }) {
  const language = useProductLocale(), t = productCopy[language], variant = variantById(variantId);
  const demo = useAppSelector(state => state.productDemo), dispatch = useAppDispatch();
  const [added, setAdded] = useState(false);
  const remaining = variant.stock - (demo.cart.find(line => line.variant === variantId)?.quantity ?? 0);
  const amount = Math.min(demo.purchaseQuantities[variantId], remaining);
  const changeQuantity = (quantity: number) => { dispatch(purchaseQuantityChanged({ variant: variantId, quantity })); setAdded(false); };
  return <>
    <section className="product-purchase" aria-label={t.add}>
      <div className="product-stock"><strong>{t.stock}: {variant.stock} {t.pieces}</strong><span>{remaining === 0 ? t.allInCart : `${remaining} ${t.pieces} · ${t.available}`}</span></div>
      <div className="product-buy-row"><QuantityInput t={t} value={amount} max={remaining} disabled={remaining === 0} onChange={changeQuantity} onEmpty={() => changeQuantity(1)} />
        <button type="button" className="product-primary" disabled={remaining === 0} onClick={() => { dispatch(cartAdded({ variant: variantId, quantity: amount })); setAdded(true); }}><Icon name="bag" />{t.add}</button></div>
      <div className="product-subtotal"><span>{t.subtotal} · {amount} {t.pieces}</span><strong>{money(variant.cents * amount, language)}</strong></div>
    </section>
    <div className="product-buy-feedback" role="status">{added && <><Icon name="check" /><span>{t.added}</span><Link to="/preview/cart">{t.viewCart}</Link></>}</div>
  </>;
}
