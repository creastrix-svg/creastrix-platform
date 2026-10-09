import { useContext, useEffect, useLayoutEffect, useRef } from 'react';
import type { FormEvent } from 'react';
import { Link, NavLink, Route, Routes, useLocation } from 'react-router';
import { AuthenticationContext } from './authentication';
import { useAppDispatch, useAppSelector } from './hooks';
import { copy } from './i18n';
import { languageSelected } from './store';

function useCopy() { return copy[useAppSelector(state => state.ui.language)]; }

function Home() {
  const t = useCopy();
  return <>
    <div className="hero">
      <section className="hero-copy">
        <p className="eyebrow">{t.homeEyebrow}</p><h1>{t.homeTitle}</h1><p className="lead">{t.homeLead}</p>
        <div className="actions">
          <Link className="button primary" to="/login">{t.login}<span aria-hidden="true">↗</span></Link>
          <Link className="text-link" to="/account">{t.openAccount}<span aria-hidden="true">→</span></Link>
        </div>
      </section>
      <aside className="preview-card" aria-label={t.previewTitle}>
        <div className="card-top"><span className="eyebrow">Creastrix</span><span className="badge">{t.pilotLabel}</span></div>
        <div className="paper-art" aria-hidden="true"><span /><span /><span /></div>
        <h2>{t.previewTitle}</h2><p>{t.previewLead}</p>
      </aside>
    </div>
    <ol className="principles">
      {[[t.stepOne, t.stepOneBody], [t.stepTwo, t.stepTwoBody], [t.stepThree, t.stepThreeBody]].map(([title, body], i) =>
        <li key={title}><span className="step-number" aria-hidden="true">0{i + 1}</span><h2>{title}</h2><p>{body}</p></li>)}
    </ol>
  </>;
}

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

function Login() {
  const t = useCopy();
  const runtime = useContext(AuthenticationContext);
  const { phase, notice } = useAppSelector(state => state.authentication);
  const tokenInput = useRef<HTMLInputElement>(null);
  const { search } = useLocation();
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    if (!runtime || phase !== 'ANONYMOUS') return;
    void runtime.login(token => {
      const input = tokenInput.current;
      if (!input || !form.isConnected) throw new Error('AUTH_FORM_UNAVAILABLE');
      input.name = token.parameterName;
      input.value = token.token;
      // Bypass this handler only after the controller's final synchronous ownership check.
      // Native navigation keeps OIDC outside fetch and may display an early opaque backend 4xx.
      HTMLFormElement.prototype.submit.call(form);
    });
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
            <button className="button primary" type="submit" disabled={!runtime || phase !== 'ANONYMOUS' || notice === 'DEV_ONLY'}>{t.continueLogin}</button>
          </form>}
        <AccountActions /><p className="small boundary-note">{t.nativeErrorNote}</p>
      </section>
      <section className="demo-invite"><h2>{t.futureMethods}</h2><div className="methods">
        {[t.signup, t.google, t.apple].map(method => <button type="button" key={method} disabled aria-describedby="methods-note">{method}<span aria-hidden="true">—</span></button>)}
      </div><p className="small" id="methods-note">{t.methodsNote}</p></section>
    </div>
  </div>;
}

function Account() {
  const t = useCopy();
  const { phase, user } = useAppSelector(state => state.authentication);
  return <>
    <section className="page-intro"><p className="eyebrow">{t.accountEyebrow}</p><h1>{t.accountTitle}</h1><p className="lead">{t.accountLead}</p></section>
    <AuthenticationStatus />
    {phase === 'AUTHENTICATED' && user && <div className="account-grid" data-private-account>
      <section className="panel profile"><h2>{t.profile}</h2>
        <dl><div><dt>{t.userId}</dt><dd>{user.id}</dd></div><div><dt>{t.access}</dt><dd>{t.activeAccess}</dd></div></dl>
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

export default function App() {
  const language = useAppSelector(state => state.ui.language);
  const auth = useAppSelector(state => state.authentication);
  const runtime = useContext(AuthenticationContext);
  const dispatch = useAppDispatch();
  const t = copy[language];
  const { pathname } = useLocation();
  const previousPath = useRef(pathname);
  const main = useRef<HTMLElement>(null);
  const currentPath = pathname.replace(/\/+$/, '').toLowerCase() || '/';
  const title = currentPath === '/' ? t.home : currentPath === '/login' ? t.login : currentPath === '/account' ? t.account : t.notFoundEyebrow;

  useEffect(() => { document.documentElement.lang = language; document.title = title + ' · Creastrix'; }, [language, title]);
  useEffect(() => {
    if (previousPath.current !== pathname) { main.current?.focus(); window.scrollTo({ top: 0, behavior: 'instant' }); previousPath.current = pathname; }
  }, [pathname]);
  useLayoutEffect(() => {
    // Reopening follows the current DOM commit, never an old result awaiting React rendering.
    if (auth.phase === 'AUTHENTICATED' && auth.owner && auth.user) runtime?.reveal(auth.owner);
  }, [auth, runtime]);

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
    <main id="main" ref={main} tabIndex={-1}><Routes><Route path="/" element={<Home />} /><Route path="/login" element={<Login />} /><Route path="/account" element={<Account />} /><Route path="*" element={<NotFound />} /></Routes></main>
    <footer><span>{t.footer}</span><span>{t.footerNote}</span></footer>
  </div>;
}
