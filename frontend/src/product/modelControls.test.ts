import { fireEvent } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { axisQuaternion, ballPoint, betweenVectors, createModelControls, eulerYXZ, inverseQuaternion, multiplyQuaternion, rotateVector, unitQuaternion } from './modelControls';
import type { ModelControlViewer, Quaternion, Vector3 } from './modelControls';

function closeVector(actual: readonly number[], expected: readonly number[]) {
  actual.forEach((value, index) => expect(value).toBeCloseTo(expected[index], 10));
}
function fromEuler([roll, pitch, yaw]: Vector3) {
  return multiplyQuaternion(multiplyQuaternion(axisQuaternion([0, 1, 0], yaw), axisQuaternion([1, 0, 0], pitch)), axisQuaternion([0, 0, 1], roll));
}

describe('public orientation math, without a renderer mock claiming WebGL proof', () => {
  it.each<Vector3>([[0, 0, 0], [Math.PI, 0, 0], [.8, 1.3, -.4], [0, Math.PI / 2, .7], [1.4, -Math.PI / 2, -.7]])('round trips YXZ orientation %j including side/upside-down poses', (...angles) => {
    const quaternion = fromEuler(angles);
    const rebuilt = fromEuler(eulerYXZ(quaternion));
    expect(Math.abs(quaternion.reduce((sum, value, index) => sum + value * rebuilt[index], 0))).toBeCloseTo(1, 8);
  });
  it('normalizes repeated rotations and rotates a fixed numeric pivot without drift', () => {
    let pose: Quaternion = [0, 0, 0, 1];
    for (let step = 0; step < 720; step++) pose = unitQuaternion(multiplyQuaternion(axisQuaternion([0, 0, 1], Math.PI / 180), pose));
    const center: Vector3 = [.13, .37, -.2];
    expect(Math.hypot(...pose)).toBeCloseTo(1, 12);
    closeVector(rotateVector(pose, center), center);
    closeVector(rotateVector(inverseQuaternion(pose), rotateVector(pose, center)), center);
    closeVector(rotateVector(axisQuaternion([0, 0, 1], Math.PI), [0, 1, 0]), [0, -1, 0]);
  });
  it('maps the center to the ball front and clamps the rim, including zero-size/antipodal controls', () => {
    closeVector(ballPoint({ x: 200, y: 150 }, { left: 0, top: 0, width: 400, height: 300 }), [0, 0, 1]);
    const rim = ballPoint({ x: 900, y: -300 }, { left: 0, top: 0, width: 400, height: 300 });
    expect(Math.hypot(...rim)).toBeCloseTo(1, 12); expect(rim[2]).toBe(0);
    expect(ballPoint({ x: 0, y: 0 }, { left: 0, top: 0, width: 0, height: 0 }).every(Number.isFinite)).toBe(true);
    closeVector(rotateVector(betweenVectors([1, 0, 0], [-1, 0, 0]), [1, 0, 0]), [-1, 0, 0]);
    closeVector(rotateVector(betweenVectors([0, 0, 1], [0, 1, 0]), [0, 0, 1]), [0, 1, 0]);
  });
});

function fixture() {
  vi.useFakeTimers();
  const viewer = document.createElement('div') as unknown as ModelControlViewer;
  let orbit = { theta: 0, phi: Math.PI / 2, radius: 2 };
  let target = { x: .1, y: .3, z: 0 };
  viewer.getCameraOrbit = () => ({ ...orbit });
  viewer.getCameraTarget = () => ({ ...target });
  viewer.interpolationDecay = 50;
  viewer.jumpCameraToGoal = vi.fn(() => {
    const values = viewer.getAttribute('camera-orbit')?.split(' ').map(parseFloat);
    if (values) orbit = { theta: values[0], phi: values[1], radius: values[2] };
    const pivot = viewer.getAttribute('camera-target')?.split(' ').map(parseFloat);
    if (pivot) target = { x: pivot[0], y: pivot[1], z: pivot[2] };
  });
  viewer.setAttribute('camera-controls', ''); viewer.setAttribute('touch-action', 'pan-y'); viewer.setAttribute('tabindex', '0');
  viewer.setAttribute('min-camera-orbit', 'auto 15deg 65%'); viewer.setAttribute('max-camera-orbit', 'auto 170deg 160%');
  const pad = document.createElement('div'); pad.tabIndex = 0; document.body.append(pad);
  vi.spyOn(pad, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 400, 300));
  const captured = new Set<number>();
  pad.setPointerCapture = vi.fn(id => { captured.add(id); });
  pad.hasPointerCapture = id => captured.has(id);
  pad.releasePointerCapture = vi.fn(id => { captured.delete(id); });
  const api = createModelControls(viewer)!;
  const nativeDrag = (nextOrbit: typeof orbit, nextTarget = target) => { orbit = { ...nextOrbit }; target = { ...nextTarget }; };
  return { viewer, pad, api, captured, nativeDrag, readOrientation: () => viewer.getAttribute('orientation') };
}
afterEach(() => { vi.useRealTimers(); document.body.replaceChildren(); });
const frame = () => vi.advanceTimersByTime(25);
function pointer(pad: HTMLElement, type: string, id: number, x: number, y: number, isPrimary = true) {
  fireEvent(pad, new PointerEvent(type, { pointerId: id, clientX: x, clientY: y, button: 0, bubbles: true, cancelable: true, isPrimary }));
}

