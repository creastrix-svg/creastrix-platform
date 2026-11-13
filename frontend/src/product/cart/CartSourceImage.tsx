import { useEffect, useRef, useState } from 'react';
import type { AccountCartAdmission } from './accountCartAdmission';
import { accountProductLabels } from './accountProductLabels';
import { useProductLocale } from '../useProductLocale';

/** Source of the applied revision, not a rendered manufacturing design. Private
 * bytes are requested only on demand and the object URL lives only in this view. */
export default function CartSourceImage({admission, lineId}: {admission: AccountCartAdmission; lineId: string}) {
  const [, label, unavailable] = accountProductLabels[useProductLocale()];
  const active = useRef<AbortController | null>(null), url = useRef<string | null>(null);
  const [state, setState] = useState<'idle'|'reading'|'failed'|{src:string}>('idle');
  useEffect(() => () => {
    active.current?.abort(); active.current = null;
    if (url.current) URL.revokeObjectURL(url.current);
    url.current = null;
  }, []);
  async function show() {
    if (active.current || !admission.access.ownsDisplay()) return;
    const op = new AbortController();
    active.current = op;
    const owns = () => !op.signal.aborted && active.current === op && admission.access.ownsDisplay();
    setState('reading');
    try {
      const response = await fetch('/api/account-cart/lines/' + encodeURIComponent(lineId) + '/artwork', {
        credentials:'same-origin', cache:'no-store', redirect:'error', headers:{Accept:'image/png'},
        signal:AbortSignal.any([op.signal, AbortSignal.timeout(10000)]),
      });
      if (!owns()) return;
      if (response.status !== 200 || response.headers.get('content-type') !== 'image/png') throw new Error('IMAGE_UNAVAILABLE');
      const blob = await response.blob();
      if (!owns()) return;
      if (!blob.size || blob.size > 1024 * 1024) throw new Error('IMAGE_UNAVAILABLE');
      const src = URL.createObjectURL(blob);
      url.current = src; setState({src});
    } catch { if (owns()) setState('failed'); }
  }
  return <div>{typeof state === 'object' ? <img src={state.src} width="64" height="64" alt={label}/>
    : state === 'failed' ? <span role="status">{label}: {unavailable}</span>
    : <button type="button" className="product-text-button" disabled={state === 'reading'} onClick={() => void show()}>{label}</button>}</div>;
}
