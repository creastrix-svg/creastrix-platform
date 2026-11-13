import { lazy, Suspense, useContext, useEffect, useLayoutEffect, useRef } from 'react';
import type { FormEvent } from 'react';
import { Link, NavLink, Route, Routes, useLocation } from 'react-router';
import { AuthenticationContext } from './authentication';
import ConfirmEmail from './ConfirmEmail';
import LoadingFrame from './LoadingFrame';
import { useAppDispatch, useAppSelector } from './hooks';
import { copy } from './i18n';
import { languageSelected } from './store';
import { isStorefrontPath } from './product/routes';
import type { LocalCartSession } from './product/cart/localCartSession';
import GuestCartTransferNotice from './product/cart/GuestCartTransferNotice';
import CartUndoVisitScope from './product/cart/CartUndoVisitScope';
import {sharedCatalogMode} from './product/catalog/permanentCatalog';
import SharedFavoriteProvider from './product/favorites/SharedFavoriteProvider';
import {designLibraryCopy} from './product/designs/designLibraryCopy';
import {bindGuestDesignLogin} from './product/designs/guestDesignHandoff';
import {designLibraryPath,isDesignLibraryPath} from './product/routes';

const ProductExperience = lazy(() => import('./product/ProductExperience'));
const LocalAccountStorefront = lazy(() => import('./product/cart/LocalAccountStorefront'));

function useCopy() { return copy[useAppSelector(state => state.ui.language)]; }

function AuthenticationStatus() {
  const t = useCopy();
  const { phase, notice } = useAppSelector(state => state.authentication);
  return <div className="auth-status" role="status" aria-live="polite">
    <p>{t.status[phase]}</p>{notice !== 'NONE' && <p className="small">{t.notice[notice]}</p>}
  </div>;
}

function AccountActions() {
  const t = useCopy();
  const runtime = useContext(AuthenticationContext);
  const { phase, notice } = useAppSelector(state => state.authentication);
  const disabled = !runtime || phase === 'TRANSITION' || phase === 'CHECKING' || notice === 'DEV_ONLY';
  return <div className="actions">
    {phase === 'AUTHENTICATED' && <button className="button primary" type="button" disabled={disabled} onClick={() => { void runtime?.logout(); }}>{t.logout}</button>}
    <button className="button" type="button" disabled={disabled} onClick={() => { void runtime?.recover(); }}>{phase === 'AUTHENTICATED' ? t.switchAccount : t.recover}</button>
    <button className="button" type="button" disabled={disabled} onClick={() => { void runtime?.recheck(); }}>{t.recheck}</button>
  </div>;
}

function Login({cartSession}:{cartSession?:LocalCartSession}) {
  const t = useCopy();
  const runtime = useContext(AuthenticationContext);
  const { phase, notice } = useAppSelector(state => state.authentication);
  const tokenInput = useRef<HTMLInputElement>(null);
  const intentInput = useRef<HTMLInputElement>(null);
  const { search } = useLocation();
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    if (!runtime || phase !== 'ANONYMOUS') return;
    let sealed:string|null=null;
    void runtime.login(token => {
      const input = tokenInput.current;
      if (!input || !form.isConnected) throw new Error('AUTH_FORM_UNAVAILABLE');
      input.name = token.parameterName;
      input.value = token.token;
      if(cartSession) {
        const field=intentInput.current;
        if(!field) throw new Error('AUTH_FORM_UNAVAILABLE');
        field.disabled=sealed===null;
        field.value=sealed??'';
      }
      // Bypass this handler only after the controller's final synchronous ownership check.
      // Native navigation keeps OIDC outside fetch and may display an early opaque backend 4xx.
      HTMLFormElement.prototype.submit.call(form);
    }, cartSession ? async current=>{
      const live=()=>form.isConnected && current();
      const draft=sharedCatalogMode?new URLSearchParams(search).get('design'):null;
      sealed=draft?await cartSession.sealBeforeLogin(live,await bindGuestDesignLogin(draft,live))
        :await cartSession.sealBeforeLogin(live);
      if (!live()) throw new Error('AUTH_FORM_UNAVAILABLE');
    } : undefined);
  }
  return <div className="two-column">
    <section className="page-intro"><p className="eyebrow">{t.loginEyebrow}</p><h1>{t.loginTitle}</h1><p className="lead">{t.loginLead}</p></section>
    <div>
      <section className="panel">
        <h2>{t.hostedLogin}</h2><AuthenticationStatus />
        {new URLSearchParams(search).get('auth') === 'failed' && <p className="boundary-note">{t.callbackFailed}</p>}
        {phase === 'AUTHENTICATED' ? <Link className="button primary" to="/account">{t.openAccount}</Link>
          : <form method="post" action="/auth/login" target="_self" onSubmit={submit}>
            <input type="hidden" name="_csrf" defaultValue="" ref={tokenInput} data-auth-csrf />
            {cartSession && <input type="hidden" name="cart_intent" disabled ref={intentInput} />}
            <button className="button primary" type="submit" disabled={!runtime || phase !== 'ANONYMOUS' || notice === 'DEV_ONLY'}>{t.continueLogin}</button>
          </form>}
        <AccountActions /><p className="small boundary-note">{t.nativeErrorNote}</p>
        <Link className="text-link" to="/confirm-email">{t.confirmation.link}<span aria-hidden="true">→</span></Link>
      </section>
      <section className="demo-invite"><h2>{t.futureMethods}</h2><div className="methods">
        {[t.signup, t.google, t.apple].map(method => <button type="button" key={method} disabled aria-describedby="methods-note">{method}<span aria-hidden="true">—</span></button>)}
      </div><p className="small" id="methods-note">{t.methodsNote}</p></section>
    </div>
  </div>;
}

