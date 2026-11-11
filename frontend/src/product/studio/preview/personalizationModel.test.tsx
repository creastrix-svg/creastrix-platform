import { act, fireEvent, render, screen } from '@testing-library/react';
import type { ModelViewerElement } from '@google/model-viewer';
import { afterEach, expect, it, vi } from 'vitest';
import ModelPreview from '../../ModelPreview';
import { productCopy } from '../../copy';
import { createPersonalizationModel, loopModelPoint, studioModelSource } from './personalizationModel';
import type { ModelDecoration } from './personalizationModel';
import { letteringFonts } from '../design/personalization';
import { loopLetteringArea } from '../design/studioGeometry';
import { projectLoopRing } from './loopSketchProjection';
import type { StudioObject } from '../design/studioComposition';
import { imageLayout, studioArea } from '../design/studioComposition';

vi.mock('@google/model-viewer', () => ({}));
const cobalt = [.017641954488384078, .07036009569659588, .6038273388553378, 1];
function material(name: string) {
  return { name, pbrMetallicRoughness: { baseColorFactor: [...cobalt], setBaseColorFactor: vi.fn(), baseColorTexture: { texture: null, setTexture: vi.fn() } } };
}
function canvasTexture() {
  const canvas = document.createElement('canvas'), backgrounds: string[] = [];
  const context = { beginPath: vi.fn(), rect: vi.fn(), clip: vi.fn(), drawImage: vi.fn(), setTransform: vi.fn(), save: vi.fn(), restore: vi.fn(), translate: vi.fn(), scale: vi.fn(), fillText: vi.fn(), setLineDash:vi.fn(), strokeRect:vi.fn(), strokeStyle:'', lineWidth:0, font: '', fillStyle: '', textAlign: '', textBaseline: '', fillRect: vi.fn(() => backgrounds.push(context.fillStyle)) };
  vi.spyOn(canvas, 'getContext').mockReturnValue(context as unknown as CanvasRenderingContext2D);
  return { source: { element: canvas, update: vi.fn() }, sampler: { setWrapS: vi.fn(), setWrapT: vi.fn() }, context, backgrounds };
}
// Public scene-graph/camera API fixture. This is lifecycle proof, not WebGL proof.
class StudioViewerFixture extends HTMLElement {
  interpolationDecay = 50;
  model = { materials: [material('Inside lower'), material('Inside upper'), material('Studio exterior lower'), material('Studio exterior upper')] };
  textures: ReturnType<typeof canvasTexture>[] = [];
  createCanvasTexture = vi.fn(() => { const texture = canvasTexture(); this.textures.push(texture); return texture; });
  getCameraOrbit() { return { theta: Math.PI / 6, phi: 68 * Math.PI / 180, radius: 1 }; }
  getCameraTarget() { return { x: 0, y: .06, z: 0 }; }
  jumpCameraToGoal() { /* Camera commands are verified by the existing viewer suite. */ }
}
if (!customElements.get('model-viewer')) customElements.define('model-viewer', StudioViewerFixture);
const design: ModelDecoration = { text: 'Loop', font: 'sans', color: '#ffffff', size: 24, x: 240, y: 280, variant: 'cobalt' };
const fixture = () => document.createElement('model-viewer') as unknown as StudioViewerFixture;
const adapter = (viewer: StudioViewerFixture) => createPersonalizationModel(viewer as unknown as ModelViewerElement);
it('places the zone edges at the same height on the front of the mesh as on the sketch', () => {
  for (const sketchY of [loopLetteringArea.top, loopLetteringArea.bottom]) {
    const point = loopModelPoint(240, sketchY), ring = projectLoopRing(1 - point.y / 1024);
    expect(point.x).toBe(1024);
    expect(Math.abs(ring.cy + ring.ry - sketchY)).toBeLessThan(0.05);
  }
});
const hosts: HTMLElement[] = [];
afterEach(() => { vi.useRealTimers(); hosts.splice(0).forEach(host => host.remove()); });

