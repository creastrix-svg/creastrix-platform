import { useId } from 'react';
import type { VariantId } from '../../fixtures/loop';
import { variantById } from '../../fixtures/loop';
import { loopLetteringArea as letteringArea } from '../design/studioGeometry';
import { useSketchDrag } from './useSketchDrag';
import { studioFontFamily } from '../design/studioFonts';
import type { Lettering } from '../design/personalization';
import { imageBounds } from '../design/studioImage';
import type { ImagePlacement } from '../design/studioImage';
import type { StudioObject } from '../design/studioComposition';
import StudioObjectsSketch from './StudioObjectsSketch';
import type { StudioObjectInteraction } from './StudioObjectsSketch';
import { loopProjection } from './loopSketchProjection';
import type { SwatchColors } from '../../productDisplay';

type Props = StudioObjectInteraction & { objects?: readonly StudioObject[]; variant: VariantId; colors?:SwatchColors; value: Lettering; artwork?: ImagePlacement; label: string; areaLabel?: string; guides?: boolean; ghost?: string; detail?: boolean; onMove?: (x: number, y: number) => void; onMoveEnd?: () => void };

/** A front elevation, deliberately labelled as a sketch rather than a photograph. */
export default function LetteringSketch({ variant, colors: appliedColors, value, artwork, objects, selectedId, onSelectObject, onMoveObject, label, areaLabel, guides = false, ghost = '', detail = false, onMove, onMoveEnd }: Props) {
  const id = useId(), colors = appliedColors ?? variantById(variant).colors;
  const drag = useSketchDrag(value, onMove, onMoveEnd);
  const { body, lower, layers, rim, opening, foot } = loopProjection;
  const lines = value.text.split('\n');
  const placement = artwork ? imageBounds(artwork) : null;
  return <svg className="studio-sketch" viewBox={detail ? '80 105 320 330' : '0 0 480 480'} role={onSelectObject ? 'group' : 'img'} aria-label={label}>
    <defs>
      <linearGradient id={id + '-shade'}>
        <stop stopColor="#fff" stopOpacity=".07" /><stop offset=".18" stopColor="#fff" stopOpacity=".23" />
        <stop offset=".48" stopColor="#fff" stopOpacity=".02" /><stop offset=".78" stopColor="#101624" stopOpacity=".16" />
        <stop offset="1" stopColor="#101624" stopOpacity=".34" />
      </linearGradient>
      <linearGradient id={id + '-depth'} x1="0" x2="0" y1="0" y2="1">
        <stop stopColor="#17202a" stopOpacity="0" /><stop offset=".54" stopColor="#17202a" stopOpacity="0" /><stop offset="1" stopColor="#17202a" stopOpacity=".26" />
      </linearGradient>
      <linearGradient id={id + '-inside'}>
        <stop stopColor="#0b1020" stopOpacity=".76" /><stop offset=".48" stopColor="#101525" stopOpacity=".62" />
        <stop offset="1" stopColor="#101525" stopOpacity=".27" />
      </linearGradient>
      <radialGradient id={id + '-shadow'}><stop stopColor="#242526" stopOpacity=".22" /><stop offset="1" stopColor="#242526" stopOpacity="0" /></radialGradient>
      <clipPath id={id + '-clip'}><path d={body} /></clipPath>
    </defs>
    <ellipse cx={foot.cx} cy={foot.cy + foot.ry * .65} rx={foot.rx * 1.4} ry={foot.ry * 1.1} fill={'url(#' + id + '-shadow)'} />
    <g aria-hidden="true">
      <path d={body} fill={colors[0]} />
      {colors[1] && <path d={lower} fill={colors[1]} />}
      <path d={body} fill={'url(#' + id + '-shade)'} />
      <path d={body} fill={'url(#' + id + '-depth)'} />
      <path d={layers} clipPath={'url(#' + id + '-clip)'} fill="none" stroke="#151b29" strokeOpacity=".14" strokeWidth=".55" />
      <ellipse cx={rim.cx} cy={rim.cy} rx={rim.rx} ry={rim.ry} fill={colors[0]} />
      <ellipse cx={rim.cx} cy={rim.cy} rx={rim.rx} ry={rim.ry} fill="#fff" fillOpacity=".22" />
      <ellipse cx={opening.cx} cy={opening.cy} rx={opening.rx} ry={opening.ry} fill={colors[0]} />
      <ellipse cx={opening.cx} cy={opening.cy} rx={opening.rx} ry={opening.ry} fill={'url(#' + id + '-inside)'} />
    </g>
    {objects === undefined && artwork?.image && placement && <image href={artwork.image.src} x={placement.x - placement.width / 2} y={placement.y - placement.height / 2} width={placement.width} height={placement.height} clipPath={'url(#' + id + '-clip)'} />}
    {guides && <g className="studio-area" aria-label={areaLabel}>
      <rect x={letteringArea.left} y={letteringArea.top} width={letteringArea.right - letteringArea.left} height={letteringArea.bottom - letteringArea.top} fill="#ffffff05" stroke={value.color === '#ffffff' ? '#ffffff99' : '#20251f99'} strokeWidth="1" strokeDasharray="4 5" />
    </g>}
    {objects ? <StudioObjectsSketch objects={objects} target={variant} selectedId={selectedId} onSelectObject={onSelectObject} onMoveObject={onMoveObject} onMoveEnd={onMoveEnd} /> : <g className={onMove && value.text ? 'studio-movable-text' : undefined} clipPath={'url(#' + id + '-clip)'}
      {...drag.handlers}>
      {value.text && onMove && <rect x={value.x - 80} y={value.y - lines.length * value.size * .6 - 6} width="160" height={lines.length * value.size * 1.2 + 12} fill="transparent" />}
      <text textAnchor="middle" dominantBaseline="central" fill={value.color} fontFamily={studioFontFamily(value.font)} fontSize={value.size} style={{ whiteSpace: 'pre' }}>
        {value.text ? lines.map((line, index) => <tspan key={index} x={value.x} y={value.y + (index - (lines.length - 1) / 2) * value.size * 1.2}>{line}</tspan>) : ghost ? <tspan x="240" y="278" fillOpacity=".55" fontSize="25">{ghost}</tspan> : null}
      </text>
    </g>}
  </svg>;
}
