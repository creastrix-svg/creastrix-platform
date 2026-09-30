import { useEffect, useRef } from 'react';
import { Link, NavLink, Route, Routes, useLocation } from 'react-router';
import { useAppDispatch, useAppSelector } from './hooks';
import { copy } from './i18n';
import { languageSelected } from './store';

function useCopy() {
  return copy[useAppSelector(state => state.ui.language)];
}

function Home() {
  const t = useCopy();
  return <>
    <div className="hero">
      <section className="hero-copy">
        <p className="eyebrow">{t.homeEyebrow}</p>
        <h1>{t.homeTitle}</h1>
        <p className="lead">{t.homeLead}</p>
        <div className="actions">
          <Link className="button primary" to="/account">{t.viewDemo}<span aria-hidden="true">↗</span></Link>
          <Link className="text-link" to="/login">{t.exploreLogin}<span aria-hidden="true">→</span></Link>
        </div>
      </section>
      <aside className="preview-card" aria-label={t.previewTitle}>
        <div className="card-top"><span className="eyebrow">Creastrix</span><span className="badge">{t.demoLabel}</span></div>
        <div className="paper-art" aria-hidden="true"><span /><span /><span /></div>
        <h2>{t.previewTitle}</h2>
        <p>{t.previewLead}</p>
      </aside>
    </div>
    <ol className="principles">
      {[[t.stepOne, t.stepOneBody], [t.stepTwo, t.stepTwoBody], [t.stepThree, t.stepThreeBody]].map(([title, body], i) =>
        <li key={title}><span className="step-number" aria-hidden="true">0{i + 1}</span><h2>{title}</h2><p>{body}</p></li>)}
    </ol>
  </>;
}

function Login() {
  const t = useCopy();
  return <div className="two-column">
    <section className="page-intro"><p className="eyebrow">{t.loginEyebrow}</p><h1>{t.loginTitle}</h1><p className="lead">{t.loginLead}</p></section>
    <div>
      <section className="panel">
        <p className="eyebrow">{t.notAvailable}</p><h2>{t.futureMethods}</h2>
        <div className="methods">
          {[t.email, t.google, t.apple].map(method => <button type="button" key={method} disabled aria-describedby="methods-note">{method}<span aria-hidden="true">—</span></button>)}
        </div>
        <p className="small" id="methods-note">{t.methodsNote}</p>
      </section>
      <section className="demo-invite"><h2>{t.demoHeading}</h2><p>{t.demoBody}</p><Link className="button primary" to="/account">{t.viewDemo}<span aria-hidden="true">↗</span></Link></section>
    </div>
  </div>;
}

function Account() {
  const t = useCopy();
  return <>
    <section className="page-intro"><p className="eyebrow">{t.accountEyebrow}</p><h1>{t.accountTitle}</h1><p className="lead">{t.accountLead}</p></section>
    <div className="account-grid">
      <section className="panel profile"><div className="card-top"><h2>{t.profile}</h2><span className="badge">{t.demoLabel}</span></div>
        <div className="identity"><span className="avatar" aria-hidden="true">A</span><div><strong>Alex</strong><p className="small">{t.fictional}</p></div></div>
        <dl><div><dt>{t.name}</dt><dd>Alex</dd></div><div><dt>{t.access}</dt><dd>{t.accessValue}</dd></div></dl>
      </section>
      <section className="panel workspace"><span className="empty-mark" aria-hidden="true">+</span><h2>{t.workspaceTitle}</h2><p>{t.workspaceBody}</p><p>{t.workspaceChoice}</p><p className="small workspace-note">{t.workspaceBoundary}</p></section>
    </div>
    <p className="boundary-note">{t.demoNotice}</p>
    <Link className="text-link" to="/">{t.backHome}<span aria-hidden="true">→</span></Link>
  </>;
}

function NotFound() {
  const t = useCopy();
  return <section className="not-found page-intro"><p className="eyebrow">{t.notFoundEyebrow}</p><h1>{t.notFoundTitle}</h1><p className="lead">{t.notFoundBody}</p><Link className="button primary" to="/">{t.backHome}<span aria-hidden="true">→</span></Link></section>;
}

export default function App() {
  const language = useAppSelector(state => state.ui.language);
  const dispatch = useAppDispatch();
  const t = copy[language];
  const { pathname } = useLocation();
  const previousPath = useRef(pathname);
  const main = useRef<HTMLElement>(null);
  const currentPath = pathname.replace(/\/+$/, '').toLowerCase() || '/';
  const title = currentPath === '/' ? t.home : currentPath === '/login' ? t.login : currentPath === '/account' ? t.account : t.notFoundEyebrow;

  useEffect(() => {
    document.documentElement.lang = language;
    document.title = `${title} · Creastrix`;
  }, [language, title]);

  useEffect(() => {
    if (previousPath.current !== pathname) {
      main.current?.focus();
      window.scrollTo({ top: 0, behavior: 'instant' });
      previousPath.current = pathname;
    }
  }, [pathname]);

  return <div className="shell">
    <a className="skip-link" href="#main">{t.skip}</a>
    <header className="header">
      <Link className="brand" to="/" aria-label={`Creastrix — ${t.home}`}><img src="/brand/creastrix-logo-horizontal-black.svg" alt="Creastrix" width="174" height="44" /></Link>
      <nav aria-label={t.navigation}><NavLink to="/" end>{t.home}</NavLink><NavLink to="/login">{t.login}</NavLink><NavLink to="/account">{t.account}</NavLink></nav>
      <div className="languages" role="group" aria-label={t.language}>
        <button type="button" lang="de" aria-label="Deutsch" aria-pressed={language === 'de'} onClick={() => dispatch(languageSelected('de'))}>DE</button>
        <button type="button" lang="en" aria-label="English" aria-pressed={language === 'en'} onClick={() => dispatch(languageSelected('en'))}>EN</button>
      </div>
    </header>
    <div className="demo-strip"><span className="badge">{t.demoLabel}</span><span>{t.disconnected}</span></div>
    <main id="main" ref={main} tabIndex={-1}><Routes><Route path="/" element={<Home />} /><Route path="/login" element={<Login />} /><Route path="/account" element={<Account />} /><Route path="*" element={<NotFound />} /></Routes></main>
    <footer><span>{t.footer}</span><span>{t.footerNote}</span></footer>
  </div>;
}