it('restores captured actual inline orbit, target, orientation and bounds instead of leaking the free view', () => {
  const { viewer, pad, api, nativeDrag } = fixture();
  api.roll('left'); api.zoom(.2); frame(); expect(viewer).not.toHaveAttribute('orientation');
  viewer.setAttribute('camera-orbit', '0rad 1.5707963267948966rad 2m');
  viewer.setAttribute('orientation', '0.1rad 0.2rad 0.3rad');
  viewer.setAttribute('min-camera-orbit', 'auto 27deg 78%'); viewer.setAttribute('max-camera-orbit', 'auto 136deg 149%');
  const actual = { theta: 1.1, phi: .9, radius: 2.7 }, pivot = { x: .4, y: .2, z: -.1 };
  nativeDrag(actual, pivot);
  api.enter(pad); expect(viewer).not.toHaveAttribute('camera-controls'); expect(viewer).toHaveAttribute('tabindex', '-1');
  api.roll('left'); api.zoom(.85); frame();
  api.exit(); expect(viewer).toHaveAttribute('camera-controls'); expect(viewer).toHaveAttribute('touch-action', 'pan-y');
  expect(viewer.getCameraOrbit()).toEqual(actual); expect(viewer.getCameraTarget()).toEqual(pivot);
  expect(viewer).toHaveAttribute('orientation', '0.1rad 0.2rad 0.3rad');
  expect(viewer).toHaveAttribute('min-camera-orbit', 'auto 27deg 78%'); expect(viewer).toHaveAttribute('max-camera-orbit', 'auto 136deg 149%');
  api.destroy();
});

it('resets the inline normal home separately from the current fullscreen start, without losing the pre-open inline selection', () => {
  const { viewer, pad, api, nativeDrag } = fixture();
  const selected = { theta: 1.1, phi: .8, radius: 2.5 }, pivot = { x: .2, y: .4, z: -.1 };
  nativeDrag(selected, pivot);
  api.enter(pad);
  const start = { ...viewer.getCameraOrbit() }, target = viewer.getCameraTarget();
  api.roll('left'); api.zoom(.7); frame();
  api.reset(); frame();
  expect(viewer.getCameraOrbit()).toEqual(start); expect(viewer.getCameraTarget()).toEqual(target);
  expect(viewer).toHaveAttribute('orientation', '0rad 0rad 0rad');
  api.exit(); expect(viewer.getCameraOrbit()).toEqual(selected); expect(viewer.getCameraTarget()).toEqual(pivot);
  api.reset(); frame();
  expect(viewer.getCameraOrbit()).toEqual({ theta: 0, phi: Math.PI / 2, radius: 2 });
  expect(viewer.getCameraTarget()).toEqual({ x: .1, y: .3, z: 0 });
  expect(viewer).toHaveAttribute('orientation', '0rad 0rad 0rad');
  expect(viewer).toHaveAttribute('min-camera-orbit', 'auto 15deg 65%'); expect(viewer).toHaveAttribute('max-camera-orbit', 'auto 170deg 160%');
  api.destroy();
});

