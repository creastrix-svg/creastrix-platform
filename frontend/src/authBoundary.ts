export const AUTH_BOUNDARY_KEY = 'creastrix.auth.boundary';

export type Owner = Readonly<{ document: string; epoch: number; request: number; revision: string }>;
export type BoundaryReason = 'PEER_CHANGED' | 'STORAGE_UNAVAILABLE' | 'SUSPENDED' | 'DISPOSED';
type Marker = { nonce: string; phase: 'checking' | 'transition' };
export type BoundaryStorage = Pick<Storage, 'getItem' | 'setItem'>;

export function sameOwner(a: Owner | null, b: Owner): boolean {
  return a !== null && a.document === b.document && a.epoch === b.epoch
    && a.request === b.request && a.revision === b.revision;
}

function marker(value: string | null): Marker | null {
  if (value === null) return null;
  if (value.length > 256) throw new Error('AUTH_STORAGE_UNAVAILABLE');
  const parsed: unknown = JSON.parse(value);
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) throw new Error('AUTH_STORAGE_UNAVAILABLE');
  const record = parsed as Record<string, unknown>;
  if (Object.keys(record).length !== 2 || typeof record.nonce !== 'string'
      || !/^[A-Za-z0-9_-]{1,80}$/.test(record.nonce)
      || typeof record.phase !== 'string' || !['checking', 'transition'].includes(record.phase)) throw new Error('AUTH_STORAGE_UNAVAILABLE');
  return record as Marker;
}

/** A UI invalidation signal, not a cookie identity, permission, lock or global login order. */
export function createAuthBoundary(options: {
  storage: () => BoundaryStorage;
  nonce: () => string;
  closePrivate: () => void;
  invalidated: (owner: Owner, reason: BoundaryReason) => void;
}) {
  const document = options.nonce();
  let epoch = 0;
  let request = 0;
  let owner: Owner | null = null;
  let revision: string | null = null;
  let disposed = false;

  function invalidate(reason: BoundaryReason, nextRevision = '') {
    options.closePrivate();
    owner = { document, epoch: ++epoch, request: ++request, revision: nextRevision };
    revision = nextRevision || null;
    options.invalidated(owner, reason);
  }

  function read() { return marker(options.storage().getItem(AUTH_BOUNDARY_KEY)); }

  function begin(phase: Marker['phase'], announce = false): Owner | null {
    if (disposed) return null;
    options.closePrivate();
    epoch++;
    try {
      let current = read();
      if (announce) {
        current = { nonce: options.nonce(), phase };
        // Parse our own representation too: an unavailable nonce source cannot weaken the gate.
        marker(JSON.stringify(current));
        options.storage().setItem(AUTH_BOUNDARY_KEY, JSON.stringify(current));
      }
      if (current === null) throw new Error('AUTH_STORAGE_UNAVAILABLE');
      revision = current.nonce;
      owner = { document, epoch, request: ++request, revision };
      return owner;
    } catch {
      invalidate('STORAGE_UNAVAILABLE');
      return null;
    }
  }

  function current(candidate: Owner): boolean {
    // Check local ownership first: an old completion must not even diagnose current storage.
    if (disposed || !sameOwner(owner, candidate)) return false;
    try {
      const live = read();
      if (live === null) throw new Error('AUTH_STORAGE_UNAVAILABLE');
      if (live.nonce !== candidate.revision) {
        invalidate('PEER_CHANGED', live.nonce);
        return false;
      }
      return true;
    } catch {
      invalidate('STORAGE_UNAVAILABLE');
      return false;
    }
  }

  function reconcile() {
    if (disposed) return;
    try {
      const live = read();
      if (live === null) throw new Error('AUTH_STORAGE_UNAVAILABLE');
      // Events can be late/out of order; their payload is never adopted. No automatic peer fetch.
      if (live.nonce !== revision) invalidate('PEER_CHANGED', live.nonce);
    } catch { invalidate('STORAGE_UNAVAILABLE'); }
  }

  return {
    begin, current, reconcile,
    suspend() { if (!disposed) invalidate('SUSPENDED', revision ?? ''); },
    dispose() { if (!disposed) { invalidate('DISPOSED'); disposed = true; } },
  };
}
