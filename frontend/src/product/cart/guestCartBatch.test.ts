import {guestStorageFixture} from '../../test/guestStorageFixture';
import { afterEach, expect, it, vi } from 'vitest';
import { ackGuestBatch, claimGuestCart, freezeGuestBatch, hasClaimedGuestWork, quarantineGuestEdit, retainRetiredBatch, sealGuestCart, versionGuestSave } from './guestCartBatch';
import { authenticationFinished, authenticationStarted, createAppStore } from '../../store';
import { createLocalCartSession } from './localCartSession';
import { studioStorage } from '../studio/session/studioStorage';
import { serializeStudio } from '../studio/session/studioStorage';
import { initialStudioComposition } from '../studio/design/studioComposition';
import { defaultLettering } from '../studio/design/personalization';
import { readFileSync } from 'node:fs';
import { guestCartTransfer, type TransferPhase } from './guestCartTransfer';

const catalogRevision=vi.hoisted(()=>({value:'2',retained:false}));
vi.mock('../catalog/permanentCatalog', () => ({ permanentCatalogMode: true, sharedCatalogMode:false }));
vi.mock('../catalog/localCatalog', async original => ({
  ...await original<typeof import('../catalog/localCatalog')>(),
  connectedSku: (sku:string) => sku==='kiln-blue' ? {product:{id:'kiln-cup'},variant:{id:'blue',sku}}
    : sku==='cobalt' ? {product:{id:'loop',personalizable:catalogRevision.value==='2',templateRevision:catalogRevision.value},
      variant:{id:sku,sku,...(catalogRevision.retained?{retainedLoopV2FeeCents:625}:{})}} : undefined,
  loadLocalCatalog: vi.fn(async () => undefined),
}));
const who='19f9cd9f-7d2a-4d19-90e6-85a9760cf135', ns='10000000-0000-4000-a000-000000000001';
const other='10000000-0000-4000-a000-000000000002', yes=()=>true;
const row={variant:'kiln-blue',quantity:2};
const snapshot=(lines:unknown[])=>JSON.stringify({version:1,lines,drafts:{work:'not cart data'},fonts:['private font'],assets:['private image'],serial:3});

