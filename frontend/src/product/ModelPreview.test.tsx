import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import ModelPreview from './ModelPreview';
import { productCopy } from './copy';

vi.mock('@google/model-viewer', () => ({}));
// Public APIs only: this fixture proves lifecycle/commands, not actual WebGL rendering.
class PublicViewerFixture extends HTMLElement {
  interpolationDecay = 50;
  orbit = { theta: Math.PI / 6, phi: 68 * Math.PI / 180, radius: 1 };
  target = { x: .1, y: .2, z: 0 };
  getCameraOrbit() { return { ...this.orbit }; }
  getCameraTarget() { return { ...this.target }; }
  jumpCameraToGoal() {
    const orbit = this.getAttribute('camera-orbit')?.split(' ').map(parseFloat);
    if (orbit) this.orbit = { theta: orbit[0], phi: orbit[1], radius: orbit[2] };
    const target = this.getAttribute('camera-target')?.split(' ').map(parseFloat);
    if (target) this.target = { x: target[0], y: target[1], z: target[2] };
  }
}
if (!customElements.get('model-viewer')) customElements.define('model-viewer', PublicViewerFixture);
const targets: HTMLElement[] = [];
function host() { const element = document.createElement('div'); document.body.append(element); targets.push(element); return element; }
afterEach(() => { vi.useRealTimers(); targets.splice(0).forEach(element => element.remove()); });
async function start(expanded = false) {
  vi.useFakeTimers();
  const target = host();
  const view = render(<ModelPreview t={productCopy.en} target={target} expanded={expanded} />);
  await act(async () => { for (let i = 0; i < 10; i++) await Promise.resolve(); });
  return { ...view, target, viewer: target.querySelector('model-viewer') as unknown as PublicViewerFixture };
}
it('loads only the local GLB without AR/external environment and cancels the deadline after real load notification', async () => {
  const { viewer } = await start();
  expect(viewer).toHaveAttribute('src', '/product/loop.glb');
  expect(viewer).toHaveAttribute('camera-controls');
  expect(viewer).toHaveAttribute('tabindex', '0');
  expect(viewer).toHaveAttribute('aria-label', productCopy.en.model);
  expect(document.getElementById(viewer.getAttribute('aria-describedby')!)).toHaveTextContent(productCopy.en.modelHint);
  expect(document.getElementById(viewer.getAttribute('aria-describedby')!)).toHaveClass('product-sr-only');
  expect(document.getElementById(viewer.getAttribute('aria-describedby')!)).not.toHaveClass('product-model-hint');
  expect(viewer).not.toHaveAttribute('ar');
  expect(viewer).not.toHaveAttribute('environment-image');
  fireEvent(viewer, new Event('load'));
  expect(screen.queryByRole('status')).not.toBeInTheDocument();
  act(() => { vi.advanceTimersByTime(60_000); });
  expect(screen.queryByText(productCopy.en.modelFailed)).not.toBeInTheDocument();
});
it.each(['error', 'deadline'])('keeps an honest fallback after %s, and a late load cannot revive timed-out work', async mode => {
  const { viewer } = await start();
  if (mode === 'error') fireEvent(viewer, new Event('error'));
  else act(() => { vi.advanceTimersByTime(30_000); });
  expect(screen.getByRole('status')).toHaveTextContent(productCopy.en.modelFailed);
  expect(screen.getByRole('button', { name: productCopy.en.resetView })).toBeDisabled();
  fireEvent(viewer, new Event('load'));
  expect(screen.getByRole('status')).toHaveTextContent(productCopy.en.modelFailed);
});

it('keeps free-rotation tools disabled until real load, then supports explicit zoom/roll/reset', async () => {
  const { viewer } = await start(true);
  for (const label of [productCopy.en.rotateLeft, productCopy.en.zoomOut, productCopy.en.resetView, productCopy.en.zoomIn, productCopy.en.rotateRight]) expect(screen.getByRole('button', { name: label })).toBeDisabled();
  expect(screen.getByRole('group', { name: productCopy.en.rotationLabel })).toHaveAttribute('tabindex', '-1');
  fireEvent(viewer, new Event('load'));
  const roll = screen.getByRole('button', { name: productCopy.en.rotateLeft }); expect(roll).toBeEnabled();
  const hint = document.getElementById(viewer.getAttribute('aria-describedby')!);
  expect(hint).toHaveClass('product-model-hint'); expect(hint).toHaveTextContent(productCopy.en.rotationHint);
  const reset = screen.getByRole('button', { name: productCopy.en.resetView });
  expect(reset).toHaveAttribute('title', productCopy.en.resetView);
  expect(reset.querySelector('path')!.getAttribute('d')).not.toBe(roll.querySelector('path')!.getAttribute('d'));
  expect(viewer).not.toHaveAttribute('camera-controls');
  fireEvent.click(roll); act(() => { vi.advanceTimersByTime(25); }); expect(viewer.getAttribute('orientation')).not.toBe('0rad 0rad 0rad');
  fireEvent.click(screen.getByRole('button', { name: productCopy.en.zoomIn })); act(() => { vi.advanceTimersByTime(25); }); expect(viewer.orbit.radius).toBeCloseTo(1.02);
  fireEvent.click(screen.getByRole('button', { name: productCopy.en.resetView })); act(() => { vi.advanceTimersByTime(25); }); expect(viewer).toHaveAttribute('orientation', '0rad 0rad 0rad'); expect(viewer.orbit.radius).toBeCloseTo(1.2);
});

