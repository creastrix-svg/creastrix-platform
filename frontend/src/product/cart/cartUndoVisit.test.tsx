import {act,render,screen} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {StrictMode,useState} from 'react';
import {Provider} from 'react-redux';
import {Link,MemoryRouter,useLocation} from 'react-router';
import {expect,it} from 'vitest';
import {createAppStore} from '../../store';
import {cartAdded,cartDisplayLines} from '../demoStore';
import CartUndoVisitScope from './CartUndoVisitScope';
import {useCartUndoVisit} from './cartUndoVisit';
import CartView from './CartView';

// Keep the visit above a temporary CHECKING screen, as App does. No seller
// records, network responses or retry handles belong to that presentation scope.
function AccountProbe(){
  const visit=useCartUndoVisit(),[owner,setOwner]=useState('buyer A'),[paint,repaint]=useState(0);
  const ids=visit.account(owner);
  return <><button onClick={()=>{ids.add('one');repaint(paint+1);}}>Remove</button>
    <button onClick={()=>setOwner('buyer B')}>Switch buyer</button>
    {ids.has('one')&&<b>Restore</b>}</>;
}
function Harness({guest=false}:{guest?:boolean}){
  const location=useLocation(),open=location.pathname==='/preview/cart',[checking,check]=useState(false);
  return <CartUndoVisitScope open={open}>
    <Link to={open?'/catalog':'/preview/cart'}>{open?'Leave':'Enter'}</Link>
    <button onClick={()=>check(!checking)}>Check</button>
    {open && !checking && (guest?<CartView/>:<AccountProbe/>)}
  </CartUndoVisitScope>;
}
it('remembers Undo across a same-route mount but not after leaving, refreshing or changing buyers',async()=>{
  const user=userEvent.setup(),store=createAppStore('en');
  function show(){return render(<StrictMode><Provider store={store}><MemoryRouter initialEntries={['/preview/cart']}><Harness/></MemoryRouter></Provider></StrictMode>);}
  const page=show();
  await user.click(screen.getByText('Remove'));await user.click(screen.getByText('Check'));await user.click(screen.getByText('Check'));
  expect(screen.getByText('Restore')).toBeVisible();
  act(()=>{
    window.dispatchEvent(new Event('pagehide'));
    window.dispatchEvent(Object.assign(new Event('pageshow'),{persisted:true}));
  });
  expect(screen.queryByText('Restore')).toBeNull();
  await user.click(screen.getByText('Remove'));
  await user.click(screen.getByText('Leave'));await user.click(screen.getByText('Enter'));expect(screen.queryByText('Restore')).toBeNull();
  await user.click(screen.getByText('Remove'));await user.click(screen.getByText('Switch buyer'));expect(screen.queryByText('Restore')).toBeNull();
  await user.click(screen.getByText('Remove'));page.unmount();show();expect(screen.queryByText('Restore')).toBeNull();
});
it('expires guest snapshots without rehydrating or removing other active quantities',async()=>{
  const user=userEvent.setup(),store=createAppStore('en');
  store.dispatch(cartAdded({variant:'cobalt',quantity:2}));store.dispatch(cartAdded({variant:'coral',quantity:1}));
  render(<StrictMode><Provider store={store}><MemoryRouter initialEntries={['/preview/cart']}><Harness guest/></MemoryRouter></Provider></StrictMode>);
  await user.click(screen.getByRole('button',{name:'Remove · Coral'}));expect(screen.getByRole('button',{name:'Restore · Coral'})).toBeEnabled();
  await user.click(screen.getByText('Check'));await user.click(screen.getByText('Check'));expect(screen.getByRole('button',{name:'Restore · Coral'})).toBeEnabled();
  await user.click(screen.getByText('Leave'));expect(store.getState().productDemo.cartRemoved).toEqual({});
  await user.click(screen.getByText('Enter'));expect(screen.queryByRole('button',{name:'Restore · Coral'})).toBeNull();
  expect(cartDisplayLines(store.getState().productDemo)).toEqual([{variant:'cobalt',quantity:2,removedRevision:null}]);
});