it('paints ordered text/image compositions with shared cover bounds, caches order and clears an explicit empty composition', () => {
  const viewer = fixture(), model = adapter(viewer), image = { id: 9, src: '', canvas: document.createElement('canvas'), aspect: 2 };
  const imageObject: StudioObject = { id: 'image', kind: 'image', value: { image, size: 85, x: 240, y: 278 }, fit: 'cover' };
  const objects: StudioObject[] = [{ id: 'first', kind: 'text', value: { ...design, text: 'First' } }, imageObject, { id: 'last', kind: 'text', value: { ...design, text: 'Last' } }];
  expect(model.update({ ...design, objects })).toBe(true);
  const context = viewer.textures[0].context, area = studioArea('cobalt'), box = imageLayout(imageObject.value, imageObject.fit, area);
  expect(context.fillText.mock.calls).toEqual([['First', 240, 280], ['Last', 240, 280]]);
  expect(context.fillText.mock.invocationCallOrder[0]).toBeLessThan(context.drawImage.mock.invocationCallOrder[0]);
  expect(context.drawImage.mock.invocationCallOrder[0]).toBeLessThan(context.fillText.mock.invocationCallOrder[1]);
  expect(context.drawImage).toHaveBeenCalledWith(image.canvas, box.x - box.width / 2, box.y - box.height / 2, box.width, box.height);
  expect(context.rect).toHaveBeenCalledWith(area.left, area.top, area.right - area.left, area.bottom - area.top);
  expect(model.update({ ...design, objects: [...objects] })).toBe(true);
  expect(viewer.textures[0].source.update).toHaveBeenCalledTimes(1);
  expect(model.update({ ...design, objects: [...objects].reverse() })).toBe(true);
  expect(viewer.textures[0].source.update).toHaveBeenCalledTimes(2);
  expect(model.update({ ...design, objects: [] })).toBe(true);
  expect(viewer.model.materials[2].pbrMetallicRoughness.baseColorTexture.setTexture).toHaveBeenLastCalledWith(null);
});

it('paints a locally decoded image without text, caches by immutable asset identity and repaints placement or replacement', () => {
  const viewer = fixture(), model = adapter(viewer), canvas = document.createElement('canvas');
  const image = { id: 1, src: 'data:image/png;base64,fixture', canvas, aspect: 2 };
  const artwork = { image, size: 80, x: 240, y: 278 };
  const input = { ...design, text: '', artwork };
  expect(model.update(input)).toBe(true); expect(viewer.createCanvasTexture).toHaveBeenCalledTimes(2);
  for (const texture of viewer.textures) expect(texture.context.drawImage).toHaveBeenCalledWith(canvas, expect.any(Number), expect.any(Number), expect.any(Number), expect.any(Number));
  expect(model.update({ ...input, artwork: { ...artwork } })).toBe(true);
  expect(viewer.textures[0].context.drawImage).toHaveBeenCalledTimes(1);
  expect(model.update({ ...input, artwork: { ...artwork, size: 100 } })).toBe(true);
  expect(model.update({ ...input, artwork: { ...artwork, image: { ...image, id: 2 } } })).toBe(true);
  expect(viewer.textures[0].context.drawImage).toHaveBeenCalledTimes(3);
  model.update({ ...design, text: '' });
  expect(viewer.model.materials[2].pbrMetallicRoughness.baseColorTexture.setTexture).toHaveBeenLastCalledWith(null);
  model.destroy();
});

