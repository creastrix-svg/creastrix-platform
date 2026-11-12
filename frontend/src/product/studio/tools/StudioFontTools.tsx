import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Icon, Spinner } from '../../Icon';
import { useStudioCreative } from '../session/studioCreativeContext';
import { builtinStudioFonts, standardStudioFonts, hasBundledStudioFont, studioFontFamily, StudioFontError, MAX_CUSTOM_FONTS } from '../design/studioFonts';
import type { BuiltinStudioFont, StudioFontId } from '../design/studioFonts';
import { studioExtensionCopy } from '../studioExtensionCopy';
import { personalizationCopy } from '../personalizationCopy';
import { useProductLocale } from '../../useProductLocale';
import { studioZoneCopy } from '../studioZoneCopy';
import { permanentCatalogMode } from '../../catalog/permanentCatalog';
import { designFontHint, designLabels } from '../../cart/accountProductLabels';

type Props = { value: StudioFontId; target: string; onChange: (font: StudioFontId) => void; allowedFonts?: readonly BuiltinStudioFont[]; allowCustomFonts?: boolean };
export default function StudioFontTools({ allowedFonts = standardStudioFonts, allowCustomFonts = true, ...props }: Props) {
  // Replacing the capability policy cancels pending decoding even if the parent
  // keeps the same product target. Ordinary draft/callback updates retain it.
  const policyKey = JSON.stringify([props.target, allowedFonts, allowCustomFonts]);
  return <ConfiguredFontTools key={policyKey} {...props} allowedFonts={allowedFonts} allowCustomFonts={allowCustomFonts} />;
}

function ConfiguredFontTools({ value, target, onChange, allowedFonts, allowCustomFonts }: Props & { allowedFonts: readonly BuiltinStudioFont[]; allowCustomFonts: boolean }) {
  const locale = useProductLocale(), e = studioExtensionCopy[locale], p = personalizationCopy[locale], session = useStudioCreative();
  const [configuredFonts] = useState(() => [...allowedFonts]);
  const input = useRef<HTMLInputElement>(null), request = useRef<AbortController | null>(null);
  const change = useRef(onChange);
  // A font may finish decoding after the buyer edits the text. Apply it to the
  // latest draft callback, never to the text captured when loading started.
  useLayoutEffect(() => { change.current = onChange; }, [onChange]);
  const [pending, setPending] = useState(false), [message, setMessage] = useState('');
  const labels = { sans: p.sans, serif: p.serif, mono: p.mono, rounded: e.fontRounded, editorial: e.fontEditorial, handwritten: e.fontHandwritten, stencil: studioZoneCopy[locale].fontStencil };
  const library = session.fonts();
  const offeredCustom=session.customFonts.filter(f=>!permanentCatalogMode || f.resource?.name==='font.ttf' || f.resource?.name==='font.otf');
  useEffect(() => {
    const controller = new AbortController();
    // Local bundled faces render their own picker samples. A failed preload does
    // not select a fallback; explicit selection still reports a loading failure.
    if(!permanentCatalogMode) for (const font of configuredFonts.filter(hasBundledStudioFont)) void library.loadBuiltin(font, { signal: controller.signal }).catch(() => {});
    return () => { controller.abort(); request.current?.abort(); };
  }, [library, target, configuredFonts]);
  async function choose(font: StudioFontId | File) {
    if (typeof font !== 'string' || font.startsWith('custom-')) {
      if (!allowCustomFonts) return;
    } else if (!configuredFonts.includes(font as BuiltinStudioFont)) return;
    request.current?.abort(); const controller = new AbortController(); request.current = controller;
    setPending(true); setMessage('');
    try {
      if (typeof font === 'string' && font.startsWith('custom-')) {
        if (!offeredCustom.some(loaded => loaded.id === font)) throw new StudioFontError('unavailable');
        change.current(font); return;
      }
      if(permanentCatalogMode && typeof font!=='string' && !/\.(ttf|otf)$/i.test(font.name)) throw new StudioFontError('unsupported');
      const loaded = typeof font === 'string' ? await library.loadBuiltin(font as typeof builtinStudioFonts[number], { signal: controller.signal }) : await library.loadCustom(font, { signal: controller.signal });
      if (!controller.signal.aborted) {
        const custom = loaded.id.startsWith('custom-');
        if (custom ? !allowCustomFonts : !configuredFonts.includes(loaded.id as BuiltinStudioFont)) throw new StudioFontError('unavailable');
        if (custom) session.rememberFont(loaded);
        change.current(loaded.id); setMessage(typeof font === 'string' ? '' : e.fontReady);
      }
    } catch (cause) {
      if (!controller.signal.aborted) setMessage(configuredFonts.length === 1 && configuredFonts[0] === 'stencil' && !allowCustomFonts
        ? studioZoneCopy[locale].cutFontFailed
        : cause instanceof StudioFontError && cause.code === 'tooLarge' ? e.fontLimit : permanentCatalogMode ? designFontHint[locale] : e.fontInvalid);
    } finally { if (!controller.signal.aborted) { setPending(false); request.current = null; } }
  }
  return <fieldset className="studio-fonts"><legend>{p.fontLabel}</legend>
    <div>{configuredFonts.map(font => <button type="button" key={font} aria-pressed={value === font} disabled={pending} onClick={() => void choose(font)}><span style={{ fontFamily: studioFontFamily(font) }}>Aa</span><small>{labels[font]}</small></button>)}</div>
    {allowCustomFonts && offeredCustom.length > 0 && <div className="studio-custom-fonts">{offeredCustom.map(font => <button type="button" key={font.id} aria-pressed={value === font.id} disabled={pending} onClick={() => void choose(font.id)}><span style={{ fontFamily: font.family }}>Aa</span><small>{font.label}</small></button>)}</div>}
    {allowCustomFonts && <><input ref={input} className="product-sr-only" type="file" accept={permanentCatalogMode?'.ttf,.otf':'.ttf,.otf,.woff2'} aria-label={e.uploadFont} disabled={pending || session.customFonts.length >= MAX_CUSTOM_FONTS} onChange={event => { const file = event.currentTarget.files?.[0]; event.currentTarget.value = ''; if (file) void choose(file); }} />
    <button type="button" className="studio-font-upload" disabled={pending || session.customFonts.length >= MAX_CUSTOM_FONTS} onClick={() => input.current?.click()}>{pending ? <Spinner /> : <Icon name="plus" />}{e.uploadFont}<small>{session.customFonts.length}/{MAX_CUSTOM_FONTS}</small></button>
    <details className="studio-font-info"><summary>{e.customFont}<Icon name="help" /></summary><p>{permanentCatalogMode ? designLabels[locale][1] : e.fontPrivacy} {e.fontRights}</p></details></>}
    <div className="studio-font-message" role="status">{message || (allowCustomFonts ? permanentCatalogMode ? 'TTF / OTF · ≤ 2 MB' : e.fontFormats : '')}</div>
  </fieldset>;
}
