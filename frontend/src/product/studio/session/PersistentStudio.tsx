import { useEffect, useMemo, useReducer, useRef, useState } from 'react';
import type { PropsWithChildren, ReactNode } from 'react';
import { useLocation } from 'react-router';
import { useAppDispatch, useAppSelector } from '../../../hooks';
import { cartHydrated } from '../../demoStore';
import { compositionReducer, initialStudioComposition } from '../design/studioComposition';
import { PersonalizationSession, usePersonalizationSession } from './personalizationSession';
import { StudioCreativeProvider } from './studioCreativeSession';
import { useStudioCreative } from './studioCreativeContext';
import { restoreStudio, serializeStudio, studioStorage } from './studioStorage';
import { cartEditNote, studioCartCopy } from '../../cart/studioCartCopy';
import { useProductLocale } from '../../useProductLocale';
import { isEditionPath, isTideOfferPath } from '../../routes';

// Redux outlives the storefront on account routes. Keep its exact CAS
// baseline with that same lifetime; rereading another tab's record is not
// permission to publish our retained cart over it. The last bounded local
// snapshot also restores our own draft/font bytes, not the other tab's design.
// A full reload creates a new store and hydrates the current stored record.
type LocalCheckpoint = { expected: string | null; snapshot: string | null };
type SaveIntent = Readonly<{ value: string }>;
const checkpoints = new WeakMap<ReturnType<typeof useAppDispatch>, LocalCheckpoint>();

type StudioGateProps = PropsWithChildren<{ browsingHome?: ReactNode }>;
export default function PersistentStudio({ children, browsingHome }: StudioGateProps) {
  const [state, dispatch] = useReducer(compositionReducer, undefined, initialStudioComposition);
  return <PersonalizationSession.Provider value={{ state, dispatch }}><StudioCreativeProvider><PersistenceGate browsingHome={browsingHome}>{children}</PersistenceGate></StudioCreativeProvider></PersonalizationSession.Provider>;
}

function PersistenceGate({ children, browsingHome }: StudioGateProps) {
  const composition = usePersonalizationSession(), creative = useStudioCreative();
  const locale = useProductLocale(), location = useLocation();
  const editingCart = location.pathname === '/products/loop/personalize' && new URLSearchParams(location.search).has('edit');
  const demo = useAppSelector(state => state.productDemo), dispatch = useAppDispatch(), c = studioCartCopy[locale];
  const [phase, setPhase] = useState<'loading' | 'ready' | 'failed' | 'memory'>(() => typeof indexedDB === 'undefined' ? 'memory' : 'loading');
  const [result, setResult] = useState<{ intent: SaveIntent; status: 'saved' | 'failed' } | null>(null);
  const { cart, cartSerial } = demo, { drafts, applied, customFonts } = creative;
  const snapshot = useMemo(() => {
    if (phase !== 'ready') return null;
    try { return serializeStudio(composition.state, { drafts, applied, customFonts }, { cart, cartSerial }); } catch { return null; }
  }, [phase, composition.state, drafts, applied, customFonts, cart, cartSerial]);
  // Identical consecutive bytes need no write. Returning to A after B is a
  // new intent: neither the first A's success nor its failure belongs to it.
  const intent = useMemo(() => snapshot === null ? null : { value: snapshot }, [snapshot]);
  const status = !intent ? 'failed' : result?.intent === intent ? result.status : 'saving';
  const initial = useRef({ composition, creative, dispatch }), pending = useRef<SaveIntent | null>(null), writing = useRef(false);
  const mounted = useRef(false), checkpoint = useRef<LocalCheckpoint | null>(null);
  useEffect(() => {
    mounted.current = true;
    const controller = new AbortController(), start = initial.current;
    if (typeof indexedDB !== 'undefined') void studioStorage('read').then(async raw => {
      if (controller.signal.aborted) return;
      const retained = checkpoints.get(start.dispatch);
      const source = retained ? retained.snapshot : raw;
      if (source !== null) {
        const restored = await restoreStudio(source, start.creative.fonts(), controller.signal);
        if (controller.signal.aborted) return;
        start.composition.dispatch({ type: 'restore', state: restored.composition });
        start.creative.restore?.(restored);
        start.dispatch(cartHydrated({ lines: restored.lines, serial: restored.serial, fontRemap: restored.fontRemap }));
      }
      checkpoint.current = retained ?? { expected: raw, snapshot: raw };
      checkpoints.set(start.dispatch, checkpoint.current);
      setPhase('ready');
    }).catch(() => {
      if (!controller.signal.aborted) {
        // Failed hydration never publishes partial state or reserves invisible
        // custom-font slots in an explicitly chosen memory-only continuation.
        start.creative.fonts().dispose(); setPhase('failed');
      }
    });
    return () => { controller.abort(); mounted.current = false; pending.current = null; };
  }, []);
  useEffect(() => {
    if (phase !== 'ready' || !checkpoint.current) return;
    pending.current = intent;
    if (!intent) return;
    checkpoint.current.snapshot = intent.value;
    async function drain() {
      if (writing.current) return;
      writing.current = true;
      try {
        while (pending.current !== null) {
          const attempt = pending.current; pending.current = null;
          try {
            const owner = checkpoint.current!;
            await studioStorage('write', attempt.value, owner.expected); owner.expected = attempt.value;
            if (mounted.current) setResult({ intent: attempt, status: 'saved' });
          } catch { if (mounted.current) setResult({ intent: attempt, status: 'failed' }); }
        }
      } finally { writing.current = false; }
    }
    void drain();
  }, [phase, intent]);
  // Reading a public landing page must not depend on local drafts. Only its
  // link-only cart is available until hydration, so it cannot mutate a stale cart.
  const publicHome = browsingHome !== undefined && (location.pathname === '/' ||
    (isEditionPath(location.pathname) && !isTideOfferPath(location.pathname)));
  if (!publicHome && phase === 'loading') return <p role="status" className="studio-local-status">{c.loading}</p>;
  if (!publicHome && phase === 'failed') return <section className="studio-local-status" role="alert"><p>{c.restoreFailed}</p><button type="button" onClick={() => setPhase('memory')}>{c.memory}</button></section>;
  // Browsing needs no routine save announcements, but an editable cart preview
  // must still disclose a failed save, even after its last line is removed.
  const homeCartNotSaved = (demo.cart.length > 0 || demo.cartChange.revision > 0)
    && (phase === 'memory' || (phase === 'ready' && status === 'failed'));
  return <>{publicHome && (phase === 'loading' || phase === 'failed') ? browsingHome : children}{(!publicHome || homeCartNotSaved) && <p role="status" className="studio-local-status" data-local-save={phase === 'memory' ? 'unavailable' : status}>
    {phase === 'memory' || status === 'failed' ? c.saveFailed : editingCart ? cartEditNote[locale] : status === 'saving' ? c.saving : c.saved}
  </p>}</>;
}
