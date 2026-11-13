// Released Loop template revision 2: these names denote these exact bytes,
// never an OS font or a mutable CSS alias. A replacement needs a new revision.
export const loopV2Fonts = ['rounded','editorial','handwritten'] as const;
export type LoopV2Font = typeof loopV2Fonts[number];
export const isLoopV2Font = (v:unknown):v is LoopV2Font => loopV2Fonts.some(id=>id===v);
const files = {
  rounded: ['Nunito.ttf',276932,'bb55a5ca5c2042335b3991af27c4d0705d0ef41cac6164ac737fd8f2a1e85207'],
  editorial: ['CormorantGaramond.ttf',1195560,'b20b7d9626dd956b2c5e558692ad328b1f19e3275e2782db4fa07670d83f35e0'],
  handwritten: ['Caveat.ttf',403648,'0bdb6b660482d31531b3945849fba5916b3ef8695da7024a9e6b9ee3c4157988'],
} as const;

export async function loopV2FontBytes(id:LoopV2Font,signal:AbortSignal) {
  const [name,size,hash]=files[id], fail=()=>new Error('STUDIO_FONT_UNAVAILABLE');
  const response=await fetch('/fonts/'+name+'?revision='+hash,{credentials:'omit',redirect:'error',signal});
  if(response.status!==200 || response.redirected || !response.body) throw fail();
  const bytes=new Uint8Array(size), reader=response.body.getReader();
  let offset=0;
  try {
    for(;;) {
      const next=await reader.read();
      if(next.done) break;
      if(offset+next.value.length>size) { await reader.cancel();throw fail(); }
      bytes.set(next.value,offset);offset+=next.value.length;
    }
  } finally { reader.releaseLock(); }
  if(offset!==size) throw fail();
  const digest=new Uint8Array(await crypto.subtle.digest('SHA-256',bytes));
  if(Array.from(digest,b=>b.toString(16).padStart(2,'0')).join('')!==hash) throw fail();
  return bytes.buffer;
}
