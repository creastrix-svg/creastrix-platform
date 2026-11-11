import { useId } from 'react';
import type { LetteringTarget } from '../design/personalization';
import type { StudioObject } from '../design/studioComposition';
import { imageLayout, studioArea } from '../design/studioComposition';
import { studioFontFamily, supportsStencilText } from '../design/studioFonts';
import { useSketchDrag } from './useSketchDrag';
import { lampPreviewInk } from './lampPreviewStyle';

export type StudioObjectInteraction = {
  selectedId?: string | null;
  onSelectObject?: (id: string) => void;
  onMoveObject?: (id: string, x: number, y: number) => void;
  onMoveEnd?: () => void;
};
type Props = StudioObjectInteraction & {
  objects: readonly StudioObject[];
  target: LetteringTarget;
  editorScale?: number | { x: number; y: number };
};

/** Objects share editor coordinates with canvas rendering. The caller maps the
 * complete layer onto the prepared face; clipping never changes mesh geometry. */
export default function StudioObjectsSketch({ objects, target, ...interaction }: Props) {
  const id = useId(), area = studioArea(target);
  return <g data-studio-composition={target}>
    <defs><clipPath id={id}><rect x={area.left} y={area.top} width={area.right - area.left} height={area.bottom - area.top} /></clipPath></defs>
    <g clipPath={`url(#${id})`}>{objects.map((object, index) => <ObjectSketch key={object.id} object={object} index={index} target={target} {...interaction} />)}</g>
  </g>;
}

function ObjectSketch({ object, index, target, selectedId, onSelectObject, onMoveObject, onMoveEnd, editorScale = 1 }: Omit<Props, 'objects'> & { object: StudioObject; index: number }) {
  const area = studioArea(target), image = object.kind === 'image' ? object.value.image : null;
  const box = object.kind === 'image' ? imageLayout(object.value, object.fit, area) : null;
  const movable = object.kind === 'text' || object.fit === 'free';
  const drag = useSketchDrag({ text: object.kind === 'text' ? object.value.text : image ? 'image' : '', x: box?.x ?? object.value.x, y: box?.y ?? object.value.y }, movable && onMoveObject ? (x, y) => onMoveObject(object.id, x, y) : undefined, onMoveEnd, editorScale);
  const selected = selectedId === object.id, interactive = !!onSelectObject;
  const ink = target === 'lamp' || target === 'lamp-panel' ? lampPreviewInk : object.kind === 'text' ? object.value.color : undefined;
  const label = object.kind === 'text' && object.value.text ? object.value.text : String(index + 1);
  if (target === 'lamp-panel' && object.kind === 'text' && (object.value.font !== 'stencil' || !supportsStencilText(object.value.text))) return null;
  return <g data-studio-object={object.id} data-object-kind={object.kind} data-object-selected={selected || undefined}
    className={movable && onMoveObject ? 'studio-movable-text' : undefined}
    pointerEvents={interactive ? undefined : 'none'}
    role={interactive ? 'button' : undefined} tabIndex={interactive ? 0 : undefined} aria-label={interactive ? label : undefined} aria-pressed={interactive ? selected : undefined}
    {...drag.handlers}
    onPointerDown={event => { event.stopPropagation(); onSelectObject?.(object.id); drag.handlers.onPointerDown(event); }}
    onClick={event => { event.stopPropagation(); if (!drag.consumeClick()) onSelectObject?.(object.id); }}
    onKeyDown={event => { if (interactive && (event.key === 'Enter' || event.key === ' ')) { event.preventDefault(); event.stopPropagation(); onSelectObject(object.id); } }}>
    {object.kind === 'image' && image && box && <>
      <image href={image.src} x={box.x - box.width / 2} y={box.y - box.height / 2} width={box.width} height={box.height} preserveAspectRatio="none" style={target.startsWith('lamp') ? { filter: 'grayscale(1) sepia(.65)' } : undefined} opacity={target.startsWith('lamp') ? .8 : 1} />
      {selected && <rect x={box.x - box.width / 2} y={box.y - box.height / 2} width={box.width} height={box.height} fill="none" stroke="#43735d" strokeWidth="1" strokeDasharray="3 2" pointerEvents="none" />}
    </>}
    {object.kind === 'text' && <text textAnchor="middle" dominantBaseline="central" fill={ink} fontFamily={studioFontFamily(object.value.font)} fontSize={object.value.size} style={{ whiteSpace: 'pre', textDecoration: selected ? 'underline' : undefined }}>
      {object.value.text.split('\n').map((line, lineIndex, lines) => <tspan key={lineIndex} x={object.value.x} y={object.value.y + (lineIndex - (lines.length - 1) / 2) * object.value.size * 1.2}>{line}</tspan>)}
    </text>}
  </g>;
}
