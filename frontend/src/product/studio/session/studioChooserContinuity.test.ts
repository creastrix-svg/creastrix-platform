import {afterEach,describe,expect,it,vi} from 'vitest';
import {createStudioChooserContinuity} from './studioChooserContinuity';
import type {AuthenticationState} from '../../../store';
import type {AccountCartAdmission} from '../../cart/accountCartAdmission';

function fixture(user:string|null){
  const controller=new AbortController(),revision='first',doc='this-document';
  let index=0;
  function state(phase:AuthenticationState['phase'],id:string|null,nonce=revision,document=doc):AuthenticationState{
    return {phase,owner:{document,revision:nonce,epoch:++index,request:index},notice:'NONE',user:id?{id,status:'ACTIVE'}:null};
  }
  let now=state(user?'AUTHENTICATED':'ANONYMOUS',user);
  const admission=user?{userId:user} as AccountCartAdmission:null;
  const continuity=createStudioChooserContinuity(()=>now,o=>o===now.owner),initial=continuity.key();
  const ready=continuity.chooser.open(controller.signal)!;
  function move(phase:AuthenticationState['phase'],id:string|null=null,nonce=revision,document=doc){
    now=state(phase,id,nonce,document);continuity.transition(now,admission);
  }
  return {controller,continuity,initial,ready,move,admission};
}
afterEach(()=>vi.useRealTimers());
describe('native studio chooser through one passive focus check',()=>{
  it.each([null,'buyer-a'])('keeps a local draft, not network ownership, after checking the same viewer (%s)',async id=>{
    const f=fixture(id);try{
      f.move('CHECKING');expect(f.continuity.held()).toEqual({account:f.admission,key:f.initial});
      const apply=vi.fn();const finished=f.ready().then(apply);await Promise.resolve();expect(apply).not.toHaveBeenCalled();
      f.move(id?'AUTHENTICATED':'ANONYMOUS',id);await finished;
      expect(apply).toHaveBeenCalledWith(true);expect(f.continuity.held()).toBeNull();expect(f.continuity.key()).toBe(f.initial);
      f.controller.abort();
      f.move('CHECKING');expect(f.continuity.held()).toBeNull();
    }finally{f.continuity.dispose();}
  });
  it.each(['different user','anonymous','denied','unknown','peer','other document','suspend','pagehide'] as const)(
    'never releases the old file through %s, even if the original user later returns',async boundary=>{
      const f=fixture('buyer-a');try{
        f.move('CHECKING');const waiting=f.ready();
        switch(boundary){
          case 'different user': f.move('AUTHENTICATED','buyer-b');break;
          case 'anonymous': f.move('ANONYMOUS');break;
          case 'denied': f.move('DENIED');break;
          case 'unknown': f.move('UNAVAILABLE');break;
          case 'peer': f.move('AUTHENTICATED','buyer-a','another-nonce');break;
          case 'other document': f.move('AUTHENTICATED','buyer-a','first','other-document');break;
          case 'suspend': f.move('RECOVERY_REQUIRED');break;
          case 'pagehide': window.dispatchEvent(new Event('pagehide'));break;
        }
        f.move('AUTHENTICATED','buyer-a');expect(await waiting).toBe(false);expect(f.continuity.held()).toBeNull();expect(f.continuity.key()).not.toBe(f.initial);
      }finally{f.continuity.dispose();}
    });
  it('allows change-then-focus ordering only within the short native handoff',()=>{
    vi.useFakeTimers();const f=fixture(null);
    try{f.controller.abort();vi.advanceTimersByTime(2000);f.move('CHECKING');expect(f.continuity.held()).toBeNull();}
    finally{f.continuity.dispose();}
  });
});