function storage(initial:string,{legacy=false,onAdd=()=>{}}:{legacy?:boolean;onAdd?:(key:unknown)=>void}={}) {
  return guestStorageFixture([['"current"',legacy ? initial : versionGuestSave(initial,null)]],onAdd);
}
afterEach(()=>vi.unstubAllGlobals());
async function approvedLogin(ownerId:string) {
  const intent=await sealGuestCart(yes);
  expect(intent).not.toBeNull();
  return claimGuestCart(ownerId,intent!,yes);
}
it('partitions once, binds only its intended owner, preserves new guest additions and never copies drafts',async()=>{
  const db=storage(snapshot([row]));
  expect(await hasClaimedGuestWork(who,yes)).toBe(false);
  const [intent,otherIntent]=await Promise.all([sealGuestCart(yes),sealGuestCart(yes)]);
  expect(intent).not.toBeNull();expect(otherIntent).toBeNull();
  expect(await Promise.all([claimGuestCart(who,intent!,yes),claimGuestCart(who,intent!,yes)])).toEqual([true,false]);
  expect(await hasClaimedGuestWork(who,yes)).toBe(true);
  const [a,b]=await Promise.all([freezeGuestBatch(who,ns,yes),freezeGuestBatch(who,ns,yes)]);
  expect(a).not.toBeNull();expect(a).toEqual(b);
  expect(a!.lines).toEqual([{kind:'plain',sku:'kiln-blue',offerId:'kiln-cup',variantId:'blue',quantity:'2'}]);
  expect(JSON.stringify(a)).not.toContain('private font');
  const guest=JSON.parse(db.get('"current"') as string);
  expect(guest).toMatchObject({lines:[],drafts:{},fonts:[]});
  expect([...db].find(([k])=>k.startsWith('["guest-studio-archive-v1"'))?.[1]).toContain('private font');
  expect(await hasClaimedGuestWork(who,yes)).toBe(true);
  expect(await hasClaimedGuestWork(other,yes)).toBe(false);
  expect(await freezeGuestBatch(other,other,yes)).toBeNull();
  await expect(freezeGuestBatch(who,other,yes)).rejects.toThrow('GUEST_CART_NAMESPACE');
  const later=versionGuestSave(snapshot([{...row,quantity:1}]),JSON.stringify(guest));
  db.set('"current"',later);
  await expect(ackGuestBatch(a!,{...a!,state:'APPLIED',ownerId:other},yes)).rejects.toThrow('GUEST_CART_ACK_MISMATCH');
  expect(await freezeGuestBatch(who,ns,yes)).toEqual(a);
  await ackGuestBatch(a!,{...a!,state:'APPLIED'},yes);
  expect(db.get('"current"')).toBe(later);
  expect(await freezeGuestBatch(who,ns,yes)).toBeNull();
  await approvedLogin(who);
  const next=await freezeGuestBatch(who,ns,yes);
  expect(next!.batchId).not.toBe(a!.batchId);
  expect(next!.lines[0].quantity).toBe('1');
  await ackGuestBatch(a!,{...a!,state:'APPLIED'},yes);
  expect(await freezeGuestBatch(who,ns,yes)).toEqual(next);
});
it.each([{...row,id:'design-1',design:{objects:[{kind:'image',value:{image:{asset:0}}}]}}, {...row,variant:'unlisted'}, {...row,quantity:-1}])(
  'retains the entire mixed contribution for its intended buyer with no partial freeze: %o',async invalid=>{
    const raw=snapshot([row,invalid]),db=storage(raw);
    await approvedLogin(who);
    const before=[...db.entries()];
    const held=before.find(([k])=>k.startsWith('["guest-cart-claim-v1"'))![1];
    expect(held).toMatchObject({ownerId:who,lines:[row,invalid]});
    if('design' in invalid) expect(held).toMatchObject({assets:['private image'],fonts:[]});
    expect(held).not.toHaveProperty('drafts');
    expect(await hasClaimedGuestWork(other,yes)).toBe(false);
    expect(await freezeGuestBatch(other,other,yes)).toBeNull();
    await expect(freezeGuestBatch(who,ns,yes)).rejects.toThrow('GUEST_CART_UNSUPPORTED');
    expect([...db.entries()]).toEqual(before);
  });
