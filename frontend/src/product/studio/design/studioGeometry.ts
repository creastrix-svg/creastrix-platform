/** Shared preview coordinates, independent of React and model-viewer. The
 * lettering box, editor controls and prepared texture faces use this contract.
 * These are visual areas, not manufacturing tolerances or exported toolpaths. */
export type StudioArea = Readonly<{ left: number; right: number; top: number; bottom: number }>;
export const LETTERING_INSET = 4;
export const loopLetteringArea: StudioArea = { left: 157, right: 323, top: 223, bottom: 332 };

export const lampBaseMapping = { width: 1536, height: 520, scale: 4.2, centerX: 768, centerY: 260, editorX: 240, editorY: 278 } as const;
export const lampBaseLetteringArea: StudioArea = {
  left: lampBaseMapping.editorX - lampBaseMapping.centerX / lampBaseMapping.scale,
  right: lampBaseMapping.editorX + (lampBaseMapping.width - lampBaseMapping.centerX) / lampBaseMapping.scale,
  top: lampBaseMapping.editorY - lampBaseMapping.centerY / lampBaseMapping.scale,
  bottom: lampBaseMapping.editorY + (lampBaseMapping.height - lampBaseMapping.centerY) / lampBaseMapping.scale,
};
/** The same complete base face in the lamp's 2D drawing coordinates. */
export const lampBaseFace = { x: 126.25, y: 319.5, width: 227.5, height: 77 } as const;
export function lampBasePoint(x: number, y: number) {
  return { x: lampBaseMapping.centerX + (x - lampBaseMapping.editorX) * lampBaseMapping.scale, y: lampBaseMapping.centerY + (y - lampBaseMapping.editorY) * lampBaseMapping.scale };
}

/** Stencil text stays centred in the prepared solid band below the motif. */
export const lampCutTextArea: StudioArea = { left: 153, right: 327, top: 254, bottom: 302 };
export const lampPanelTextMapping = { width: 960, height: 1280, scale: 4, centerX: 120, centerY: 286, editorX: 240, editorY: 278, left: 37, top: 268, bandWidth: 166, bandHeight: 40 } as const;
export function lampPanelTextPoint(x: number, y: number) {
  return { x: lampPanelTextMapping.centerX + x - lampPanelTextMapping.editorX, y: lampPanelTextMapping.centerY + y - lampPanelTextMapping.editorY };
}
