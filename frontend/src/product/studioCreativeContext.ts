import { createContext, useContext } from 'react';
import type { VariantId } from './catalog';
import { emptyImagePlacement } from './studioImage';
import type { ImagePlacement } from './studioImage';
import type { createStudioFontLibrary, LoadedStudioFont } from './studioFonts';
import type { LampPatternId } from './lampPatterns';

// Surface/base drafts and the cut-panel draft have separate ownership. Choosing a
// motif must never replace the picture or lettering on another physical zone.
export type StudioExample = VariantId | 'lamp' | 'lamp-panel';
export type CreativeDraft = { artwork: ImagePlacement; content: 'pattern' | 'image'; pattern: LampPatternId; bridges: boolean; prompt: string };
export const emptyCreativeDraft = (): CreativeDraft => ({ artwork: emptyImagePlacement(), content: 'pattern', pattern: 'botanical', bridges: true, prompt: '' });
export type CreativeSession = {
  drafts: Partial<Record<StudioExample, CreativeDraft>>;
  applied: Partial<Record<StudioExample, CreativeDraft>>;
  edit: (key: StudioExample, value: CreativeDraft | ((previous: CreativeDraft) => CreativeDraft)) => void;
  apply: (key: StudioExample, value: CreativeDraft) => void;
  remove: (key: StudioExample) => void;
  fonts: () => ReturnType<typeof createStudioFontLibrary>;
  customFonts: LoadedStudioFont[];
  rememberFont: (font: LoadedStudioFont) => void;
  restore?: (value: Pick<CreativeSession, 'drafts' | 'applied' | 'customFonts'>) => void;
};
export const CreativeContext = createContext<CreativeSession | null>(null);
export function useStudioCreative() {
  const session = useContext(CreativeContext);
  if (!session) throw new Error('Studio creative provider is required.');
  return session;
}
export const creativeDraft = (session: CreativeSession, key: StudioExample) => session.drafts[key] ?? emptyCreativeDraft();
export const sameArtwork = (a: CreativeDraft, b?: CreativeDraft) => !!b && a.content === b.content && a.artwork.image === b.artwork.image && a.artwork.size === b.artwork.size && a.artwork.x === b.artwork.x && a.artwork.y === b.artwork.y && a.pattern === b.pattern && a.bridges === b.bridges;
