import { createServer } from 'node:http';
import { randomBytes, timingSafeEqual } from 'node:crypto';
import { spawn } from 'node:child_process';
import { pathToFileURL } from 'node:url';

const LIMIT = 12 * 1024 * 1024;
const slug = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
const fragment = '#key=';
const html = (product,variant) => `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>Product photo · Creastrix</title><style>body{max-width:32rem;margin:10vh auto;padding:1rem;font:16px/1.5 system-ui;color:#17231f}small{color:#58665d}input,button{display:block;margin:.8rem 0;padding:.6rem;max-width:100%;font:inherit}button{background:#173a30;border:0;border-radius:.5rem;color:white;padding:.65rem 1.2rem}button:disabled{opacity:.55}p[role=status]{min-height:1.5rem}</style>
<main><small>Creastrix · Local seller</small><h1>Replace cover photo</h1><p>Product: <b>${product}</b> · Variant: <b>${variant}</b></p>
<form><label>Original photo <input type="file" accept="image/jpeg,image/png" required></label>
<p>Choose a still JPEG or PNG: up to 12 MiB, 24 megapixels and 12,000 pixels on either side. For PNG use sRGB without an embedded ICC profile. For a wide-gamut or print image, export a copy converted to sRGB JPEG. The original stays private. Publishing replaces this variant’s cover with a catalogue thumbnail and a larger product image.</p>
<button disabled>Publish local photo</button></form><p role="status" aria-live="polite"></p></main><script src="/panel.js"></script></html>`;
const client = `const key=new URLSearchParams(location.hash.slice(1)).get('key');
history.replaceState(null,'','/');
const f=document.querySelector('form'),file=f.querySelector('input'),b=f.querySelector('button'),status=document.querySelector('[role=status]');
let busy=false,done=false;
const changed=()=>{b.disabled=busy||done||!key||!file.files[0]||file.files[0].size===0||file.files[0].size>12582912;
if(!key)status.textContent='Open the private link from the seller terminal.';
else if(file.files[0]?.size>12582912)status.textContent='Choose a file no larger than 12 MiB.';
else if(!busy&&!done)status.textContent='';};
file.onchange=changed;changed();
f.onsubmit=async e=>{e.preventDefault();if(b.disabled)return;busy=true;changed();file.disabled=true;status.textContent='Preparing both images…';
try{const r=await fetch('/publish',{method:'POST',credentials:'omit',redirect:'error',cache:'no-store',headers:{'Content-Type':'application/octet-stream','X-Creastrix-Photo-Key':key},body:file.files[0]});
if(r.ok){done=true;status.textContent='Published. Reload the catalogue or product page to see the new photo.';}
else if(r.status===422||r.status===413){status.textContent='Not published. Export a still sRGB JPEG or PNG within the limits above, then choose that file.';}
else if(r.status===404){done=true;status.textContent='This product or variant is not available for publication. Start a new seller session for the correct variant.';}
else{done=true;status.textContent='Publication could not be confirmed. Check the catalogue before starting another upload.';}}
catch{done=true;status.textContent='Publication could not be confirmed. Check the catalogue before trying again.';}
finally{busy=false;b.disabled=done||!key;file.disabled=done;}};`;

// A short-lived, one-operation capability is delivered only to the trusted operator.
// No authority comes from browsing the storefront or being on localhost by itself.
export async function openPhotoPanel({product,variant,publish,ttl=5*60_000}) {
  if(!slug.test(product)||!slug.test(variant)||product.length>96||variant.length>96) throw Error('Invalid product or variant');
  const key=randomBytes(32).toString('hex');
  let origin, busy=false,consumed=false,timer;
  const server=createServer({headersTimeout:10000,requestTimeout:40000,keepAliveTimeout:1000},async(req,res)=>{
    const send=(code,type,body)=>{res.writeHead(code,{'Content-Type':type,'Cache-Control':'no-store','X-Content-Type-Options':'nosniff',
      'Referrer-Policy':'no-referrer','Content-Security-Policy':"default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; connect-src 'self'; form-action 'none'; base-uri 'none'; frame-ancestors 'none'"});res.end(body);};
    if('http://'+req.headers.host!==origin) return send(403,'text/plain','Refused');
    if(req.method==='GET'&&req.url==='/') return send(200,'text/html; charset=utf-8',html(product,variant));
    if(req.method==='GET'&&req.url==='/panel.js') return send(200,'text/javascript; charset=utf-8',client);
    if(req.method!=='POST'||req.url!=='/publish') return send(404,'text/plain','Not found');
    const candidate=req.headers['x-creastrix-photo-key'];
    if(req.headers.origin!==origin||typeof candidate!=='string'||!/^[a-f0-9]{64}$/.test(candidate)||
       !timingSafeEqual(Buffer.from(candidate),Buffer.from(key))) return send(403,'text/plain','Refused');
    if(busy||consumed) return send(409,'text/plain','Already in progress or completed');
    const size=Number(req.headers['content-length']);
    if(req.headers['content-type']!=='application/octet-stream'||!Number.isSafeInteger(size)||size<1||size>LIMIT)
      return send(413,'text/plain','Invalid size or content type');
    busy=true;
    try {
      const parts=[];let length=0;
      for await(const part of req){length+=part.length;if(length>size) return send(413,'text/plain','Invalid size');parts.push(part);}
      if(length!==size||res.destroyed) return;
      const outcome=await publish(Buffer.concat(parts,length));
      if(outcome==='invalid') return send(422,'text/plain','Image rejected');
      // Never repeat an uncertain commit via this link, even if the response is lost.
      consumed=true;
      if(outcome==='target') return send(404,'text/plain','Product variant not available');
      if(outcome!=='published') return send(503,'text/plain','Outcome unknown');
      return send(201,'text/plain','Published');
    } catch {consumed=true;if(!res.headersSent&&!res.destroyed)send(503,'text/plain','Outcome unknown');}
    finally {busy=false;if(consumed){clearTimeout(timer);timer=setTimeout(()=>server.close(),1000);timer.unref();}}
  });
  await new Promise((ok,no)=>{server.once('error',no);server.listen(0,'127.0.0.1',ok);});
  origin='http://127.0.0.1:'+server.address().port;
  timer=setTimeout(()=>server.close(),ttl);timer.unref();
  server.on('close',()=>clearTimeout(timer));
  return {url:origin+'/'+fragment+key,close:()=>server.close()};
}
function operator(product,variant,bytes){
  return new Promise(resolve=>{
    const cli=spawn('java',['-Djava.awt.headless=true','-cp',process.env.CREASTRIX_PHOTO_CLASSPATH,
      'com.creastrix.platform.localcatalog.LocalSellerPhotoCommand',product,variant,'-'],{stdio:['pipe','pipe','ignore'],timeout:60_000});
    let output='';
    cli.stdout.setEncoding('utf8');
    cli.stdout.on('data',chunk=>{if(output.length<200)output+=chunk;});
    cli.on('error',()=>resolve('unknown'));cli.stdin.on('error',()=>{});cli.stdin.end(bytes);
    cli.on('close',code=>resolve(code===0&&/^published [a-f0-9-]{36}\s*$/.test(output)?'published':code===2?'invalid':code===3?'target':'unknown'));
  });
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
  const [product,variant]=process.argv.slice(2);
  const {url}=await openPhotoPanel({product,variant,publish:bytes=>operator(product,variant,bytes)});
  console.log('Open this one-use private link in Chrome. Do not share it:\n'+url);
}
