import { sameOwner } from '../../authBoundary';
import type { Authentication } from '../../authentication';
import type { AppStore } from '../../store';
import { createAccountCartAccess, type AccountCartTransport } from './useAccountCartAccess';
import type { DirectCartAdditions } from './directCartAdditions';
import type { localCartCommands } from './localCartCommands';
import type { AccountQuantityDraft } from './accountQuantityDrafts';

/** Presentation from an admitted reader, not an import payload or a catalog lookup key.
 * Quantities are validated positive decimal strings: JSON numbers cannot carry all int64s. */
export type AccountCartLines = readonly Readonly<{
  lineId: string; offerLabel: string; variantLabel: string; designLabel: string | null; quantity: string; removed?: boolean;
  product?: Readonly<{ offerId: string; variantId: string; listed: boolean; templateRevision: string | null; swatch?:string;secondSwatch?:string;
    href?:string|null; sku?:string; studioFormat?:string; designId?:string;designRevision?:string }>;
}>[];

/** Intentionally has no default transport. The browser's auth owner invalidates
 * presentation; it cannot grant server read access or fence a business write. */
export function bindAccountCartReader(
  store: AppStore, transport: AccountCartTransport<AccountCartLines>, authRuntime: Pick<Authentication, 'isCurrentOwner'>,
  directAdditions?:DirectCartAdditions,
  editLine?:ReturnType<typeof localCartCommands>['editLine'],
  quantityDraft?:AccountQuantityDraft,
) {
  const { phase, owner, user } = store.getState().authentication;
  if (phase !== 'AUTHENTICATED' || !owner || !user || !authRuntime.isCurrentOwner(owner)) throw new Error('CART_ADMISSION_REQUIRED');
  const userId = user.id;
  const sameLocalAdmission = () => {
    const auth = store.getState().authentication;
    return auth.phase === 'AUTHENTICATED' && auth.user?.id === userId && sameOwner(auth.owner, owner);
  };
  // Read the live marker at each I/O boundary. A store subscription alone
  // sees a peer transition only after the browser delivers its storage event.
  const access = createAccountCartAccess(userId, () => sameLocalAdmission() && authRuntime.isCurrentOwner(owner), transport);
  const unsubscribe = store.subscribe(() => { if (!sameLocalAdmission()) dispose(); });
  function dispose() { unsubscribe(); access.retire(); }
  return { access, userId, owner, dispose, directAdditions, editLine, quantityDraft };
}

export type AccountCartAdmission = ReturnType<typeof bindAccountCartReader>;
