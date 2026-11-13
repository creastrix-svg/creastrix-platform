import { useEffect, useMemo, useReducer, useRef, useState } from 'react';
import type { PropsWithChildren, ReactNode } from 'react';
import { Link, useLocation } from 'react-router';
import { useAppDispatch, useAppSelector } from '../../../hooks';
import { cartHydrated } from '../../demoStore';
import { compositionReducer, initialStudioComposition } from '../design/studioComposition';
import { PersonalizationSession, usePersonalizationSession } from './personalizationSession';
import { StudioCreativeProvider } from './studioCreativeSession';
import { useStudioCreative } from './studioCreativeContext';
import { restoreStudio, serializeStudio, studioStorage, type UnavailableCartLine } from './studioStorage';
import { cartEditNote, quarantinedCartNote, studioCartCopy, unlistedCartNote } from '../../cart/studioCartCopy';
import { productCopy } from '../../copy';
import { useProductLocale } from '../../useProductLocale';
import { isEditionPath, isTideOfferPath } from '../../routes';
import { entryForPath } from '../../catalog/localCatalog';
import { guestPartition, quarantineGuestEdit, subscribeGuestPartition, versionGuestSave } from '../../cart/guestCartBatch';
import { permanentCatalogMode } from '../../catalog/permanentCatalog';

// Redux outlives the storefront on account routes. Keep its exact CAS
// baseline with that same lifetime; rereading another tab's record is not
// permission to publish our retained cart over it. The last bounded local
// snapshot also restores our own draft/font bytes, not the other tab's design.
// A full reload creates a new store and hydrates the current stored record.
type LocalCheckpoint = { expected: string | null; snapshot: string | null; editedUnlisted?: boolean; archiveId?:string; fenced?:boolean };
type SaveIntent = Readonly<{ value: string }>;
const checkpoints = new WeakMap<ReturnType<typeof useAppDispatch>, LocalCheckpoint>();

async function preserveEdit(owner:LocalCheckpoint) {
  const base=owner.expected,id=owner.archiveId??=crypto.randomUUID();
  // An edit can arrive while IDB is committing. Retain the last version of
  // this very checkpoint before disabling its UI; never grant it a new base.
  for (;;) {
    const local=owner.snapshot;
    if(local===null || local===base || owner.expected!==base) return;
    const moved=await quarantineGuestEdit(id,base,local);
    if(owner.expected!==base) return;
    if(owner.snapshot===local) { if(moved) owner.fenced=true; return; }
  }
}

type StudioGateProps = PropsWithChildren<{ browsingHome?: ReactNode; showGuestCartNotices?: boolean }>;
export default function PersistentStudio({ children, browsingHome, showGuestCartNotices }: StudioGateProps) {
  const [partition, changed] = useReducer((prior: number) => prior + 1, 0);
  useEffect(() => subscribeGuestPartition(changed), []);
  return <StudioPartition key={partition} browsingHome={browsingHome} showGuestCartNotices={showGuestCartNotices}>{children}</StudioPartition>;
}
function StudioPartition({ children, browsingHome, showGuestCartNotices }: StudioGateProps) {
  const [state, dispatch] = useReducer(compositionReducer, undefined, initialStudioComposition);
  return <PersonalizationSession.Provider value={{ state, dispatch }}><StudioCreativeProvider><PersistenceGate browsingHome={browsingHome} showGuestCartNotices={showGuestCartNotices}>{children}</PersistenceGate></StudioCreativeProvider></PersonalizationSession.Provider>;
}

