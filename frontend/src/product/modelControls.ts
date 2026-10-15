export type Vector3 = readonly [number, number, number];
export type Quaternion = readonly [number, number, number, number];
type Point = { x: number; y: number };
type Bounds = Pick<DOMRect, 'left' | 'top' | 'width' | 'height'>;
type Orbit = { theta: number; phi: number; radius: number };
type InlineView = { orbit: Orbit; target: Vector3; attributes: Record<string, string | null> };

export interface ModelControlViewer extends HTMLElement {
  getCameraOrbit(): Orbit;
  getCameraTarget(): { x: number; y: number; z: number };
  jumpCameraToGoal(): void;
  interpolationDecay: number;
}

export function unitQuaternion(q: Quaternion): Quaternion {
  const length = Math.hypot(...q);
  return length > 1e-12 ? [q[0] / length, q[1] / length, q[2] / length, q[3] / length] : [0, 0, 0, 1];
}

export function multiplyQuaternion(a: Quaternion, b: Quaternion): Quaternion {
  const [x, y, z, w] = a, [X, Y, Z, W] = b;
  return [w * X + x * W + y * Z - z * Y, w * Y - x * Z + y * W + z * X,
    w * Z + x * Y - y * X + z * W, w * W - x * X - y * Y - z * Z];
}

export function inverseQuaternion(q: Quaternion): Quaternion { return [-q[0], -q[1], -q[2], q[3]]; }

export function axisQuaternion(axis: Vector3, angle: number): Quaternion {
  const scale = Math.sin(angle / 2);
  return [axis[0] * scale, axis[1] * scale, axis[2] * scale, Math.cos(angle / 2)];
}

export function rotateVector(q: Quaternion, vector: Vector3): Vector3 {
  const value = multiplyQuaternion(multiplyQuaternion(q, [...vector, 0]), inverseQuaternion(q));
  return [value[0], value[1], value[2]];
}