it('does not accept a stale admission, and corrupt pending data must never be downgraded to plain',async()=>{
  const db=storage(snapshot([row]));
  await expect(sealGuestCart(()=>false)).rejects.toThrow('GUEST_CART_ADMISSION_CHANGED');
  const intent=(await sealGuestCart(yes))!;
  await expect(claimGuestCart(who,intent,()=>false)).rejects.toThrow('GUEST_CART_ADMISSION_CHANGED');
  await claimGuestCart(who,intent,yes);
  const batch=(await freezeGuestBatch(who,ns,yes))!;
  db.set(JSON.stringify(['guest-cart-batch-v1',who]),{...batch,lines:[{...batch.lines[0],designId:'unrecognised'}]});
  await expect(freezeGuestBatch(who,ns,yes)).rejects.toThrow('GUEST_CART_OUTBOX_INVALID');
  await expect(ackGuestBatch(batch,{...batch,state:'APPLIED'},yes)).rejects.toThrow();
  expect(db.size).toBe(3); // current, exact archived creative work and corrupt outbox
});
it('does not repeat a raw CAS token after A→B→A, and retains its partition identity',()=>{
  const a=versionGuestSave(snapshot([row]),null);
  const partition=crypto.randomUUID();
  const b=versionGuestSave(snapshot([]),JSON.stringify({...JSON.parse(a),guestPartition:partition}));
  const next=versionGuestSave(snapshot([row]),b);
  expect(JSON.parse(next).guestPartition).toBe(partition);
  expect(JSON.parse(next).guestWrite).not.toBe(JSON.parse(a).guestWrite);
  expect(JSON.parse(b).guestWrite).not.toBe(JSON.parse(next).guestWrite);
});
it('retains only the matched terminal not-applied batch privately, preserving other designs and later additions',async()=>{
  const db=storage(snapshot([row]));
  await approvedLogin(who);
  const batch=(await freezeGuestBatch(who,ns,yes))!;
  const prior=db.get('"current"') as string;
  const otherLine={variant:'legacy',id:'design-4',quantity:3,design:{objects:[1]}};
  const active=versionGuestSave(snapshot([{...row,quantity:1},otherLine]),prior);
  db.set('"current"',active);
  await expect(retainRetiredBatch(batch,{...batch,state:'RETIRED',namespace:other},yes)).rejects.toThrow('GUEST_CART_ACK_MISMATCH');
  expect(db.get('"current"')).toBe(active);
  expect(await retainRetiredBatch(batch,{...batch,state:'RETIRED'},yes)).toBe(true);
  expect(db.get(JSON.stringify(['guest-cart-retired-v1',who,batch.batchId]))).toEqual({state:'RETIRED',batch});
  expect(db.get('"current"')).toBe(active);
  expect(await hasClaimedGuestWork(other,yes)).toBe(false);
  expect(await hasClaimedGuestWork(who,yes)).toBe(false);
  expect(await retainRetiredBatch(batch,{...batch,state:'RETIRED'},yes)).toBe(false);
});
it.each(['addition','reduction','design'] as const)('keeps a pre-seal unsaved %s outside both buyers even after B adds genuine units',async edit=>{
  const db=storage(snapshot([row])),base=db.get('"current"') as string;
  const mine=snapshot(edit==='addition' ? [{...row,quantity:3}] : edit==='reduction' ? [] :
    [{...row,id:'design-3',design:{objects:[{value:{image:{asset:0}}}]}}]);
  const conflict=crypto.randomUUID(),key=JSON.stringify(['guest-cart-edit-conflict-v1',conflict]);
  expect(await quarantineGuestEdit(conflict,base,mine)).toBe(false); // same-partition save previously failed
  const intentA=(await sealGuestCart(yes))!;
  const empty=db.get('"current"') as string;
  await expect(studioStorage('write',versionGuestSave(mine,base),base)).rejects.toThrow();
  expect(await quarantineGuestEdit(conflict,base,mine)).toBe(true);
  expect(db.get('"current"')).toBe(empty);
  expect(await sealGuestCart(yes)).toBeNull();
  const latest=db.get('"current"') as string;
  const genuine=versionGuestSave(snapshot([{...row,quantity:4}]),latest);
  await studioStorage('write',genuine,latest);
  expect(await quarantineGuestEdit(conflict,base,mine)).toBe(true);
  expect(db.get('"current"')).toBe(genuine);expect(db.get(key)).toEqual({base,snapshot:mine});
  await approvedLogin(other);
  expect((await freezeGuestBatch(other,other,yes))!.lines[0].quantity).toBe('4');
  await claimGuestCart(who,intentA,yes);
  expect((await freezeGuestBatch(who,ns,yes))!.lines[0].quantity).toBe('2');
  expect(db.get(key)).toEqual({base,snapshot:mine});
});
it('fences a stale null CAS even if there was no current record at sign-in',async()=>{
  const db=storage(snapshot([]));db.delete('"current"');
  const mine=snapshot([row]),key=crypto.randomUUID();
  expect(await sealGuestCart(yes)).toBeNull();
  const active=db.get('"current"') as string;
  expect(JSON.parse(active)).toMatchObject({lines:[],guestPartition:expect.any(String)});
  await expect(studioStorage('write',versionGuestSave(mine,null),null)).rejects.toThrow();
  expect(await quarantineGuestEdit(key,null,mine)).toBe(true);
  expect(db.get('"current"')).toBe(active);
  expect(db.get(JSON.stringify(['guest-cart-edit-conflict-v1',key]))).toEqual({base:null,snapshot:mine});
});

