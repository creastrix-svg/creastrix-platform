import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router';
import { useStore } from 'react-redux';
import { useAppDispatch, useAppSelector } from '../../hooks';
import type { RootState } from '../../store';
import { isVariantId, variantById, loopVariants } from '../fixtures/loop';
import { readCartSku } from '../fixtures/cartSkus';
import { money, photoDescription } from '../productDisplay';
import type { VariantId } from '../fixtures/loop';
import { productCopy } from '../copy';
import { cartAdded, cartLineDesignCleared, cartLineId, cartLinePrice, configurationCartQuantity, favoriteToggled, productDescriptionChanged, productMediaModeSelected, productVariantSelected, purchaseQuantityChanged, variantCartQuantity, type CartLine } from '../demoStore';
import { loopPrice } from '../loopPricing';
import MediaGallery from '../media/MediaGallery';
import ProductPurchasePanel from './ProductPurchasePanel';
import { loopCarouselContent } from './carouselContent';
import { connectedMode, entryForId } from '../catalog/localCatalog';
import ProductPrice from '../ProductPrice';
import PersonalizationEntry from '../studio/PersonalizationEntry';
import { getStudioProduct } from '../studio/studioProducts';
import { useProductLocale } from '../useProductLocale';
import { usePersonalizationSession } from '../studio/session/personalizationSession';
import { designKey, saveDesign, visibleDesign } from '../studio/session/designSnapshot';
import type { SavedDesign } from '../studio/session/designSnapshot';
import { firstLettering, type CompositionDocument } from '../studio/design/studioComposition';
import { useDesignDocument } from '../studio/session/useDesignDocument';
import { studioCartCopy } from '../cart/studioCartCopy';
import { Icon } from '../Icon';
import { reviewLinks } from '../reviews/reviewNavigation';
import ProductCardLayout from './ProductCardLayout';
import { catalogCopy } from '../browse/catalogCopy';

export default function ProductPage() {
  const [params] = useSearchParams(), location = useLocation(), navigate = useNavigate();
  const remembered = useAppSelector(state => state.productDemo.selectedVariant), dispatch = useAppDispatch();
  const requested = params.getAll('variant');
  const refs = params.getAll('cartLine'), bound = refs.length > 0;
  const variantId = requested.length === 0 ? remembered : requested.length === 1 && isVariantId(requested[0]) ? requested[0] : 'cobalt';
  useEffect(() => { if (!bound) dispatch(productVariantSelected(variantId)); }, [dispatch, variantId, bound]);
  function selectVariant(id: VariantId) {
    if (bound && id === variantId) return;
    const next = new URLSearchParams(params); next.set('variant', id);
    // Choosing another finish is ordinary shopping, not an edit to the purchased configuration.
    next.delete('cartLine');
    // Replace this selection entry, not the history of genuine route changes.
    navigate({ search: '?' + next.toString(), hash: location.hash }, { replace: true, preventScrollReset: true });
  }
  if (bound) {
    if (refs.length !== 1 || requested.length !== 1 || !isVariantId(requested[0])) return <CartItemUnavailable/>;
    return <CartLineCard key={variantId} id={refs[0]} variantId={variantId} selectVariant={selectVariant}/>;
  }
  return <CatalogProductCard variantId={variantId} selectVariant={selectVariant} />;
}

type Selection = { variantId: VariantId; selectVariant: (variant: VariantId) => void };

function CartItemUnavailable({ failed = false }: { failed?: boolean }) {
  const language = useProductLocale(), c = studioCartCopy[language];
  return <section><p role="alert">{failed ? c.restoreFailed : c.missing}</p>
    <Link className="product-back" to="/preview/cart">{productCopy[language].viewCart}</Link></section>;
}

