import { useId, useState } from 'react';
import type { ProductCopy } from './copy';

export default function QuantityInput({ value, max, onChange, onEmpty, t, disabled = false, label = t.quantity, context, commitOnBlur=false, exactValue, decreaseDisabled=false }: {
  value: number; max: number; onChange: (quantity: number) => void; onEmpty?: () => void; t: ProductCopy; disabled?: boolean; label?: string; context?: string;
  commitOnBlur?:boolean;exactValue?:string;decreaseDisabled?:boolean;
}) {
  const id = useId();
  const [empty, setEmpty] = useState(false);
  const [limited, setLimited] = useState(false);
  const [draft,setDraft]=useState<string|null>(null);
  function commit() {
    if(!commitOnBlur || draft===null)return;
    const amount=Number(draft);
    const confirmed=Math.min(max,Math.max(1,amount));setLimited(amount!==confirmed);setDraft(null);
    if(!disabled && confirmed>0 && confirmed!==value)onChange(confirmed);
  }
  function change(raw: string) {
    if (!/^\d*$/.test(raw)) return;
    if(commitOnBlur){setDraft(raw);setLimited(false);return;}
    setEmpty(raw === '');
    if (!raw) { onEmpty?.(); return; }
    const number = Number(raw);
    setLimited(number < 1 || number > max);
    onChange(Math.min(max, Math.max(1, number)));
  }
  return <div className="product-quantity-field">
    <label htmlFor={id}>{label}{context && <span className="product-sr-only"> · {context}</span>}</label>
    <div className="product-quantity">
      <button type="button" aria-label={context ? `${t.decrease} · ${context}` : t.decrease} disabled={disabled || decreaseDisabled || value <= 1} onClick={() => { setEmpty(false); setLimited(false); onChange(value-1); }}>−</button>
      <input id={id} type="text" inputMode="numeric" pattern="[0-9]*" autoComplete="off" maxLength={12} value={commitOnBlur?draft??exactValue??value:empty && !disabled ? '' : value}
        disabled={disabled} aria-describedby={id + '-limit'} onChange={event => change(event.target.value)}
        onKeyDown={event=>{if(commitOnBlur && event.key==='Enter'){event.preventDefault();event.currentTarget.blur();}}}
        onBlur={() => { if(commitOnBlur){commit();return;} if (empty) { setEmpty(false); setLimited(true); onChange(1); } }} />
      <button type="button" aria-label={context ? `${t.increase} · ${context}` : t.increase} disabled={disabled || value >= max} onClick={() => { setEmpty(false); setLimited(false); onChange(value + 1); }}>+</button>
      <span className="product-quantity-notice" id={id + '-limit'} role="status">{limited && !disabled && max > 0 ? `${t.limit} ${max}.` : ''}</span>
    </div>
  </div>;
}