function PersistenceGate({ children, browsingHome, showGuestCartNotices = true }: StudioGateProps) {
  const composition = usePersonalizationSession(), creative = useStudioCreative();
  const locale = useProductLocale(), location = useLocation();
  const editingCart = showGuestCartNotices && location.pathname === '/products/loop/personalize' && new URLSearchParams(location.search).has('edit');
  const demo = useAppSelector(state => state.productDemo), dispatch = useAppDispatch(), c = studioCartCopy[locale];
  const [phase, setPhase] = useState<'loading' | 'ready' | 'failed' | 'memory' | 'conflict'|'quarantined'>(() =>
    checkpoints.get(dispatch)?.fenced ? 'quarantined' : typeof indexedDB === 'undefined' ? permanentCatalogMode ? 'failed' : 'memory' : 'loading');
  const [result, setResult] = useState<{ intent: SaveIntent; status: 'saved' | 'failed' } | null>(null);
  const [unavailableLines, setUnavailableLines] = useState<UnavailableCartLine[]>([]);
  const [editedUnlisted, setEditedUnlisted] = useState(() => checkpoints.get(dispatch)?.editedUnlisted ?? false);
  const { cart, cartSerial } = demo, { drafts, applied, customFonts } = creative;
  const snapshot = useMemo(() => {
    if (phase !== 'ready') return null;
    try { return serializeStudio(composition.state, { drafts, applied, customFonts }, { cart, cartSerial }, unavailableLines); } catch { return null; }
  }, [phase, composition.state, drafts, applied, customFonts, cart, cartSerial, unavailableLines]);
  // Identical consecutive bytes need no write. Returning to A after B is a
  // new intent: neither the first A's success nor its failure belongs to it.
  const intent = useMemo(() => snapshot === null ? null : { value: snapshot }, [snapshot]);
  const status = !intent ? 'failed' : result?.intent === intent ? result.status : 'saving';
  const initial = useRef({ composition, creative, dispatch, cart, cartSerial, dirtyCart:cart.length>0 || demo.cartChange.revision>0 }),
    pending = useRef<SaveIntent | null>(null), writing = useRef(false);
  const mounted = useRef(false), checkpoint = useRef<LocalCheckpoint | null>(null);
  useEffect(() => {
    mounted.current = true;
    const controller = new AbortController(), start = initial.current;
    if (typeof indexedDB !== 'undefined') void studioStorage('read').then(async raw => {
      if (controller.signal.aborted) return;
      const retained = checkpoints.get(start.dispatch);
      if(retained?.fenced) { setPhase('quarantined'); return; }
      if(permanentCatalogMode && !retained && start.dirtyCart) {
        // A memory-only/older mount may leave Redux populated with no CAS
        // baseline at all. Never attach those bytes to the current partition.
        try {
          const local=serializeStudio(start.composition.state,start.creative,start);
          const archiveId=crypto.randomUUID();
          await quarantineGuestEdit(archiveId,null,local);
          checkpoints.set(start.dispatch,{expected:null,snapshot:local,archiveId,fenced:true});
          if(!controller.signal.aborted) setPhase('quarantined');
        } catch { if(!controller.signal.aborted) setPhase('conflict'); }
        return;
      }
      const repartitioned = !!retained && guestPartition(raw) !== guestPartition(retained.expected);
      if (repartitioned && retained.snapshot !== retained.expected) {
        try {
          await preserveEdit(retained);
          if (!controller.signal.aborted) setPhase(retained.fenced ? 'quarantined' : 'conflict');
        } catch {
          // Storage may still be unavailable. Keep the dirty checkpoint in
          // memory; do not allow a memory-only continuation to publish it.
          if (!controller.signal.aborted) setPhase('conflict');
        }
        return;
      }
      if (controller.signal.aborted) return;
      // Captured units now belong to an immutable pending batch. Never restore
      // them from retained Redux state or grant its old checkpoint a new baseline.
      const source = retained && !repartitioned ? retained.snapshot : raw;
      if (source !== null) {
        const restored = await restoreStudio(source, start.creative.fonts(), controller.signal);
        if (controller.signal.aborted) return;
        start.composition.dispatch({ type: 'restore', state: restored.composition });
        start.creative.restore?.(restored);
        start.dispatch(cartHydrated({ lines: restored.lines, serial: restored.serial, fontRemap: restored.fontRemap,
          ...(repartitioned ? { replaceCaptured: true } : {}) }));
        setUnavailableLines(restored.unavailableLines);
      }
      checkpoint.current = retained && !repartitioned ? retained : { expected: raw, snapshot: raw };
      checkpoints.set(start.dispatch, checkpoint.current);
      setPhase('ready');
    }).catch(() => {
      if (!controller.signal.aborted) {
        // Failed hydration never publishes partial state or reserves invisible
        // custom-font slots in an explicitly chosen memory-only continuation.
        start.creative.fonts().dispose(); setPhase('failed');
      }
    });
    return () => {
      controller.abort(); mounted.current = false;
      // The last queued synthetic save keeps its own original CAS. Let it
      // complete there or archive on conflict even if the route has unmounted.
      if(!permanentCatalogMode) pending.current = null;
    };
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
        while (pending.current !== null && !checkpoint.current!.fenced) {
          const attempt = pending.current; pending.current = null;
          const owner = checkpoint.current!;
          try {
            const stored = versionGuestSave(attempt.value, owner.expected);
            await studioStorage('write', stored, owner.expected);
            owner.expected = stored;
            owner.archiveId=undefined;
            if (owner.snapshot === attempt.value) owner.snapshot = stored;
            if (mounted.current) setResult({ intent: attempt, status: 'saved' });
          } catch {
            if(permanentCatalogMode) {
              try { await preserveEdit(owner); } catch { /* No durable claim; keep original checkpoint for remount. */ }
            }
            if (mounted.current) {
              if(owner.fenced) setPhase('quarantined');
              else setResult({ intent: attempt, status: 'failed' });
            }
          }
        }
      } finally { writing.current = false; }
    }
    void drain();
  }, [phase, intent]);
  // Reading a public landing page must not depend on local drafts. Only its
  // link-only cart is available until hydration, so it cannot mutate a stale cart.
  const publicHome = browsingHome !== undefined && (location.pathname === '/' ||
    (isEditionPath(location.pathname) && !isTideOfferPath(location.pathname) && entryForPath(location.pathname)?.kind !== 'offer'));
  if (!publicHome && phase === 'loading') return <p role="status" className="studio-local-status">{c.loading}</p>;
  if (phase === 'quarantined') return <p className="studio-local-status" role="status" data-guest-edit-quarantined>{quarantinedCartNote[locale]}</p>;
  if (phase === 'conflict') return showGuestCartNotices ? <p className="studio-local-status" role="alert">{c.saveFailed}</p> : <>{children}</>;
  if (!publicHome && phase === 'failed') return <section className="studio-local-status" role="alert"><p>{c.restoreFailed}</p>{!permanentCatalogMode && <button type="button" onClick={() => setPhase('memory')}>{c.memory}</button>}</section>;
  // Browsing needs no routine save announcements, but an editable cart preview
  // must still disclose a failed save, even after its last line is removed.
  const homeCartNotSaved = showGuestCartNotices && (demo.cart.length > 0 || demo.cartChange.revision > 0 || unavailableLines.length > 0 || editedUnlisted)
    && (phase === 'memory' || (phase === 'ready' && status === 'failed'));
  return <>{showGuestCartNotices && unavailableLines.length > 0 && <aside className="studio-local-status">
    <p>{unlistedCartNote[locale]}</p>
    {location.pathname.replace(/\/+$/, '') === '/preview/cart'
      ? <ul>{unavailableLines.map(({ variant, quantity }) => <li key={variant}>{variant} × {quantity}{' '}
        <button type="button" aria-label={productCopy[locale].remove + ' · ' + variant} onClick={() => {
          // Removing the last unlisted item leaves no active cart row to signal a failed write on Home.
          if (checkpoint.current) checkpoint.current.editedUnlisted = true;
          setEditedUnlisted(true);
          setUnavailableLines(lines => lines.filter(line => line.variant !== variant));
        }}>{productCopy[locale].remove}</button>
      </li>)}</ul>
      : <Link to="/preview/cart">{productCopy[locale].viewCart}</Link>}
  </aside>}{publicHome && (phase === 'loading' || phase === 'failed') ? browsingHome : children}{(!publicHome || homeCartNotSaved) && <p role="status" className="studio-local-status" data-local-save={phase === 'memory' ? 'unavailable' : status}>
    {phase === 'memory' || status === 'failed' ? c.saveFailed : editingCart ? cartEditNote[locale] : status === 'saving' ? c.saving : c.saved}
  </p>}</>;
}