function CartLineCard({ id, ...selection }: Selection & { id: string }) {
  const location = useLocation(), navigate = useNavigate(), dispatch = useAppDispatch(), store = useStore<RootState>();
  const [conversion, setConversion] = useState<{ key: string; from: string } | null>(null);
  // Redux converts the row synchronously, while the router can commit its new
  // URL later. Only this click can bridge that gap; stale deep links stay stale.
  const finishingClear = conversion?.key === location.key && conversion.from === id;
  const lineId = finishingClear ? selection.variantId : id;
  const line = useAppSelector(state => state.productDemo.cart.find(item => cartLineId(item) === lineId && item.variant === selection.variantId));
  const { document: composition, error } = useDesignDocument(line?.design), language = useProductLocale();
  const anchor = useRef<{ element: HTMLElement; top: number } | null>(null);
  useLayoutEffect(() => {
    const previous = anchor.current;
    if (!previous || line?.design) return;
    anchor.current = null;
    if (previous.element.isConnected && document.activeElement === previous.element) {
      // Restoring the photo thumbnails can grow the gallery above the action.
      // Keep that action still on screen, compensating only for actual movement.
      const delta = previous.element.getBoundingClientRect().top - previous.top;
      if (Math.abs(delta) > 1) window.scrollBy({ top: delta, behavior: 'instant' });
    }
  }, [line]);
  if (!line) return <CartItemUnavailable/>;
  if (error) return <CartItemUnavailable failed/>;
  if (line.design && !composition) return <p role="status">{studioCartCopy[language].loading}</p>;
  function clearCartDesign() {
    if (!line?.id || !line.design) return;
    const element = document.activeElement;
    const top = element instanceof HTMLElement && element.closest('.product-layout') ? element.getBoundingClientRect().top : -1;
    const before = store.getState().productDemo;
    dispatch(cartLineDesignCleared({ id: line.id, variant: line.variant, expected: designKey(line.design) }));
    const after = store.getState().productDemo;
    if (before === after || after.cart.some(item => cartLineId(item) === id)) return;
    if (element instanceof HTMLElement && window.scrollY > 0 && top >= 0 && top < window.innerHeight) {
      anchor.current = { element, top };
    }
    setConversion({ key: location.key, from: id });
    const next = new URLSearchParams(location.search);
    next.set('cartLine', line.variant);
    navigate({ search: next.toString(), hash: location.hash }, { replace: true, preventScrollReset: true, state: location.state });
  }
  return <ProductCard {...selection} line={line} design={line.design} applied={composition} onClear={clearCartDesign}/>;
}

function CatalogProductCard({ variantId, selectVariant }: Selection) {
  const { state } = usePersonalizationSession();
  const applied = readCartSku(variantId).acceptsDesign ? state.applied[variantId] : undefined;
  const design = useMemo(() => {
    const snapshot = saveDesign(applied);
    return visibleDesign(snapshot) ? snapshot : undefined;
  }, [applied]);
  return <ProductCard variantId={variantId} selectVariant={selectVariant} applied={applied} design={design}/>;
}

