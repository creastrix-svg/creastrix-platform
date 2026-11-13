import type { AccountCartLines } from './accountCartAdmission';
import type { AccountCartTransport } from './useAccountCartAccess';
import { localCartCommands, tokenId, type Binding, type Guard } from './localCartCommands';
import {isCatalogOfferHref} from '../routes';

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const invalid = (): never => { throw new Error('INVALID_ACCOUNT_CART_READ'); };
const record = (v: unknown): Record<string, unknown> =>
  v !== null && typeof v === 'object' && !Array.isArray(v) ? v as Record<string, unknown> : invalid();
const id = (v: unknown) => typeof v === 'string' && uuid.test(v) ? v : invalid();
// Import authority is separate from read authority. An absent/invalid intent
// grants no local claim; it does not erase an otherwise valid account cart.
const optionalIntent = (v:unknown) => typeof v==='string' && uuid.test(v) ? v : null;
const label = (v: unknown) => typeof v === 'string' && v.trim() && v.length <= 512 ? v : invalid();
const slug = (v: unknown) => typeof v === 'string' && v.length <= 200 && /^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(v) ? v : invalid();
const positive = (v: unknown) => typeof v === 'string' && /^[1-9][0-9]{0,18}$/.test(v) && BigInt(v) <= 9223372036854775807n ? v : invalid();
function parse(v: unknown) {
  const body = record(v);
  if (body.kind !== 'ready' || !Array.isArray(body.cart) || body.cart.length > 1000) return invalid();
  const seen = new Set<string>();
  const version=body.version===undefined?undefined:body.version==='0'?'0':positive(body.version);
  return { kind: 'ready' as const, ownerId: id(body.ownerId),...(version!==undefined?{version}:{}), cart: body.cart.map(value => {
    const line = record(value), lineId = id(line.lineId);
    if (seen.has(lineId) || typeof line.listed !== 'boolean' || (line.designLabel === null) !== (line.templateRevision === null)) return invalid();
    seen.add(lineId);
    if(line.swatch!==undefined && (typeof line.swatch!=='string' || !/^#[0-9a-f]{6}$/i.test(line.swatch))) return invalid();
    if(line.secondSwatch!=null && (!line.swatch || typeof line.secondSwatch!=='string' || !/^#[0-9a-f]{6}$/i.test(line.secondSwatch))) return invalid();
    if(line.href!=null && (typeof line.href!=='string' || !isCatalogOfferHref(line.href)))return invalid();
    if((line.designId!=null)!==(line.designRevision!=null))return invalid();
    if(line.removed!==undefined && typeof line.removed!=='boolean' || version!==undefined && typeof line.removed!=='boolean')return invalid();
    const display={lineId,offerLabel:label(line.offerLabel),variantLabel:label(line.variantLabel),
      designLabel:line.designLabel===null?null:label(line.designLabel),quantity:positive(line.quantity),
      ...(typeof line.removed==='boolean'?{removed:line.removed}:{})};
    // A historical pre-limit identity is not a valid navigation/write key. Its
    // bounded labels and quantity still belong in the buyer's cart. Discard the
    // oversize key rather than storing it or invalidating unrelated lines.
    if([line.offerId,line.variantId,line.sku].some(v=>typeof v==='string' && v.length>200)){
      return {...display,product:{listed:false,offerId:'',variantId:'',templateRevision:null}};
    }
    return { ...display,
      product: { offerId: slug(line.offerId), variantId: slug(line.variantId), listed: line.listed,
        ...(Object.hasOwn(line,'href')?{href:line.href==null?null:String(line.href)}:{}),
        ...(line.sku!=null?{sku:slug(line.sku)}:{}),...(line.studioFormat!=null?{studioFormat:slug(line.studioFormat)}:{}),
        ...(line.designId!=null?{designId:id(line.designId),designRevision:positive(line.designRevision)}:{}),
        templateRevision: line.templateRevision === null ? null : positive(line.templateRevision),
        ...(typeof line.swatch==='string'?{swatch:line.swatch}:{}), ...(typeof line.secondSwatch==='string'?{secondSwatch:line.secondSwatch}:{}) } };
  }) };
}

/** Without an explicit synthetic-lane guard this stays strictly read-only.
 * An arbitrary 409/network failure is not a known binding rollback. */
export function accountCartHttp(fetcher: typeof fetch, guard?:Guard) {
  const commands = guard ? localCartCommands(fetcher,guard) : null;
  let binding: Binding|null = null;
  let guestIntent:string|null = null;
  const transport:AccountCartTransport<AccountCartLines> = {
    async read(signal,expectedTicket) {
      binding = null;
      guestIntent = null;
      if (expectedTicket !== undefined && !guard) return {kind:'denied'};
      const deadline = AbortSignal.timeout(10000);
      const requestSignal = AbortSignal.any([signal, deadline]);
      function isReadFailure(error: unknown) {
        return !signal.aborted && (deadline.aborted || error instanceof TypeError
          || (error instanceof DOMException && (error.name === 'NetworkError' || error.name === 'TimeoutError')));
      }
      let response: Response;
      try {
        response = await fetcher('/api/account-cart', { method: 'GET', credentials: 'same-origin',
          signal: requestSignal, cache: 'no-store', redirect: 'error', headers: { Accept: 'application/json' } });
      } catch (error) {
        if (isReadFailure(error)) return { kind: 'read-unavailable' };
        throw error;
      }
      if (signal.aborted || (guard && !guard.owns())) return {kind:'denied'};
      if (response.status === 401 || response.status === 403) return { kind: 'denied' };
      if (response.redirected) return invalid();
      if (response.status === 503) return { kind: 'read-unavailable' };
      if ((response.status !== 200 && !(response.status === 409 && guard)) || response.headers.get('content-type')?.split(';')[0] !== 'application/json') return invalid();
      let body: string;
      try { body = await response.text(); }
      catch (error) {
        if (isReadFailure(error)) return { kind: 'read-unavailable' };
        throw error;
      }
      // A truncated/bad JSON document or invalid line is not a transient GET.
      if (signal.aborted || (guard && !guard.owns())) return {kind:'denied'};
      const data = record(JSON.parse(body));
      if (response.status === 409) {
        // A real account session can outlive its local shopping lane. This
        // refusal carries no binding ticket and never authorizes automatic recovery.
        if(guard && Object.keys(data).length===1 && data.error==='request_not_completed')return {kind:'reauth-required'};
        if (guard && data.kind === 'pending' && data.ownerId === guard.ownerId
            && (expectedTicket === undefined || expectedTicket === data.ticket)) {
          const ticket=tokenId(data.ticket);
          guestIntent = optionalIntent(data.guestIntent);
          return {kind:'pending',ownerId:guard.ownerId,ticket};
        }
        return {kind:'denied'};
      }
      const ready = parse(data);
      if (guard) {
        if (ready.ownerId !== guard.ownerId || (expectedTicket !== undefined && data.writeContext !== expectedTicket))
          return {kind:'denied'};
        binding = {ownerId:ready.ownerId,namespace:tokenId(data.namespace),writeContext:tokenId(data.writeContext)};
        guestIntent = optionalIntent(data.guestIntent);
        return { ...ready, binding,...(guestIntent?{guestIntent}:{}) };
      }
      return ready;
    },
    async recover(ticket,signal) { return commands ? commands.recover(ticket,signal) : 'denied'; },
  };
  return Object.assign(transport,{binding:() => binding,guestIntent:() => guestIntent});
}
