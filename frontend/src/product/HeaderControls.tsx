import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { useAppDispatch, useAppSelector } from '../hooks';
import { mediaForVariant, money, photoDescription, swatchBackground, variantById } from './catalog';
import { productCopy } from './copy';
import { cartCount, cartLineRemoved, cartQuantityChanged, cartTotal } from './demoStore';
import { Icon } from './Icon';
import QuantityInput from './QuantityInput';
import { searchExamples } from './search';
import { useProductLocale } from './useProductLocale';

export function SearchControl() {
  const language = useProductLocale(), t = productCopy[language];
  const location = useLocation();
  const [draft, setDraft] = useState<{ key: string; value: string } | null>(null), [open, setOpen] = useState(false);
  const [composing, setComposing] = useState(false);
  const [selected, setSelected] = useState<{ key: string; term: string; id: string } | null>(null);
  const query = draft?.key === location.key ? draft.value : location.pathname.replace(/\/+$/, '') === '/preview/search' ? (new URLSearchParams(location.search).get('q') ?? '').slice(0, 100) : '';
  const form = useRef<HTMLFormElement>(null), input = useRef<HTMLInputElement>(null);
  const id = useId(), navigate = useNavigate();
  const term = query.trim().toLocaleLowerCase();
  const items = searchExamples(query, language);
  const showing = open && draft?.key === location.key && term.length > 0 && !composing;
  const activeId = selected?.key === location.key && selected.term === term && items.some(item => item.id === selected.id) ? selected.id : undefined;
  const path = (id: string) => id === 'loop' ? '/products/loop' : '/preview/items/' + id;
  function finish() { setOpen(false); setSelected(null); setDraft(null); }
  useEffect(() => {
    const outside = (event: PointerEvent) => { if (event.target instanceof Node && !form.current?.contains(event.target)) setOpen(false); };
    document.addEventListener('pointerdown', outside);
    return () => document.removeEventListener('pointerdown', outside);
  }, []);
  return <form className="product-search" ref={form} role="search" onSubmit={event => { event.preventDefault(); if (composing) return; finish(); navigate('/preview/search?q=' + encodeURIComponent(query.trim())); }}
    onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false); }}
    onKeyDown={event => {
      if (composing || event.nativeEvent.isComposing) return;
      if (event.key === 'Escape') { event.preventDefault(); input.current?.focus(); setOpen(false); setSelected(null); }
      if (!showing) return;
      if (event.key === 'Enter' && activeId) { event.preventDefault(); finish(); navigate(path(activeId)); }
      if (!items.length || !['ArrowDown', 'ArrowUp'].includes(event.key)) return;
      event.preventDefault();
      const index = items.findIndex(item => item.id === activeId);
      const next = index < 0 ? event.key === 'ArrowDown' ? 0 : items.length - 1 : (index + (event.key === 'ArrowDown' ? 1 : items.length - 1)) % items.length;
      setSelected({ key: location.key, term, id: items[next].id });
    }}>
    <label className="product-sr-only" htmlFor={id}>{t.search}</label><input id={id} ref={input} type="search" role="combobox" aria-autocomplete="list" aria-expanded={showing} aria-controls={id + '-results'} aria-activedescendant={showing && activeId ? id + '-' + activeId : undefined} maxLength={100} placeholder={t.search} value={query}
      onChange={event => { setDraft({ key: location.key, value: event.target.value.slice(0, 100) }); setSelected(null); setOpen(true); }}
      onCompositionStart={() => { setComposing(true); setSelected(null); }} onCompositionEnd={event => { setComposing(false); setDraft({ key: location.key, value: event.currentTarget.value.slice(0, 100) }); setOpen(true); }}
      onFocus={() => { setDraft({ key: location.key, value: query }); setOpen(true); }} />
    <button type="submit" className="product-icon-button" aria-label={t.searchSubmit}><Icon name="search" /></button>
    {showing && <div className="product-search-suggestions"><ul id={id + '-results'} role="listbox" aria-label={t.results}>
      {items.map(item => <li key={item.id}><Link id={id + '-' + item.id} role="option" aria-selected={activeId === item.id} tabIndex={-1} to={path(item.id)} onMouseDown={event => event.preventDefault()} onClick={finish}><img src={`/product/reference-asset-${item.image}.webp`} alt="" width="48" height="48" /><span>{item.name}<small>{item.description}</small></span><Icon name="arrow" /></Link></li>)}
    </ul>{items.length === 0 && <p role="status">{t.noResults}</p>}<button type="submit" className="product-text-button">{t.results}<Icon name="arrow" /></button></div>}
  </form>;
}

