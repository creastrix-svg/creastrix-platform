import { useEffect, useLayoutEffect, useRef } from 'react';
import { useStore } from 'react-redux';
import { Link, Navigate, Route, Routes, useLocation, useMatch } from 'react-router';
import { isFavoriteEvent, readFavoriteIds } from './favorites';
import { favoritesRead } from './demoStore';
import { useAppDispatch, useAppSelector } from '../hooks';
import { productCopy } from './copy';
import { Icon } from './Icon';
import ProductPage from './detail/ProductPage';
import { HeaderCart, SearchControl } from './HeaderControls';
import { useStorefrontNavigation } from './useStorefrontNavigation';
import { StorefrontOrigin, useStorefrontOrigin } from './storefrontOrigin';
import { useProductMetadata } from './metadata';
import { useProductLocale } from './useProductLocale';
import { useAppearance } from './appearance';
import StorefrontNavigation from './StorefrontNavigation';
import CartView from './cart/CartView';
import AccountCartView from './cart/AccountCartView';
import {AccountCartBadge} from './cart/AccountCartBadge';
import { GuestCartActionsEnabled } from './cart/guestCartActions';
import { AccountPurchaseContext } from './cart/accountPurchaseContext';
import type { AccountCartAdmission } from './cart/accountCartAdmission';
import CheckoutView from './checkout/CheckoutView';
import { SearchResultsView } from './browse/SearchResultsView';
import { ConceptView } from './browse/ConceptView';
import { FavoritesView } from './favorites/FavoritesView';
import { InfoView } from './info/InfoView';
import { NotFoundView } from './NotFoundView';
import { ReviewFormView } from './reviews/ReviewFormView';
import { ReviewsView } from './reviews/ReviewsView';
import ConnectedReviewView from './reviews/ConnectedReviewView';
import CatalogView from './browse/CatalogView';
import EditionView from './browse/EditionView';
import { catalogDetailRoutes, editionCollectionPath } from './routes';
import StorefrontHome from './home/StorefrontHome';
import TideProductPage from './detail/TideProductPage';
import { tideOffer } from './fixtures/tideOffer';
import { catalogCopy } from './browse/catalogCopy';
import './product.css';
import { rememberProductPreferences, serializeProductPreferences } from './preferences';
import PersonalizationView from './studio/PersonalizationView';
import PersistentStudio from './studio/session/PersistentStudio';
import ConnectedCatalog from './catalog/ConnectedCatalog';
import { catalogReady, connectedProduct, entryForPath } from './catalog/localCatalog';
import ConnectedProductPage from './detail/ConnectedProductPage';
import { permanentCatalogMode, sharedCatalogMode } from './catalog/permanentCatalog';
import { AccountStudio } from './studio/session/AccountStudio';
import AccountDesignsRoute from './designs/AccountDesignsRoute';
import {designLibraryPath} from './routes';

