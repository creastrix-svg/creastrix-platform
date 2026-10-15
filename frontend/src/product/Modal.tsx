import { useId, useLayoutEffect, useRef } from 'react';
import type { KeyboardEventHandler, ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { Icon } from './Icon';

export default function Modal({ title, closeLabel, onClose, onKeyDown, actions, className = '', children }: { title: string; closeLabel: string; onClose: () => void; onKeyDown?: KeyboardEventHandler<HTMLDivElement>; actions?: ReactNode; className?: string; children: ReactNode }) {
  const id = useId();
  const dialog = useRef<HTMLDivElement>(null);
  const closeButton = useRef<HTMLButtonElement>(null);
  useLayoutEffect(() => {
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const content = document.querySelector<HTMLElement>('[data-storefront-content]');
    const previousInert = content?.inert ?? false;
    const previousOverflow = document.body.style.overflow;
    if (content) content.inert = true;
    document.body.style.overflow = 'hidden';
    const first = () => dialog.current?.querySelector<HTMLElement>('button:not(:disabled),a[href],input:not(:disabled),[tabindex="0"]');
    closeButton.current?.focus({ preventScroll: true });
    const keepFocus = (event: FocusEvent) => { if (event.target instanceof Node && !dialog.current?.contains(event.target)) first()?.focus(); };
    document.addEventListener('focusin', keepFocus);
    return () => {
      document.removeEventListener('focusin', keepFocus);
      if (content) content.inert = previousInert;
      document.body.style.overflow = previousOverflow;
      if (opener?.isConnected) opener.focus({ preventScroll: true });
    };
  }, []);
  return createPortal(<div className="product-overlay" onMouseDown={event => { if (event.target === event.currentTarget) onClose(); }}>
    <div className={'product-dialog ' + className} ref={dialog} role="dialog" aria-modal="true" aria-labelledby={id}
      onKeyDown={event => {
        onKeyDown?.(event);
        if (event.defaultPrevented) return;
        if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); onClose(); }
        if (event.key !== 'Tab') return;
        const targets = [...event.currentTarget.querySelectorAll<HTMLElement>('button:not(:disabled),a[href],input:not(:disabled),[tabindex="0"]')]
          .filter(element => element.tabIndex >= 0 && !element.closest('[hidden]') && getComputedStyle(element).display !== 'none' && getComputedStyle(element).visibility !== 'hidden');
        const first = targets[0], last = targets.at(-1);
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
      }}>
      <div className="product-dialog-heading"><h2 id={id}>{title}</h2><div className="product-dialog-actions">{actions}<button ref={closeButton} type="button" className="product-icon-button product-dialog-close" aria-label={closeLabel} onClick={onClose}><Icon name="close" /><span>{closeLabel}</span></button></div></div>
      {children}
    </div>
  </div>, document.querySelector('[data-storefront-modal]') ?? document.body);
}
