import type { ModelViewerElement } from '@google/model-viewer';
import type { Material, RGBA, Texture } from '@google/model-viewer/lib/features/scene-graph/api';
import type { VariantId } from '../../fixtures/loop';
import { studioFontFamily } from '../design/studioFonts';
import { imageBounds } from '../design/studioImage';
import type { ImagePlacement } from '../design/studioImage';
import type { StudioFontId } from '../design/studioFonts';
import { loopLetteringArea } from '../design/studioGeometry';
import { projectLoopRing } from './loopSketchProjection';
import type { StudioObject } from '../design/studioComposition';
import { compositionKey } from '../design/studioComposition';
import { paintComposition } from './studioCompositionCanvas';

export type ModelDecoration = {
  text: string; font: StudioFontId; color: '#ffffff' | '#20251f' | '#b68a45';
  size: number; x: number; y: number; variant: VariantId;
  artwork?: ImagePlacement;
  objects?: readonly StudioObject[];
  /** Studio-only guide; omitted when showing an applied product design. */
  showArea?: boolean;
};
export const studioModelSource = (variant: VariantId) => `/product/studio-loop-${variant}.glb`;

type Surface = { material: Material; originalFactor: RGBA; originalTexture: Texture | null; texture: Texture; canvas: HTMLCanvasElement; context: CanvasRenderingContext2D };
const exteriorNames = ['Studio exterior lower', 'Studio exterior upper'];
const width = 2048, height = 1024;
// UV V is the fraction of physical height from the foot. Match the top and
// bottom of the existing editable zone to the front of the photographed shape.
function frontLevelAt(sketchY: number) {
  let low = 0, high = 1;
  for (let i = 0; i < 22; i++) {
    const mid = (low + high) / 2, ring = projectLoopRing(mid);
    if (ring.cy + ring.ry > sketchY) low = mid; else high = mid;
  }
  return (low + high) / 2;
}
const area = loopLetteringArea, top = frontLevelAt(area.top), bottom = frontLevelAt(area.bottom);
const middle = projectLoopRing(frontLevelAt((area.top + area.bottom) / 2));
const horizontalScale = width / (2 * Math.PI * middle.rx);
const verticalScale = height * (top - bottom) / (area.bottom - area.top);
export function loopModelPoint(x: number, y: number) {
  return { x: width / 2 + (x - middle.cx) * horizontalScale, y: height * (1 - top) + (y - area.top) * verticalScale };
}

function srgb(channel: number) { return Math.round(255 * (channel <= .0031308 ? 12.92 * channel : 1.055 * channel ** (1 / 2.4) - .055)); }

/** One synchronous, reusable canvas per exterior material. Images and fonts are
 * already decoded locally by their owning loaders before reaching this adapter.
 * No network, async decode, timer, private scene API or stale promise lives here.
 * Decoration follows the mesh and cannot appear on inner/cap faces.
 */
