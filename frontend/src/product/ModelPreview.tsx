import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import type { ModelViewerElement } from '@google/model-viewer';
import type { ProductCopy } from './copy';
import { Icon, Spinner } from './Icon';
import { createModelControls } from './modelControls';
import type { ModelControls } from './modelControls';

type Props = { t: ProductCopy; target: HTMLElement | null; expanded: boolean; source?: string; onPhase?: (phase: 'importing' | 'loading' | 'ready' | 'failed') => void };

export default function ModelPreview(props: Props) { return <ModelInstance key={props.source ?? '/product/loop.glb'} {...props} />; }

function ModelInstance({ t, target, expanded, source = '/product/loop.glb', onPhase }: Props) {
  const hintId = useId();
  const mount = useRef<HTMLDivElement>(null);
  const pad = useRef<HTMLDivElement>(null);
  const controls = useRef<ModelControls | null>(null);
  const [viewer, setViewer] = useState<ModelViewerElement | null>(null);
  const [phase, setPhase] = useState<'importing' | 'loading' | 'ready' | 'failed'>('importing');
  useLayoutEffect(() => { onPhase?.(phase); }, [phase, onPhase]);

  useEffect(() => {
    let active = true;
    let unavailable = false;
    let element: ModelViewerElement | null = null;
    const failed = () => {
      if (!active || unavailable) return;
      clearTimeout(timeout); unavailable = true; controls.current?.destroy(); controls.current = null;
      if (element) element.style.display = 'none';
      if (active) setPhase('failed');
    };
    const loaded = () => {
      if (!active || unavailable || controls.current) return;
      clearTimeout(timeout);
      controls.current = createModelControls(element!);
      if (!controls.current) { failed(); return; }
      setPhase('ready');
    };
    const timeout = window.setTimeout(failed, 30_000);
    // One custom element owns the mesh/camera for this component's entire lifetime.
    // Its parent may change, but React never owns or recreates the viewer itself.
    void import('@google/model-viewer').then(() => {
      if (!active || unavailable) return;
      element = document.createElement('model-viewer') as ModelViewerElement;
      const attributes = {
        'camera-controls': '', 'disable-pan': '', 'touch-action': 'pan-y', 'interaction-prompt': 'none',
        'camera-orbit': '30deg 68deg 105%', 'min-camera-orbit': 'auto 15deg 65%', 'max-camera-orbit': 'auto 170deg 160%',
        'shadow-intensity': '0.7', exposure: '1', loading: 'eager', tabindex: '0',
      };
      for (const [name, value] of Object.entries(attributes)) element.setAttribute(name, value);
      element.addEventListener('load', loaded); element.addEventListener('error', failed);
      // This self-contained local GLB needs no AR, CDN environment or decoder.
      element.setAttribute('src', source);
      setViewer(element); setPhase('loading');
    }).catch(failed);
    return () => {
      active = false; clearTimeout(timeout); controls.current?.destroy(); controls.current = null;
      element?.removeEventListener('load', loaded); element?.removeEventListener('error', failed); element?.remove();
    };
  }, [source]);

  useLayoutEffect(() => {
    const host = mount.current;
    if (viewer && host) host.append(viewer);
    return () => { viewer?.remove(); };
  }, [viewer, target]);

  useEffect(() => {
    if (!viewer) return;
    viewer.setAttribute('aria-label', t.model);
    viewer.setAttribute('alt', t.model);
    viewer.setAttribute('aria-describedby', hintId);
  }, [viewer, t.model, hintId]);

  useLayoutEffect(() => {
    const api = controls.current;
    if (phase === 'ready' && expanded && pad.current) api?.enter(pad.current);
    return () => { api?.exit(); };
  }, [phase, expanded, target]);

  if (!target) return null;
  return createPortal(<div className="product-model" data-expanded={expanded}>
    <div className="product-model-mount" ref={mount} />
    {expanded && <>
      <div className="product-model-pad" ref={pad} tabIndex={phase === 'ready' ? 0 : -1} role="group" aria-label={t.rotationLabel} aria-describedby={hintId} aria-disabled={phase !== 'ready'} />
      <div className="product-model-tools" role="group" aria-label={t.fullRotation}>
        <button type="button" className="product-icon-button" disabled={phase !== 'ready'} aria-label={t.rotateLeft} onClick={() => controls.current?.roll('left')}><Icon name="rotateLeft" /></button>
        <button type="button" className="product-icon-button" disabled={phase !== 'ready'} aria-label={t.zoomOut} onClick={() => controls.current?.zoom(1 / .85)}><Icon name="minus" /></button>
        <button type="button" className="product-icon-button" disabled={phase !== 'ready'} aria-label={t.resetView} title={t.resetView} onClick={() => controls.current?.reset()}><Icon name="reset" /></button>
        <button type="button" className="product-icon-button" disabled={phase !== 'ready'} aria-label={t.zoomIn} onClick={() => controls.current?.zoom(.85)}><Icon name="plus" /></button>
        <button type="button" className="product-icon-button" disabled={phase !== 'ready'} aria-label={t.rotateRight} onClick={() => controls.current?.roll('right')}><Icon name="rotateRight" /></button>
      </div>
    </>}
    {!expanded && <div className="product-model-inline-tools" role="group" aria-label={t.model}>
      <button type="button" className="product-icon-button" disabled={phase !== 'ready'} aria-label={t.rotateLeft} title={t.rotateLeft} onClick={() => controls.current?.orbitInline('left')}><Icon name="rotateLeft" /></button>
      <button type="button" className="product-icon-button" disabled={phase !== 'ready'} aria-label={t.resetView} title={t.resetView} onClick={() => controls.current?.reset()}><Icon name="reset" /></button>
      <button type="button" className="product-icon-button" disabled={phase !== 'ready'} aria-label={t.rotateRight} title={t.rotateRight} onClick={() => controls.current?.orbitInline('right')}><Icon name="rotateRight" /></button>
    </div>}
    {phase !== 'ready' && <p className="product-model-status" role="status">{phase === 'failed' ? t.modelFailed : <><Spinner />{t.loadingModel}</>}</p>}
    <p className={expanded ? 'product-model-hint' : 'product-sr-only'} id={hintId}>{expanded ? t.rotationHint : t.modelHint}</p>
  </div>, target);
}