it.each(['rollback','failed-read','in-flight','first-local-write'] as const)(
  'isolates the original login through %s, lets B read and import only genuine B units, and never guesses on a fresh A login',async fault=>{
    let blockClaim=fault==='first-local-write';
    const db=storage(snapshot([row]),{onAdd:key=>{
      if(blockClaim && Array.isArray(key) && key[0]==='guest-cart-claim-v1') throw Error('quota');
    }});
    const firstIntent=(await sealGuestCart(yes))!;
    const sealedKey=JSON.stringify(['guest-cart-intent-v1',firstIntent]), sealed=db.get(sealedKey);
    const active=()=>JSON.parse(db.get('"current"') as string);
    let current=who, ready=false, reads=0, recoveries=0;
    let guestIntent:string|null=firstIntent;
    let unblock!: (response:Response)=>void;
    const delayed=new Promise<Response>(resolve=>{unblock=resolve;});
    const prepared:{ownerId:string;batchId:string;quantity:string}[]=[];
    const applied:string[]=[];
    const readyResponse=(ownerId:string)=>Response.json({kind:'ready',ownerId,namespace:ownerId===who ? ns : other,writeContext:ns,guestIntent,cart:[]});
    const http:typeof fetch=async (input,init)=>{
      const path=String(input);
      if(path==='/api/account-cart') {
        ++reads;
        if(current===who && !ready) {
          if(fault==='in-flight') return delayed;
          if(fault==='failed-read') throw new TypeError('offline');
          if(fault==='rollback') return Response.json({kind:'pending',ownerId:who,ticket:ns,guestIntent},{status:409});
        }
        return readyResponse(current);
      }
      if(path==='/auth/csrf') return Response.json({headerName:'X-CSRF-TOKEN',parameterName:'_csrf',token:'test'});
      if(path==='/api/account-cart/recover') {
        ++recoveries; ready=true; return Response.json({kind:'recovered'});
      }
      const match=path.match(/^\/api\/account-cart\/imports\/([^/]+)\/(status|prepare|apply)$/);
      if(!match) throw new Error('Unexpected request');
      const [,batchId,action]=match;
      if(action==='status') return Response.json({state:'UNKNOWN',version:null});
      if(action==='prepare') {
        const {lines}=JSON.parse(String(init?.body));
        expect(lines).toEqual([{sku:'kiln-blue',quantity:expect.any(String),designId:null,designRevision:null}]);
        prepared.push({ownerId:current,batchId,quantity:lines[0].quantity});
        return Response.json({state:'PENDING',version:null});
      }
      applied.push(batchId);
      return Response.json({state:'APPLIED',version:'1'});
    };
    function documentSession() {
      const store=createAppStore('en',vi.fn());
      const session=createLocalCartSession(store,{isCurrentOwner:yes},http);
      const authenticate=(ownerId:string|null)=>{
        const owner={document:'test-page',epoch:1,request:1,revision:crypto.randomUUID()};
        store.dispatch(authenticationStarted({owner,phase:'CHECKING',notice:'NONE'}));
        store.dispatch(authenticationFinished({owner,result:ownerId
          ? {phase:'AUTHENTICATED',user:{id:ownerId,status:'ACTIVE'},notice:'NONE'}
          : {phase:'ANONYMOUS',user:null,notice:'NONE'}}));
      };
      return {session,authenticate};
    }
    const first=documentSession();
    first.authenticate(who);
    first.session.getSnapshot()!.access.start(); // A simultaneous cart mount must wait for the checkpoint too.
    await vi.waitFor(()=>expect(reads).toBeGreaterThan(0));
    expect(active().lines).toEqual([]);
    if(fault==='rollback') {
      await vi.waitFor(()=>expect(first.session.getSnapshot()!.access.getSnapshot().phase).toBe('ready'));
      expect(recoveries).toBe(1);
      await vi.waitFor(()=>expect(first.session.getTransferSnapshot()).toBe('binding')); // Recovery alone never imports.
    } else if(fault!=='in-flight') {
      await vi.waitFor(()=>expect(first.session.getTransferSnapshot()).toBe('pending'));
    }
    if(fault==='first-local-write') {
      await vi.waitFor(()=>expect(first.session.getSnapshot()!.access.getSnapshot().phase).toBe('ready'));
    }
    if(fault!=='rollback') expect(db.get(sealedKey)).toEqual(sealed);
    else expect(db.get(JSON.stringify(['guest-cart-claim-v1',who,firstIntent]))).toMatchObject({ownerId:who,lines:[row]});
    expect(prepared).toEqual([]);
    first.authenticate(null);
    expect(first.session.getTransferSnapshot()).toBe('idle');
    first.session.dispose(); // discard all in-memory ownership from A's document
    blockClaim=false;
    current=other;
    guestIntent=null;
    const second=documentSession();
    second.authenticate(other);
    second.session.getSnapshot()!.access.start();
    unblock(readyResponse(who)); // ignored if the old admission already ended
    await vi.waitFor(()=>expect(second.session.getSnapshot()!.access.getSnapshot().phase).toBe('ready'));
    expect(prepared).toEqual([]);
    if(fault!=='rollback') expect(db.get(sealedKey)).toEqual(sealed);
    second.authenticate(null);
    db.set('"current"',versionGuestSave(snapshot([{...row,quantity:1}]),db.get('"current"') as string));
    guestIntent=await sealGuestCart(yes);
    second.authenticate(other);
    await vi.waitFor(()=>expect(second.session.getTransferSnapshot()).toBe('applied'));
    expect(prepared).toEqual([{ownerId:other,batchId:guestIntent,quantity:'1'}]);
    expect(prepared[0].batchId).not.toBe(firstIntent);
    expect(applied).toEqual([prepared[0].batchId]);
    expect(await hasClaimedGuestWork(who,yes)).toBe(fault==='rollback');
    expect(await hasClaimedGuestWork(other,yes)).toBe(false);
    second.authenticate(null);
    second.session.dispose();

    const reload=documentSession();
    current=who; ready=true; guestIntent=null;
    reload.authenticate(who);
    reload.session.getSnapshot()!.access.start();
    // Like the mounted React subscriber, also start the post-ack handle.
    await vi.waitFor(()=>{
      const display=reload.session.getSnapshot()!.access;
      display.start();expect(display.getSnapshot().phase).toBe('ready');
    });
    if(fault==='rollback') {
      await vi.waitFor(()=>expect(reload.session.getTransferSnapshot()).toBe('applied'));
      expect(prepared[1]).toEqual({ownerId:who,batchId:firstIntent,quantity:'2'});
      expect(applied).toEqual([prepared[0].batchId,firstIntent]);
    } else {
      expect(prepared).toHaveLength(1);
      expect(db.get(sealedKey)).toEqual(sealed);
    }
    expect(await hasClaimedGuestWork(who,yes)).toBe(false);
    expect(active()).toMatchObject({lines:[],assets:[],fonts:[]});
    reload.session.dispose();
  });

