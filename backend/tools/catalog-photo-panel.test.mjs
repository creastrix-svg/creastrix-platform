import test from 'node:test';
import assert from 'node:assert/strict';
import {openPhotoPanel} from './catalog-photo-panel.mjs';

async function panel(t, publish) {
  const link = await openPhotoPanel({product:'loop',variant:'cobalt',publish});
  t.after(link.close);
  const url=new URL(link.url), key=new URLSearchParams(url.hash.slice(1)).get('key');
  return { origin:url.origin, post:(headers={},body='photo')=>fetch(url.origin+'/publish',{
    method:'POST',body,headers:{Origin:url.origin,'X-Creastrix-Photo-Key':key,'Content-Type':'application/octet-stream',...headers}
  })};
}
test('buyer requests have no publish authority; neither the form nor script discloses the private link',async t=>{
  let calls=0;
  const {origin,post}=await panel(t,async()=>{calls++;return 'published';});
  for(const resource of ['/','/panel.js']) {
    const r=await fetch(origin+resource);
    assert.equal(r.status,200);assert.equal(r.headers.get('cache-control'),'no-store');
    assert.doesNotMatch(await r.text(),/\b[a-f0-9]{64}\b/);
  }
  assert.equal((await post({'X-Creastrix-Photo-Key':''})).status,403);
  assert.equal((await post({Origin:'http://127.0.0.1:4174'})).status,403);
  assert.equal((await post({'Content-Type':'text/plain'})).status,413);
  assert.equal(calls,0);
});
test('file rejection permits correction; concurrent clicks and repeat submit cannot publish twice',async t=>{
  let calls=0, release, entered;
  const starting=new Promise(done=>{entered=done;});
  const block=new Promise(done=>{release=done;});
  const {post}=await panel(t,async bytes=>{
    calls++;if(calls===1)return 'invalid';
    assert.equal(bytes.toString(),'photo');entered();await block;return 'published';
  });
  assert.equal((await post()).status,422);
  const first=post();await starting;
  assert.equal((await post()).status,409);
  release();assert.equal((await first).status,201);
  assert.equal((await post()).status,409);assert.equal(calls,2);
});
test('unknown commit outcome disables further publication for that link',async t=>{
  let calls=0;const {post}=await panel(t,async()=>{calls++;throw Error('uncertain');});
  assert.equal((await post()).status,503);assert.equal((await post()).status,409);assert.equal(calls,1);
});
test('an unavailable target cannot be fixed by submitting a different photo to that session',async t=>{
  let calls=0;const {post}=await panel(t,async()=>{calls++;return 'target';});
  assert.equal((await post()).status,404);assert.equal((await post()).status,409);assert.equal(calls,1);
});
