import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { Link } from 'react-router';
import { useAppDispatch } from '../hooks';
import { productCopy } from './copy';
import { useProductLocale } from './useProductLocale';
import { storefrontLocales, isStorefrontLocale } from './locale';
import { productLocaleSelected } from './demoStore';
import type { Appearance } from './appearance';
import { Icon } from './Icon';
import Modal from './Modal';

function ThemeMenu({ appearance, onAppearance }: { appearance: Appearance; onAppearance: (appearance: Appearance) => void }) {
  const t = productCopy[useProductLocale()], id = useId();
  const options = [['system', 'monitor', t.appearanceSystem], ['light', 'sun', t.appearanceLight], ['dark', 'moon', t.appearanceDark]] as const;
  const selected = options.findIndex(([value]) => value === appearance);
  const [active, setActive] = useState<number | null>(null);
  const wrapper = useRef<HTMLDivElement>(null), trigger = useRef<HTMLButtonElement>(null), menu = useRef<HTMLDivElement>(null);
  const items = useRef<(HTMLButtonElement | null)[]>([]);
  const open = active !== null;
  function close(restore = false) { setActive(null); if (restore) trigger.current?.focus(); }
  function choose(index: number) { onAppearance(options[index][0]); close(true); }
  useLayoutEffect(() => { if (active !== null) items.current[active]?.focus(); }, [active]);
  useLayoutEffect(() => {
    if (!open) return;
    const position = () => {
      if (!trigger.current || !menu.current) return;
      const rect = trigger.current.getBoundingClientRect(), height = window.visualViewport?.height ?? window.innerHeight;
      const offset = window.visualViewport?.offsetTop ?? 0;
      const below = Math.max(0, offset + height - rect.bottom - 12), above = Math.max(0, rect.top - offset - 12);
      const upward = below < 156 && above > below;
      menu.current.style.top = upward ? 'auto' : 'calc(100% + 6px)';
      menu.current.style.bottom = upward ? 'calc(100% + 6px)' : 'auto';
      menu.current.style.maxHeight = `${Math.max(0, upward ? above : below)}px`;
    };
    position(); window.addEventListener('resize', position); window.addEventListener('scroll', position, true);
    window.visualViewport?.addEventListener('resize', position);
    return () => { window.removeEventListener('resize', position); window.removeEventListener('scroll', position, true); window.visualViewport?.removeEventListener('resize', position); };
  }, [open]);
  useEffect(() => {
    if (!open) return;
    const outside = (event: PointerEvent) => { if (event.target instanceof Node && !wrapper.current?.contains(event.target)) setActive(null); };
    document.addEventListener('pointerdown', outside);
    return () => document.removeEventListener('pointerdown', outside);
  }, [open]);
  return <div className="product-theme-wrap" ref={wrapper} onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) close(); }}>
    <button className="product-theme-trigger" ref={trigger} type="button" aria-label={`${t.appearance}: ${options[selected][2]}`} title={`${t.appearance}: ${options[selected][2]}`} aria-haspopup="menu" aria-expanded={open} aria-controls={id}
      onClick={() => { if (open) close(); else setActive(selected); }} onKeyDown={event => {
        if (event.altKey || event.ctrlKey || event.metaKey) return;
        if (event.key === 'ArrowDown' || event.key === 'ArrowUp') { event.preventDefault(); setActive(event.key === 'ArrowDown' ? 0 : options.length - 1); }
      }}><Icon name={options[selected][1]} /><span className="product-theme-chevron"><Icon name="chevron" /></span></button>
    {open && <div className="product-theme-menu" role="menu" aria-label={t.appearance} id={id} ref={menu} onKeyDown={event => {
      if (event.altKey || event.ctrlKey || event.metaKey) return;
      if (event.key === 'Tab') { close(true); return; }
      if (!['ArrowDown', 'ArrowUp', 'Home', 'End', 'Enter', ' ', 'Escape'].includes(event.key)) return;
      event.preventDefault(); event.stopPropagation();
      const index = active ?? selected;
      if (event.key === 'Escape') close(true);
      else if (event.key === 'Enter' || event.key === ' ') choose(index);
      else setActive(event.key === 'Home' ? 0 : event.key === 'End' ? options.length - 1 : (index + (event.key === 'ArrowDown' ? 1 : options.length - 1)) % options.length);
    }}>{options.map(([value, icon, label], index) => <button key={value} type="button" role="menuitemradio" aria-checked={appearance === value} aria-label={label} title={label} tabIndex={active === index ? 0 : -1} ref={element => { items.current[index] = element; }} onFocus={() => setActive(previous => previous === index ? previous : index)} onClick={() => choose(index)}><Icon name={icon} />{appearance === value && <span className="product-theme-check"><Icon name="check" /></span>}</button>)}</div>}
  </div>;
}

export default function StorefrontNavigation({ appearance, onAppearance }: { appearance: Appearance; onAppearance: (appearance: Appearance) => void }) {
  const locale = useProductLocale(), t = productCopy[locale], dispatch = useAppDispatch();
  const [drawer, setDrawer] = useState(false);
  const id = useId();
  const categories = [[t.living, 'living'], [t.gifts, 'gifts'], [t.personalizable, 'personal']] as const;
  return <>
    <div className="product-nav-bar"><div className="product-nav-inner product-width"><nav aria-label={t.navigation}>
      <button type="button" className="product-category-menu" aria-expanded={drawer} aria-haspopup="dialog" onClick={() => setDrawer(true)}><Icon name="menu" /><span>{t.categories}</span></button>
      {categories.map(([label, category]) => <Link key={category} className={'product-category-link product-category-' + category} to={'/preview/collection?category=' + category}>{label}</Link>)}
    </nav><div className="product-utilities">
      <Link className="product-help" to="/preview/info/help" aria-label={t.help}><Icon name="help" /><span>{t.help}</span></Link>
      <span className="product-language-wrap"><label className="product-sr-only" htmlFor={id + '-language'}>{t.language}</label><select id={id + '-language'} value={locale} onChange={event => { if (isStorefrontLocale(event.target.value)) dispatch(productLocaleSelected(event.target.value)); }}>
        {storefrontLocales.map(value => <option key={value} value={value} lang={value}>{value.toUpperCase()}</option>)}
      </select><span className="product-select-chevron"><Icon name="chevron" /></span></span>
      <ThemeMenu appearance={appearance} onAppearance={onAppearance} />
    </div></div></div>
    {drawer && <Modal title={t.categories} closeLabel={t.close} className="product-category-drawer" onClose={() => setDrawer(false)}><div className="product-category-links">{categories.map(([label, category]) => <Link key={category} to={'/preview/collection?category=' + category} onClick={() => setDrawer(false)}>{label}<Icon name="arrow" /></Link>)}<Link to="/preview/info/help" onClick={() => setDrawer(false)}>{t.help}<Icon name="arrow" /></Link></div><p>{t.categoryNote}</p></Modal>}
  </>;
}