it('preserves an already-namespaced v1 receipt and independently claims fresh units for this sign-in',async()=>{
  const db=storage(snapshot([{...row,quantity:3}]));
  const legacy={ownerId:who,namespace:ns,batchId:crypto.randomUUID(),lines:[
    {kind:'plain',sku:'kiln-blue',offerId:'kiln-cup',variantId:'blue',quantity:'2'},
  ]} as const;
  db.set(JSON.stringify(['guest-cart-batch-v1',who]),legacy);
  await approvedLogin(who);
  expect(await hasClaimedGuestWork(other,yes)).toBe(false);
  expect(await freezeGuestBatch(who,ns,yes)).toEqual(legacy);
  await ackGuestBatch(legacy,{...legacy,state:'APPLIED'},yes);
  const fresh=await freezeGuestBatch(who,ns,yes);
  expect(fresh!.batchId).not.toBe(legacy.batchId);
  expect(fresh!.lines[0].quantity).toBe('3');
});

it('rolls back the full seal on failure; only a fresh guest login may retry it',async()=>{
  let fail=true;
  const db=storage(snapshot([row]),{onAdd:()=>{if(fail) throw Error('quota');}});
  const before=[...db];
  await expect(sealGuestCart(yes)).rejects.toThrow();
  expect([...db]).toEqual(before);
  fail=false;
  expect(await sealGuestCart(yes)).not.toBeNull();
  const empty=db.get('"current"') as string;
  expect(await sealGuestCart(yes)).toBeNull();
  const next=JSON.parse(db.get('"current"') as string);
  expect(next.guestPartition).not.toBe(JSON.parse(empty).guestPartition);
  expect(next.guestWrite).not.toBe(JSON.parse(empty).guestWrite);
});

