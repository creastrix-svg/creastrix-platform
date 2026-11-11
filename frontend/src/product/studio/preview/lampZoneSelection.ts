import type { ModelViewerElement } from '@google/model-viewer';

export type LampZone = 'panel' | 'base';
export type LampZoneSelection = { selected: LampZone; onSelect: (zone: LampZone) => void };
type Material = NonNullable<ModelViewerElement['model']>['materials'][number];

const zoneMaterials: Record<string, LampZone> = {
  'Studio lamp front panel': 'panel',
  'Studio lamp front cut edge': 'panel',
  'Studio lamp base engraving': 'base',
};

/** Prepared surfaces only: a ray hitting fixed wood, the light or empty space
 * must not be guessed into a zone. The material facade is the public viewer API. */
export function createLampZoneSelection(viewer: ModelViewerElement, input: HTMLElement, marker: HTMLElement) {
  const materials = new Map<Material, LampZone>();
  for (const material of viewer.model?.materials ?? []) {
    const zone = zoneMaterials[material.name];
    if (zone) materials.set(material, zone);
  }
  let selection: LampZoneSelection | undefined;
  let disposed = false;
  let gesture: { id: number; x: number; y: number; start: number; cancelled: boolean } | undefined;
  let pendingHover: { x: number; y: number; cursor: HTMLElement } | undefined;
  let hoverFrame: number | undefined;
  let visible = true;
  let cameraSettlesAt = 0;
  let cursorOwner: { element: HTMLElement; value: string; priority: string } | undefined;

  function restoreCursor() {
    if (!cursorOwner) return;
    const { element, value, priority } = cursorOwner;
    // A camera control may have taken ownership meanwhile (e.g. grabbing).
    // Restore only our unchanged declaration rather than overwrite its state.
    if (element.style.getPropertyValue('cursor') === 'pointer' && element.style.getPropertyPriority('cursor') === '') {
      if (value) element.style.setProperty('cursor', value, priority);
      else element.style.removeProperty('cursor');
    }
    cursorOwner = undefined;
  }
  function showHover(zone?: LampZone, cursor?: HTMLElement) {
    if (cursorOwner?.element !== cursor || !zone) restoreCursor();
    if (zone && cursor && !cursorOwner) {
      cursorOwner = { element: cursor, value: cursor.style.getPropertyValue('cursor'), priority: cursor.style.getPropertyPriority('cursor') };
      cursor.style.setProperty('cursor', 'pointer');
    }
    marker.hidden = !zone;
    if (zone) marker.dataset.hoverZone = zone;
    else delete marker.dataset.hoverZone;
  }
  function clearHover() {
    if (hoverFrame !== undefined) window.cancelAnimationFrame(hoverFrame);
    hoverFrame = undefined; pendingHover = undefined;
    showHover();
  }
  function pointInside(x: number, y: number) {
    const bounds = input.getBoundingClientRect();
    return bounds.width > 0 && bounds.height > 0 && x >= bounds.left && x <= bounds.right && y >= bounds.top && y <= bounds.bottom;
  }
  function zoneAt(x: number, y: number) {
    try { const material = viewer.materialFromPoint(x, y); return material ? materials.get(material) : undefined; }
    catch { return undefined; } // Explicit zone buttons remain usable without picking.
  }
  function paintHover() {
    hoverFrame = undefined;
    const point = pendingHover; pendingHover = undefined;
    if (disposed || !point || gesture || !visible || document.hidden || !input.isConnected
      || input.closest('[hidden]') || performance.now() < cameraSettlesAt || !pointInside(point.x, point.y)) { clearHover(); return; }
    const zone = zoneAt(point.x, point.y);
    // The persistent outline is on the selected face's texture. Mark the actual
    // mouse hit independently, without adding light or touching any material.
    if (zone) {
      const bounds = input.getBoundingClientRect();
      marker.style.left = `${point.x - bounds.left}px`;
      marker.style.top = `${point.y - bounds.top}px`;
    }
    showHover(zone, point.cursor);
  }
  const hoverMove = (event: PointerEvent) => {
    if (event.pointerType !== 'mouse' || event.buttons !== 0 || gesture) { clearHover(); return; }
    const path = event.composedPath();
    if (path.some(node => node instanceof Element && node.matches('button, a, input, select, textarea'))) { clearHover(); return; }
    // The public composed event path supplies the actual hit element, even
    // with model-viewer's shadow input. No internal selector or symbol is used.
    const cursor = path.find((node): node is HTMLElement => node instanceof HTMLElement) ?? input;
    pendingHover = { x: event.clientX, y: event.clientY, cursor };
    if (hoverFrame === undefined) hoverFrame = window.requestAnimationFrame(paintHover);
  };
  const cameraChanged = () => {
    cameraSettlesAt = performance.now() + 100;
    clearHover();
  };
  const visibilityChanged = (event: Event) => {
    visible = (event as CustomEvent<{ visible: boolean }>).detail.visible;
    if (!visible) clearHover();
  };

  const clear = () => { gesture = undefined; clearHover(); };
  const cancel = () => { if (gesture) gesture.cancelled = true; clearHover(); };
  const documentDown = (event: PointerEvent) => {
    clearHover();
    // A second finger anywhere cancels the entire tap; lifting it does not
    // reinterpret the remaining finger as a new tap after a pinch.
    if (gesture && event.pointerId !== gesture.id) cancel();
  };
  const down = (event: PointerEvent) => {
    if (gesture) { cancel(); return; }
    if (event.button !== 0 || !event.isPrimary) return;
    if (event.composedPath().some(node => node instanceof Element && node.matches('button, a, input, select, textarea'))) return;
    gesture = { id: event.pointerId, x: event.clientX, y: event.clientY, start: event.timeStamp, cancelled: false };
  };
  const move = (event: PointerEvent) => {
    if (gesture?.id === event.pointerId && Math.hypot(event.clientX - gesture.x, event.clientY - gesture.y) > 6) cancel();
  };
  const end = (event: PointerEvent) => {
    if (!gesture || gesture.id !== event.pointerId) return;
    const tap = gesture;
    clear();
    if (tap.cancelled || event.button !== 0 || event.timeStamp - tap.start > 650
      || Math.hypot(event.clientX - tap.x, event.clientY - tap.y) > 6
      || !pointInside(event.clientX, event.clientY)
      || !event.composedPath().includes(input)) return;
    // Coordinates are viewport CSS pixels, including when the public viewer is
    // behind the expanded arcball pad. Do not cancel or capture camera input.
    const zone = zoneAt(event.clientX, event.clientY);
    if (zone && selection && zone !== selection.selected) selection.onSelect(zone);
  };
  const cancelled = (event: PointerEvent) => { clearHover(); if (gesture?.id === event.pointerId) clear(); };

  // Capture observes the expanded pad before its controls stop propagation or
  // release pointer capture. All listeners are passive and never alter rotation.
  input.addEventListener('pointerdown', down, { capture: true, passive: true });
  input.addEventListener('pointermove', hoverMove, { capture: true, passive: true });
  input.addEventListener('pointerleave', clearHover);
  viewer.addEventListener('camera-change', cameraChanged);
  viewer.addEventListener('model-visibility', visibilityChanged);
  document.addEventListener('pointerdown', documentDown, { capture: true, passive: true });
  document.addEventListener('pointermove', move, { capture: true, passive: true });
  document.addEventListener('pointerup', end, { capture: true, passive: true });
  document.addEventListener('pointercancel', cancelled, true);
  document.addEventListener('lostpointercapture', cancelled, true);
  document.addEventListener('visibilitychange', clear);
  window.addEventListener('scroll', cancel, { capture: true, passive: true });
  window.addEventListener('resize', cancel);
  window.addEventListener('wheel', cancel, { capture: true, passive: true });
  window.addEventListener('keydown', cancel, true);
  window.addEventListener('blur', clear);

  return {
    update(next: LampZoneSelection) {
      if (disposed) return;
      selection = next;
    },
    destroy() {
      if (disposed) return;
      disposed = true; clear(); selection = undefined;
      input.removeEventListener('pointerdown', down, true);
      input.removeEventListener('pointermove', hoverMove, true);
      input.removeEventListener('pointerleave', clearHover);
      viewer.removeEventListener('camera-change', cameraChanged);
      viewer.removeEventListener('model-visibility', visibilityChanged);
      document.removeEventListener('pointerdown', documentDown, true);
      document.removeEventListener('pointermove', move, true);
      document.removeEventListener('pointerup', end, true);
      document.removeEventListener('pointercancel', cancelled, true);
      document.removeEventListener('lostpointercapture', cancelled, true);
      document.removeEventListener('visibilitychange', clear);
      window.removeEventListener('scroll', cancel, true);
      window.removeEventListener('resize', cancel);
      window.removeEventListener('wheel', cancel, true);
      window.removeEventListener('keydown', cancel, true);
      window.removeEventListener('blur', clear);
      materials.clear();
    },
  };
}
