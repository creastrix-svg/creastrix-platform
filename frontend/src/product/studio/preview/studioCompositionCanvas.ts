import type { LetteringTarget } from '../design/personalization';
import type { StudioObject } from '../design/studioComposition';
import { imageLayout, studioArea } from '../design/studioComposition';
import { studioFontFamily } from '../design/studioFonts';
import { lampPreviewInk } from './lampPreviewStyle';

/** The caller has mapped editor coordinates onto one prepared texture face.
 * Images retain aspect ratio; cover is cropped by the same zone as the SVG. */
export function paintComposition(context: CanvasRenderingContext2D, objects: readonly StudioObject[], target: LetteringTarget) {
  const area = studioArea(target);
  context.save(); context.beginPath(); context.rect(area.left, area.top, area.right - area.left, area.bottom - area.top); context.clip();
  for (const object of objects) {
    context.save();
    if (object.kind === 'image' && object.value.image) {
      const box = imageLayout(object.value, object.fit, area);
      if (target.startsWith('lamp')) { context.filter = 'grayscale(1) sepia(.65)'; context.globalAlpha = .8; }
      context.drawImage(object.value.image.canvas, box.x - box.width / 2, box.y - box.height / 2, box.width, box.height);
    } else if (object.kind === 'text') {
      const value = object.value;
      context.font = `${value.size}px ${studioFontFamily(value.font)}`;
      context.fillStyle = target === 'lamp' || target === 'lamp-panel' ? lampPreviewInk : value.color;
      context.textAlign = 'center'; context.textBaseline = 'middle';
      value.text.split('\n').forEach((line, index, lines) => context.fillText(line, value.x, value.y + (index - (lines.length - 1) / 2) * value.size * 1.2));
    }
    context.restore();
  }
  context.restore();
}