it('recovers an unbound seal only with its original login proof, keeping its batch ID through the first failed owner write',async()=>{
  let fail=true;
  const db=storage(snapshot([row]),{onAdd:key=>{
    if(fail && Array.isArray(key) && key[0]==='guest-cart-claim-v1') throw Error('unavailable');
  }});
  const id=(await sealGuestCart(yes))!,before=[...db];
  await expect(claimGuestCart(who,id,yes)).rejects.toThrow();
  expect([...db]).toEqual(before);
  expect(await claimGuestCart(other,crypto.randomUUID(),yes)).toBe(false);
  expect(await freezeGuestBatch(other,other,yes)).toBeNull();
  fail=false;
  // Same original session's private read continues to echo this exact ID.
  expect(await claimGuestCart(who,id,yes)).toBe(true);
  expect((await freezeGuestBatch(who,ns,yes))!.batchId).toBe(id);
  expect(await claimGuestCart(who,id,yes)).toBe(false);
  expect(JSON.parse(db.get('"current"') as string).lines).toEqual([]);
});

it.each(['hydrate','login'] as const)('quarantines exact legacy mixed rows, assets and drafts before %s; a later guest edit never blesses them',async path=>{
  const mixed=snapshot([row,{...row,id:'design-2',design:{objects:[{value:{image:{asset:0}}}]}}]);
  const legacy=JSON.stringify({...JSON.parse(mixed),guestPartition:ns,guestWrite:crypto.randomUUID()});
  const db=storage(legacy,{legacy:true});
  localStorage.setItem('creastrix.guest-cart.handoff.v1.'+who+'.old','1');
  localStorage.setItem('creastrix.guest-cart.handoff.v1.'+other+'.old','1');
  expect(()=>versionGuestSave(snapshot([{...row,quantity:3}]),legacy)).toThrow('GUEST_CART_UNPROVEN');
  if(path==='login') expect(await sealGuestCart(yes)).toBeNull();
  else expect(JSON.parse((await studioStorage('read'))!).lines).toEqual([]);
  const current=db.get('"current"') as string;
  expect(JSON.parse(current)).toMatchObject({lines:[],drafts:{},assets:[],fonts:[]});
  const quarantined=[...db].filter(([k])=>k.startsWith('["guest-cart-quarantine-v1"'));
  expect(quarantined).toHaveLength(1);expect(quarantined[0][1]).toBe(legacy);
  expect(await quarantineGuestEdit(crypto.randomUUID(),legacy,snapshot([{...row,quantity:3}]))).toBe(true);
  expect(db.get('"current"')).toBe(current);
  db.set('"current"',versionGuestSave(snapshot([{...row,quantity:1}]),current));
  await approvedLogin(other);
  const batch=await freezeGuestBatch(other,other,yes);
  expect(batch!.lines).toEqual([{kind:'plain',sku:'kiln-blue',offerId:'kiln-cup',variantId:'blue',quantity:'1'}]);
  expect(await hasClaimedGuestWork(who,yes)).toBe(false);
  expect(db.get(quarantined[0][0])).toBe(legacy);
});

