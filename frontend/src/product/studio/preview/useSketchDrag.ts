import { useEffect, useRef } from 'react';
import type { PointerEvent as ReactPointerEvent } from 'react';

/** Convert the visible pointer into the SVG's own coordinates, including
 * letterboxing, expanded viewBox crops and CSS transforms. */
export function sketchPointer(svg: SVGSVGElement, clientX: number, clientY: number) {
  try {
    const matrix = svg.getScreenCTM?.();
    if (matrix && svg.createSVGPoint) {
      const point = svg.createSVGPoint(); point.x = clientX; point.y = clientY;
      const local = point.matrixTransform(matrix.inverse());
      if (Number.isFinite(local.x) && Number.isFinite(local.y)) return { x: local.x, y: local.y };
    }
  } catch { /* A detached SVG can have no invertible screen transform. */ }
  const rect = svg.getBoundingClientRect();
  const [x, y, width, height] = (svg.getAttribute('viewBox') ?? '').trim().split(/\s+/).map(Number);
  const scale = Math.min(rect.width / width, rect.height / height);
  if (!(scale > 0) || ![x, y, width, height].every(Number.isFinite)) return null;
  return { x: x + (clientX - rect.left - (rect.width - width * scale) / 2) / scale, y: y + (clientY - rect.top - (rect.height - height * scale) / 2) / scale };
}

/** Shared drag conversion. The owner clamps editor coordinates and groups undo;
 * this hook only tracks one pointer and translates the rendered SVG scale. */
export function useSketchDrag(value: { text: string; x: number; y: number }, onMove?: (x: number, y: number) => void, onMoveEnd?: () => void, editorScale: number | { x: number; y: number } = 1) {
  const drag = useRef<{ pointer: number; x: number; y: number; originX: number; originY: number } | null>(null);
  const moved = useRef(false), end = useRef(onMoveEnd);
  const enabled = !!onMove && !!value.text;
  const scaleX = typeof editorScale === 'number' ? editorScale : editorScale.x, scaleY = typeof editorScale === 'number' ? editorScale : editorScale.y;
  useEffect(() => { end.current = onMoveEnd; }, [onMoveEnd]);
  useEffect(() => { if (!enabled && drag.current) { drag.current = null; moved.current = false; end.current?.(); } }, [enabled]);
  useEffect(() => () => { if (drag.current) { drag.current = null; end.current?.(); } }, []);
  const finish = (event: ReactPointerEvent<SVGGElement>) => {
    if (drag.current?.pointer !== event.pointerId) return;
    drag.current = null; end.current?.();
    if (event.type === 'pointercancel') moved.current = false;
    if (event.currentTarget.hasPointerCapture?.(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
  };
  return {
    consumeClick: () => { const consumed = moved.current; moved.current = false; return consumed; },
    handlers: {
      onPointerDown(event: ReactPointerEvent<SVGGElement>) {
        if (!onMove || !value.text || event.button !== 0 || drag.current || !(scaleX > 0 && scaleY > 0)) return;
        const svg = event.currentTarget.ownerSVGElement;
        const point = svg && sketchPointer(svg, event.clientX, event.clientY);
        if (!point) return;
        moved.current = false;
        drag.current = { pointer: event.pointerId, ...point, originX: value.x, originY: value.y };
        event.preventDefault(); event.currentTarget.setPointerCapture?.(event.pointerId);
      },
      onPointerMove(event: ReactPointerEvent<SVGGElement>) {
        const current = drag.current, svg = event.currentTarget.ownerSVGElement;
        if (!current || current.pointer !== event.pointerId || !svg) return;
        const point = sketchPointer(svg, event.clientX, event.clientY);
        if (!point) return;
        const dx = (point.x - current.x) / scaleX, dy = (point.y - current.y) / scaleY;
        if (dx || dy) moved.current = true;
        onMove?.(current.originX + dx, current.originY + dy);
      },
      onPointerUp: finish, onPointerCancel: finish, onLostPointerCapture: finish,
    },
  };
}
