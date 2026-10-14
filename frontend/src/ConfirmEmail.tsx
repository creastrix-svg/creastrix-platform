import { useCallback, useContext, useLayoutEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { useStore } from 'react-redux';
import { Link } from 'react-router';
import { AuthenticationContext } from './authentication';
import { createEmailConfirmation, EmailConfirmationContext } from './emailConfirmation';
import type { ConfirmationResult } from './emailConfirmation';
import { useAppSelector } from './hooks';
import { copy } from './i18n';
import type { RootState } from './store';

export default function ConfirmEmail() {
  const t = copy[useAppSelector(state => state.ui.language)].confirmation;
  const { phase, notice } = useAppSelector(state => state.authentication);
  const runtime = useContext(AuthenticationContext);
  const transport = useContext(EmailConfirmationContext);
  const store = useStore<RootState>();
  // Match main.tsx's fixed development boundary; a context alone must never connect build preview.
  const connected = Boolean(runtime) && (transport ? transport.enabled
    : import.meta.env.DEV && window.location.origin === 'http://localhost:3000') && notice !== 'DEV_ONLY';
  const eligible = connected && phase !== 'AUTHENTICATED' && phase !== 'CHECKING' && phase !== 'TRANSITION';
  const emailInput = useRef<HTMLInputElement>(null);
  const codeInput = useRef<HTMLInputElement>(null);
  const mounted = useRef(false);
  const [suspended, setSuspended] = useState(document.visibilityState === 'hidden');
  const [result, setResult] = useState<ConfirmationResult>('IDLE');
  const clientRef = useRef<ReturnType<typeof createEmailConfirmation> | null>(null);
  const clearFields = useCallback(() => {
    // DOM clearing is synchronous even when React scheduling or bfcache pauses a commit.
    if (emailInput.current) emailInput.current.value = '';
    if (codeInput.current) codeInput.current.value = '';
  }, []);
  const invalidate = useCallback(() => { clientRef.current?.cancel(); setResult('IDLE'); }, []);

  useLayoutEffect(() => {
    mounted.current = true;
    let observed = store.getState().authentication;
    let hidden = document.visibilityState === 'hidden';
    let preserving = false;
    const client = createEmailConfirmation({
      enabled: () => {
        const current = store.getState().authentication;
        // Network ownership never returns with retained fields after a new authentication epoch.
        return mounted.current && connected && !hidden && document.visibilityState !== 'hidden'
          && current === observed && current.phase !== 'CHECKING' && current.phase !== 'TRANSITION'
          && current.phase !== 'AUTHENTICATED' && current.notice !== 'DEV_ONLY';
      },
      fetch: (path, init) => transport ? transport.fetch(path, init) : window.fetch(path, init),
      changed: next => { if (mounted.current) setResult(next); },
    });
    clientRef.current = client;
    function clear() {
      preserving = false;
      client.cancel();
      clearFields();
      if (mounted.current) setResult('IDLE');
    }
    clear();
    // Observe every Redux transition synchronously, including intermediate states React batches away.
    const unsubscribe = store.subscribe(() => {
      const next = store.getState().authentication;
      if (next === observed) return;
      const previous = observed;
      observed = next;
      const sameContext = previous.owner !== null && next.owner !== null
        && previous.owner.document === next.owner.document && previous.owner.revision === next.owner.revision;
      const checking = next.phase === 'CHECKING' && next.notice === 'NONE';
      const anonymous = next.phase === 'ANONYMOUS' && next.notice === 'SESSION_MISSING';
      const retain = connected && !hidden && document.visibilityState !== 'hidden' && sameContext
        && ((previous.phase === 'ANONYMOUS' && checking)
          || (preserving && previous.phase === 'CHECKING' && (checking || anonymous)));
      if (!retain) { clear(); return; }
      preserving = checking;
      // Only form data/outcome survives; cancel before any old CSRF work can advance to POST.
      const retained = client.pause();
      if (retained !== null) setResult(retained);
    });
    function suspend() {
      hidden = true;
      clear();
      setSuspended(true);
    }
    function resume() {
      // Returning restores only an empty form, never provider work or a previous result.
      if (document.visibilityState !== 'hidden') {
        hidden = false;
        clear();
        setSuspended(false);
      }
    }
    function visibility() { if (document.visibilityState === 'hidden') suspend(); else resume(); }
    window.addEventListener('pagehide', suspend);
    window.addEventListener('pageshow', resume);
    document.addEventListener('visibilitychange', visibility);
    return () => {
      mounted.current = false;
      unsubscribe();
      client.dispose();
      if (clientRef.current === client) clientRef.current = null;
      clearFields();
      window.removeEventListener('pagehide', suspend);
      window.removeEventListener('pageshow', resume);
      document.removeEventListener('visibilitychange', visibility);
    };
  }, [connected, runtime, transport, store, clearFields]);

  function canSubmit() {
    const current = store.getState().authentication;
    return connected && !suspended && document.visibilityState !== 'hidden'
      && current.phase !== 'CHECKING' && current.phase !== 'TRANSITION' && current.phase !== 'AUTHENTICATED';
  }
  function confirm(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canSubmit()) return;
    void clientRef.current?.confirm(emailInput.current?.value ?? '', codeInput.current?.value ?? '');
  }
  function resend() { if (canSubmit()) void clientRef.current?.resend(emailInput.current?.value ?? ''); }
  function cancel() { clearFields(); invalidate(); }
  const disabled = !eligible || suspended || result === 'PENDING';
  const message = result === 'IDLE' ? (!connected ? t.disconnected : phase === 'AUTHENTICATED' ? t.authenticated
    : !eligible ? t.wait : t.ready) : t.result[result];

  return <div className="two-column">
    <section className="page-intro"><p className="eyebrow">{t.eyebrow}</p><h1>{t.title}</h1><p className="lead">{t.lead}</p></section>
    <section className="panel">
      <h2>{t.formTitle}</h2><p className="small boundary-note">{t.boundary}</p>
      <form className="confirmation-form" onSubmit={confirm} autoComplete="off" noValidate>
        <label htmlFor="confirmation-email">{t.email}</label>
        {/* A text input avoids type=email silently trimming the entered identifier. */}
        <input id="confirmation-email" type="text" inputMode="email" autoComplete="off" autoCapitalize="none" spellCheck={false}
          maxLength={128} defaultValue="" ref={emailInput} onChange={invalidate} disabled={!eligible || suspended} />
        <label htmlFor="confirmation-code">{t.code}</label>
        <input id="confirmation-code" type="text" autoComplete="off" autoCapitalize="none" spellCheck={false}
          maxLength={2048} defaultValue="" ref={codeInput} onChange={invalidate} disabled={!eligible || suspended} />
        <div className="actions">
          <button className="button primary" type="submit" disabled={disabled}>{t.confirm}</button>
          <button className="button" type="button" disabled={disabled} onClick={resend}>{t.resend}</button>
          <button className="button" type="button" onClick={cancel}>{t.cancel}</button>
        </div>
      </form>
      <p className="boundary-note" role="status" aria-live="polite">{phase === 'CHECKING' && result !== 'IDLE' ? t.wait + ' ' : ''}{message}</p>
      <Link className="text-link" to="/login" onClick={cancel}>{t.backLogin}<span aria-hidden="true">→</span></Link>
      <p className="small boundary-note">{t.transient}</p>
    </section>
  </div>;
}
