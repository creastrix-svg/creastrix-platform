// Real local React/OIDC/PG; deliberately no page.route fulfil(), Redux injection,
// cookie manipulation, or resetting cart data. "import" adds three synthetic units.
const assert = require('node:assert/strict');
const {execFileSync} = require('node:child_process');
const {createHash} = require('node:crypto');
const {join} = require('node:path');
const {chromium} = require(process.env.CART_PLAYWRIGHT_MODULE || 'playwright');
const mode = process.argv[2] || 'read';
assert.ok(['read','import','auto','manual'].includes(mode),'Expected read, import, auto or manual');
const root='http://localhost:4317', sample='kiln-blue', buyer='10000000-0000-4000-8000-000000000001';
const db = operation => execFileSync('bash',[join(__dirname,'account-cart-local.sh'),operation],{encoding:'utf8'});
const snapshot = () => JSON.parse(db('snapshot-json'));
const sourceHash='c7d7f035cb9bdc77a79d7a67f51916fe5c82e3f4222a8e3bc3ee96f4ad4b5f21';
const events=[],errors=[],outside=[];
let checkpoint='initial snapshot', browser;
const count=(path,method,status) => events.filter(e=>e.path===path && e.method===method && (!status||e.status===status)).length;
async function run() {
  const before=snapshot(), plain=before.lines.find(row=>row.sku===sample && row.design===null);
  const decorated=before.lines.find(row=>row.sku===sample && row.design!==null);
  assert.ok(plain && decorated,'Install the synthetic fixture first');
  if (mode==='auto' || mode==='manual') db(mode==='auto'?'fault-once':'fault-twice');
  browser=await chromium.launch({executablePath:process.env.CART_CHROME||undefined,headless:true,chromiumSandbox:true,
    args:['--disable-background-networking','--no-proxy-server','--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE localhost, EXCLUDE 127.0.0.1']});
  const context=await browser.newContext({locale:'en-US',serviceWorkers:'block'}), page=await context.newPage();
  await context.route('**/*',route=>{
    const u=new URL(route.request().url());
    if (u.origin===root || u.origin==='http://127.0.0.1:8192' || u.protocol==='blob:' || u.protocol==='data:') return route.continue();
    outside.push(u.protocol); return route.abort();
  });
  page.on('pageerror',e=>errors.push(e.name));
  page.on('response',r=>{
    const p=new URL(r.url()).pathname;
    if (/^\/(?:api\/|auth\/|login\/oauth2\/|authorize$)/.test(p))
      events.push({method:r.request().method(),path:p.replace(/(\/imports\/)\b[0-9a-f-]{36}\b/g,'$1:batch'),status:r.status()});
  });
  page.setDefaultTimeout(18000);
  const login=async()=>{
    await page.goto(root+'/login');
    await page.getByRole('button',{name:'Continue to hosted sign-in',exact:true}).click();
    await page.waitForURL(u=>u.pathname==='/account');
    assert.equal((await (await context.request.get(root+'/api/me')).json()).id,buyer);
  };
  const sessionCookie=async()=>{
    const cookie=(await context.cookies(root)).find(c=>c.name==='JSESSIONID');
    assert.ok(cookie && cookie.value,'Missing signed-in servlet session'); return cookie.value;
  };
  checkpoint='anonymous cart and card';
  assert.equal((await context.request.get(root+'/api/account-cart')).status(),401);
  await page.goto(root+'/products/kiln-cup?variant=blue');
  await page.getByRole('heading',{name:'Kiln Cup',exact:true}).first().waitFor();
  if(mode==='import'){
    for(let n=1;n<=3;n++){
      await page.getByRole('button',{name:'Add to cart',exact:true}).click();
      await page.getByText('Already in cart: '+n,{exact:true}).waitFor();
    }
    await page.locator('[data-local-save="saved"]').waitFor({state:'attached'});
    await page.goto(root+'/preview/cart');
    await page.locator('.product-cart-line').first().waitFor();
    assert.equal(await page.locator('.product-cart-line').count(),1);
    assert.equal(await page.locator('.product-cart-line input').first().inputValue(),'3');
  }
  checkpoint='first synthetic login';
  await login();
  const initialSession=await sessionCookie();
  if(mode==='import') {
    checkpoint='durable guest acknowledgement';
    await page.getByText('Guest cart synced.',{exact:true}).waitFor();
  }
  checkpoint='cart access';
  await page.goto(root+'/preview/cart');
  if(mode==='manual'){
    checkpoint='explicit binding retry';
    const retry=page.getByRole('button',{name:'Retry',exact:true});
    await retry.waitFor();
    assert.equal(await page.locator('[data-private-account] li').count(),0);
    assert.equal(await page.getByText('Your cart is empty.',{exact:true}).count(),0);
    assert.equal(count('/api/account-cart/recover','POST'),1);
    assert.equal(count('/api/account-cart/recover','POST',503),1);
    await retry.click();
  }
  const rows=page.locator('[data-private-account] li');
  const qty=(BigInt(plain.quantity)+(mode==='import'?3n:0n)).toString();
  const checkRows=async()=>{
    await rows.filter({hasText:'Checker motif'}).waitFor();
    assert.equal(await rows.count(),before.lines.length);
    const plainRow=rows.filter({hasNotText:'Checker motif'}).filter({hasText:'Kiln Cup'}).first();
    assert.ok((await plainRow.innerText()).includes('× '+qty));
    assert.ok((await rows.filter({hasText:'Checker motif'}).first().innerText()).includes('× '+decorated.quantity));
  };
  await checkRows();
  assert.equal(await sessionCookie(),initialSession);
  assert.equal((await (await context.request.get(root+'/api/me')).json()).id,buyer);
  checkpoint='private original artwork';
  const artwork='/api/account-cart/lines/'+decorated.id+'/artwork';
  const [image]=await Promise.all([page.waitForResponse(r=>new URL(r.url()).pathname===artwork),
    rows.filter({hasText:'Checker motif'}).getByRole('button',{name:'Source image'}).click()]);
  const bytes=await image.body();
  assert.equal(image.status(),200); assert.equal(image.headers()['content-type'],'image/png');
  assert.match(image.headers()['cache-control'],/no-store/); assert.equal(image.headers()['x-content-type-options'],'nosniff');
  assert.equal(bytes.length,90); assert.equal(createHash('sha256').update(bytes).digest('hex'),sourceHash);
  await page.getByRole('img',{name:'Source image'}).waitFor();
  assert.equal((await context.request.get(root+'/api/account-cart/lines/'+plain.id+'/artwork')).status(),404);
  const after=snapshot();
  if(mode==='import'){
    checkpoint='exact first import then genuine logout and repeated login';
    assert.deepEqual(after.lines,before.lines.map(l=>l.id===plain.id?{...l,quantity:qty}:l));
    assert.equal(BigInt(after.version),BigInt(before.version)+1n);
    const additions=after.receipts.filter(r=>!before.receipts.some(b=>b.id===r.id));
    assert.equal(after.receipts.length,before.receipts.length+1);
    assert.deepEqual(additions,[{id:additions[0].id,state:'APPLIED',version:after.version}]);
    assert.equal(count('/api/account-cart/imports/:batch/apply','POST',200),1);
    await page.goto(root+'/account');
    const [logout]=await Promise.all([page.waitForResponse(r=>new URL(r.url()).pathname==='/auth/logout'),
      page.getByRole('button',{name:'Sign out locally',exact:true}).click()]);
    assert.equal(logout.status(),204);
    await login();
    await page.goto(root+'/preview/cart'); await checkRows();
    assert.deepEqual(snapshot(),after);
    assert.equal(count('/api/account-cart/imports/:batch/prepare','POST'),1);
    assert.equal(count('/api/account-cart/imports/:batch/apply','POST'),1);
  } else {
    assert.deepEqual(after,before);
    assert.equal(events.filter(e=>e.path.includes('/imports/')).length,0);
  }
  const expectedRecovery=mode==='auto'?1:mode==='manual'?2:0;
  assert.equal(count('/api/account-cart/recover','POST'),expectedRecovery);
  if(expectedRecovery) assert.equal(count('/api/account-cart/recover','POST',200),1);
  assert.equal(count('/login/oauth2/code/cognito','GET',303),mode==='import'?2:1);
  assert.equal(count('/auth/logout','POST',204),mode==='import'?1:0);
  assert.deepEqual(outside,[]);assert.deepEqual(errors,[]);
  return {result:'PASS',mode,chrome:browser.version(),before,after,privateSourceBytes:bytes.length,sameOriginalSession:true,events};
}
run().then(result=>console.log(JSON.stringify(result))).catch(e=>{
  console.error(JSON.stringify({result:'INCOMPLETE',mode,checkpoint,error:e.name,events,errors,outside}));
  process.exitCode=1;
}).finally(async()=>{if(browser)await browser.close();});
