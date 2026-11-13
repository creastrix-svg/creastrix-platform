import {act, fireEvent, render, screen, waitFor, within} from '@testing-library/react';
import {Provider} from 'react-redux';
import {createMemoryRouter, RouterProvider, type To} from 'react-router';
import {expect,it,vi} from 'vitest';
import {createAppStore} from '../store';
import ProductExperience from './ProductExperience';
import {catalogCopy} from './browse/catalogCopy';
import {productCopy} from './copy';

function fixture(initial='/') {
  const router=createMemoryRouter([{path:'*',element:<ProductExperience/>}],{initialEntries:[initial]});
  const real=router.navigate.bind(router);
  const back=vi.fn();
  vi.spyOn(router,'navigate').mockImplementation((to:To|number|null,options)=>{
    if(to===-1){back();return Promise.resolve();} // History has accepted the request; POP is not delivered yet.
    return typeof to==='number'?real(to):real(to,options);
  });
  render(<Provider store={createAppStore('en')}><RouterProvider router={router}/></Provider>);
  return {back,router,visit:(to:string|number)=>act(async()=>{
    if(typeof to==='number')await real(to);else await real(to);
  })};
}
async function findCrumb(){
  await screen.findByRole('heading',{level:1,name:'Loop'});
  return within(document.querySelector<HTMLElement>('.product-breadcrumb')!).getByRole('link',{name:catalogCopy.en.title});
}

it('allows only one pending Back per entry in both the product breadcrumb and the catalog return control',async()=>{
  const {back,visit}=fixture();
  await visit('/catalog');await visit('/products/loop');
  let link=await findCrumb();
  fireEvent.click(link);fireEvent.click(link);
  expect(back).toHaveBeenCalledTimes(1);
  await visit(-1);
  link=await screen.findByRole('link',{name:productCopy.en.back});
  fireEvent.click(link);fireEvent.click(link);
  expect(back).toHaveBeenCalledTimes(2);
  await visit(-1);await visit(1);await visit(1);
  fireEvent.click(await findCrumb());expect(back).toHaveBeenCalledTimes(3);
});

it('leaves native modified clicks alone and releases a paused entry on a persisted pageshow',async()=>{
  const {back,visit}=fixture();await visit('/catalog');await visit('/products/loop');
  const link=await findCrumb();
  expect(link).toHaveAttribute('href','/catalog');
  expect(fireEvent.click(link,{ctrlKey:true})).toBe(true);expect(back).not.toHaveBeenCalled();
  fireEvent.click(link);expect(back).toHaveBeenCalledTimes(1);
  const restored=Object.assign(new Event('pageshow'),{persisted:true});
  fireEvent(window,restored);
  fireEvent.click(link);fireEvent.click(link);
  expect(back).toHaveBeenCalledTimes(2);
});

it('uses the ordinary catalog link on direct entry without calling Back',async()=>{
  const {back,router}=fixture('/products/loop');const link=await findCrumb();
  expect(link).toHaveAttribute('href','/catalog');fireEvent.click(link);
  await waitFor(()=>expect(router.state.location.pathname).toBe('/catalog'));
  expect(back).not.toHaveBeenCalled();
});
