import { lazy, Suspense, useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import LampZoneSketch from './LampZoneSketch';
import MediaErrorBoundary from '../../MediaErrorBoundary';
import Modal from '../../Modal';
import { Icon, Spinner } from '../../Icon';
import { studioExtensionCopy } from '../studioExtensionCopy';
import { personalizationCopy } from '../personalizationCopy';
import { productCopy } from '../../copy';
import { lampModelSource } from './lampModel';
import type { CreativeDraft } from '../session/studioCreativeContext';
import type { Lettering } from '../design/personalization';
import { useProductLocale } from '../../useProductLocale';
import { studioZoneCopy } from '../studioZoneCopy';
import type { StudioObject } from '../design/studioComposition';
import type { StudioObjectInteraction } from './StudioObjectsSketch';

const ModelPreview = lazy(() => new Promise<typeof import('../../ModelPreview')>((resolve, reject) => {
  const deadline = setTimeout(() => reject(new Error('LAMP_PREVIEW_IMPORT_TIMEOUT')), 30_000);
  void import('../../ModelPreview').then(module => { clearTimeout(deadline); resolve(module); }, error => { clearTimeout(deadline); reject(error); });
}));

export default function StudioLampPreview({ value, panelText, draft, zone, onSelectZone, expanded, onExpand, onClose, onMove, onMoveEnd, highlightTextBand = false, baseObjects, panelObjects, selectedId, onSelectObject, onMoveObject }: StudioObjectInteraction & { baseObjects?: readonly StudioObject[]; panelObjects?: readonly StudioObject[]; value: Lettering; panelText?: Lettering; draft: CreativeDraft; zone: 'panel' | 'base'; onSelectZone: (zone: 'panel' | 'base') => void; expanded: boolean; onExpand: () => void; onClose: () => void; onMove?: (x: number, y: number) => void; onMoveEnd?: () => void; highlightTextBand?: boolean }) {
  const locale = useProductLocale(), e = studioExtensionCopy[locale], p = personalizationCopy[locale], t = productCopy[locale], z = studioZoneCopy[locale];
  const [mode, setMode] = useState<'sketch' | 'model'>('model');
  const [inlineHost, setInlineHost] = useState<HTMLDivElement | null>(null);
  const [expandedHost, setExpandedHost] = useState<HTMLDivElement | null>(null);
  const [decorationFailed, setDecorationFailed] = useState(false);
  const decoration = useMemo(() => ({ value, artwork: draft.artwork, panelText, baseObjects, panelObjects, showBaseArea: zone === 'base', showPanelTextArea: zone === 'panel' && highlightTextBand }), [value, draft.artwork, panelText, baseObjects, panelObjects, zone, highlightTextBand]);
  const selection = mode === 'model' ? { selected: zone, onSelect: onSelectZone } : undefined;
  const selectionStatus = <div className="studio-zone-feedback">
    {expanded ? <div className="studio-model-zones" role="group" aria-label={z.zoneLabel}>{(['panel', 'base'] as const).map(area => <button type="button" key={area} aria-pressed={zone === area} onClick={() => onSelectZone(area)}>{z.zoneNames[area]}</button>)}</div> : <span className="studio-zone-current" role="status"><Icon name="check" />{z.zoneNames[zone]}</span>}
    <span>{mode === 'model' ? z.pickHint : z.flatPickHint}</span>
  </div>;
  const tabs = <div className="studio-view-switch" role="group" aria-label={p.preview}>
    <button type="button" aria-pressed={mode === 'sketch'} onClick={() => setMode('sketch')}><Icon name="image" />{z.flatView}</button>
    <button type="button" aria-pressed={mode === 'model'} onClick={() => setMode('model')}><Icon name="cube" />{z.modelView}</button>
  </div>;
  const sketch = <LampZoneSketch value={value} panelText={panelText} baseObjects={baseObjects} panelObjects={panelObjects} selectedId={selectedId} onSelectObject={onSelectObject} onMoveObject={onMoveObject} draft={draft} zone={zone} label={e.lampName + ' · ' + p.edit} zoneNames={z.zoneNames} onSelectZone={onSelectZone} onMove={onMove} onMoveEnd={onMoveEnd} highlightTextBand={highlightTextBand} />;
  // Keep the custom element attached while its host is hidden in 2D. Detaching
  // an already loaded model-viewer can dispose its render scene even though its
  // outer component still reports ready when the user returns to 3D.
  const target = expanded ? expandedHost : inlineHost;
  const fallback = <div className="studio-model-error" role="status"><Icon name="cube" /><p>{p.modelUnavailable}</p><button type="button" className="product-secondary" onClick={() => setMode('sketch')}>{z.flatView}</button></div>;
  return <div className="studio-preview-card studio-lamp-preview">
    <div className="studio-preview-toolbar"><span>{e.lampName}</span>{tabs}<button type="button" className="product-icon-button" aria-label={p.expand} onClick={onExpand}><Icon name="expand" /></button></div>
    <div className={'studio-stage studio-lamp-stage studio-lamp-' + mode}>
      <div className="studio-model-host" ref={setInlineHost} hidden={expanded || mode !== 'model'} />
      {!expanded && mode === 'sketch' && sketch}
    </div>
    {!expanded && selectionStatus}
    <div className="studio-preview-bottom"><span>{e.lampMaterial}</span><span className="studio-material-label">{zone === 'base' ? z.operations.engrave : z.operations.cut}</span></div>
    {decorationFailed && <p role="status" className="studio-model-note">{p.modelUnavailable}</p>}
    {expanded && <Modal title={p.preview + ' · ' + e.lampName} closeLabel={t.close} className="studio-expanded" actions={tabs} onClose={onClose}>
      <div className={'studio-stage studio-lamp-stage studio-lamp-' + mode}>
        <div className="studio-model-host" ref={setExpandedHost} hidden={mode !== 'model'} />
        {mode === 'sketch' && sketch}
      </div>
      {selectionStatus}
    </Modal>}
    <MediaErrorBoundary fallback={mode === 'model' && target ? createPortal(fallback, target) : null}>
      <Suspense fallback={mode === 'model' && target ? createPortal(<p className="product-model-status" role="status"><Spinner />{t.loadingModel}</p>, target) : null}>
        <ModelPreview t={{ ...t, model: e.lampName + ' · ' + z.modelAlt }} target={target} expanded={expanded && mode === 'model'} source={lampModelSource(draft.pattern)} lampDecoration={decoration} lampZoneSelection={selection} onDecorationError={setDecorationFailed} />
      </Suspense>
    </MediaErrorBoundary>
  </div>;
}
