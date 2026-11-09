import type { ModelViewerElement } from '@google/model-viewer';
import type { Material, RGBA, Texture } from '@google/model-viewer/lib/features/scene-graph/api';
import type { Lettering } from './personalization';
import type { ImagePlacement } from './studioImage';
import type { LampPatternId } from './lampPatterns';
import { imageBounds } from './studioImage';
import { studioFontFamily, supportsStencilText } from './studioFonts';
import { lampBaseMapping, lampBasePoint, lampPanelTextMapping, lampPanelTextPoint } from './studioGeometry';
import type { StudioObject } from './studioComposition';
import { compositionKey } from './studioComposition';
import { paintComposition } from './studioCompositionCanvas';
import { lampPreviewInk, lampPreviewWood } from './lampPreviewStyle';
export { lampBaseMapping, lampBasePoint, lampPanelTextMapping, lampPanelTextPoint } from './studioGeometry';

/** panelText is supplied only after the owning studio has loaded the prepared
 * stencil and checked layout. Pending/failed loads must pass undefined rather
 * than permitting a browser fallback font to look like a valid cut stencil. */
export type LampDecoration = { value: Lettering; artwork?: ImagePlacement; panelText?: Lettering; baseObjects?: readonly StudioObject[]; panelObjects?: readonly StudioObject[]; showBaseArea?: boolean; showPanelTextArea?: boolean };
export const lampModelSource = (pattern: LampPatternId) => `/product/studio-lamp-${pattern}.glb`;
export const supportsLampPanelText = (value: Lettering) => value.font === 'stencil' && supportsStencilText(value.text);
type Surface = { material: Material; texture: Texture; originalTexture: Texture | null; originalFactor: RGBA; canvas: HTMLCanvasElement; context: CanvasRenderingContext2D };
type SurfaceKind = 'base' | 'panel';

/** Public model-viewer material adapter. Decoded assets are synchronous inputs;
 * Each prepared face owns a bounded canvas and never changes mesh geometry.
 * Selection markers are separate from the artwork; neither requires emission. */