it('keeps one viewer across hosts/languages but restores the captured actual inline view on fullscreen exit', async () => {
  const { viewer, target, rerender } = await start();
  fireEvent(viewer, new Event('load'));
  const selected = { theta: .8, phi: .9, radius: 1.35 }, targetPoint = { x: .2, y: .1, z: .05 };
  viewer.orbit = { ...selected }; viewer.target = { ...targetPoint };
  const expandedHost = host();
  rerender(<ModelPreview t={productCopy.en} target={expandedHost} expanded />);
  expect(expandedHost.querySelector('model-viewer')).toBe(viewer); expect(target.querySelector('model-viewer')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: productCopy.en.rotateLeft })); act(() => { vi.advanceTimersByTime(25); });
  const pose = viewer.getAttribute('orientation');
  rerender(<ModelPreview t={productCopy.de} target={expandedHost} expanded />);
  expect(expandedHost.querySelector('model-viewer')).toBe(viewer); expect(viewer).toHaveAttribute('aria-label', productCopy.de.model);
  expect(viewer).toHaveAttribute('orientation', pose!); expect(screen.queryByRole('status')).not.toBeInTheDocument();
  rerender(<ModelPreview t={productCopy.de} target={target} expanded={false} />);
  expect(target.querySelector('model-viewer')).toBe(viewer); expect(viewer).toHaveAttribute('camera-controls');
  expect(viewer).toHaveAttribute('orientation', '0rad 0rad 0rad');
  expect(viewer.orbit).toEqual(selected); expect(viewer.target).toEqual(targetPoint);
  expect(viewer).toHaveAttribute('min-camera-orbit', 'auto 15deg 65%'); expect(viewer).toHaveAttribute('max-camera-orbit', 'auto 170deg 160%');
  rerender(<ModelPreview t={productCopy.de} target={null} expanded={false} />); expect(viewer.isConnected).toBe(false);
  rerender(<ModelPreview t={productCopy.en} target={expandedHost} expanded />);
  expect(expandedHost.querySelector('model-viewer')).toBe(viewer); expect(viewer).toHaveAttribute('orientation', '0rad 0rad 0rad');
  expect(viewer).toHaveAttribute('src', '/product/loop.glb'); expect(screen.queryByRole('status')).not.toBeInTheDocument();
});

it('cancels a not-yet-painted free gesture and restores inline before the fullscreen host disappears', async () => {
  const { viewer, target, rerender } = await start(true); fireEvent(viewer, new Event('load'));
  const before = viewer.getAttribute('orientation');
  fireEvent.click(screen.getByRole('button', { name: productCopy.en.rotateLeft }));
  expect(viewer).toHaveAttribute('orientation', before!);
  const next = host(); rerender(<ModelPreview t={productCopy.en} target={next} expanded={false} />);
  expect(next.querySelector('model-viewer')).toBe(viewer); expect(target.querySelector('model-viewer')).toBeNull();
  expect(viewer.getAttribute('orientation')).toBe(before); expect(viewer.orbit.radius).toBeCloseTo(1);
  expect(vi.getTimerCount()).toBe(0);
  act(() => { vi.advanceTimersByTime(25); }); expect(viewer.orbit.radius).toBeCloseTo(1);
});

