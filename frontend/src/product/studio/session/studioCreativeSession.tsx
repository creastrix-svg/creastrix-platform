import { useEffect, useRef, useState } from 'react';
import type { PropsWithChildren } from 'react';
import { createStudioFontLibrary } from '../design/studioFonts';
import type { LoadedStudioFont } from '../design/studioFonts';
import { CreativeContext, emptyCreativeDraft } from './studioCreativeContext';
import type { CreativeSession } from './studioCreativeContext';

/** Runtime resources are owned by the storefront. The bounded persistence
 * adapter stores validated raster/font bytes, never FontFace or canvas objects. */
export function StudioCreativeProvider({ children }: PropsWithChildren) {
  const [drafts, setDrafts] = useState<CreativeSession['drafts']>({});
  const [applied, setApplied] = useState<CreativeSession['applied']>({});
  const [customFonts, setCustomFonts] = useState<LoadedStudioFont[]>([]);
  const library = useRef<ReturnType<typeof createStudioFontLibrary> | null>(null);
  const fonts = () => library.current ??= createStudioFontLibrary();
  useEffect(() => () => { library.current?.dispose(); }, []);
  return <CreativeContext.Provider value={{ drafts, applied, customFonts, fonts,
    edit: (key, value) => setDrafts(previous => ({ ...previous, [key]: typeof value === 'function' ? value(previous[key] ?? emptyCreativeDraft()) : value })),
    apply: (key, value) => setApplied(previous => ({ ...previous, [key]: { ...value, artwork: { ...value.artwork } } })),
    remove: key => setApplied(previous => { const next = { ...previous }; delete next[key]; return next; }),
    rememberFont: font => setCustomFonts(previous => previous.some(existing => existing.id === font.id) ? previous : [...previous, font]),
    restore: value => { setDrafts(value.drafts); setApplied(value.applied); setCustomFonts(value.customFonts); },
  }}>{children}</CreativeContext.Provider>;
}