export function createLampModel(viewer: ModelViewerElement) {
  const surfaces = new Map<SurfaceKind, Surface>(), previous = new Map<SurfaceKind, string>();
  let destroyed = false, failed = false;
  const restore = (surface?: Surface) => {
    if (!surface) return;
    try { surface.material.pbrMetallicRoughness.baseColorTexture?.setTexture(surface.originalTexture); } catch { /* Release must not interrupt camera cleanup. */ }
    try { surface.material.pbrMetallicRoughness.setBaseColorFactor(surface.originalFactor); } catch { /* Keep the original solid mesh available on failure. */ }
  };
  const clear = (kind: SurfaceKind) => { restore(surfaces.get(kind)); previous.delete(kind); };
  const release = () => {
    for (const surface of surfaces.values()) { restore(surface); surface.canvas.width = 1; surface.canvas.height = 1; }
    surfaces.clear(); previous.clear();
  };
  const surfaceFor = (kind: SurfaceKind): Surface => {
    const existing = surfaces.get(kind);
    if (existing) return existing;
    const material = viewer.model?.materials.find(material => material.name === (kind === 'base' ? 'Studio lamp base engraving' : 'Studio lamp front panel'));
    if (!material?.pbrMetallicRoughness.baseColorTexture) throw new Error('LAMP_PREPARED_MATERIAL_UNAVAILABLE');
    const texture = viewer.createCanvasTexture(), canvas = texture.source.element;
    if (!(canvas instanceof HTMLCanvasElement)) throw new Error('LAMP_PREPARED_CANVAS_UNAVAILABLE');
    const mapping = kind === 'base' ? lampBaseMapping : lampPanelTextMapping;
    canvas.width = mapping.width; canvas.height = mapping.height;
    const context = canvas.getContext('2d');
    if (!context) { canvas.width = 1; canvas.height = 1; throw new Error('LAMP_PREPARED_CONTEXT_UNAVAILABLE'); }
    const surface = { material, texture, canvas, context, originalTexture: material.pbrMetallicRoughness.baseColorTexture.texture, originalFactor: [...material.pbrMetallicRoughness.baseColorFactor] as RGBA };
    // Register ownership before sampler configuration, so an API failure still
    // shrinks this exact canvas and restores this exact material.
    surfaces.set(kind, surface);
    texture.sampler.setWrapS(33071); texture.sampler.setWrapT(33071);
    return surface;
  };
  return {
    update(decoration?: LampDecoration): boolean {
      if (destroyed) return false;
      if (!decoration) { clear('base'); clear('panel'); return true; }
      const hasContent = (objects: readonly StudioObject[]) => objects.some(object => object.kind === 'text' ? object.value.text.trim() : object.value.image);
      const base = (decoration.baseObjects ? hasContent(decoration.baseObjects) : !!decoration.value.text.trim() || !!decoration.artwork?.image) || !!decoration.showBaseArea;
      const requestedPanel = decoration.panelObjects ? hasContent(decoration.panelObjects) : !!decoration.panelText?.text.trim();
      const panel = requestedPanel && (decoration.panelObjects ? decoration.panelObjects.every(object => object.kind !== 'text' || !object.value.text.trim() || supportsLampPanelText(object.value)) : supportsLampPanelText(decoration.panelText!));
      const panelSurface = panel || !!decoration.showPanelTextArea;
      if (!base) clear('base');
      if (!panelSurface) clear('panel');
      if (!base && !panelSurface) return !requestedPanel;
      if (failed) return false;
      try {
        const baseKey = JSON.stringify({ value: decoration.value, objects: decoration.baseObjects ? compositionKey(decoration.baseObjects) : undefined, artwork: decoration.artwork ? { ...decoration.artwork, image: decoration.artwork.image?.id } : null, showArea: decoration.showBaseArea });
        if (base && baseKey !== previous.get('base')) {
          const { context, material, texture } = surfaceFor('base');
          context.setTransform(1,0,0,1,0,0);
          context.fillStyle = lampPreviewWood; context.fillRect(0,0,lampBaseMapping.width,lampBaseMapping.height);
          if (decoration.baseObjects) {
            const origin = lampBasePoint(0, 0);
            context.save(); context.translate(origin.x, origin.y); context.scale(lampBaseMapping.scale, lampBaseMapping.scale);
            paintComposition(context, decoration.baseObjects, 'lamp'); context.restore();
          } else {
          if (decoration.artwork?.image) {
            const box = imageBounds(decoration.artwork), point = lampBasePoint(box.x, box.y);
            context.save(); context.filter = 'grayscale(1) sepia(.65)'; context.globalAlpha = .8;
            context.drawImage(decoration.artwork.image.canvas, point.x - box.width * lampBaseMapping.scale / 2, point.y - box.height * lampBaseMapping.scale / 2, box.width * lampBaseMapping.scale, box.height * lampBaseMapping.scale);
            context.restore();
          }
          const { value } = decoration, point = lampBasePoint(value.x, value.y);
          context.save(); context.translate(point.x, point.y); context.scale(lampBaseMapping.scale, lampBaseMapping.scale);
          context.fillStyle = lampPreviewInk; context.font = `${value.size}px ${studioFontFamily(value.font)}`;
          context.textAlign = 'center'; context.textBaseline = 'middle';
          const lines = value.text.split('\n');
          lines.forEach((line, index) => context.fillText(line, 0, (index - (lines.length - 1) / 2) * value.size * 1.2));
          context.restore();
          }
          if (decoration.showBaseArea) {
            // The outer edge of the stroke is the complete prepared face. This
            // guide uses the same texture as engraving and follows mesh motion.
            context.save(); context.strokeStyle = '#43735d'; context.lineWidth = 8; context.setLineDash([24,24]);
            context.strokeRect(4,4,lampBaseMapping.width - 8,lampBaseMapping.height - 8); context.restore();
          }
          material.pbrMetallicRoughness.setBaseColorFactor([1,1,1,1]);
          material.pbrMetallicRoughness.baseColorTexture!.setTexture(texture); texture.source.update(); previous.set('base', baseKey);
        }
        const panelText = decoration.panelText, panelKey = JSON.stringify({ panelText: panel ? panelText : undefined, objects: panel && decoration.panelObjects ? compositionKey(decoration.panelObjects) : undefined, showArea: decoration.showPanelTextArea });
        if (panelSurface && panelKey !== previous.get('panel')) {
          const { context, material, texture } = surfaceFor('panel'), mapping = lampPanelTextMapping;
          context.setTransform(1,0,0,1,0,0);
          context.fillStyle = lampPreviewWood; context.fillRect(0,0,mapping.width,mapping.height);
          context.save(); context.scale(mapping.scale,mapping.scale);
          // Bound even a stale/invalid draft to the reserved band. The caller
          // separately validates fit and loaded stencil glyph support.
          context.beginPath(); context.rect(mapping.left,mapping.top,mapping.bandWidth,mapping.bandHeight); context.clip();
          if (panel && decoration.panelObjects) {
            const origin = lampPanelTextPoint(0, 0);
            context.save(); context.translate(origin.x, origin.y);
            paintComposition(context, decoration.panelObjects, 'lamp-panel'); context.restore();
          } else if (panel && panelText) {
            const point = lampPanelTextPoint(panelText.x, panelText.y);
            context.textAlign = 'center'; context.textBaseline = 'middle';
            context.font = `${panelText.size}px ${studioFontFamily(panelText.font)}`;
            context.fillStyle = lampPreviewInk; context.fillText(panelText.text,point.x,point.y);
          }
          if (decoration.showPanelTextArea) {
            context.strokeStyle = '#43735d'; context.lineWidth = 1.5; context.setLineDash([5,5]);
            context.strokeRect(mapping.left + .75,mapping.top + .75,mapping.bandWidth - 1.5,mapping.bandHeight - 1.5);
          }
          context.restore();
          material.pbrMetallicRoughness.setBaseColorFactor([1,1,1,1]);
          material.pbrMetallicRoughness.baseColorTexture!.setTexture(texture);
          // GLB panel caps retain extrusion grid UVs × 8: U [-1920,1920],
          // V [0,5120]. A public sampler transform maps them to one canvas.
          // setTexture restores TextureInfo's transform, so apply ours last.
          texture.sampler.setScale({ u:1 / 3840, v:1 / 5120 });
          texture.sampler.setOffset({ u:.5, v:0 });
          texture.source.update(); previous.set('panel',panelKey);
        }
        return !requestedPanel || panel;
      } catch { release(); failed = true; return false; }
    },
    destroy() { if (!destroyed) { destroyed = true; release(); } },
  };
}
