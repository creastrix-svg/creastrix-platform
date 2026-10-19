import { useEffect, useLayoutEffect, useRef } from 'react';
import { Link, Route, Routes } from 'react-router';
import { useAppSelector } from '../hooks';
import { productCopy } from './copy';
import { Icon } from './Icon';
import ProductPage from './ProductPage';
import { HeaderCart, SearchControl } from './HeaderControls';
import { useStorefrontNavigation } from './useStorefrontNavigation';
import { useProductMetadata } from './metadata';
import { useProductLocale } from './useProductLocale';
import { useAppearance } from './appearance';
import StorefrontNavigation from './StorefrontNavigation';
import CartView from './CartView';
import CheckoutView from './CheckoutView';
import { CollectionView, ConceptView, FavoritesView, InfoView, NotFoundView, ReviewFormView, ReviewsView } from './PreviewViews';
import './product.css';
import { rememberProductPreferences, serializeProductPreferences } from './preferences';

export default function ProductExperience() {
  const language = useProductLocale(), t = productCopy[language];
  const demo = useAppSelector(state => state.productDemo);
  const preferences = serializeProductPreferences(demo);
  useEffect(() => { rememberProductPreferences(preferences); }, [preferences]);
  const { appearance, setAppearance, theme } = useAppearance();
  const main = useRef<HTMLElement>(null);
  const header = useRef<HTMLElement>(null);
  useLayoutEffect(() => {
    const element = header.current, shell = element?.closest<HTMLElement>('.product-storefront');
    if (!element || !shell) return;
    // Measured clearance is local to this storefront; resize never scrolls the page.
    const measure = () => { const height = element.getBoundingClientRect().height; if (height > 0) shell.style.setProperty('--product-header-height', `${height}px`); };
    measure();
    const observer = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(measure) : null;
    if (observer) observer.observe(element); else window.addEventListener('resize', measure);
    return () => { observer?.disconnect(); window.removeEventListener('resize', measure); shell.style.removeProperty('--product-header-height'); };
  }, []);
  useStorefrontNavigation(main);
  useProductMetadata(t, language);
  return <div className="product-storefront" data-theme={theme}>
    <div data-storefront-content>
      <a className="product-skip" href="#product-main">{t.skip}</a>
      <header ref={header} className="product-header product-width">
        <Link to="/" className="product-logo" aria-label={t.home}><img src="/brand/creastrix-logo-horizontal-black.svg" alt="Creastrix" width="174" height="44" /></Link>
        <SearchControl />
        <div className="product-header-actions"><Link to="/account" className="product-icon-button" aria-label={t.account}><Icon name="user" /></Link><Link to="/preview/saved" className="product-icon-button" aria-label={t.favorites}><Icon name="heart" />{demo.favorite && <span className="product-count">1</span>}</Link><HeaderCart /></div>
      </header>
      <StorefrontNavigation appearance={appearance} onAppearance={setAppearance} />
      <main className="product-main product-width" id="product-main" tabIndex={-1} ref={main}><Routes>
        <Route path="/products/loop" element={<ProductPage />} /><Route path="/products/loop/reviews" element={<ReviewsView />} /><Route path="/products/loop/reviews/new" element={<ReviewFormView />} />
        <Route path="/preview/cart" element={<CartView />} /><Route path="/preview/checkout" element={<CheckoutView />} /><Route path="/preview/saved" element={<FavoritesView />} />
        <Route path="/preview/search" element={<CollectionView search />} /><Route path="/preview/collection" element={<CollectionView />} />
        <Route path="/preview/items/:id" element={<ConceptView />} /><Route path="/preview/info/:topic" element={<InfoView />} /><Route path="/preview/studio" element={<InfoView studio />} />
        <Route path="*" element={<NotFoundView />} />
      </Routes></main>
      <footer className="product-footer"><div className="product-width"><div className="product-footer-grid">
        <div className="product-footer-brand"><Link to="/" className="product-logo"><img src="/brand/creastrix-logo-horizontal-black.svg" alt="Creastrix" width="160" height="40" /></Link><p>{t.footerTagline}</p></div>
        <section><h2>{t.buyers}</h2><Link to="/products/loop">Loop</Link><Link to="/preview/collection">{t.collection}</Link><Link to="/preview/saved">{t.favorites}</Link></section>
        <section><h2>{t.support}</h2><Link to="/preview/info/help">{t.help}</Link><Link to="/preview/info/delivery">{t.delivery}</Link><Link to="/preview/info/returns">{t.returns}</Link><a href="mailto:support@creastrix.com">support@creastrix.com</a></section>
        <section><h2>{t.about}</h2><Link to="/preview/info/about">{t.how}</Link><Link to="/preview/studio">Loop Studio</Link><a href="mailto:hello@creastrix.com">{t.contact}</a><Link to="/preview/info/legal">{t.legal}</Link></section>
      </div><div className="product-footer-bottom"><span>© Creastrix</span><Link to="/preview/info/legal">{t.legal}</Link></div></div></footer>
    </div><div data-storefront-modal />
  </div>;
}
