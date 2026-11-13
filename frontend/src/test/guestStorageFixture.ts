import {expect,vi} from 'vitest';

// Exercise the same open/request/commit/abort path as the browser with a
// deterministic transaction fixture. Real cross-document IDB is checked separately.
export function guestStorageFixture(initial:Iterable<readonly [string,unknown]>=[],onAdd:(key:unknown)=>void=()=>{}) {
  const committed=new Map<string,unknown>(initial);
  type Range={lower:string[];upper:unknown[]};
  vi.stubGlobal('IDBKeyRange',{bound:(lower:string[],upper:unknown[])=>({lower,upper})});
  function transaction() {
    const staged=new Map(committed);
    let stopped=false;
    const finish=()=>{if(stopped)return; stopped=true; committed.clear(); for(const [k,v] of staged) committed.set(k,v);tx.oncomplete?.();};
    function request(result:unknown) {
      const r={result,onsuccess:null as null|(()=>void)};
      queueMicrotask(()=>{r.onsuccess?.();queueMicrotask(finish);});return r;
    }
    function first(range:Range) {
      expect(range.upper).toEqual([...range.lower,[]]);
      return [...staged.keys()].sort().find(k=> {
        const key:unknown=JSON.parse(k);
        return Array.isArray(key) && key.length===3 && key[0]===range.lower[0] && key[1]===range.lower[1];
      });
    }
    const store={
      get(key:unknown){return request(staged.get(JSON.stringify(key)));},
      getKey(range:Range){const k=first(range);return request(k && JSON.parse(k));},
      openCursor(range:Range){const k=first(range);
        return request(k ? {key:JSON.parse(k),value:staged.get(k),delete:()=>staged.delete(k)} : null);},
      add(value:unknown,key:unknown){onAdd(key);if(staged.has(JSON.stringify(key))) throw Error('duplicate'); staged.set(JSON.stringify(key),structuredClone(value));},
      put(value:unknown,key:unknown){staged.set(JSON.stringify(key),structuredClone(value));},
      delete(key:unknown){staged.delete(JSON.stringify(key));},
    };
    const tx={ objectStore:()=>store, oncomplete:null as null|(()=>void),onabort:null as null|(()=>void),abort(){stopped=true;tx.onabort?.();} };
    return tx;
  }
  const db={close(){},transaction};
  vi.stubGlobal('indexedDB',{open(){const request={result:db,onsuccess:null as null|(()=>void)};
    queueMicrotask(()=>request.onsuccess?.());return request;}});
  return committed;
}