function Account({designs=false}:{designs?:boolean}) {
  const t = useCopy();
  const library=designLibraryCopy[useAppSelector(s=>s.ui.language)];
  const { phase, user } = useAppSelector(state => state.authentication);
  return <>
    <section className="page-intro"><p className="eyebrow">{t.accountEyebrow}</p><h1>{t.accountTitle}</h1><p className="lead">{t.accountLead}</p></section>
    <AuthenticationStatus />
    {phase === 'AUTHENTICATED' && user && <div className="account-grid" data-private-account>
      <section className="panel profile"><h2>{t.profile}</h2>
        <dl><div><dt>{t.userId}</dt><dd>{user.id}</dd></div><div><dt>{t.access}</dt><dd>{t.activeAccess}</dd></div></dl>
        {designs&&<Link className="text-link" to={designLibraryPath}>{library.title}<span aria-hidden="true">→</span></Link>}
      </section>
      <section className="panel workspace"><span className="empty-mark" aria-hidden="true">+</span><h2>{t.workspaceTitle}</h2><p>{t.workspaceBody}</p><p>{t.workspaceChoice}</p><p className="small workspace-note">{t.workspaceBoundary}</p></section>
    </div>}
    {phase === 'ANONYMOUS' && <Link className="button primary" to="/login">{t.login}</Link>}
    <AccountActions /><p className="boundary-note">{t.accountBoundary}</p>
    <Link className="text-link" to="/">{t.backHome}<span aria-hidden="true">→</span></Link>
  </>;
}

function NotFound() {
  const t = useCopy();
  return <section className="not-found page-intro"><p className="eyebrow">{t.notFoundEyebrow}</p><h1>{t.notFoundTitle}</h1><p className="lead">{t.notFoundBody}</p><Link className="button primary" to="/">{t.backHome}<span aria-hidden="true">→</span></Link></section>;
}

export default function App({cartSession}:{cartSession?:LocalCartSession}={}){
  return sharedCatalogMode&&cartSession?<SharedFavoriteProvider session={cartSession}><AppRoutes cartSession={cartSession}/></SharedFavoriteProvider>:<AppRoutes cartSession={cartSession}/>;
}
function AppRoutes({cartSession}:{cartSession?:LocalCartSession}) {
  const language = useAppSelector(state => state.ui.language);
  const auth = useAppSelector(state => state.authentication);
  const runtime = useContext(AuthenticationContext);
  const dispatch = useAppDispatch();
  const t = copy[language];
  const { pathname } = useLocation();
  const storefront = isStorefrontPath(pathname)||(sharedCatalogMode&&!!cartSession&&isDesignLibraryPath(pathname));
  const previousPath = useRef(pathname);
  const main = useRef<HTMLElement>(null);
  const currentPath = pathname.replace(/\/+$/, '').toLowerCase() || '/';
  const title = currentPath === '/login' ? t.login : currentPath === '/account' ? t.account
    : currentPath === '/confirm-email' ? t.confirmation.page : t.notFoundEyebrow;

  useEffect(() => { if (!storefront) { document.documentElement.lang = language; document.title = title + ' · Creastrix'; } }, [language, title, storefront]);
  useEffect(() => {
    if (previousPath.current !== pathname) {
      if (!storefront) { main.current?.focus(); window.scrollTo({ top: 0, behavior: 'instant' }); }
      previousPath.current = pathname;
    }
  }, [pathname, storefront]);
  useLayoutEffect(() => {
    // Reopening follows the current DOM commit, never an old result awaiting React rendering.
    if (auth.phase === 'AUTHENTICATED' && auth.owner && auth.user) runtime?.reveal(auth.owner);
  }, [auth, runtime]);

  if (storefront) {
    const page=<Suspense fallback={<LoadingFrame label={language==='de'?'Lädt…':'Loading…'}/>}>{cartSession ? <><GuestCartTransferNotice session={cartSession} /><LocalAccountStorefront session={cartSession} /></> : <ProductExperience />}</Suspense>;
    return <CartUndoVisitScope open={currentPath==='/preview/cart'}>{page}</CartUndoVisitScope>;
  }

  return <div className="shell">
    <a className="skip-link" href="#main">{t.skip}</a>
    <header className="header">
      <Link className="brand" to="/" aria-label={'Creastrix — ' + t.home}><img src="/brand/creastrix-logo-horizontal-black.svg" alt="Creastrix" width="174" height="44" /></Link>
      <nav aria-label={t.navigation}><NavLink to="/" end>{t.home}</NavLink><NavLink to="/login">{t.login}</NavLink><NavLink to="/account">{t.account}</NavLink></nav>
      <div className="languages" role="group" aria-label={t.language}>
        <button type="button" lang="de" aria-label="Deutsch" aria-pressed={language === 'de'} onClick={() => dispatch(languageSelected('de'))}>DE</button>
        <button type="button" lang="en" aria-label="English" aria-pressed={language === 'en'} onClick={() => dispatch(languageSelected('en'))}>EN</button>
      </div>
    </header>
    <div className="demo-strip"><span className="badge">{t.pilotLabel}</span><span>{t.pilotBoundary}</span></div>
    <main id="main" ref={main} tabIndex={-1}><Routes><Route path="/login" element={<Login cartSession={cartSession} />} /><Route path="/confirm-email" element={<ConfirmEmail />} /><Route path="/account" element={<><Account designs={sharedCatalogMode&&!!cartSession}/>{cartSession && <GuestCartTransferNotice session={cartSession} />}</>} /><Route path="*" element={<NotFound />} /></Routes></main>
    <footer><span>{t.footer}</span><span>{t.footerNote}</span></footer>
  </div>;
}
