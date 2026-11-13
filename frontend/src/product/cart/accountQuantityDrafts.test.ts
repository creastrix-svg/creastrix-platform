import {expect,it} from 'vitest';
import {createAccountQuantityDrafts} from './accountQuantityDrafts';
import type {AuthenticationState} from '../../store';
import type {Owner} from '../../authBoundary';

const initial:Owner={document:'document-a',revision:'revision-a',epoch:1,request:1};
const next={...initial,epoch:2,request:2};
const observed=(phase:AuthenticationState['phase'],owner:Owner=initial,id='a'):AuthenticationState=>({
  phase,owner,user:phase==='AUTHENTICATED'?{id,status:'ACTIVE'}:null,notice:'NONE',
});
it('keeps only selected quantities per SKU across same-viewer passive revalidation; nothing is available to old handles',()=>{
  const drafts=createAccountQuantityDrafts();drafts.observe(observed('AUTHENTICATED'));
  const original=drafts.bind(initial,()=>true);original.set('blue',3);original.set('red',5);original.set('suede',99);
  drafts.observe(observed('CHECKING',next));expect(original.get('blue')).toBe(1);original.set('red',42);
  drafts.observe(observed('AUTHENTICATED',next));
  const after=drafts.bind(next,()=>true);expect(after.get('blue')).toBe(3);expect(after.get('red')).toBe(5);
  original.set('blue',1);expect(after.get('blue')).toBe(3);expect(original.get('red')).toBe(1);
  drafts.bind(next,()=>false).set('suede',1);expect(after.get('suede')).toBe(99);
  after.set('blue',1);expect(after.get('blue')).toBe(1);expect(after.get('red')).toBe(5);
  for(const bad of [-3,0,1.3,NaN,1_000_001])after.set('red',bad);
  expect(after.get('red')).toBe(5);
});
it.each(['denied','anonymous','transition','suppressed','unavailable','other user','new document','peer revision'] as const)(
  'forgets the original selection after %s, even if the same buyer comes back',cause=>{
    const drafts=createAccountQuantityDrafts();drafts.observe(observed('AUTHENTICATED'));
    const old=drafts.bind(initial,()=>true);old.set('blue',7);drafts.observe(observed('CHECKING',next));
    switch(cause){
      case 'other user':drafts.observe(observed('AUTHENTICATED',next,'b'));break;
      case 'new document':drafts.observe(observed('CHECKING',{...next,document:'other'}));break;
      case 'peer revision':drafts.observe(observed('CHECKING',{...next,revision:'other'}));break;
      default: drafts.observe(observed(({denied:'DENIED',anonymous:'ANONYMOUS',transition:'TRANSITION',
        suppressed:'RECOVERY_REQUIRED',unavailable:'UNAVAILABLE'} as const)[cause],next));
    }
    drafts.observe(observed('AUTHENTICATED',initial));const current=drafts.bind(initial,()=>true);
    expect(current.get('blue')).toBe(1);current.set('blue',2);old.set('blue',1);expect(old.get('blue')).toBe(1);
    expect(current.get('blue')).toBe(2);drafts.clear();expect(current.get('blue')).toBe(1);
  });
