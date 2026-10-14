import { useCallback, useContext, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
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
  const { phase, notice, owner } = useAppSelector(state => state.authentication);
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
  const available = useRef(false);
  const transportRef = useRef(transport);
  const [suspended, setSuspended] = useState(document.visibilityState === 'hidden');
  const boundary = useMemo(() => ({ eligible, phase, notice, owner, suspended, transport }),
    [eligible, phase, notice, owner, suspended, transport]);
  const boundaryRef = useRef(boundary);
  const [outcome, setOutcome] = useState({ result: 'IDLE' as ConfirmationResult, boundary });
  // A changed account/lifecycle boundary hides even previously completed results during that render.
  const result = outcome.boundary === boundary ? outcome.result : 'IDLE';
  const clientRef = useRef<ReturnType<typeof createEmailConfirmation> | null>(null);
  const clearFields = useCallback(() => {
    // DOM clearing is synchronous even when React scheduling or bfcache pauses a commit.
    if (emailInput.current) emailInput.current.value = '';
    if (codeInput.current) codeInput.current.value = '';
  }, []);
  const invalidate = useCallback(() => { clientRef.current?.cancel(); setOutcome({ result: 'IDLE', boundary: boundaryRef.current }); }, []);

  useLayoutEffect(() => {
    mounted.current = true;
    const client = createEmailConfirmation({
      enabled: () => {
        const current = store.getState().authentication;
        const expected = boundaryRef.current;
        // Redux can change before React commits; an old form must not issue another network step then.
        return mounted.current && available.current && document.visibilityState !== 'hidden'
          && current.owner === expected.owner && current.phase === expected.phase && current.notice === expected.notice;
      },
      fetch: (path, init) => transportRef.current ? transportRef.current.fetch(path, init) : window.fetch(path, init),
      changed: next => { if (mounted.current) setOutcome({ result: next, boundary: boundaryRef.current }); },
    });
    clientRef.current = client;
    return () => {
      mounted.current = false;
      available.current = false;
      client.dispose();
      if (clientRef.current === client) clientRef.current = null;
      clearFields();
    };
  }, [store, clearFields]);
  useLayoutEffect(() => {
    transportRef.current = transport;
    boundaryRef.current = boundary;
    available.current = eligible && !suspended;
    clientRef.current?.cancel();
    clearFields();
  }, [boundary, eligible, suspended, transport, clearFields]);
  useEffect(() => {
    function suspend() {
      available.current = false;
      clearFields();
      invalidate();
      setSuspended(true);
    }
    function resume() {
      // Returning restores only an empty form, never provider work or a previous result.
      if (document.visibilityState !== 'hidden') {
        clearFields();
        invalidate();
        available.current = boundaryRef.current.eligible;
        setSuspended(false);
      }
    }
    function visibility() { if (document.visibilityState === 'hidden') suspend(); else resume(); }
    window.addEventListener('pagehide', suspend);
    window.addEventListener('pageshow', resume);
    document.addEventListener('visibilitychange', visibility);
    return () => {
      window.removeEventListener('pagehide', suspend);
      window.removeEventListener('pageshow', resume);
      document.removeEventListener('visibilitychange', visibility);
    };
  }, [clearFields, invalidate]);

  function confirm(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void clientRef.current?.confirm(emailInput.current?.value ?? '', codeInput.current?.value ?? '');
  }
  function resend() { void clientRef.current?.resend(emailInput.current?.value ?? ''); }
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
      <p className="boundary-note" role="status" aria-live="polite">{message}</p>
      <Link className="text-link" to="/login" onClick={cancel}>{t.backLogin}<span aria-hidden="true">→</span></Link>
      <p className="small boundary-note">{t.transient}</p>
    </section>
  </div>;
}
