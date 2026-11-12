import { expect, it, vi } from 'vitest';
import { accountCartHttp } from './accountCartHttp';

const ownerId = '10000000-0000-4000-8000-000000000001';
const line = {lineId:'50000000-0000-4000-8000-000000000001',offerId:'kiln-cup',variantId:'blue',listed:false,
  offerLabel:'Retired cup',variantLabel:'Blue',quantity:'9223372036854775807',designLabel:'Original',templateRevision:'1'};
const response = (cart: unknown[]) => Response.json({kind:'ready',ownerId,cart});
it.each(['ready','pending'] as const)('exposes only the intent of this validated %s response and clears stale proof',async kind=>{
  const ticket=crypto.randomUUID(),intent=crypto.randomUUID(),other=crypto.randomUUID();
  const body=(who:string,proof:unknown)=>Response.json({kind,ownerId:who,guestIntent:proof,
    ...(kind==='ready'?{cart:[line],namespace:ticket,writeContext:ticket}:{ticket})},{status:kind==='ready'?200:409});
  const fetcher=vi.fn<typeof fetch>().mockResolvedValueOnce(body(ownerId,intent)).mockResolvedValueOnce(body(ownerId,'not a UUID'))
    .mockResolvedValueOnce(body(other,intent));
  const http=accountCartHttp(fetcher,{ownerId,owns:()=>true}),signal=new AbortController().signal;
  expect((await http.read(signal)).kind).toBe(kind);expect(http.guestIntent()).toBe(intent);
  expect((await http.read(signal)).kind).toBe(kind);expect(http.guestIntent()).toBeNull();
  expect((await http.read(signal)).kind).toBe('denied');expect(http.guestIntent()).toBeNull();
});
it('reads a private stable variant and its original template, preserving unavailable rows and int64 quantity', async () => {
  const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(response([line]));
  const http = accountCartHttp(fetcher);
  expect(await http.read(new AbortController().signal)).toMatchObject({kind:'ready',ownerId,cart:[{
    quantity:line.quantity,product:{listed:false,offerId:'kiln-cup',variantId:'blue',templateRevision:'1'},
  }]});
  expect(fetcher).toHaveBeenCalledExactlyOnceWith('/api/account-cart',expect.objectContaining({
    method:'GET', credentials:'same-origin', cache:'no-store', redirect:'error',
  }));
});
it.each([401,403,409,503])('does not manufacture a binding recovery command after status %s', async status => {
  const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(new Response(null,{status}));
  const http = accountCartHttp(fetcher);
  const result = http.read(new AbortController().signal);
  if (status === 401 || status === 403) await expect(result).resolves.toEqual({kind:'denied'});
  else if (status === 503) await expect(result).resolves.toEqual({kind:'read-unavailable'});
  else await expect(result).rejects.toThrow();
  await expect(http.recover('not-issued',new AbortController().signal)).resolves.toBe('denied');
  expect(fetcher).toHaveBeenCalledTimes(1);
});
it.each([{offerId:'../foreign'}, {quantity:'9223372036854775808'}, {templateRevision:null}, {listed:undefined}])(
  'rejects invalid account rows without presenting a partial or empty cart: %o', async delta => {
  await expect(accountCartHttp(async () => response([line,{...line,lineId:ownerId,...delta}]))
    .read(new AbortController().signal)).rejects.toThrow('INVALID_ACCOUNT_CART_READ');
});

it('preserves only validated finish swatches for a cold two-tone preview',async()=>{
  const signal=new AbortController().signal, colors={swatch:'#1542bb',secondSwatch:'#eeebe2'};
  await expect(accountCartHttp(async()=>response([{...line,...colors}])).read(signal)).resolves.toMatchObject({
    cart:[{product:colors}],
  });
  await expect(accountCartHttp(async()=>response([{...line,...colors,secondSwatch:'url(x)'}])).read(signal)).rejects.toThrow('INVALID_ACCOUNT_CART_READ');
});

it.each([new TypeError('fetch failed'), new DOMException('network', 'NetworkError'),
  new DOMException('deadline', 'TimeoutError')])('offers a GET-only retry for transport rejection %s', async failure => {
  const fetcher = vi.fn<typeof fetch>().mockRejectedValueOnce(failure);
  const result = await accountCartHttp(fetcher).read(new AbortController().signal);
  expect(result).toEqual({kind:'read-unavailable'});
  expect(fetcher).toHaveBeenCalledExactlyOnceWith('/api/account-cart', expect.objectContaining({method:'GET'}));
});
it('distinguishes a body read failure from invalid JSON in a fully received body', async () => {
  const interrupted = response([line]);
  vi.spyOn(interrupted,'text').mockRejectedValueOnce(new TypeError('body interrupted'));
  const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(interrupted)
    .mockResolvedValueOnce(new Response('{', {headers:{'content-type':'application/json'}}));
  const http = accountCartHttp(fetcher);
  await expect(http.read(new AbortController().signal)).resolves.toEqual({kind:'read-unavailable'});
  await expect(http.read(new AbortController().signal)).rejects.toBeInstanceOf(SyntaxError);
});
it('does not turn lifetime cancellation or an unexpected adapter failure into a retry', async () => {
  const fetcher = vi.fn<typeof fetch>().mockRejectedValueOnce(new TypeError('cancelled underlying read'))
    .mockRejectedValueOnce(new Error('unexpected bug'));
  const http = accountCartHttp(fetcher);
  await expect(http.read(AbortSignal.abort())).rejects.toThrow('cancelled underlying read');
  await expect(http.read(new AbortController().signal)).rejects.toThrow('unexpected bug');
});