const image='iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jmpkAAAAASUVORK5CYII=';
const face=readFileSync('public/fonts/AllertaStencil.ttf').toString('base64');
function personalCart() {
  const design={objects:[{kind:'text',id:'studio-object-1', value:{...defaultLettering('cobalt'),text:'ALICE',font:'custom-1',x:233.25}},
    {kind:'image',id:'studio-object-9',fit:'cover',value:{size:85,x:240,y:278,image:{aspect:1,asset:1}}}]};
  return {version:1,serial:3,fonts:[{id:'custom-1',label:'private filename',resource:{name:'font.ttf',data:face}},
    {id:'custom-2',label:'unrelated font',resource:{name:'font.ttf',data:'unrelated bytes'}}],
    assets:['unrelated image','data:image/png;base64,'+image],drafts:{coral:'unrelated draft'},applied:{cobalt:design},
    lines:[row,{variant:'cobalt',quantity:1,templateRevision:'2',id:'design-2',design}]};
}
it('freezes only applied layers and exact resources; the next guest has neither draft nor artwork',async()=>{
  const raw=JSON.stringify(personalCart()),db=storage(raw);
  const original=db.get('"current"'),intent=(await sealGuestCart(yes))!;
  const held=db.get(JSON.stringify(['guest-cart-intent-v1',intent]));
  for(const term of ['unrelated','private filename']) expect(JSON.stringify(held)).not.toContain(term);
  expect([...db].find(([k])=>k.startsWith('["guest-studio-archive-v1"'))?.[1]).toBe(original);
  expect(JSON.parse(db.get('"current"') as string)).toMatchObject({lines:[],applied:{},drafts:{},assets:[],fonts:[]});
  expect(await freezeGuestBatch(other,other,yes)).toBeNull();
  await claimGuestCart(who,intent,yes);
  const batch=(await freezeGuestBatch(who,ns,yes))!;
  const line=batch.lines[1]; if(line.kind!=='design') throw Error('expected design');
  expect(line.design).toEqual({sku:'cobalt',zone:'surface',templateRevision:'2',objects:[
    {kind:'text',text:'ALICE',font:'resource',data:face,color:'#ffffff',x:233.25,y:278,size:24},
    {kind:'image',fit:'cover',data:image,x:240,y:278,size:85}]});
  expect(await freezeGuestBatch(who,ns,yes)).toEqual(batch);
  expect(JSON.stringify(batch)).not.toMatch(/unrelated|private filename|custom-1|studio-object/);
});
it.each(['lost-font','unsupported-font','os-font','missing-image','different-template'] as const)('keeps all lines of a %s contribution recoverable',async fault=>{
  const cart=personalCart();
  if(fault==='lost-font') cart.fonts.shift();
  if(fault==='unsupported-font') cart.fonts[0].resource={name:'font.woff2',data:'d09GMgABAAAAAAAA'};
  if(fault==='os-font') Object.assign(cart.applied.cobalt.objects[0].value,{font:'sans'});
  if(fault==='missing-image') cart.assets.pop();
  if(fault==='different-template') Object.assign(cart.lines[1],{templateRevision:'1'});
  const db=storage(JSON.stringify(cart)); await approvedLogin(who); const before=[...db];
  await expect(freezeGuestBatch(who,ns,yes)).rejects.toThrow();
  expect([...db]).toEqual(before);
});
it('does not archive untouched default studio layers on an empty sign-in',async()=>{
  const raw=serializeStudio(initialStudioComposition(),{drafts:{},applied:{},customFonts:[]},{cart:[],cartSerial:1});
  const db=storage(raw);
  expect(await sealGuestCart(yes)).toBeNull(); expect(await sealGuestCart(yes)).toBeNull();
  expect([...db.keys()]).toEqual(['"current"']);
});