it.each([50, 73])('restores absent-attribute numeric damping %s together with the saved inline view and bounds', decay => {
  const { viewer, pad, api } = fixture();
  let reflectedDecay: number | null = decay;
  Object.defineProperty(viewer, 'interpolationDecay', { configurable: true, get: () => reflectedDecay, set: (value: number) => { reflectedDecay = value; } });
  const setAttribute = viewer.setAttribute.bind(viewer), removeAttribute = viewer.removeAttribute.bind(viewer);
  vi.spyOn(viewer, 'setAttribute').mockImplementation((name, value) => {
    setAttribute(name, value);
    if (name === 'interpolation-decay') reflectedDecay = Number(value);
  });
  const remove = vi.spyOn(viewer, 'removeAttribute').mockImplementation(name => {
    removeAttribute(name);
    if (name === 'interpolation-decay') reflectedDecay = null;
  });
  // Reproduce Lit's Number removal behavior rather than a property-blind DOM fake.
  viewer.removeAttribute('interpolation-decay'); expect(viewer.interpolationDecay).toBeNull();
  viewer.interpolationDecay = decay;
  expect(viewer).not.toHaveAttribute('interpolation-decay'); remove.mockClear();
  const beforeOrbit = viewer.getCameraOrbit(), beforeTarget = viewer.getCameraTarget();
  const minimum = viewer.getAttribute('min-camera-orbit'), maximum = viewer.getAttribute('max-camera-orbit');
  api.enter(pad); expect(viewer.interpolationDecay).toBe(0);
  api.roll('left'); api.zoom(1.8); frame();
  expect(viewer.getCameraOrbit().radius).toBeCloseTo(3.6);
  api.exit();
  expect(viewer.interpolationDecay).toBe(decay); expect(viewer).toHaveAttribute('interpolation-decay', String(decay));
  expect(remove).not.toHaveBeenCalledWith('interpolation-decay');
  expect(viewer).toHaveAttribute('orientation', '0rad 0rad 0rad');
  expect(viewer.getCameraOrbit()).toEqual(beforeOrbit); expect(viewer.getCameraTarget()).toEqual(beforeTarget);
  expect(viewer).toHaveAttribute('min-camera-orbit', minimum!); expect(viewer).toHaveAttribute('max-camera-orbit', maximum!);
  api.enter(pad); api.exit(); expect(viewer.interpolationDecay).toBe(decay); api.destroy();
});

it('supports keyboard pitch/yaw/roll/zoom/reset, ignores modified keys, and prevents recognized keys reaching photo navigation', () => {
  const { viewer, pad, api, readOrientation } = fixture(); api.enter(pad);
  const parentKey = vi.fn(); document.body.addEventListener('keydown', parentKey);
  for (const key of ['ArrowUp', 'ArrowRight', 'q', 'e', 'ArrowDown', 'ArrowLeft']) {
    const before = readOrientation(); fireEvent.keyDown(pad, { key }); frame(); expect(readOrientation()).not.toBe(before);
  }
  expect(parentKey).not.toHaveBeenCalled();
  const before = readOrientation(); fireEvent.keyDown(pad, { key: 'ArrowUp', ctrlKey: true }); frame(); expect(readOrientation()).toBe(before);
  fireEvent.keyDown(pad, { key: '+' }); frame(); expect(viewer.getCameraOrbit().radius).toBeCloseTo(2.04);
  fireEvent.keyDown(pad, { key: '-' }); frame(); expect(viewer.getCameraOrbit().radius).toBeCloseTo(2.4);
  fireEvent.keyDown(pad, { key: 'Home' }); frame(); expect(readOrientation()).toBe('0rad 0rad 0rad');
  expect(viewer.getCameraOrbit().radius).toBeCloseTo(2.4);
  api.destroy(); document.body.removeEventListener('keydown', parentKey);
});

it('accepts a second non-primary touch for twist/pinch and releases captures on cancel, lost capture, blur and teardown', () => {
  const { viewer, pad, api, captured, readOrientation } = fixture(); api.enter(pad);
  pointer(pad, 'pointerdown', 1, 120, 150); pointer(pad, 'pointerdown', 2, 280, 150, false);
  expect(captured.size).toBe(2);
  pointer(pad, 'pointermove', 2, 290, 200, false); frame();
  expect(readOrientation()).not.toBe('0rad 0rad 0rad'); expect(viewer.getCameraOrbit().radius).toBeLessThan(2.4);
  pointer(pad, 'pointercancel', 2, 290, 200, false); expect(captured.size).toBe(1);
  pointer(pad, 'lostpointercapture', 1, 120, 150); expect(captured.size).toBe(0);
  pointer(pad, 'pointerdown', 3, 200, 150); fireEvent.blur(window); expect(captured.size).toBe(0);
  pointer(pad, 'pointerdown', 4, 200, 150); api.destroy(); expect(captured.size).toBe(0);
  const final = readOrientation(); pointer(pad, 'pointermove', 4, 300, 200); frame(); expect(readOrientation()).toBe(final);
});

