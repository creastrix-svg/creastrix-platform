import { useId, useState } from 'react';
import type { ProductCopy } from './copy';

export default function QuantityInput({ value, max, onChange, onEmpty, t, disabled = false, label = t.quantity }: {
  value: number; max: number; onChange: (quantity: number) => void; onEmpty?: () => void; t: ProductCopy; disabled?: boolean; label?: string;
}) {
  const id = useId();
  const [empty, setEmpty] = useState(false);
  const [limited, setLimited] = useState(false);
  function change(raw: string) {
    if (!/^\d*$/.test(raw)) return;
    setEmpty(raw === '');
    if (!raw) { onEmpty?.(); return; }
    const number = Number(raw);
    setLimited(number < 1 || number > max);
    onChange(Math.min(max, Math.max(1, number)));
  }
  return <div className="product-quantity-field">
    <label htmlFor={id}>{label}</label>
    <div className="product-quantity">
      <button type="button" aria-label={t.decrease} disabled={disabled || value <= 1} onClick={() => { setEmpty(false); setLimited(false); onChange(value - 1); }}>−</button>
      <input id={id} type="text" inputMode="numeric" pattern="[0-9]*" autoComplete="off" maxLength={12} value={empty && !disabled ? '' : value}
        disabled={disabled} aria-describedby={id + '-limit'} onChange={event => change(event.target.value)}
        onBlur={() => { if (empty) { setEmpty(false); setLimited(true); onChange(1); } }} />
      <button type="button" aria-label={t.increase} disabled={disabled || value >= max} onClick={() => { setEmpty(false); setLimited(false); onChange(value + 1); }}>+</button>
      <span className="product-quantity-notice" id={id + '-limit'} role="status">{limited ? `${t.limit} ${max}.` : ''}</span>
    </div>
  </div>;
}