it('replays a lost stage by its frozen ID; a lost apply checks the original receipt without staging again',async()=>{
  storage(JSON.stringify(personalCart()));
  const guestIntent=await sealGuestCart(yes);
  let stagedId:string|undefined, stageCalls=0,applied=0;
  let status='UNKNOWN';
  const requests:string[]=[],http:typeof fetch=async(url,options)=>{
    const path=String(url); requests.push(path);
    if(path==='/api/account-cart') return Response.json({kind:'ready',ownerId:who,cart:[],namespace:ns,writeContext:ns,guestIntent});
    if(path==='/auth/csrf') return Response.json({parameterName:'_csrf',headerName:'X-CSRF-TOKEN',token:'fixed'});
    if(path.endsWith('/status')) return Response.json({state:status,version:status==='APPLIED'?'1':null});
    expect(options?.method).toBe('POST');
    expect(options?.headers).toMatchObject({'X-CSRF-TOKEN':'fixed','X-Cart-Namespace':ns,'X-Cart-Context':ns});
    if(path.startsWith('/api/account-cart/designs/')) {
      const id=path.split('/').at(-1)!;stageCalls++;
      if(stagedId!==undefined) expect(id).toBe(stagedId); else stagedId=id;
      expect(JSON.parse(String(options?.body)).objects[0]).toMatchObject({font:'resource',data:face});
      expect(String(options?.body)).not.toMatch(/unrelated|filename|custom-1|drafts|studio-object/);
      if(stageCalls===1) throw new TypeError('stage reply lost');
      return Response.json({designId:id,designRevision:'1'});
    }
    if(path.endsWith('/prepare')) {
      expect(JSON.parse(String(options?.body)).lines).toEqual([
        {sku:'kiln-blue',quantity:'2',designId:null,designRevision:null},
        {sku:'cobalt',quantity:'1',designId:stagedId,designRevision:'1'},
      ]);
      status='PENDING'; return Response.json({state:status,version:null});
    }
    if(path.endsWith('/apply')) { applied++;status='APPLIED';throw new TypeError('apply reply lost'); }
    throw Error('unexpected request');
  };
  const phases:TransferPhase[]=[],refresh=vi.fn();
  const task=guestCartTransfer(http,{ownerId:who,owns:yes},v=>phases.push(v),refresh);
  await task.run();expect(phases.at(-1)).toBe('pending');expect(await hasClaimedGuestWork(who,yes)).toBe(true);
  await task.run();expect(phases.at(-1)).toBe('pending');expect(await hasClaimedGuestWork(who,yes)).toBe(true);
  await task.run();expect(phases.at(-1)).toBe('applied');expect(await hasClaimedGuestWork(who,yes)).toBe(false);
  expect(stageCalls).toBe(2);expect(applied).toBe(1);expect(refresh).toHaveBeenCalledOnce();
  expect(requests.filter(p=>p.endsWith('prepare'))).toEqual(['/api/account-cart/imports/'+guestIntent+'/prepare']);
  task.dispose();
});

it('imports the original v2 composition under its proven login after v3; no authority keeps the whole claim unchanged',async()=>{
  const raw=JSON.stringify(personalCart()), disk=storage(raw);
  catalogRevision.value='3';
  try {
    await approvedLogin(who);
    const before=[...disk.entries()];
    await expect(freezeGuestBatch(who,ns,yes)).rejects.toThrow('GUEST_CART_UNSUPPORTED');
    expect([...disk.entries()]).toEqual(before);
    expect(await freezeGuestBatch(other,other,yes)).toBeNull();
    catalogRevision.retained=true;
    const batch=(await freezeGuestBatch(who,ns,yes))!, line=batch.lines[1];
    if(line.kind!=='design') throw new Error('Expected the pinned design');
    expect(line.design).toMatchObject({sku:'cobalt',templateRevision:'2',objects:[
      {kind:'text',text:'ALICE',data:face},{kind:'image',data:image,fit:'cover'},
    ]});
    expect(await freezeGuestBatch(who,ns,yes)).toEqual(batch);
    await ackGuestBatch(batch,{...batch,state:'APPLIED'},yes);
    expect(await hasClaimedGuestWork(who,yes)).toBe(false);
  } finally { catalogRevision.value='2';catalogRevision.retained=false; }
});
