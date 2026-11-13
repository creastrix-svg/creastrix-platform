import type {AuthenticationState} from '../../../store';
import type {Owner} from '../../../authBoundary';
import type {AccountCartAdmission} from '../../cart/accountCartAdmission';
import type {StudioChooser} from './studioChooserContext';

type Scope={user:string|null;document:string;revision:string;url:string};
type Ticket={scope:Scope;signal:AbortSignal;waiting:boolean;checked:boolean;
  listeners:Set<()=>void>;stop:()=>void};

/** One document, one visible native chooser, no storage. Observe every Redux auth
 * transition synchronously: batching A→denied→A must never revive the old editor.
 * Only its local UI survives a passive CHECKING; old I/O handles are retired by
 * the cart session as usual. Hide/pagehide, navigation or a changed owner erase it. */
export function createStudioChooserContinuity(current:()=>AuthenticationState,owns:(owner:Owner)=>boolean){
  let previous=current(), admissionKey=key(previous),ticket:Ticket|null=null;
  let held:{account:AccountCartAdmission|null;key:string}|null=null;
  function key(s:AuthenticationState){return s.owner?[s.phase,s.user?.id??'',s.owner.document,s.owner.epoch,s.owner.request,s.owner.revision].join(':'):'initial';}
  function principal(s:AuthenticationState){return s.phase==='AUTHENTICATED'&&s.user?s.user.id:s.phase==='ANONYMOUS'?null:undefined;}
  function matches(t:Ticket,s:AuthenticationState){return !!s.owner&&s.owner.document===t.scope.document&&s.owner.revision===t.scope.revision
    && location.href===t.scope.url && document.visibilityState!=='hidden';}
  function wake(t:Ticket){for(const f of t.listeners)f();t.listeners.clear();}
  function clear(){const old=ticket;ticket=null;held=null;if(old){old.stop();wake(old);}}
  function transition(s:AuthenticationState,oldAdmission:AccountCartAdmission|null){
    if(s===previous)return;
    const before=previous;previous=s;
    if(ticket && matches(ticket,s)){
      if(s.phase==='CHECKING' && !ticket.checked && !ticket.waiting && principal(before)===ticket.scope.user){
        ticket.waiting=true;
        held={account:oldAdmission,key:admissionKey};return;
      }
      if(ticket.waiting && principal(s)===ticket.scope.user){
        held=null;ticket.waiting=false;ticket.checked=true;wake(ticket);
        if(ticket.signal.aborted)clear();
        return;
      }
    }
    clear();admissionKey=key(s);
  }
  const chooser:StudioChooser={open(signal){
    clear();const s=current(),user=principal(s);
    if(signal.aborted||user===undefined||!s.owner||!owns(s.owner)||document.visibilityState==='hidden')return null;
    const lifecycle=new AbortController(),options={capture:true,signal:lifecycle.signal};
    let grace:ReturnType<typeof setTimeout>|undefined;
    const t:Ticket={scope:{user,document:s.owner.document,revision:s.owner.revision,url:location.href},
      signal,waiting:false,checked:false,listeners:new Set(),stop:()=>{clearTimeout(grace);lifecycle.abort();}};
    ticket=t;
    const forget=()=>{if(ticket===t)clear();};
    window.addEventListener('pagehide',forget,options);window.addEventListener('pageshow',forget,options);
    document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='hidden')forget();},options);
    // Some browsers deliver change/cancel just before focus. Allow one passive
    // check in that short interval, without retaining the File or callback here.
    signal.addEventListener('abort',()=>{if(t.checked)forget();else if(!t.waiting)grace=setTimeout(forget,1200);},{...options,once:true});
    return async()=>{
      if(ticket===t&&t.waiting&&!signal.aborted)await new Promise<void>(resolve=>t.listeners.add(resolve));
      const now=current();return ticket===t&&!signal.aborted&&!t.waiting&&matches(t,now)
        && principal(now)===t.scope.user && !!now.owner&&owns(now.owner);
    };
  }};
  return {chooser,transition,held:()=>held,key:()=>admissionKey,dispose:clear};
}
