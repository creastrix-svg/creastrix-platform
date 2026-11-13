import {act,render,screen,waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {afterEach,expect,it,vi} from 'vitest';
import {Provider} from 'react-redux';
import {MemoryRouter} from 'react-router';
import {createAppStore} from '../../store';
import type {AccountCartAdmission,AccountCartLines} from '../cart/accountCartAdmission';
import {createAccountCartAccess} from '../cart/useAccountCartAccess';
import type {AppliedLoopDesign} from '../cart/appliedLoopDesign';
import {SaveLibraryDesign} from './LibraryStudio';

afterEach(()=>vi.restoreAllMocks());
it.each(['hold command','lose stage acknowledgement','different buyer'] as const)(
  'keeps a retry scoped to its original editor during focus revalidation: %s',async mode=>{
  const store=createAppStore('en'),user=userEvent.setup(),docId=crypto.randomUUID(),revision=crypto.randomUUID();
  const admission=(id:string,request:number)=>{
    const binding={ownerId:id,namespace:crypto.randomUUID(),writeContext:crypto.randomUUID()};
    const access=createAccountCartAccess<AccountCartLines>(id,()=>true,{
      read:async()=>({kind:'ready',cart:[],ownerId:id,binding}),recover:async()=>'denied'});
    const a:AccountCartAdmission={access,userId:id,owner:{document:docId,epoch:1,revision,request},dispose:access.retire,
      directAdditions:undefined,editLine:undefined,quantityDraft:undefined};
    return {a,binding};
  };
  const first=admission(crypto.randomUUID(),1),second=admission(mode==='different buyer'?crypto.randomUUID():first.a.userId,2);
  const log:{url:string;body:string;context:string|null}[]=[],created=vi.fn();
  let accept!:(result:Response)=>void;
  const delayed=new Promise<Response>(resolve=>{accept=resolve;});
  vi.spyOn(window,'fetch').mockImplementation(async (u,init)=>{
    const url=String(u);
    if(url==='/auth/csrf')return Response.json({headerName:'X-CSRF-TOKEN',parameterName:'_csrf',token:'one'});
    const req={url,body:String(init?.body),context:new Headers(init?.headers).get('X-Cart-Context')};log.push(req);
    if(url.startsWith('/api/account-cart/designs/')){
      if(log.length===1 && mode==='lose stage acknowledgement')throw TypeError('No acknowledgement');
      return Response.json({designId:url.split('/').at(-1),designRevision:'1'});
    }
    if(req.context===first.binding.writeContext)return delayed;
    return Response.json({id:JSON.parse(req.body).id,version:'1'});
  });
  const draft=(label:string):AppliedLoopDesign=>({sku:'cobalt',zone:'surface',templateRevision:'2',
    objects:[{kind:'text',text:label,font:'rounded',color:'#20251f',x:240,y:278,size:22}]});
  const tree=(a:AccountCartAdmission,text:string)=><Provider store={store}><MemoryRouter>
    <SaveLibraryDesign admission={a} enabled doc={{objects:[],selectedId:null}} snapshot={()=>draft(text)} onCreated={created}/>
  </MemoryRouter></Provider>;
  const component=render(tree(first.a,'Original'));
  try{
    const save=screen.getByRole('button',{name:'Save design'});await waitFor(()=>expect(save).toBeEnabled());await user.click(save);
    if(mode==='lose stage acknowledgement')await screen.findByRole('button',{name:'Try again'});
    else await waitFor(()=>expect(log).toHaveLength(2));
    component.rerender(tree(second.a,'Newer text'));
    const held=log.find(v=>v.url.includes('/commands/'));
    if(held)await act(async()=>accept(Response.json({id:JSON.parse(held.body).id,version:'1'})));
    expect(created).not.toHaveBeenCalled();
    const action=screen.getByRole('button',{name:mode==='different buyer'?'Save design':'Try again'});
    await waitFor(()=>expect(action).toBeEnabled());await user.click(action);
    await waitFor(()=>expect(created).toHaveBeenCalledTimes(1));
    const stages=log.filter(v=>v.url.includes('/designs/')),commands=log.filter(v=>v.url.includes('/commands/'));
    expect(commands.at(-1)?.context).toBe(second.binding.writeContext);
    if(mode==='hold command'){
      expect(stages).toHaveLength(1);expect(commands).toHaveLength(2);
      expect(commands[1]).toMatchObject({url:commands[0].url,body:commands[0].body});
    }else if(mode==='lose stage acknowledgement'){
      expect(stages).toHaveLength(2);expect(stages[1]).toMatchObject({url:stages[0].url,body:stages[0].body});
      expect(JSON.parse(stages[1].body).objects[0].text).toBe('Original');
    }else{
      expect(stages).toHaveLength(2);expect(stages[0].url).not.toBe(stages[1].url);
      expect(JSON.parse(stages[1].body).objects[0].text).toBe('Newer text');expect(commands[0].url).not.toBe(commands[1].url);
    }
  }finally{component.unmount();first.a.dispose();second.a.dispose();}
});