it('discards pending arcball motion on exit and bounds zoom without wheel leakage', () => {
  const { viewer, pad, api, readOrientation } = fixture(); api.enter(pad);
  pointer(pad, 'pointerdown', 1, 200, 150); pointer(pad, 'pointermove', 1, 320, 210);
  api.exit(); expect(readOrientation()).toBe('0rad 0rad 0rad');
  expect(vi.getTimerCount()).toBe(0); frame(); expect(readOrientation()).toBe('0rad 0rad 0rad');
  api.enter(pad);
  api.zoom(1e-8); frame(); expect(viewer.getCameraOrbit().radius).toBeCloseTo(1.1);
  api.zoom(1e8); frame(); expect(viewer.getCameraOrbit().radius).toBeCloseTo(3.6);
  const event = new WheelEvent('wheel', { deltaY: -30, bubbles: true, cancelable: true });
  const parentWheel = vi.fn(); document.body.addEventListener('wheel', parentWheel);
  fireEvent(pad, event); frame(); expect(event.defaultPrevented).toBe(true); expect(parentWheel).not.toHaveBeenCalled();
  const before = viewer.getCameraOrbit().radius;
  api.zoom(Number.NaN); api.zoom(0); api.zoom(-1); frame(); expect(viewer.getCameraOrbit().radius).toBe(before);
  api.destroy(); document.body.removeEventListener('wheel', parentWheel);
});

it('steps the actual native inline orbit without changing orientation or its bounded controls', () => {
  const { viewer, pad, api, nativeDrag } = fixture();
  const chosen = { theta: 1.2, phi: .7, radius: 2.8 };
  nativeDrag(chosen);
  api.orbitInline('left'); expect(viewer.getCameraOrbit().theta).toBeCloseTo(.8);
  expect(viewer.getCameraOrbit().phi).toBe(chosen.phi); expect(viewer.getCameraOrbit().radius).toBe(chosen.radius);
  expect(viewer).not.toHaveAttribute('orientation');
  expect(viewer).toHaveAttribute('min-camera-orbit', 'auto 15deg 65%');
  api.enter(pad); const free = viewer.getCameraOrbit(); api.orbitInline('right'); expect(viewer.getCameraOrbit()).toEqual(free);
  api.exit(); expect(viewer.getCameraOrbit().theta).toBeCloseTo(.8);
  api.orbitInline('right'); expect(viewer.getCameraOrbit().theta).toBeCloseTo(chosen.theta);
  api.destroy();
});

it('repeats enter/reset/exit with live inline snapshots and removes captures, stale frames and listeners each time', () => {
  const { viewer, pad, api, captured, nativeDrag } = fixture();
  for (let cycle = 0; cycle < 5; cycle++) {
    const orbit = { theta: .4 + cycle * .1, phi: .8 + cycle * .05, radius: 2 + cycle * .1 };
    const target = { x: .1 + cycle * .01, y: .3, z: -.1 };
    nativeDrag(orbit, target);
    api.enter(pad); api.roll('left'); frame(); api.reset(); frame();
    expect(viewer.getCameraOrbit().radius).toBeCloseTo(orbit.radius * 1.2);
    pointer(pad, 'pointerdown', cycle + 1, 200, 150); pointer(pad, 'pointermove', cycle + 1, 310, 210);
    expect(captured.size).toBe(1); expect(vi.getTimerCount()).toBe(1);
    api.exit();
    expect(captured.size).toBe(0); expect(vi.getTimerCount()).toBe(0);
    expect(viewer.getCameraOrbit()).toEqual(orbit); expect(viewer.getCameraTarget()).toEqual(target);
    expect(viewer).toHaveAttribute('orientation', '0rad 0rad 0rad');
    expect(viewer).toHaveAttribute('min-camera-orbit', 'auto 15deg 65%'); expect(viewer).toHaveAttribute('max-camera-orbit', 'auto 170deg 160%');
    expect(fireEvent.keyDown(pad, { key: 'q' })).toBe(true);
    pointer(pad, 'pointermove', cycle + 1, 20, 20); frame();
    expect(viewer.getCameraOrbit()).toEqual(orbit); expect(viewer.getCameraTarget()).toEqual(target);
  }
  const final = viewer.getCameraOrbit(); api.destroy(); api.destroy(); api.reset(); api.enter(pad); api.orbitInline('left');
  expect(viewer.getCameraOrbit()).toEqual(final); expect(vi.getTimerCount()).toBe(0);
});

it('rejects an unavailable or nonfinite public camera instead of enabling pretend controls', () => {
  const { viewer } = fixture(); viewer.getCameraOrbit = () => ({ theta: 0, phi: 1, radius: Number.NaN });
  expect(createModelControls(viewer)).toBeNull();
});
