import { useEffect, useMemo } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router';
import { useAppDispatch, useAppSelector } from '../hooks';
import { isVariantId, variantById, variants } from './fixtures/loop';
import { readCartSku } from './fixtures/cartSkus';
import { photoDescription } from './productDisplay';
import type { VariantId } from './fixtures/loop';
import { productCopy } from './copy';
import { cartAdded, configurationCartQuantity, favoriteToggled, productDescriptionChanged, productMediaModeSelected, productVariantSelected, purchaseQuantityChanged, variantCartQuantity } from './demoStore';
import { loopPrice } from './loopPricing';
import MediaGallery from './MediaGallery';
import ProductPurchasePanel from './ProductPurchasePanel';
import { loopCarouselContent } from './carouselContent';
import ProductPrice from './ProductPrice';
import PersonalizationEntry from './studio/PersonalizationEntry';
import { getStudioProduct } from './studio/studioProducts';
import { useProductLocale } from './useProductLocale';
import { usePersonalizationSession } from './studio/session/personalizationSession';
import { saveDesign, visibleDesign } from './studio/session/designSnapshot';
import type { SavedDesign } from './studio/session/designSnapshot';
import { firstLettering } from './studio/design/studioComposition';
import ProductCardLayout from './ProductCardLayout';
import { catalogCopy } from './browse/catalogCopy';

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
  const language = useProductLocale(), t = productCopy[language];
  const demo = useAppSelector(state => state.productDemo), dispatch = useAppDispatch();
  const variant = variantById(variantId);
  const acceptsDesign = readCartSku(variantId).acceptsDesign;
  const { state } = usePersonalizationSession();
  const applied = acceptsDesign ? state.applied[variantId] : undefined;
  const design = useMemo(() => {
    const snapshot = saveDesign(applied);
    return visibleDesign(snapshot) ? snapshot : undefined;
  }, [applied]);
  const price = loopPrice(variantId, Boolean(design));
  const profile = getStudioProduct('loop');
  const recommendations = loopCarouselContent(t);
  const decoration = applied && design ? { ...firstLettering(applied, variantId), variant: variantId, objects: applied.objects } : undefined;
  const facts = [[t.material, t.plastic], [t.dimensions, catalogCopy[language].productDetails], [t.process, t.printing]] as const;
  return <ProductCardLayout locale={language} name="Loop" subtitle={t.subtitle} category={{ label: t.living, href: '/catalog?category=living' }}
    selected={variantId} urlVariant={variantId} finishes={variants.map(v => ({ ...v, name: v.name[language] }))} onSelect={selectVariant}
    gallery={<MediaGallery key={variantId} variant={variantId} locale={language} decoration={decoration} initialMode={demo.mediaMode} onModeChange={mode => dispatch(productMediaModeSelected(mode))} favorite={demo.favorites.includes(variantId)} toggleFavorite={() => dispatch(favoriteToggled(variantId))} t={{ ...t, photoAlt: photoDescription(t.photoAlt, variant.name[language]) }} />}
    reviews={{ list: '/products/loop/reviews?variant=' + variantId, write: score => `/products/loop/reviews/new?rating=${score}&variant=${variantId}` }}
    price={<ProductPrice cents={price.unitCents} designFeeCents={price.feeCents} previousCents={variant.previousCents} language={language} t={t} />}
    purchase={<PurchaseControls key={variantId} variantId={variantId} unitCents={price.unitCents} design={design} />}
    seller={{ name: 'Loop Studio', byline: t.studioBadge + ' · ' + t.seller, href: '/preview/studio', label: t.studioLink }}
    recommendations={{ studio: recommendations, related: recommendations }}
    personalization={acceptsDesign ? <PersonalizationEntry profile={profile} variant={variantId} feeCents={price.feeCents} /> : undefined}
    features={[t.features[0], t.features[1], t.features[2]]}
    about={{ title: t.aboutTitle, body: t.aboutBody, facts }}
    description={{ intro: t.descriptionIntro, sections: [
      [t.descriptionDesignTitle, t.descriptionDesignBody],
      [t.descriptionCustomTitle, t.descriptionCustomBody],
      [t.descriptionUseTitle, t.descriptionUseBody],
    ], facts: [[t.specsModel, 'Loop'], ...facts, [t.specsColors, variants.map(color => color.name[language]).join(' · ')], [t.specsPersonal, t.specsPersonalValue]] }}
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