export function createPersonalizationModel(viewer: ModelViewerElement) {
  let surfaces: Surface[] = [], destroyed = false, unavailable = false, previous = '';
  function restore() {
    for (const surface of surfaces) {
      try { surface.material.pbrMetallicRoughness.baseColorTexture?.setTexture(surface.originalTexture); } catch { /* Preserve shape even when material access fails. */ }
      try { surface.material.pbrMetallicRoughness.setBaseColorFactor(surface.originalFactor); } catch { /* Disposal must not break the viewer lifecycle. */ }
    }
  }
  function release() {
    restore();
    // v4.0.0 exposes no public texture.dispose(). Detach through the public API
    // and release large canvas backing stores. This does not prove immediate GPU
    // disposal: cached renderer-resource lifetime remains library-controlled.
    for (const surface of surfaces) { surface.canvas.width = 1; surface.canvas.height = 1; }
    surfaces = [];
  }
  function initialize() {
    if (surfaces.length) return;
    const materials = exteriorNames.map(name => viewer.model?.materials.find(material => material.name === name));
    if (materials.some(material => !material?.pbrMetallicRoughness.baseColorTexture)) throw new Error('STUDIO_MATERIAL_UNAVAILABLE');
    for (const material of materials as Material[]) {
      const pbr = material.pbrMetallicRoughness, texture = viewer.createCanvasTexture();
      const canvas = texture.source.element;
      if (!(canvas instanceof HTMLCanvasElement)) throw new Error('STUDIO_CANVAS_UNAVAILABLE');
      canvas.width = width; canvas.height = height;
      const context = canvas.getContext('2d');
      if (!context) { canvas.width = 1; canvas.height = 1; throw new Error('STUDIO_CONTEXT_UNAVAILABLE'); }
      surfaces.push({ material, texture, canvas, context, originalFactor: [...pbr.baseColorFactor], originalTexture: pbr.baseColorTexture!.texture });
      texture.sampler.setWrapS(33071); texture.sampler.setWrapT(33071);
    }
  }
  return {
    update(decoration?: ModelDecoration): boolean {
      if (destroyed) return false;
      if (!decoration || (!(decoration.objects ? decoration.objects.some(object => object.kind === 'text' ? object.value.text.trim() : object.value.image) : decoration.text.trim() || decoration.artwork?.image) && !decoration.showArea)) { restore(); previous = ''; return true; }
      if (unavailable) return false;
      const key = JSON.stringify({ ...decoration, objects: decoration.objects ? compositionKey(decoration.objects) : undefined, artwork: decoration.artwork ? { ...decoration.artwork, image: decoration.artwork.image?.id } : undefined });
      if (key === previous) return true;
      try {
        initialize();
        for (const surface of surfaces) {
          const { context, originalFactor, texture, material } = surface;
          context.setTransform(1, 0, 0, 1, 0, 0);
          context.fillStyle = `rgb(${originalFactor.slice(0, 3).map(srgb).join(',')})`;
          context.fillRect(0, 0, width, height);
          if (decoration.objects) {
            const origin = loopModelPoint(0, 0);
            context.save(); context.translate(origin.x, origin.y); context.scale(horizontalScale, verticalScale);
            paintComposition(context, decoration.objects, decoration.variant); context.restore();
          } else {
          if (decoration.artwork?.image) {
            const box = imageBounds(decoration.artwork);
            const { x,y } = loopModelPoint(box.x,box.y);
            context.drawImage(decoration.artwork.image.canvas, x - box.width * horizontalScale / 2, y - box.height * verticalScale / 2, box.width * horizontalScale, box.height * verticalScale);
          }
          context.save();
          // The home camera faces U=.5. Canvas y runs down; exterior V runs up.
          const point = loopModelPoint(decoration.x,decoration.y);
          context.translate(point.x,point.y);
          context.scale(horizontalScale, verticalScale);
          context.font = `${decoration.size}px ${studioFontFamily(decoration.font)}`;
          context.fillStyle = decoration.color; context.textAlign = 'center'; context.textBaseline = 'middle';
          const lines = decoration.text.split('\n');
          lines.forEach((line, index) => context.fillText(line, 0, (index - (lines.length - 1) / 2) * decoration.size * 1.2));
          context.restore();
          }
          if (decoration.showArea) {
            const area = loopLetteringArea, corner = loopModelPoint(area.left,area.top);
            context.save(); context.translate(corner.x,corner.y); context.scale(horizontalScale,verticalScale);
            context.strokeStyle = decoration.color === '#ffffff' ? '#ffffff99' : '#20251f99';
            context.lineWidth = 1; context.setLineDash([4,5]);
            context.strokeRect(0,0,area.right - area.left,area.bottom - area.top); context.restore();
          }
          material.pbrMetallicRoughness.setBaseColorFactor([1, 1, 1, 1]);
          material.pbrMetallicRoughness.baseColorTexture!.setTexture(texture);
          texture.source.update();
        }
        previous = key;
        return true;
      } catch {
        release(); unavailable = true; previous = '';
        return false;
      }
    },
    destroy() { if (!destroyed) { destroyed = true; release(); } },
  };
}
