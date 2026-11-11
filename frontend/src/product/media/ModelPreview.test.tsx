import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import ModelPreview from './ModelPreview';
import { productCopy } from '../copy';

vi.mock('@google/model-viewer', () => ({}));
// Public APIs only: this fixture proves lifecycle/commands, not actual WebGL rendering.
class PublicViewerFixture extends HTMLElement {
  interpolationDecay = 50;
  orbit = { theta: Math.PI / 6, phi: 68 * Math.PI / 180, radius: 1 };
  target = { x: .1, y: .2, z: 0 };
  model = { materials: ['Studio lamp front panel','Studio lamp front cut edge','Studio lamp base engraving'].map(name => ({ name, emissiveFactor: [0,0,0], setEmissiveFactor: vi.fn() })) };
  materialFromPoint = vi.fn(() => this.model.materials[0]);
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
async function start(expanded = false, source?: string) {
  vi.useFakeTimers();
  const target = host();
  const view = render(<ModelPreview t={productCopy.en} target={target} expanded={expanded} source={source} />);
  await act(async () => { await vi.dynamicImportSettled(); });
  return { ...view, target, viewer: target.querySelector('model-viewer') as unknown as PublicViewerFixture };
}
it.each(['/product/loop-shaped-cobalt.glb', '/product/studio-loop-cobalt-chalk.glb', '/product/studio-lamp-forest.glb'])('uses the same long lens for product and studio Loop, without changing lamp framing: %s', async source => {
  const { viewer } = await start(false, source);
  if (source.includes('lamp')) {
    expect(viewer).not.toHaveAttribute('field-of-view');
    expect(viewer).toHaveAttribute('camera-orbit', '30deg 68deg 105%');
  } else {
    expect(viewer).toHaveAttribute('field-of-view', '12deg');
    expect(viewer).toHaveAttribute('camera-orbit', '30deg 77.5deg 105%');
  }
});
it('loads only the local GLB without AR/external environment and cancels the deadline after real load notification', async () => {
  const { viewer } = await start();
  expect(viewer).toHaveAttribute('src', '/product/loop-shaped-cobalt.glb');
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
  expect(viewer).toHaveAttribute('src', '/product/loop-shaped-cobalt.glb'); expect(screen.queryByRole('status')).not.toBeInTheDocument();
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

function tapZone(element: HTMLElement, id = 1) {
  for (const type of ['pointerdown','pointerup']) fireEvent(element,new PointerEvent(type,{pointerId:id,clientX:100,clientY:120,button:0,isPrimary:true,bubbles:true,composed:true}));
}
it('selects zones only after load and keeps picking/highlighting attached to the current inline or expanded host', async () => {
  const {viewer,target,rerender,unmount} = await start(), onSelect = vi.fn();
  const props = { t:productCopy.en,target,expanded:false,lampZoneSelection:{selected:'base' as const,onSelect} };
  rerender(<ModelPreview {...props} />);
  vi.spyOn(viewer,'getBoundingClientRect').mockReturnValue(new DOMRect(0,0,400,300));
  tapZone(viewer); expect(viewer.materialFromPoint).not.toHaveBeenCalled();
  fireEvent(viewer,new Event('load')); tapZone(viewer); expect(onSelect).toHaveBeenLastCalledWith('panel');
  expect(target.querySelector('.studio-model-hover-marker')).toHaveAttribute('hidden');
  expect(viewer.model.materials[2].setEmissiveFactor).not.toHaveBeenCalled();
  const expandedHost = host(); rerender(<ModelPreview {...props} target={expandedHost} expanded lampZoneSelection={{selected:'panel',onSelect}} />);
  const pad = expandedHost.querySelector<HTMLElement>('.product-model-pad')!;
  vi.spyOn(pad,'getBoundingClientRect').mockReturnValue(new DOMRect(0,0,400,300));
  viewer.materialFromPoint.mockReturnValue(viewer.model.materials[2]);
  tapZone(pad); expect(onSelect).toHaveBeenLastCalledWith('base'); expect(onSelect).toHaveBeenCalledTimes(2);
  tapZone(viewer); expect(onSelect).toHaveBeenCalledTimes(2);
  fireEvent.click(screen.getByRole('button',{name:productCopy.en.resetView})); expect(onSelect).toHaveBeenCalledTimes(2);
  rerender(<ModelPreview {...props} lampZoneSelection={{selected:'panel',onSelect}} />);
  tapZone(pad); expect(onSelect).toHaveBeenCalledTimes(2);
  tapZone(viewer); expect(onSelect).toHaveBeenCalledTimes(3);
  unmount(); tapZone(viewer); expect(onSelect).toHaveBeenCalledTimes(3);
  for (const material of viewer.model.materials) expect(material.setEmissiveFactor).not.toHaveBeenCalled();
});

it('removes selection behavior and highlight when the optional feature is removed or loading fails', async () => {
  const {viewer,target,rerender} = await start(), onSelect = vi.fn();
  vi.spyOn(viewer,'getBoundingClientRect').mockReturnValue(new DOMRect(0,0,400,300));
  fireEvent(viewer,new Event('load'));
  rerender(<ModelPreview t={productCopy.en} target={target} expanded={false} lampZoneSelection={{selected:'base',onSelect}} />);
  tapZone(viewer); expect(onSelect).toHaveBeenCalledOnce();
  rerender(<ModelPreview t={productCopy.en} target={target} expanded={false} />);
  tapZone(viewer); expect(onSelect).toHaveBeenCalledOnce();
  expect(target.querySelector('.studio-model-hover-marker')).toBeNull();
  rerender(<ModelPreview t={productCopy.en} target={target} expanded={false} lampZoneSelection={{selected:'base',onSelect}} />);
  fireEvent(viewer,new Event('error')); tapZone(viewer); expect(onSelect).toHaveBeenCalledOnce();
  expect(viewer.model.materials[2].setEmissiveFactor).not.toHaveBeenCalled();
});

it('cleans the hover marker, cursor ownership and queued rays across hidden mode, fullscreen and source replacement', async () => {
  const {viewer,target,rerender,unmount} = await start(), onSelect=vi.fn();
  const props = { t:productCopy.en,target,expanded:false,lampZoneSelection:{selected:'base' as const,onSelect} };
  viewer.style.cursor='grab';
  vi.spyOn(viewer,'getBoundingClientRect').mockReturnValue(new DOMRect(0,0,400,300));
  fireEvent(viewer,new Event('load')); rerender(<ModelPreview {...props} />);
  const hover = (element:HTMLElement) => { fireEvent(element,new PointerEvent('pointermove',{pointerType:'mouse',pointerId:1,buttons:0,clientX:100,clientY:120,bubbles:true,composed:true})); act(() => vi.advanceTimersByTime(20)); };
  hover(viewer); expect(viewer.style.cursor).toBe('pointer');
  expect(target.querySelector('.studio-model-hover-marker')).toHaveAttribute('data-hover-zone','panel');
  rerender(<ModelPreview {...props} lampZoneSelection={undefined} />);
  expect(viewer.style.cursor).toBe('grab'); expect(target.querySelector('.studio-model-hover-marker')).toBeNull();
  const expandedHost=host(); rerender(<ModelPreview {...props} target={expandedHost} expanded />);
  const pad=expandedHost.querySelector<HTMLElement>('.product-model-pad')!;
  vi.spyOn(pad,'getBoundingClientRect').mockReturnValue(new DOMRect(0,0,400,300));
  hover(pad); expect(pad.style.cursor).toBe('pointer'); expect(onSelect).not.toHaveBeenCalled();
  expect(expandedHost.querySelector('.studio-model-hover-marker')).toHaveAttribute('data-hover-zone','panel');
  rerender(<ModelPreview {...props} source="/product/studio-lamp-forest.glb" />);
  expect(pad.style.cursor).toBe(''); expect(expandedHost.querySelector('.studio-model-hover-marker')).toBeNull();
  expect(viewer.model.materials[0].setEmissiveFactor).not.toHaveBeenCalled();
  const previousCalls=viewer.materialFromPoint.mock.calls.length; hover(pad); expect(viewer.materialFromPoint).toHaveBeenCalledTimes(previousCalls);
  await act(async () => { await vi.dynamicImportSettled(); });
  const current=target.querySelector('model-viewer') as unknown as PublicViewerFixture;
  expect(current).not.toBe(viewer); vi.spyOn(current,'getBoundingClientRect').mockReturnValue(new DOMRect(0,0,400,300));
  fireEvent(current,new Event('load')); hover(current); expect(current.style.cursor).toBe('pointer');
  unmount(); expect(current.style.cursor).toBe(''); expect(vi.getTimerCount()).toBe(0);
});
