import { useId, useRef, useState } from 'react';
import LampSketch from './LampSketch';
import type { CreativeDraft } from '../session/studioCreativeContext';
import type { Lettering } from '../design/personalization';
import { imageBounds } from '../design/studioImage';
import { studioFontFamily } from '../design/studioFonts';
import { supportsLampPanelText } from './lampModel';
import { lampBaseFace as baseFace, lampBaseMapping, lampBasePoint, lampPanelTextMapping, lampPanelTextPoint } from '../design/studioGeometry';
import { useSketchDrag } from './useSketchDrag';
import { lampPanel } from './lampPatterns';
import type { LampZone } from './lampZoneSelection';
import type { StudioObject } from '../design/studioComposition';
import StudioObjectsSketch from './StudioObjectsSketch';
import type { StudioObjectInteraction } from './StudioObjectsSketch';
import { lampPreviewInk } from './lampPreviewStyle';

type ZoneNames = Record<LampZone, string>;
const panelTransform = 'translate(135 30.25) scale(.875)';

function ZoneShape({ zone, className }: { zone: LampZone; className: string }) {
  return zone === 'panel'
    ? <path className={className} d={lampPanel.shape} transform={panelTransform} />
    : <rect className={className} {...baseFace} />;
}

function ZoneTarget({ zone, selected, name, onSelect, drag }: { zone: LampZone; selected: boolean; name: string; onSelect: (zone: LampZone) => void; drag?: ReturnType<typeof useSketchDrag> }) {
  const [hovered, setHovered] = useState(false), spacePressed = useRef(false);
  return <g className={'studio-sketch-zone' + (drag ? ' studio-movable-text' : '')} role="button" tabIndex={0} aria-label={name} aria-pressed={selected}
    data-sketch-zone={zone} data-hovered={hovered} data-lamp-active-zone={selected ? zone : undefined}
    {...drag?.handlers}
    onPointerEnter={event => { if (event.pointerType !== 'touch') setHovered(true); }} onPointerLeave={() => setHovered(false)} onPointerCancel={event => { setHovered(false); drag?.handlers.onPointerCancel(event); }}
    onClick={() => { if (!drag?.consumeClick()) onSelect(zone); }} onBlur={() => { spacePressed.current = false; }}
    onKeyDown={event => {
      if (event.key === ' ') { event.preventDefault(); if (!event.repeat) spacePressed.current = true; }
      if (event.key === 'Enter') { event.preventDefault(); if (!event.repeat) onSelect(zone); }
    }} onKeyUp={event => {
      if (event.key === ' ') { event.preventDefault(); if (spacePressed.current) onSelect(zone); spacePressed.current = false; }
    }}>
    <ZoneShape zone={zone} className="studio-sketch-zone-surface" />
    <ZoneShape zone={zone} className="studio-sketch-zone-focus" />
    {selected && <g className="studio-sketch-zone-check" aria-hidden="true" transform={zone === 'panel' ? 'translate(330 47)' : 'translate(340 333)'}>
      <circle r="10" /><path d="M-4 0L-1 3L5-4" />
    </g>}
  </g>;
}

/** Orthographic preview of the same prepared front panel and base as the GLB.
 * Panel geometry is immutable apart from choosing a prepared motif. Engraving
 * is restricted to the base face. Dark stencil lettering shows placement
 * in the panel's reserved band; it is not a generated cut contour. */
