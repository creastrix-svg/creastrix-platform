import { useId } from 'react';
import { Icon } from './Icon';
import { imageBounds } from './studioImage';
import type { ImagePlacement } from './studioImage';
import { studioExtensionCopy } from './studioExtensionCopy';
import { personalizationCopy } from './personalizationCopy';
import { useProductLocale } from './useProductLocale';
import { imageLayout } from './studioComposition';
import type { ImageFit } from './studioComposition';
import type { StudioArea } from './studioGeometry';
import { studioCompositionCopy } from './studioCompositionCopy';

export default function StudioArtworkTools({ value, lamp, onChange, area, fit = 'free', onFitChange, endGroup, pending, onReplace }: {
  value: ImagePlacement; lamp: boolean; pending: boolean; onReplace: () => void;
  onChange: (value: ImagePlacement, group?: string) => void;
  area?: StudioArea; fit?: ImageFit; onFitChange?: (fit: ImageFit) => void; endGroup?: () => void;
}) {
  const locale = useProductLocale(), e = studioExtensionCopy[locale], p = personalizationCopy[locale], id = useId();
  const c = studioCompositionCopy[locale];
  const position = area ? imageLayout(value, fit, area) : imageBounds(value);
  const bounds = area ?? { left: 160, right: 320, top: 226, bottom: 329 };
  const minX = bounds.left + position.width / 2, maxX = bounds.right - position.width / 2;
  const minY = bounds.top + position.height / 2, maxY = bounds.bottom - position.height / 2;
  const fixedFit = !!area && fit !== 'free';
  return <div className="studio-artwork-tools">
    {lamp && <p className="studio-reference-note">{e.engravingReference}</p>}
    {value.image && <>
      <button type="button" className="studio-replace product-secondary" disabled={pending} onClick={onReplace}><img src={value.image.src} alt="" />{e.replaceImage}</button>
      {area && onFitChange && <fieldset className="studio-image-fit"><legend>{c.fit}</legend><div>{(['free', 'contain', 'cover'] as const).map(mode => <button type="button" key={mode} aria-pressed={mode === fit} disabled={pending} onClick={() => { endGroup?.(); onFitChange(mode); }}>{c[mode]}</button>)}</div><p>{c.fitHint}</p></fieldset>}
      <div className="studio-range"><label htmlFor={id + '-size'}>{e.imageSize}<output>{Math.round(value.size)}{area ? '' : '%'}</output></label><input id={id + '-size'} type="range" min={20} max={150} value={value.size} disabled={pending || fixedFit} onChange={event => onChange({ ...value, size: Number(event.target.value) }, 'image-size')} onBlur={endGroup} onPointerUp={endGroup} /></div>
      {(!lamp || area) && <details className="studio-position"><summary>{e.imagePosition}<Icon name="chevron" /></summary><div>
        <div className="studio-range"><label htmlFor={id + '-x'}>{p.horizontal}</label><input id={id + '-x'} type="range" min={Math.min(minX, maxX)} max={Math.max(minX, maxX)} step="any" value={position.x} disabled={pending || fixedFit || minX >= maxX} onChange={event => onChange({ ...value, x: Number(event.target.value) }, 'image-position')} onBlur={endGroup} onPointerUp={endGroup} /></div>
        <div className="studio-range"><label htmlFor={id + '-y'}>{p.vertical}</label><input id={id + '-y'} type="range" min={Math.min(minY, maxY)} max={Math.max(minY, maxY)} step="any" value={position.y} disabled={pending || fixedFit || minY >= maxY} onChange={event => onChange({ ...value, y: Number(event.target.value) }, 'image-position')} onBlur={endGroup} onPointerUp={endGroup} /></div>
      </div></details>}
    </>}
  </div>;
}
