import {afterEach,expect,it,vi} from 'vitest';
import {guestStorageFixture} from '../../test/guestStorageFixture';
import {claimGuestCart,sealGuestCart} from '../cart/guestCartBatch';
import {emptyGuestPartition} from '../cart/guestCartHandoff';
import {guestFavoriteKey,guestFavoriteList,setGuestFavorite,subscribeGuestFavorites,syncGuestFavorites} from './guestFavoriteHandoff';
import type {GuestFavoriteBatch} from './guestFavoriteHandoff';

vi.mock('../catalog/permanentCatalog',()=>({permanentCatalogMode:true,sharedCatalogMode:true}));
const always=()=>true, a=crypto.randomUUID(),b=crypto.randomUUID();
const sealedKey=(intent:string)=>JSON.stringify(['guest-favorites-login-v1',intent]);
afterEach(()=>{localStorage.clear();vi.unstubAllGlobals();});

it('starts a new store without reading, importing or erasing existing unattributed hearts',async()=>{
  guestStorageFixture();localStorage.setItem('creastrix.product.loop.favorites.v1.cobalt','1');
  const guest=await guestFavoriteList();expect(guest.skus).toEqual([]);
  expect(await sealGuestCart(always)).toBeNull();
  await expect(setGuestFavorite('cobalt',true,always,guest.partition)).rejects.toThrow();
  expect(localStorage.getItem('creastrix.product.loop.favorites.v1.cobalt')).toBe('1');
});
it('serializes independent tab edits; removing a colour never drops another',async()=>{
  guestStorageFixture();const {partition}=await guestFavoriteList(),notify=vi.fn();
  const stop=subscribeGuestFavorites(notify);
  await Promise.all([setGuestFavorite('cobalt',true,always,partition),setGuestFavorite('chalk',true,always,partition)]);
  expect((await guestFavoriteList()).skus).toEqual(['chalk','cobalt']);
  await setGuestFavorite('chalk',true,always,partition);expect(notify).toHaveBeenCalledTimes(2);
  await setGuestFavorite('cobalt',false,always,partition);stop();
  expect((await guestFavoriteList()).skus).toEqual(['chalk']);
});
it('binds hearts and cart to the same design-login proof in one rotation',async()=>{
  const db=guestStorageFixture(),cart=JSON.parse(emptyGuestPartition()),proof=crypto.randomUUID();
  cart.lines=[{variant:'tide-cobalt',quantity:2}];db.set('"current"',JSON.stringify(cart));
  const {partition}=await guestFavoriteList();await setGuestFavorite('cobalt',true,always,partition);
  expect(await sealGuestCart(always,proof)).toBe(proof);
  expect(await claimGuestCart(a,proof,always)).toBe(true);
  const accept=vi.fn(async()=>{});await syncGuestFavorites(a,proof,always,accept);
  expect(accept).toHaveBeenCalledExactlyOnceWith({id:proof,skus:['cobalt']});
  expect(db.has(sealedKey(proof))).toBe(false);expect(await guestFavoriteList()).not.toEqual({partition,skus:[]});
});
it('rolls back both cart and hearts if the held cart cannot be written',async()=>{
  const db=guestStorageFixture([],key=>{if(Array.isArray(key)&&key[0]==='guest-cart-intent-v1')throw Error('quota');});
  const cart=JSON.parse(emptyGuestPartition());cart.lines=[{variant:'tide-cobalt',quantity:2}];
  db.set('"current"',JSON.stringify(cart));const {partition}=await guestFavoriteList();
  await setGuestFavorite('cobalt',true,always,partition);const before=[...db];
  await expect(sealGuestCart(always)).rejects.toThrow();expect([...db]).toEqual(before);
});
it('detaches hearts once on an empty-cart login; next buyer and stale guest tab never pick up a missing proof',async()=>{
  const db=guestStorageFixture(),{partition}=await guestFavoriteList();
  await setGuestFavorite('chalk',true,always,partition);
  const [first,second]=await Promise.all([sealGuestCart(always),sealGuestCart(always)]);
  expect(first).not.toBeNull();expect(second).toBeNull();expect(await claimGuestCart(a,first!,always)).toBe(false);
  const send=vi.fn(async()=>{});await syncGuestFavorites(b,null,always,send);
  await syncGuestFavorites(b,crypto.randomUUID(),always,send);
  expect(send).not.toHaveBeenCalled();expect(db.has(sealedKey(first!))).toBe(true);
  await expect(setGuestFavorite('cobalt',true,always,partition)).rejects.toThrow();
  const fresh=await guestFavoriteList();expect(fresh.skus).toEqual([]);
  await setGuestFavorite('tide-cobalt',true,always,fresh.partition);
  const newer=await sealGuestCart(always);await syncGuestFavorites(b,newer,always,send);
  expect(send).toHaveBeenCalledExactlyOnceWith({id:newer,skus:['tide-cobalt']});
});
it('persists the owner before sending, pins the same payload on lost ACK, and does not replay after success',async()=>{
  const db=guestStorageFixture(),{partition}=await guestFavoriteList();
  await setGuestFavorite('cobalt',true,always,partition);const proof=(await sealGuestCart(always))!;
  const expected={id:proof,skus:['cobalt']},failed=vi.fn(async()=>{throw Error('lost');});
  await expect(syncGuestFavorites(a,proof,always,failed)).rejects.toThrow('lost');
  expect(db.get(sealedKey(proof))).toEqual({...expected,ownerId:a});
  const success=vi.fn(async()=>{});await expect(syncGuestFavorites(b,proof,always,success)).rejects.toThrow();
  expect(success).not.toHaveBeenCalled();await syncGuestFavorites(a,proof,always,success);
  expect(success).toHaveBeenCalledExactlyOnceWith(expected);await syncGuestFavorites(a,proof,always,success);
  expect(success).toHaveBeenCalledTimes(1);
});
it('keeps the owned outbox after a successful server response reaches a retired UI',async()=>{
  const db=guestStorageFixture(),{partition}=await guestFavoriteList();
  await setGuestFavorite('chalk',true,always,partition);const proof=(await sealGuestCart(always))!;
  let active=true;
  await expect(syncGuestFavorites(a,proof,()=>active,async(b:GuestFavoriteBatch)=>{
    expect(b.id).toBe(proof);active=false;
  })).rejects.toThrow();
  expect(db.get(sealedKey(proof))).toEqual({id:proof,skus:['chalk'],ownerId:a});
});
it('keeps a terminally refused import for its owner and never sends it again on a new mount',async()=>{
  const db=guestStorageFixture(),{partition}=await guestFavoriteList();
  await setGuestFavorite('cobalt',true,always,partition);
  const proof=(await sealGuestCart(always))!,refuse=vi.fn(async()=>'terminal' as const);
  expect(await syncGuestFavorites(a,proof,always,refuse)).toBe('terminal');
  const saved={id:proof,ownerId:a,skus:['cobalt'],terminal:true};
  expect(db.get(sealedKey(proof))).toEqual(saved);
  const subsequent=vi.fn(async()=>{});
  expect(await syncGuestFavorites(a,proof,always,subsequent)).toBe('terminal');
  await expect(syncGuestFavorites(b,proof,always,subsequent)).rejects.toThrow();
  expect(subsequent).not.toHaveBeenCalled();expect(db.get(sealedKey(proof))).toEqual(saved);
});
it('preserves invalid or oversized local data instead of claiming or overwriting it',async()=>{
  const unknown={partition:crypto.randomUUID(),skus:['invalid sku']},raw=JSON.stringify(guestFavoriteKey);
  const db=guestStorageFixture([[raw,unknown]]);await expect(guestFavoriteList()).rejects.toThrow();
  await expect(sealGuestCart(always)).rejects.toThrow();expect(db.get(raw)).toEqual(unknown);
  db.set(raw,{...unknown,skus:Array.from({length:1000},(_,i)=>'key-'+String(i).padStart(4,'0'))});
  await expect(setGuestFavorite('new-number',true,always,unknown.partition)).rejects.toThrow();
  expect((await guestFavoriteList()).skus).toHaveLength(1000);
});