it('leaves an undecorated model untouched, reuses two canvas textures for latest edits, and never changes inside/caps', () => {
  const viewer = fixture(), model = adapter(viewer);
  expect(model.update()).toBe(true); expect(model.update({ ...design, text: '' })).toBe(true);
  expect(viewer.createCanvasTexture).not.toHaveBeenCalled();
  expect(model.update(design)).toBe(true);
  expect(model.update({ ...design, text: 'New\ntext', font: 'mono', color: '#b68a45', size: 32, x: 250, y: 270 })).toBe(true);
  expect(viewer.createCanvasTexture).toHaveBeenCalledTimes(2);
  for (const texture of viewer.textures) {
    expect(texture.context.fillText.mock.calls).toEqual([['Loop', 0, 0], ['New', 0, -19.2], ['text', 0, 19.2]]);
    expect(texture.context.font).toBe(`32px ${letteringFonts.mono}`);
    expect(texture.context.fillStyle).toBe('#b68a45');
    expect(texture.backgrounds).toEqual(['rgb(36,75,204)', 'rgb(36,75,204)']);
    expect(texture.sampler.setWrapS).toHaveBeenCalledWith(33071); expect(texture.sampler.setWrapT).toHaveBeenCalledWith(33071);
    expect(texture.source.update).toHaveBeenCalledTimes(2);
  }
  for (const inside of viewer.model.materials.slice(0, 2)) {
    expect(inside.pbrMetallicRoughness.setBaseColorFactor).not.toHaveBeenCalled();
    expect(inside.pbrMetallicRoughness.baseColorTexture.setTexture).not.toHaveBeenCalled();
  }
  expect(model.update({ ...design, text: ' ' })).toBe(true);
  for (const exterior of viewer.model.materials.slice(2)) {
    expect(exterior.pbrMetallicRoughness.baseColorTexture.setTexture).toHaveBeenLastCalledWith(null);
    expect(exterior.pbrMetallicRoughness.setBaseColorFactor).toHaveBeenLastCalledWith(cobalt);
  }
  expect(model.update(design)).toBe(true); expect(viewer.createCanvasTexture).toHaveBeenCalledTimes(2);
  model.destroy();
  for (const texture of viewer.textures) expect([texture.source.element.width, texture.source.element.height]).toEqual([1, 1]);
  expect(model.update(design)).toBe(false);
});

it('does not redraw identical input and fails closed on missing editor materials without altering the original model', () => {
  const viewer = fixture(), model = adapter(viewer);
  model.update(design); model.update({ ...design });
  expect(viewer.textures[0].source.update).toHaveBeenCalledTimes(1);
  model.destroy();
  const original = fixture(); original.model.materials = original.model.materials.slice(0, 2);
  expect(adapter(original).update(design)).toBe(false); expect(original.createCanvasTexture).not.toHaveBeenCalled();
});
it('draws an empty studio guide on the exterior in the same coordinates as lettering and removes it outside the studio', () => {
  const viewer = fixture(), model = adapter(viewer), area = loopLetteringArea;
  expect(model.update({ ...design,text:'',showArea:true })).toBe(true);
  const corner = loopModelPoint(area.left,area.top);
  for (const texture of viewer.textures) {
    expect(texture.context.translate).toHaveBeenLastCalledWith(corner.x,corner.y);
    expect(texture.context.strokeRect).toHaveBeenCalledWith(0,0,area.right-area.left,area.bottom-area.top);
    expect(texture.context.setLineDash).toHaveBeenCalledWith([4,5]);
  }
  expect(model.update({ ...design,text:'',showArea:false })).toBe(true);
  for (const material of viewer.model.materials.slice(2)) expect(material.pbrMetallicRoughness.baseColorTexture.setTexture).toHaveBeenLastCalledWith(null);
  model.destroy();
  for (const texture of viewer.textures) expect([texture.source.element.width,texture.source.element.height]).toEqual([1,1]);
});
it.each([[loopLetteringArea.left,loopLetteringArea.top],[loopLetteringArea.right,loopLetteringArea.bottom]])('maps a guide endpoint and lettering at %s,%s identically and retains artwork', (x,y) => {
  const viewer = fixture(), model = adapter(viewer), canvas=document.createElement('canvas');
  const artwork={ image:{ id:3,src:'data:image/png;base64,fixture',canvas,aspect:1 },size:60,x:240,y:278 };
  expect(model.update({ ...design,x,y,artwork,showArea:true })).toBe(true);
  const point=loopModelPoint(x,y);
  for (const texture of viewer.textures) {
    expect(texture.context.translate).toHaveBeenNthCalledWith(1,point.x,point.y);
    expect(texture.context.drawImage).toHaveBeenCalledOnce();
    expect(texture.context.fillText).toHaveBeenLastCalledWith('Loop',0,0);
  }
  model.update({ ...design,x,y,artwork });
  for (const texture of viewer.textures) { expect(texture.source.update).toHaveBeenCalledTimes(2); expect(texture.context.strokeRect).toHaveBeenCalledTimes(1); }
});