export function HeaderCart() {
  const language = useProductLocale(), t = productCopy[language];
  const demo = useAppSelector(state => state.productDemo), dispatch = useAppDispatch();
  const location = useLocation();
  const [openKey, setOpenKey] = useState<string | null>(null);
  const open = openKey === location.key;
  const [initialAddSerial] = useState(demo.addSerial);
  const anchor = useRef<HTMLDivElement>(null), opener = useRef<HTMLAnchorElement>(null), popup = useRef<HTMLElement>(null);
  const closeTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const id = useId();
  function cancelClose() { if (closeTimer.current !== null) clearTimeout(closeTimer.current); closeTimer.current = null; }
  function reveal() { cancelClose(); setOpenKey(location.key); }
  useLayoutEffect(() => {
    if (!open) return;
    // Pin the non-scrolling header/footer inside even a short visual viewport.
    // Only the middle item list can consume or scroll the remaining space.
    const position = () => {
      if (!popup.current || !anchor.current) return;
      const rect = anchor.current.getBoundingClientRect();
      const viewport = window.visualViewport;
      const height = viewport?.height ?? window.innerHeight, offset = viewport?.offsetTop ?? 0;
      const width = Math.min(390, window.innerWidth - 24);
      const top = Math.max(offset + 8, Math.min(rect.bottom + 4, offset + height - 240));
      const left = Math.max(12, Math.min(rect.right - width, window.innerWidth - width - 12));
      popup.current.style.setProperty('--product-peek-top', `${top}px`);
      popup.current.style.setProperty('--product-peek-left', `${left}px`);
      popup.current.style.setProperty('--product-peek-height', `${Math.max(0, offset + height - top - 12)}px`);
    };
    position();
    window.addEventListener('resize', position);
    window.addEventListener('scroll', position, { passive: true });
    window.visualViewport?.addEventListener('resize', position);
    return () => { window.removeEventListener('resize', position); window.removeEventListener('scroll', position); window.visualViewport?.removeEventListener('resize', position); };
  }, [open]);
  useEffect(() => {
    const outside = (event: PointerEvent) => { if (event.target instanceof Node && !anchor.current?.contains(event.target)) setOpenKey(null); };
    document.addEventListener('pointerdown', outside);
    return () => { document.removeEventListener('pointerdown', outside); if (closeTimer.current !== null) clearTimeout(closeTimer.current); };
  }, []);
  return <div className="product-cart-anchor" ref={anchor}
    onPointerEnter={event => { if (event.pointerType === 'mouse') reveal(); }}
    onPointerLeave={event => {
      if (event.relatedTarget instanceof Node && event.currentTarget.contains(event.relatedTarget)) return;
      cancelClose();
      // A short hover bridge keeps the popup usable while crossing its visual edge.
      closeTimer.current = setTimeout(() => { if (!anchor.current?.contains(document.activeElement)) setOpenKey(null); }, 150);
    }}
    onFocusCapture={reveal} onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) setOpenKey(null); }}
    onKeyDown={event => { if (event.key === 'Escape') { event.preventDefault(); opener.current?.focus(); setOpenKey(null); } }}>
    <Link to="/preview/cart" className="product-icon-button" ref={opener} aria-label={t.cart} aria-expanded={open} aria-controls={id} onClick={() => setOpenKey(null)}><Icon name="bag" />{cartCount(demo) > 0 && <span key={demo.addSerial} className={'product-count' + (demo.addSerial !== initialAddSerial ? ' product-count-added' : '')}>{cartCount(demo)}</span>}</Link>
    {open && <section className="product-cart-peek" ref={popup} id={id} aria-label={t.cartPreview}>
      <h2>{t.cart}</h2>
      <div className="product-peek-items">{demo.cart.length === 0 ? <p>{t.emptyCart}</p> : demo.cart.slice(-5).map(line => { const variant = variantById(line.variant); return <div className="product-peek-line" key={line.variant}>
        {mediaForVariant(line.variant).photos[0] ? <img src={mediaForVariant(line.variant).photos[0]} alt={photoDescription(t.photoAlt, variant.name[language])} width="58" height="58" /> : <span className="product-peek-swatch" style={{ background: swatchBackground(variant.colors) }} aria-label={t.noPhoto} />}
        <div><strong>Loop · {variant.name[language]}</strong><span>{money(variant.cents * line.quantity, language)}</span><QuantityInput value={line.quantity} max={variant.stock} onChange={quantity => dispatch(cartQuantityChanged({ variant: line.variant, quantity }))} t={t} context={variant.name[language]} /></div>
        <button type="button" className="product-icon-button" aria-label={`${t.remove} · ${variant.name[language]}`} onClick={() => { opener.current?.focus({ preventScroll: true }); dispatch(cartLineRemoved(line.variant)); }}><Icon name="close" /></button>
      </div>; })}</div>
      <div className="product-peek-footer"><div className="product-peek-total"><span>{t.total}</span><strong>{money(cartTotal(demo), language)}</strong></div><Link className="product-primary" to="/preview/cart" onClick={() => setOpenKey(null)}>{t.viewCart}<Icon name="arrow" /></Link></div>
    </section>}
  </div>;
}