export default function LampZoneSketch({ value, panelText, draft, zone, label, onSelectZone, zoneNames, onMove, onMoveEnd, highlightTextBand = false, baseObjects, panelObjects, selectedId, onSelectObject, onMoveObject }: StudioObjectInteraction & {
  baseObjects?: readonly StudioObject[]; panelObjects?: readonly StudioObject[];
  value: Lettering; draft: CreativeDraft; zone: LampZone; label: string;
  panelText?: Lettering;
  onSelectZone?: (zone: LampZone) => void; zoneNames?: ZoneNames;
  onMove?: (x: number, y: number) => void; onMoveEnd?: () => void; highlightTextBand?: boolean;
}) {
  const id = useId(), { width: faceWidth, height: faceHeight, x, y } = baseFace;
  // Noninteractive thumbnails retain image semantics. An interactive SVG must
  // expose its labelled buttons instead of hiding them inside role="img".
  const interactive = !!onSelectZone && !!zoneNames;
  const point = lampBasePoint(value.x, value.y), scaleX = faceWidth / lampBaseMapping.width, scaleY = faceHeight / lampBaseMapping.height;
  const drag = useSketchDrag(value, zone === 'base' && interactive ? onMove : undefined, onMoveEnd, { x: lampBaseMapping.scale * scaleX, y: lampBaseMapping.scale * scaleY });
  const image = draft.artwork.image, bounds = image ? imageBounds(draft.artwork) : null;
  const imagePoint = bounds ? lampBasePoint(bounds.x, bounds.y) : null;
  const panelPoint = panelText && supportsLampPanelText(panelText) ? lampPanelTextPoint(panelText.x,panelText.y) : null;
  return <svg className="studio-sketch studio-lamp-zone-sketch" viewBox="0 0 480 480" role={interactive ? 'group' : 'img'} aria-label={label} data-lamp-zone={zone}>
    <defs>
      <linearGradient id={id + '-wood'}><stop stopColor="#d9ad71" /><stop offset=".5" stopColor="#ecd0a2" /><stop offset="1" stopColor="#cca16a" /></linearGradient>
      <radialGradient id={id + '-shadow'}><stop stopColor="#22342c" stopOpacity=".18" /><stop offset="1" stopColor="#22342c" stopOpacity="0" /></radialGradient>
      <clipPath id={id + '-base'}><rect x={x} y={y} width={faceWidth} height={faceHeight} /></clipPath>
      <clipPath id={id + '-panel-text'}><rect x={lampPanelTextMapping.left} y={lampPanelTextMapping.top} width={lampPanelTextMapping.bandWidth} height={lampPanelTextMapping.bandHeight} /></clipPath>
    </defs>
    <g aria-hidden="true">
    <ellipse cx="240" cy="425" rx="159" ry="21" fill={'url(#' + id + '-shadow)'} />
    <rect x="134" y="19" width="212" height="14" rx="3" fill={'url(#' + id + '-wood)'} />
    <rect x="138" y="30" width="204" height="274" fill="#ffe1a1" />
    <svg x="30" y="-31" width="420" height="420" viewBox="0 0 480 480" aria-hidden="true">
      <LampSketch pattern={draft.pattern} bridges mode="panel" label="" showPattern />
    </svg>
    {panelObjects === undefined && panelText?.text && panelPoint && <g transform={panelTransform} clipPath={'url(#' + id + '-panel-text)'} data-lamp-cut-text="preview">
      <text x={panelPoint.x} y={panelPoint.y} textAnchor="middle" dominantBaseline="central" fill={lampPreviewInk} fontFamily={studioFontFamily(panelText.font)} fontSize={panelText.size} style={{ whiteSpace:'pre' }}>{panelText.text}</text>
    </g>}
    <rect x="107" y="311" width="266" height="95" rx="4" fill={'url(#' + id + '-wood)'} stroke="#ac7f48" />
    <rect x="124" y="406" width="24" height="10" rx="2" fill="#655c48" />
    <rect x="332" y="406" width="24" height="10" rx="2" fill="#655c48" />
    {baseObjects === undefined && <g clipPath={'url(#' + id + '-base)'}>
    <g data-lamp-base-decoration="true" transform={`translate(${x} ${y}) scale(${scaleX} ${scaleY})`}>
      {image && bounds && imagePoint && <image href={image.src} x={imagePoint.x - bounds.width * lampBaseMapping.scale / 2} y={imagePoint.y - bounds.height * lampBaseMapping.scale / 2} width={bounds.width * lampBaseMapping.scale} height={bounds.height * lampBaseMapping.scale} style={{ filter: 'grayscale(1) sepia(.65)' }} opacity=".8" />}
      <text textAnchor="middle" dominantBaseline="central" fill={lampPreviewInk} fontFamily={studioFontFamily(value.font)} fontSize={value.size * lampBaseMapping.scale} style={{ whiteSpace: 'pre' }}>
        {value.text.split('\n').map((line, index, lines) => <tspan key={index} x={point.x} y={point.y + (index - (lines.length - 1) / 2) * value.size * 1.2 * lampBaseMapping.scale}>{line}</tspan>)}
      </text>
    </g>
    </g>}
    </g>
    {interactive ? (['panel', 'base'] as const).map(area => <ZoneTarget key={area} zone={area} selected={zone === area} name={zoneNames![area]} onSelect={onSelectZone!} drag={area === 'base' && zone === 'base' && onMove && value.text ? drag : undefined} />)
      : <rect data-lamp-active-zone={zone} x={zone === 'panel' ? 131 : x} y={zone === 'panel' ? 26 : y} width={zone === 'panel' ? 218 : faceWidth} height={zone === 'panel' ? 283 : faceHeight} fill="none" stroke="#43735d" strokeWidth="1.5" strokeDasharray="5 5" pointerEvents="none" />}
    {highlightTextBand && zone === 'panel' && <rect data-lamp-text-band="true" transform={panelTransform} x={lampPanelTextMapping.left} y={lampPanelTextMapping.top} width={lampPanelTextMapping.bandWidth} height={lampPanelTextMapping.bandHeight} fill="none" stroke="#43735d" strokeWidth="1.5" strokeDasharray="5 5" pointerEvents="none" aria-hidden="true" />}
    {baseObjects && <g transform={`translate(${x} ${y}) scale(${scaleX} ${scaleY}) translate(${lampBaseMapping.centerX - lampBaseMapping.editorX * lampBaseMapping.scale} ${lampBaseMapping.centerY - lampBaseMapping.editorY * lampBaseMapping.scale}) scale(${lampBaseMapping.scale})`}>
      <StudioObjectsSketch objects={baseObjects} target="lamp" selectedId={zone === 'base' ? selectedId : undefined} onSelectObject={zone === 'base' ? onSelectObject : undefined} onMoveObject={zone === 'base' ? onMoveObject : undefined} onMoveEnd={onMoveEnd} editorScale={{ x: scaleX * lampBaseMapping.scale, y: scaleY * lampBaseMapping.scale }} />
    </g>}
    {panelObjects && <g transform={`${panelTransform} translate(${lampPanelTextMapping.centerX - lampPanelTextMapping.editorX} ${lampPanelTextMapping.centerY - lampPanelTextMapping.editorY})`} data-lamp-cut-text="preview">
      <StudioObjectsSketch objects={panelObjects} target="lamp-panel" selectedId={zone === 'panel' ? selectedId : undefined} onSelectObject={zone === 'panel' ? onSelectObject : undefined} onMoveObject={zone === 'panel' ? onMoveObject : undefined} onMoveEnd={onMoveEnd} editorScale={.875} />
    </g>}
  </svg>;
}
