import {beforeEach,expect,it,vi} from 'vitest';
import type {AppliedLoopDesign} from '../cart/appliedLoopDesign';
import {bindGuestDesignLogin,forgetHeldDesign,holdGuestDesign,loadHeldDesign,matchingGuestDesign} from './guestDesignHandoff';

const {files}=vi.hoisted(()=>({files:new Map<string,{data:unknown;login?:string}>()}));
vi.mock('../cart/pendingDesignFiles',()=>({guestLibraryFile:async(key:string,action:string,login?:string,design?:unknown)=>{
  const f=files.get(key);
  if(action==='put')files.set(key,{data:structuredClone(design)});
  else if(action==='reserve'&&f&&!f.login){f.login=login;return f.data;}
  else if(action==='discard'&&f?.login===login)files.delete(key);
  else if(action==='readBound'&&f&&f.login===login)return structuredClone(f.data);
}}));
beforeEach(()=>{files.clear();sessionStorage.clear();});
const design:AppliedLoopDesign={sku:'cobalt',templateRevision:'2',zone:'surface',objects:[
  {kind:'text',text:'Ada',font:'rounded',color:'#20251f',x:240,y:278,size:22},
]};
it('offers the explicitly chosen draft only to its exact login, and only once',async()=>{
  const id=await holdGuestDesign(design);
  expect(sessionStorage.getItem('creastrix.library-handoff.v1')).not.toContain('Ada');
  expect(matchingGuestDesign(null)).toBeNull();
  const key='creastrix.library-handoff.v1',clonedTab=sessionStorage.getItem(key)!;
  const proof=await bindGuestDesignLogin(id,()=>true);
  expect(matchingGuestDesign(crypto.randomUUID())).toBeNull();
  await expect(bindGuestDesignLogin(id,()=>true)).rejects.toThrow();
  const reserved=sessionStorage.getItem(key)!;sessionStorage.setItem(key,clonedTab);
  await expect(bindGuestDesignLogin(id,()=>true)).rejects.toThrow();sessionStorage.setItem(key,reserved);
  const ticket=matchingGuestDesign(proof)!;expect(ticket.draft).toBe(id);
  expect(await loadHeldDesign(ticket,proof)).toEqual(design);
  await forgetHeldDesign(ticket);expect(matchingGuestDesign(proof)).toBeNull();expect(files.size).toBe(0);
});
it('does not move a missing or superseded file through a later login',async()=>{
  const id=await holdGuestDesign(design);
  await expect(bindGuestDesignLogin(id,()=>false)).rejects.toThrow();
  const replacement=await holdGuestDesign(design);
  await expect(bindGuestDesignLogin(id,()=>true)).rejects.toThrow();
  files.clear();await expect(bindGuestDesignLogin(replacement,()=>true)).rejects.toThrow();
});
