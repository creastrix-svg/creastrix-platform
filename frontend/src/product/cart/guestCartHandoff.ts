// Only an empty/new guest partition can acquire this provenance. In older
// builds even a UUID guestWrite/guestPartition could belong to a buyer whose
// very first local owner marker failed. Never infer ownership from those IDs.
export const guestFormat = 'sealed-login-v1';
export function emptyGuestPartition() {
  return JSON.stringify({version:1,serial:1,lines:[],drafts:{},applied:{},creativeDrafts:{},creativeApplied:{},assets:[],fonts:[],
    guestFormat,guestPartition:crypto.randomUUID(),guestWrite:crypto.randomUUID()});
}

/** Run before guest hydration and before native sign-in, in the SAME write
 * transaction as reading current. Preserve the exact old snapshot without
 * looking up a buyer or trusting either a single or competing v1 markers.
 * No normal reader enumerates quarantine records. Guest drafts stay local. */
export function isolateLegacyCart(raw:string, store:IDBObjectStore, limit:number):string {
  if(raw.length>limit) throw new Error('GUEST_CART_UNSUPPORTED');
  const data=JSON.parse(raw);
  if (!data || data.version!==1 || !Array.isArray(data.lines)) throw new Error('GUEST_CART_UNSUPPORTED');
  if (data.guestFormat===guestFormat || !data.lines.length) return raw;
  const next=emptyGuestPartition();
  if(next.length>limit) throw new Error('GUEST_CART_UNSUPPORTED');
  store.add(raw,['guest-cart-quarantine-v1',crypto.randomUUID()]);
  store.put(next,'current');
  return next;
}
