import {afterEach,expect,it,vi} from 'vitest';
import {createDirectCartAdditions,purchaseDesignDigest} from './directCartAdditions';
import {projectAccountDesign,type AppliedLoopDesign} from './appliedLoopDesign';

const ownerId=crypto.randomUUID(), namespace=crypto.randomUUID(), writeContext=crypto.randomUUID();
const b={ownerId,namespace,writeContext};
const sku='newly-configured-coral',lineId=crypto.randomUUID();
const drawing:AppliedLoopDesign={sku,templateRevision:'2',zone:'surface',objects:[
  {kind:'text',text:'Summer',size:22,x:235,y:278,color:'#20251f',font:'rounded'},
]};
afterEach(()=>sessionStorage.clear());
function rig(){
  const artwork=new Map<string,AppliedLoopDesign>(),stages=new Map<string,unknown>(),receipts=new Map<string,unknown>();
  const calls:{path:string;body:Record<string,unknown>}[]=[], loss={stage:false,add:false,refuse:false},refresh=vi.fn();
  const fetcher:typeof fetch=async(path,init)=>{
    if(path==='/auth/csrf')return Response.json({headerName:'X-CSRF-TOKEN',parameterName:'_csrf',token:'csrf'});
    const route=String(path),id=route.split('/').at(-1)!,body=JSON.parse(String(init?.body));calls.push({path:route,body});
    if(route.includes('/designs/')){
      if(loss.refuse)return Response.json({error:'request_not_completed'},{status:422});
      if(stages.has(id))expect(stages.get(id)).toEqual(body);else stages.set(id,body);
      if(loss.stage){loss.stage=false;throw new TypeError('delivery lost');}
      return Response.json({designId:id,designRevision:'1'});
    }
    if(!stages.has(body.designId))throw Error('Addition without staged design');
    if(receipts.has(id))expect(receipts.get(id)).toEqual(body);else receipts.set(id,body);
    if(loss.add){loss.add=false;throw new TypeError('delivery lost');}
    return Response.json({state:'APPLIED',version:'1',lineId});
  };
  const create=(actor=ownerId)=>createDirectCartAdditions(fetcher,{ownerId:actor,owns:()=>true},refresh,()=>sessionStorage,()=>true,
    async(key,action,value)=>{if(action==='put')artwork.set(key,value!);else if(action==='remove')artwork.delete(key);return artwork.get(key);});
  return {create,stages,receipts,calls,loss,artwork};
}
it('resumes a lost stage response and a lost addition response across two reloads; one immutable design and one quantity',async()=>{
  const api=rig();api.loss.stage=true;api.loss.add=true;
  let sender=api.create();sender.open(b,sku);
  await sender.submit(b,sku,2,false,drawing);
  expect(sender.getSnapshot().phase).toBe('pending');expect(api.artwork.size).toBe(1);
  expect(sessionStorage.getItem(sessionStorage.key(0)!)).not.toContain('Summer');
  sender.dispose();sender=api.create();sender.open(b,sku);
  const different=crypto.randomUUID(),other=api.create(different);
  expect(other.pendingFor({...b,ownerId:different})).toEqual([]);
  expect(sender.pendingFor({...b,namespace:different})).toEqual([]);
  expect(api.calls).toHaveLength(1);
  await sender.retryExisting(b,sender.pendingFor(b)![0]);
  expect(api.artwork.size).toBe(0);expect(api.stages.size).toBe(1);expect(api.receipts.size).toBe(1);
  expect(sender.getSnapshot().phase).toBe('pending');sender.dispose();sender=api.create();
  await sender.retryExisting(b,sender.pendingFor(b)![0]);
  expect(api.calls.map(r=>r.path.split('/').at(-2))).toEqual(['designs','designs','additions','additions']);
  expect(api.calls[0]).toEqual(api.calls[1]);expect(api.calls[2]).toEqual(api.calls[3]);
  expect(api.calls[2].body).toEqual({sku,quantity:'2',designId:[...api.stages.keys()][0],designRevision:'1'});
  expect(api.receipts.size).toBe(1);expect(sessionStorage.length).toBe(0);
  expect(sender.lineFor(b,sku,await purchaseDesignDigest({...drawing}))).toBe(lineId);
  const changed={...drawing,objects:drawing.objects.map(o=>o.kind==='text'?{...o,text:'Different'}:o)};
  expect(sender.lineFor(b,sku,await purchaseDesignDigest(changed))).toBeUndefined();
});
it('does not silently replace incompatible or expired artwork with a plain purchase',async()=>{
  const api=rig(),writer=api.create();writer.open(b,sku);
  api.loss.refuse=true;await writer.submit(b,sku,2,false,drawing);
  expect(writer.getSnapshot().phase).toBe('rejected');expect(api.receipts.size).toBe(0);expect(sessionStorage.length).toBe(0);
  api.loss.refuse=false;api.loss.stage=true;await writer.submit(b,sku,2,false,drawing);api.artwork.clear();
  await writer.retryExisting(b,writer.pendingFor(b)![0]);
  expect(writer.getSnapshot().phase).toBe('rejected');expect(api.receipts.size).toBe(0);expect(sessionStorage.length).toBe(0);
});
it('projects the selected surface independent of product name and rejects unresolved resources',()=>{
  const value={text:'ABC',size:22,x:240,y:275,color:'#ffffff' as const,font:'rounded' as const};
  expect(projectAccountDesign(sku,'2',{objects:[{kind:'text',id:'studio-object-3',value}]})).toEqual({
    sku,templateRevision:'2',zone:'surface',objects:[{kind:'text',...value}]});
  expect(()=>projectAccountDesign(sku,'2',{objects:[{kind:'text',id:'studio-object-3',value:{...value,font:'custom-12'}}]})).toThrow();
  expect(()=>projectAccountDesign(sku,'8',{objects:[{kind:'text',id:'studio-object-3',value}]})).toThrow();
});