export function betweenVectors(a: Vector3, b: Vector3): Quaternion {
  const cross: Vector3 = [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
  const dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
  if (dot < -.999999) return unitQuaternion(Math.abs(a[0]) < .8 ? [0, a[2], -a[1], 0] : [-a[2], 0, a[0], 0]);
  return unitQuaternion([...cross, 1 + dot]);
}

// Public model-viewer orientation is roll/pitch/yaw; its scene graph uses YXZ.
export function eulerYXZ(q: Quaternion): Vector3 {
  const [x, y, z, w] = q;
  const m23 = 2 * (y * z - x * w);
  const pitch = Math.asin(-Math.max(-1, Math.min(1, m23)));
  const regular = Math.abs(m23) < .9999999;
  const yaw = regular ? Math.atan2(2 * (x * z + y * w), 1 - 2 * (x * x + y * y)) : Math.atan2(-2 * (x * z - y * w), 1 - 2 * (y * y + z * z));
  const roll = regular ? Math.atan2(2 * (x * y + z * w), 1 - 2 * (x * x + z * z)) : 0;
  return [roll, pitch, yaw];
}

export function ballPoint(point: Point, bounds: Bounds): Vector3 {
  const radius = Math.max(1, Math.min(bounds.width, bounds.height) * .42);
  const x = (point.x - bounds.left - bounds.width / 2) / radius;
  const y = (bounds.top + bounds.height / 2 - point.y) / radius;
  const distance = x * x + y * y;
  return distance > 1 ? [x / Math.sqrt(distance), y / Math.sqrt(distance), 0] : [x, y, Math.sqrt(1 - distance)];
}

export type ModelControls = {
  enter(pad: HTMLElement): void;
  exit(): void;
  roll(direction: 'left' | 'right'): void;
  orbitInline(direction: 'left' | 'right'): void;
  zoom(factor: number): void;
  reset(): void;
  destroy(): void;
};

export function createModelControls(viewer: ModelControlViewer): ModelControls | null {
  const home = { ...viewer.getCameraOrbit() };
  const initialTarget = viewer.getCameraTarget();
  if (![home.theta, home.phi, home.radius, initialTarget.x, initialTarget.y, initialTarget.z].every(Number.isFinite) || home.radius <= 0) return null;
  const homeTarget: Vector3 = [initialTarget.x, initialTarget.y, initialTarget.z];
  const homeView: InlineView = { orbit: { ...home }, target: homeTarget, attributes: captureAttributes() };
  const points = new Map<number, Point>();
  let pose: Quaternion = [0, 0, 0, 1];
  let orbit = { ...home };
  let center = homeTarget;
  let camera: Quaternion = [0, 0, 0, 1];
  let frame: number | null = null;
  let pad: HTMLElement | null = null;
  let removeListeners: (() => void) | null = null;
  let saved: InlineView | null = null;
  let destroyed = false;
  type Gesture = { count: 1; ball: Vector3; pose: Quaternion; bounds: DOMRect } | { count: 2; angle: number; distance: number; radius: number; pose: Quaternion };
  let gesture: Gesture | null = null;
  const boundRadius = (radius: number) => {
    const base = saved?.orbit.radius ?? home.radius;
    return Math.max(base * .55, Math.min(base * 1.8, radius));
  };

  function captureAttributes() {
    const attributes = Object.fromEntries(['camera-controls', 'disable-pan', 'touch-action', 'orientation', 'min-camera-orbit', 'max-camera-orbit', 'interpolation-decay', 'shadow-intensity', 'tabindex'].map(key => [key, viewer.getAttribute(key)]));
    // Removing a Lit Number attribute produces null, not its initial default.
    const decay = viewer.interpolationDecay;
    attributes['interpolation-decay'] ??= String(Number.isFinite(decay) ? decay : 50);
    return attributes;
  }
  function restore(view: InlineView) {
    for (const [key, value] of Object.entries(view.attributes)) {
      if (key === 'orientation') continue;
      if (value === null) viewer.removeAttribute(key); else viewer.setAttribute(key, value);
    }
    viewer.setAttribute('orientation', view.attributes.orientation ?? '0rad 0rad 0rad');
    // Restore public numeric values, not stale attributes or an auto target
    // evaluated against the temporarily rotated fullscreen bounds.
    viewer.setAttribute('camera-target', view.target.map(value => `${value}m`).join(' '));
    viewer.setAttribute('camera-orbit', `${view.orbit.theta}rad ${view.orbit.phi}rad ${view.orbit.radius}m`);
    viewer.jumpCameraToGoal();
  }

  function paint() {
    frame = null;
    if (!pad || destroyed) return;
    viewer.setAttribute('orientation', eulerYXZ(pose).map(value => `${value}rad`).join(' '));
    viewer.setAttribute('camera-target', rotateVector(pose, center).map(value => `${value}m`).join(' '));
    viewer.setAttribute('camera-orbit', `${orbit.theta}rad ${orbit.phi}rad ${orbit.radius}m`);
    viewer.jumpCameraToGoal();
  }
  function schedule() { if (frame === null) frame = window.requestAnimationFrame(paint); }
  function setCameraBasis() {
    camera = unitQuaternion(multiplyQuaternion(axisQuaternion([0, 1, 0], orbit.theta), axisQuaternion([1, 0, 0], orbit.phi - Math.PI / 2)));
  }
  function screenTurn(delta: Quaternion, start = pose) {
    return unitQuaternion(multiplyQuaternion(multiplyQuaternion(multiplyQuaternion(camera, delta), inverseQuaternion(camera)), start));
  }
  function rebase() {
    if (!pad) return;
    const values = [...points.values()];
    pad.dataset.dragging = String(values.length > 0);
    if (!values.length) { gesture = null; return; }
    const bounds = pad.getBoundingClientRect();
    gesture = values.length === 1 ? { count: 1, ball: ballPoint(values[0], bounds), pose: [...pose], bounds } : {
      count: 2, angle: Math.atan2(values[1].y - values[0].y, values[1].x - values[0].x),
      distance: Math.max(1, Math.hypot(values[1].x - values[0].x, values[1].y - values[0].y)), radius: orbit.radius, pose: [...pose],
    };
  }
  function release() {
    const ids = [...points.keys()];
    points.clear(); gesture = null;
    if (pad) {
      pad.dataset.dragging = 'false';
      for (const id of ids) if (pad.hasPointerCapture?.(id)) pad.releasePointerCapture(id);
    }
  }
  function turn(axis: Vector3, angle: number) { if (!pad) return; pose = screenTurn(axisQuaternion(axis, angle)); schedule(); rebase(); }
  function zoom(factor: number) {
    if (!pad || !Number.isFinite(factor) || factor <= 0) return;
    orbit.radius = boundRadius(orbit.radius * factor); schedule(); rebase();
  }
  function reset() {
    if (destroyed) return;
    if (!pad || !saved) { restore(homeView); return; }
    // A fullscreen reset belongs to this viewing session, not the global home.
    // It must not overwrite the pre-open inline snapshot restored by exit.
    release(); pose = [0, 0, 0, 1]; center = saved.target;
    orbit = { ...saved.orbit, radius: saved.orbit.radius * 1.2 }; setCameraBasis(); schedule();
  }
  function orbitInline(direction: 'left' | 'right') {
    if (pad || destroyed) return;
    const current = viewer.getCameraOrbit();
    if (![current.theta, current.phi, current.radius].every(Number.isFinite)) return;
    viewer.setAttribute('camera-orbit', `${current.theta + (direction === 'left' ? -.4 : .4)}rad ${current.phi}rad ${current.radius}m`);
    viewer.jumpCameraToGoal();
  }
  function exit() {
    release();
    // Discard pending free motion before restoring inline; never paint it later.
    if (frame !== null) { window.cancelAnimationFrame(frame); frame = null; }
    removeListeners?.(); removeListeners = null;
    const previous = saved;
    saved = null; pad = null;
    if (previous) restore(previous);
  }
  function enter(nextPad: HTMLElement) {
    if (pad === nextPad || destroyed) return;
    exit();
    const actual = viewer.getCameraOrbit(), target = viewer.getCameraTarget();
    if (![actual.theta, actual.phi, actual.radius, target.x, target.y, target.z].every(Number.isFinite) || actual.radius <= 0) return;
    saved = { orbit: { ...actual }, target: [target.x, target.y, target.z], attributes: captureAttributes() };
    pose = [0, 0, 0, 1]; center = saved.target;
    orbit = { ...actual, radius: actual.radius * 1.2 };
    setCameraBasis();
    pad = nextPad;
    viewer.removeAttribute('camera-controls');
    viewer.setAttribute('touch-action', 'none');
    viewer.setAttribute('interpolation-decay', '0');
    viewer.setAttribute('shadow-intensity', '0');
    viewer.setAttribute('tabindex', '-1');
    viewer.setAttribute('min-camera-orbit', `auto auto ${actual.radius * .55}m`);
    viewer.setAttribute('max-camera-orbit', `auto auto ${actual.radius * 1.8}m`);

    const down = (event: PointerEvent) => {
      if (event.button !== 0 || points.size >= 2) return;
      event.preventDefault(); event.stopPropagation(); nextPad.focus({ preventScroll: true });
      points.set(event.pointerId, { x: event.clientX, y: event.clientY });
      nextPad.setPointerCapture?.(event.pointerId); rebase();
    };
    const move = (event: PointerEvent) => {
      if (!points.has(event.pointerId)) return;
      event.preventDefault(); event.stopPropagation();
      points.set(event.pointerId, { x: event.clientX, y: event.clientY });
      const values = [...points.values()];
      if (values.length === 1 && gesture?.count === 1) pose = screenTurn(betweenVectors(gesture.ball, ballPoint(values[0], gesture.bounds)), gesture.pose);
      else if (values.length === 2 && gesture?.count === 2) {
        const angle = Math.atan2(values[1].y - values[0].y, values[1].x - values[0].x);
        pose = screenTurn(axisQuaternion([0, 0, 1], gesture.angle - angle), gesture.pose);
        orbit.radius = boundRadius(gesture.radius * gesture.distance / Math.max(1, Math.hypot(values[1].x - values[0].x, values[1].y - values[0].y)));
      }
      schedule();
    };
    const end = (event: PointerEvent) => {
      if (!points.has(event.pointerId)) return;
      points.delete(event.pointerId);
      if (nextPad.hasPointerCapture?.(event.pointerId)) nextPad.releasePointerCapture(event.pointerId);
      rebase();
    };
    const wheel = (event: WheelEvent) => {
      event.preventDefault(); event.stopPropagation();
      const delta = event.deltaY * (event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? nextPad.clientHeight : 1);
      zoom(Math.exp(Math.max(-.3, Math.min(.3, delta * .002))));
    };
    const key = (event: KeyboardEvent) => {
      if (event.altKey || event.ctrlKey || event.metaKey) return;
      const value = event.key.toLowerCase(), step = Math.PI / 12;
      if (!['arrowleft', 'arrowright', 'arrowup', 'arrowdown', 'q', 'e', '+', '=', '-', 'home'].includes(value)) return;
      event.preventDefault(); event.stopPropagation();
      if (value === 'home') reset();
      else if (value === '+' || value === '=') zoom(.85);
      else if (value === '-') zoom(1 / .85);
      else if (value === 'q' || value === 'e') turn([0, 0, 1], value === 'q' ? step : -step);
      else turn(value === 'arrowup' || value === 'arrowdown' ? [1, 0, 0] : [0, 1, 0], value === 'arrowup' || value === 'arrowleft' ? -step : step);
    };
    const click = (event: MouseEvent) => { event.preventDefault(); event.stopPropagation(); };
    nextPad.addEventListener('pointerdown', down);
    nextPad.addEventListener('pointermove', move);
    for (const event of ['pointerup', 'pointercancel', 'lostpointercapture']) nextPad.addEventListener(event, end as EventListener);
    nextPad.addEventListener('wheel', wheel, { passive: false });
    nextPad.addEventListener('keydown', key);
    nextPad.addEventListener('click', click);
    nextPad.addEventListener('blur', release);
    window.addEventListener('blur', release);
    removeListeners = () => {
      nextPad.removeEventListener('pointerdown', down); nextPad.removeEventListener('pointermove', move);
      for (const event of ['pointerup', 'pointercancel', 'lostpointercapture']) nextPad.removeEventListener(event, end as EventListener);
      nextPad.removeEventListener('wheel', wheel); nextPad.removeEventListener('keydown', key);
      nextPad.removeEventListener('click', click); nextPad.removeEventListener('blur', release);
      window.removeEventListener('blur', release);
    };
    paint();
  }
  return { enter, exit, orbitInline, roll: direction => turn([0, 0, 1], direction === 'left' ? Math.PI / 6 : -Math.PI / 6), zoom, reset, destroy: () => { exit(); destroyed = true; } };
}