export default function ProductExperience({ accountCart,studioKey }: { accountCart?: AccountCartAdmission;studioKey?:string } = {}) {
  const cartPage = useMatch('/preview/cart'), checkoutPage = useMatch('/preview/checkout'), designLibrary=useMatch(designLibraryPath);
  // Guest IndexedDB restore may expose removed/unlisted rows and its own retry
  // action. It is not part of reading or recovering an admitted account cart.
  if (accountCart && sharedCatalogMode) {
    const {userId,owner}=accountCart;
    return <AccountStudio key={studioKey??[userId,owner.document,owner.epoch,owner.request,owner.revision].join(':')}>
      {cartPage || checkoutPage || designLibrary ? <Storefront accountCart={accountCart}/> : <ConnectedCatalog><Storefront accountCart={accountCart}/></ConnectedCatalog>}
    </AccountStudio>;
  }
  // A cold guest deep link must redirect before mounting catalog-backed navigation.
  if(sharedCatalogMode&&designLibrary)return <AccountDesignsRoute/>;
  if (accountCart && (cartPage || checkoutPage)) return <Storefront accountCart={accountCart} />;
  if (accountCart && permanentCatalogMode) return <ConnectedCatalog><Storefront accountCart={accountCart} /></ConnectedCatalog>;
  return <ConnectedCatalog><PersistentStudio showGuestCartNotices={!accountCart} browsingHome={<Storefront cartReady={false} accountCart={accountCart} />}><Storefront accountCart={accountCart} /></PersistentStudio></ConnectedCatalog>;
}
function CollectionItemRoute({offline}: {offline:'edition'|'concept'}) {
  const {pathname} = useLocation(), entry = entryForPath(pathname);
  if (entry?.kind === 'offer') return <ConnectedProductPage key={entry.id}/>;
  return offline === 'edition' ? <EditionView /> : <ConceptView />;
}
function PermanentTideRoute() {
  const {search}=useLocation();
  return <Navigate to={'/products/tide'+search} replace/>;
}
// Resolve this only when the product route mounts: a cold account cart is independent of catalog loading.
function LoopRoute({studio = false, account}: {studio?: boolean; account: boolean}) {
  if (permanentCatalogMode && ((!sharedCatalogMode && account) || !connectedProduct('loop')?.personalizable)) {
    return studio ? <NotFoundView /> : <ConnectedProductPage />;
  }
  return studio ? <PersonalizationView /> : <ProductPage />;
}
function Storefront({ cartReady = true, accountCart }: { cartReady?: boolean; accountCart?: AccountCartAdmission }) {
  const language = useProductLocale(), t = productCopy[language];
  const demo = useAppSelector(state => state.productDemo);
  const dispatch = useAppDispatch(), store = useStore();
  const catalogAvailable = catalogReady();
  useEffect(() => {
    if (!catalogAvailable) return;
    const refresh = () => { const ids = readFavoriteIds(store.getState); if (ids) dispatch(favoritesRead(ids)); };
    const changed = (event: StorageEvent) => { if (isFavoriteEvent(event)) refresh(); };
    window.addEventListener('storage', changed); refresh();
    return () => window.removeEventListener('storage', changed);
  }, [catalogAvailable, dispatch, store]);
  // Cold account entry has no product stock against which to validate or save preferences.
  const preferences = catalogAvailable && !permanentCatalogMode ? serializeProductPreferences(demo) : null;
  useEffect(() => { if (preferences !== null) rememberProductPreferences(preferences); }, [preferences]);
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
    // Dock to the whole header: compact layouts can change only its vertical padding.
    if (observer) observer.observe(element, { box: 'border-box' }); else window.addEventListener('resize', measure);
    return () => { observer?.disconnect(); window.removeEventListener('resize', measure); shell.style.removeProperty('--product-header-height'); };
  }, []);
  useStorefrontNavigation(main);
  const origin = useStorefrontOrigin();
  useProductMetadata(t, language, Boolean(accountCart));
  return <div className="product-storefront" data-theme={theme}>
    <div data-storefront-content>
      <a className="product-skip" href="#product-main">{t.skip}</a>
      <header ref={header} className="product-header product-width">
        <Link to="/" className="product-logo" aria-label={t.home}><img src="/brand/creastrix-logo-horizontal-black.svg" alt="Creastrix" width="174" height="44" /></Link>
        <SearchControl />
        <div className="product-header-actions"><Link to="/account" className="product-icon-button" aria-label={t.account}><Icon name="user" /></Link><Link to="/preview/saved" className="product-icon-button" aria-label={demo.favorites.length ? t.favorites + ' · ' + demo.favorites.length : t.favorites}><Icon name="heart" />{demo.favorites.length > 0 && <span className="product-count">{demo.favorites.length}</span>}</Link>{accountCart ? sharedCatalogMode ? <AccountCartBadge admission={accountCart} label={t.cart}/> : <Link className="product-icon-button" aria-label={t.cart} to="/preview/cart"><Icon name="bag" /></Link> : <HeaderCart ready={cartReady} />}</div>
      </header>
      {demo.favoritesMemoryOnly && <p role="status" className="product-width">{t.favoritesMemoryOnly}</p>}
      <StorefrontNavigation appearance={appearance} onAppearance={setAppearance} />
      <main className="product-main product-width" id="product-main" tabIndex={-1} ref={main}><StorefrontOrigin.Provider value={origin}><AccountPurchaseContext.Provider value={accountCart??null}><GuestCartActionsEnabled.Provider value={!accountCart}><Routes>
        <Route path="/" element={permanentCatalogMode && !sharedCatalogMode ? <Navigate to="/catalog" replace /> : <StorefrontHome />} />
        <Route path={editionCollectionPath} element={<EditionView />} />
        <Route path={tideOffer.href} element={sharedCatalogMode ? <ConnectedProductPage/> : permanentCatalogMode ? <PermanentTideRoute /> : <TideProductPage />} />
        <Route path={tideOffer.href + '/reviews'} element={<ReviewsView product="tide" />} />
        <Route path={tideOffer.href + '/reviews/new'} element={<ReviewFormView product="tide" />} />
        {catalogDetailRoutes.map(path => <Route key={path + '/reviews'} path={path + '/reviews'} element={<ConnectedReviewView/>}/>)}
        {catalogDetailRoutes.map(path => <Route key={path + '/reviews/new'} path={path + '/reviews/new'} element={<ConnectedReviewView write/>}/>)}
        <Route path="/products/:id" element={<ConnectedProductPage />} />
        <Route path={editionCollectionPath + '/:id'} element={<CollectionItemRoute offline="edition" />} />
        <Route path="/products/loop" element={<LoopRoute account={!!accountCart} />} /><Route path="/products/loop/reviews" element={<ReviewsView />} /><Route path="/products/loop/reviews/new" element={<ReviewFormView />} />
        <Route path="/products/loop/personalize" element={<LoopRoute studio account={!!accountCart} />} />
        <Route path="/preview/cart" element={accountCart ? <AccountCartView admission={accountCart} /> : <CartView />} />
        {sharedCatalogMode && <Route path={designLibraryPath} element={<AccountDesignsRoute admission={accountCart}/>}/>}
        <Route path="/preview/checkout" element={accountCart ? <Link to="/preview/cart">{t.viewCart}</Link> : <CheckoutView />} /><Route path="/preview/saved" element={<FavoritesView />} />
        <Route path="/preview/search" element={<SearchResultsView />} /><Route path="/catalog" element={<CatalogView />} /><Route path="/preview/collection" element={<CatalogView />} />
        <Route path="/preview/items/:id" element={<CollectionItemRoute offline="concept" />} /><Route path="/preview/info/:topic" element={<InfoView />} /><Route path="/preview/studio" element={<InfoView studio />} />
        <Route path="*" element={<NotFoundView />} />
      </Routes></GuestCartActionsEnabled.Provider></AccountPurchaseContext.Provider></StorefrontOrigin.Provider></main>
      <footer className="product-footer"><div className="product-width"><div className="product-footer-grid">
        <div className="product-footer-brand"><Link to="/" className="product-logo"><img src="/brand/creastrix-logo-horizontal-black.svg" alt="Creastrix" width="160" height="40" /></Link><p>{t.footerTagline}</p></div>
        <section><h2>{t.buyers}</h2><Link to="/catalog">{catalogCopy[language].title}</Link><Link to="/preview/saved">{t.favorites}</Link></section>
        <section><h2>{t.support}</h2><Link to="/preview/info/help">{t.help}</Link><Link to="/preview/info/delivery">{t.delivery}</Link><Link to="/preview/info/returns">{t.returns}</Link><a href="mailto:support@creastrix.com">support@creastrix.com</a></section>
        <section><h2>{t.about}</h2><Link to="/preview/info/about">{t.how}</Link><Link to="/preview/info/contact">{t.contact}</Link></section>
      </div><div className="product-footer-bottom"><span>© Creastrix</span><Link to="/preview/info/legal">{t.legal}</Link></div></div></footer>
    </div><div data-storefront-modal />
  </div>;
}