function ProductCard({ variantId, selectVariant, line, design, applied, onClear }: Selection & {
  line?: CartLine; design?: SavedDesign; applied?: CompositionDocument; onClear?: () => void;
}) {
  const language = useProductLocale(), t = productCopy[language];
  const demo = useAppSelector(state => state.productDemo), dispatch = useAppDispatch();
  const variant = variantById(variantId), acceptsDesign = readCartSku(variantId).acceptsDesign;
  const viewCart = useRef<HTMLAnchorElement>(null);
  const price = line ? cartLinePrice(line) : loopPrice(variantId, Boolean(design));
  const review = reviewLinks('loop', new URLSearchParams({ variant:variantId, ...(line ? {cartLine:cartLineId(line)} : {}) }));
  const profile = getStudioProduct('loop');
  const variants = loopVariants();
  const entry = entryForId('loop');
  const decoration = applied && design ? { ...firstLettering(applied, variantId), variant: variantId, objects: applied.objects } : undefined;
  const facts = [[t.material, entry?.material[language] ?? t.plastic], [t.dimensions, catalogCopy[language].productDetails], [t.process, t.printing]] as const;
  return <ProductCardLayout locale={language} name={profile.name} subtitle={entry?.title[language] ?? t.subtitle} category={{ label: t.living, href: '/catalog?category=living' }}
    selected={variantId} urlVariant={variantId} finishes={variants.map(v => ({ ...v, name: v.name[language] }))} onSelect={selectVariant}
    gallery={<MediaGallery key={variantId} variant={variantId} locale={language} decoration={decoration} initialMode={demo.mediaMode} onModeChange={mode => dispatch(productMediaModeSelected(mode))} favorite={demo.favorites.includes(variantId)} toggleFavorite={() => dispatch(favoriteToggled(variantId))} t={{ ...t, photoAlt: photoDescription(t.photoAlt, variant.name[language]) }} />}
    reviews={{ list:review.list, write:score => review.form + '&rating=' + score }}
    price={<ProductPrice cents={price.unitCents} designFeeCents={price.feeCents} hasDesign={Boolean(design)} previousCents={variant.previousCents} language={language} t={t} />}
    purchase={line ? <section className="product-purchase" aria-label={t.cart}>
      <Link ref={viewCart} className="product-back" to="/preview/cart"><Icon name="bag"/>{t.viewCart}</Link>
      <div className="product-subtotal"><span>{t.subtotal} · {line.quantity} × {money(price.unitCents, language)}</span>
        <strong>{money(line.quantity * price.unitCents, language)}</strong></div>
    </section> : <PurchaseControls key={variantId} variantId={variantId} unitCents={price.unitCents} design={design} />}
    seller={connectedMode ? { name: 'Creastrix', byline: t.seller, href: '/preview/info/about', label: t.about }
      : { name: 'Loop Studio', byline: t.studioBadge + ' · ' + t.seller, href: '/preview/studio', label: t.studioLink }}
    recommendations={{ studio: loopCarouselContent(language, 'studio'), related: loopCarouselContent(language, 'related'),
      section: loopCarouselContent(language, 'section') }}
    personalization={acceptsDesign || line?.design ? <PersonalizationEntry profile={profile} variant={variantId} feeCents={price.feeCents}
      editingEnabled={acceptsDesign} cartItem={line && onClear ? { id: cartLineId(line), applied, onRemove: () => {
        // A retired studio has no surviving personalisation link to receive focus.
        if (!acceptsDesign) viewCart.current?.focus({ preventScroll: true });
        onClear();
      } } : undefined} /> : undefined}
    features={[t.features[0], t.features[1], t.features[2]]}
    about={{ title: t.aboutTitle, body: t.aboutBody, facts }}
    description={{ intro: entry?.description[language] ?? t.descriptionIntro, sections: [
      [t.descriptionDesignTitle, t.descriptionDesignBody],
      [t.descriptionCustomTitle, t.descriptionCustomBody],
      [t.descriptionUseTitle, t.descriptionUseBody],
    ], facts: [[t.specsModel, profile.name], ...facts, [t.specsColors, variants.map(color => color.name[language]).join(' · ')], [t.specsPersonal, acceptsDesign ? t.specsPersonalValue : t.without]] }}
    descriptionOpen={demo.descriptionOpen} onDescriptionChange={open => dispatch(productDescriptionChanged(open))} />;
}

function PurchaseControls({ variantId, unitCents, design }: { variantId: VariantId; unitCents: number; design?: SavedDesign }) {
  const language = useProductLocale(), t = productCopy[language], variant = variantById(variantId);
  const demo = useAppSelector(state => state.productDemo), dispatch = useAppDispatch();
  const alreadyInCart = useMemo(() => configurationCartQuantity(demo.cart, variantId, design), [demo.cart, variantId, design]);
  const remaining = variant.stock - variantCartQuantity(demo, variantId);
  return <ProductPurchasePanel locale={language} stock={variant.stock} remaining={remaining} unitCents={unitCents}
    inCart={alreadyInCart} requested={demo.purchaseQuantities[variantId]} exhaustedLabel={t.allInCart}
    onQuantity={quantity => dispatch(purchaseQuantityChanged({ variant: variantId, quantity }))}
    onAdd={quantity => dispatch(cartAdded({ variant: variantId, quantity, ...(design ? { design } : {}) }))} />;
}