it.each(['creation', 'paint'])('restores the shape/materials and releases canvas backing stores after %s failure', failure => {
  const viewer = fixture(), model = adapter(viewer);
  if (failure === 'creation') viewer.createCanvasTexture.mockImplementationOnce(() => { const texture = canvasTexture(); viewer.textures.push(texture); return texture; }).mockImplementationOnce(() => { throw new Error('unavailable'); });
  else { model.update(design); viewer.textures[1].source.update.mockImplementation(() => { throw new Error('unavailable'); }); }
  expect(model.update({ ...design, text: 'Latest' })).toBe(false);
  for (const texture of viewer.textures) expect(texture.source.element.width).toBe(1);
  for (const exterior of viewer.model.materials.slice(2, failure === 'creation' ? 3 : 4)) {
    expect(exterior.pbrMetallicRoughness.baseColorTexture.setTexture).toHaveBeenLastCalledWith(null);
    expect(exterior.pbrMetallicRoughness.setBaseColorFactor).toHaveBeenLastCalledWith(cobalt);
  }
  expect(model.update(design)).toBe(false); expect(model.update()).toBe(true); model.destroy();
});

async function start() {
  vi.useFakeTimers();
  const target = document.createElement('div'); document.body.append(target); hosts.push(target);
  const onDecorationError = vi.fn();
  const view = render(<ModelPreview t={productCopy.en} target={target} expanded source={studioModelSource('cobalt')} decoration={design} onDecorationError={onDecorationError} />);
  await act(async () => { for (let i = 0; i < 10; i++) await Promise.resolve(); });
  const viewer = target.querySelector('model-viewer') as unknown as StudioViewerFixture;
  return { ...view, viewer, target, onDecorationError };
}

it('applies only the latest pre-load input, updates without recreating viewer or pose, and works without an error callback', async () => {
  const { viewer, target, rerender, unmount } = await start();
  expect(viewer.createCanvasTexture).not.toHaveBeenCalled();
  rerender(<ModelPreview t={productCopy.en} target={target} expanded source={studioModelSource('cobalt')} decoration={{ ...design, text: 'Latest' }} />);
  fireEvent(viewer, new Event('load'));
  expect(viewer.textures[0].context.fillText).toHaveBeenLastCalledWith('Latest', 0, 0);
  fireEvent.click(screen.getByRole('button', { name: productCopy.en.rotateLeft })); act(() => vi.advanceTimersByTime(25));
  const pose = viewer.getAttribute('orientation');
  rerender(<ModelPreview t={productCopy.en} target={target} expanded source={studioModelSource('cobalt')} decoration={{ ...design, text: 'Newest' }} />);
  expect(target.querySelector('model-viewer')).toBe(viewer); expect(viewer).toHaveAttribute('orientation', pose!);
  expect(viewer.textures[0].context.fillText).toHaveBeenLastCalledWith('Newest', 0, 0);
  fireEvent(viewer, new Event('load')); expect(viewer.createCanvasTexture).toHaveBeenCalledTimes(2);
  unmount(); expect(viewer.textures[0].source.element.width).toBe(1); expect(vi.getTimerCount()).toBe(0);
});

it('retains interactive real 3D shape when decoration fails and reports a separate decoration error', async () => {
  const { viewer, onDecorationError } = await start();
  viewer.createCanvasTexture.mockImplementation(() => { throw new Error('canvas unavailable'); });
  fireEvent(viewer, new Event('load'));
  expect(onDecorationError).toHaveBeenLastCalledWith(true);
  expect(screen.queryByRole('status')).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: productCopy.en.resetView })).toBeEnabled();
  expect(viewer.style.display).not.toBe('none');
});

it.each(['error', 'source'])('releases decoration on viewer %s and ignores stale notifications', async reason => {
  const { viewer, target, rerender } = await start(); fireEvent(viewer, new Event('load'));
  const painted = viewer.textures[0].source.update.mock.calls.length;
  if (reason === 'error') fireEvent(viewer, new Event('error'));
  else rerender(<ModelPreview t={productCopy.en} target={target} expanded source={studioModelSource('coral')} decoration={{ ...design, variant: 'coral', text: 'Coral' }} />);
  expect(viewer.textures[0].source.element.width).toBe(1);
  fireEvent(viewer, new Event('load')); fireEvent(viewer, new Event('error'));
  expect(viewer.textures[0].source.update).toHaveBeenCalledTimes(painted);
  expect(screen.getByRole('button', { name: productCopy.en.resetView })).toBeDisabled();
});