it('exposes a disabled-until-load inline reset and bounded orbit steps, then resets the full normal view', async () => {
  const { viewer, rerender, target } = await start();
  const reset = screen.getByRole('button', { name: productCopy.en.resetView });
  expect(reset).toBeDisabled(); expect(target.querySelector('.product-model-pad')).toBeNull();
  expect(reset).toHaveAttribute('title', productCopy.en.resetView);
  expect(reset.querySelector('path')!.getAttribute('d')).not.toBe(screen.getByRole('button', { name: productCopy.en.rotateLeft }).querySelector('path')!.getAttribute('d'));
  fireEvent(viewer, new Event('load')); expect(reset).toBeEnabled();
  const home = { ...viewer.orbit }, center = { ...viewer.target };
  fireEvent.click(screen.getByRole('button', { name: productCopy.en.rotateLeft })); expect(viewer.orbit.theta).toBeCloseTo(home.theta - .4);
  fireEvent.click(screen.getByRole('button', { name: productCopy.en.rotateRight })); expect(viewer.orbit.theta).toBeCloseTo(home.theta);
  viewer.orbit = { theta: 1.2, phi: .5, radius: 1.5 }; viewer.target = { x: .2, y: .4, z: 0 };
  viewer.setAttribute('orientation', '.1rad .2rad .3rad');
  rerender(<ModelPreview t={productCopy.de} target={target} expanded={false} />);
  expect(viewer.orbit.theta).toBe(1.2);
  fireEvent.click(screen.getByRole('button', { name: productCopy.de.resetView }));
  expect(screen.getByRole('button', { name: productCopy.de.resetView })).toHaveAttribute('title', productCopy.de.resetView);
  expect(viewer.orbit).toEqual(home); expect(viewer.target).toEqual(center);
  expect(viewer).toHaveAttribute('orientation', '0rad 0rad 0rad');
  expect(viewer).toHaveAttribute('min-camera-orbit', 'auto 15deg 65%'); expect(viewer).toHaveAttribute('max-camera-orbit', 'auto 170deg 160%');
  fireEvent(viewer, new Event('error'));
  expect(screen.getByRole('status')).toHaveTextContent(productCopy.de.modelFailed);
  for (const name of [productCopy.de.rotateLeft, productCopy.de.resetView, productCopy.de.rotateRight]) expect(screen.getByRole('button', { name })).toBeDisabled();
});

it('does not recreate controls or reset pose on a duplicate load event and cleans pending work on unmount', async () => {
  const { viewer, unmount } = await start(true); fireEvent(viewer, new Event('load'));
  fireEvent.click(screen.getByRole('button', { name: productCopy.en.rotateLeft })); act(() => { vi.advanceTimersByTime(25); });
  const pose = viewer.getAttribute('orientation'); fireEvent(viewer, new Event('load')); expect(viewer).toHaveAttribute('orientation', pose!);
  fireEvent.click(screen.getByRole('button', { name: productCopy.en.rotateRight })); unmount();
  expect(viewer.isConnected).toBe(false); expect(vi.getTimerCount()).toBe(0);
  act(() => { vi.advanceTimersByTime(60_000); }); fireEvent(viewer, new Event('load')); expect(screen.queryByRole('status')).not.toBeInTheDocument();
});

it('replaces a changed material asset with a fresh loading owner, ignoring stale source load/error and queued controls', async () => {
  const { viewer: old, target, rerender } = await start(true); fireEvent(old, new Event('load'));
  fireEvent.click(screen.getByRole('button', { name: productCopy.en.rotateLeft }));
  rerender(<ModelPreview source="/product/loop-shaped-coral.glb" t={productCopy.en} target={target} expanded />);
  expect(old.isConnected).toBe(false); expect(screen.getByRole('button', { name: productCopy.en.resetView })).toBeDisabled();
  await act(async () => { for (let i = 0; i < 10; i++) await Promise.resolve(); });
  const current = target.querySelector('model-viewer')!;
  expect(current).not.toBe(old); expect(current).toHaveAttribute('src', '/product/loop-shaped-coral.glb');
  fireEvent(old, new Event('load')); fireEvent(old, new Event('error'));
  expect(screen.getByRole('button', { name: productCopy.en.resetView })).toBeDisabled();
  expect(screen.getByRole('status')).toHaveTextContent(productCopy.en.loadingModel);
  fireEvent(current, new Event('load'));
  expect(screen.getByRole('button', { name: productCopy.en.resetView })).toBeEnabled();
  act(() => vi.advanceTimersByTime(25)); expect(current).toHaveAttribute('orientation', '0rad 0rad 0rad');
});

it.each(['rejected', 'deadline'])('keeps a module-import %s failure terminal even if import later resolves', async mode => {
  vi.resetModules();
  let release: (() => void) | undefined;
  vi.doMock('@google/model-viewer', () => mode === 'rejected' ? Promise.reject(new Error('module unavailable')) : new Promise(resolve => { release = () => resolve({}); }));
  try {
    const FreshPreview = (await import('./ModelPreview')).default;
    vi.useFakeTimers(); const target = host();
    render(<FreshPreview t={productCopy.en} target={target} expanded />);
    await act(async () => { for (let i = 0; i < 10; i++) await Promise.resolve(); });
    if (mode === 'deadline') act(() => { vi.advanceTimersByTime(30_000); });
    expect(screen.getByRole('status')).toHaveTextContent(productCopy.en.modelFailed);
    await act(async () => { release?.(); for (let i = 0; i < 10; i++) await Promise.resolve(); });
    expect(target.querySelector('model-viewer')).toBeNull(); expect(screen.getByRole('button', { name: productCopy.en.rotateLeft })).toBeDisabled();
  } finally { vi.doMock('@google/model-viewer', () => ({})); vi.resetModules(); }
});
