import { Icon } from '../../Icon';
import { MAX_STUDIO_OBJECTS } from '../design/studioComposition';
import type { StudioObject } from '../design/studioComposition';
import { studioCompositionCopy } from '../design/studioCompositionCopy';
import { useProductLocale } from '../../useProductLocale';

export default function StudioObjectTools({ objects, selectedId, onSelect, onAdd, onDelete, onReorder, canAddText, canAddImage, imagePending = false, imageHintId }: {
  objects: readonly StudioObject[]; selectedId: string | null;
  onSelect: (id: string) => void; onAdd: (kind: 'text' | 'image') => void;
  onDelete: () => void; onReorder: (direction: -1 | 1) => void;
  canAddText: boolean; canAddImage: boolean; imagePending?: boolean; imageHintId?: string;
}) {
  const c = studioCompositionCopy[useProductLocale()];
  const selectedIndex = objects.findIndex(object => object.id === selectedId), full = objects.length >= MAX_STUDIO_OBJECTS;
  return <section className="studio-object-tools" aria-label={c.objects}>
    <div className="studio-object-heading"><h2>{c.objects}</h2><span>{objects.length} / {MAX_STUDIO_OBJECTS}</span></div>
    {objects.length ? <ol className="studio-object-list">{objects.map((object, index) => {
      const kind = object.kind === 'text' ? c.text : c.image;
      const description = object.kind === 'text' ? object.value.text.trim() || c.emptyText : c.image;
      return <li key={object.id}><button type="button" aria-label={`${kind} ${index + 1}: ${description}`} aria-pressed={object.id === selectedId} onClick={() => onSelect(object.id)}>
        {object.kind === 'image' && object.value.image ? <img src={object.value.image.src} alt="" /> : <Icon name="layers" />}<span>{description}</span><small>{index + 1}</small>
      </button></li>;
    })}</ol> : <p className="studio-object-empty">{c.empty}</p>}
    <div className="studio-object-add">
      {canAddText && <button type="button" className="product-secondary" disabled={full} onClick={() => onAdd('text')}><Icon name="layers" />{c.addText}</button>}
      {canAddImage && <button type="button" className="product-secondary" disabled={full || imagePending} aria-describedby={imageHintId} onClick={() => onAdd('image')}><Icon name="image" />{c.addImage}</button>}
    </div>
    <div className="studio-object-actions">
      <button type="button" disabled={selectedIndex <= 0} onClick={() => onReorder(-1)}>{c.backward}</button>
      <button type="button" disabled={selectedIndex < 0 || selectedIndex === objects.length - 1} onClick={() => onReorder(1)}>{c.forward}</button>
      <button type="button" className="product-icon-button" aria-label={c.deleteObject} title={c.deleteObject} disabled={selectedIndex < 0} onClick={onDelete}><Icon name="trash" /></button>
    </div>
    {full && <p className="studio-object-limit" role="status">{c.limit}</p>}
  </section>;
}
