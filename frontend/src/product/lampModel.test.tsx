import { render } from '@testing-library/react';
import type { ModelViewerElement } from '@google/model-viewer';
import { expect, it, vi } from 'vitest';
import { createLampModel, lampBaseMapping, lampBasePoint, lampModelSource, lampPanelTextMapping, lampPanelTextPoint } from './lampModel';
import { defaultLettering } from './personalization';
import { emptyCreativeDraft } from './studioCreativeContext';
import LampZoneSketch from './LampZoneSketch';
import { lampBaseFace, lampBaseLetteringArea } from './studioGeometry';
import type { StudioObject } from './studioComposition';
import { imageLayout, studioArea } from './studioComposition';

function fixture() {
  const material = (name: string) => ({ name, setEmissiveFactor:vi.fn(), pbrMetallicRoughness: { baseColorFactor: [.7454,.4969,.2307,1], baseColorTexture: { texture: null, setTexture: vi.fn() }, setBaseColorFactor: vi.fn() } });
  const resources = Array.from({ length:2 }, () => {
    const canvas = document.createElement('canvas');
    const context = { setTransform: vi.fn(), fillRect: vi.fn(), drawImage: vi.fn(), save: vi.fn(), restore: vi.fn(), translate: vi.fn(), scale: vi.fn(), fillText: vi.fn(), beginPath:vi.fn(), rect:vi.fn(), clip:vi.fn(), setLineDash:vi.fn(), strokeRect:vi.fn(), strokeStyle:'', lineWidth:0, fillStyle: '', font: '', textAlign: '', textBaseline: '', filter: '', globalAlpha: 1 };
    vi.spyOn(canvas,'getContext').mockReturnValue(context as unknown as CanvasRenderingContext2D);
    const texture = { source: { element: canvas, update: vi.fn() }, sampler: { setWrapS: vi.fn(), setWrapT: vi.fn(), setScale:vi.fn(), setOffset:vi.fn() } };
    return { canvas,context,texture };
  });
  let next = 0;
  const viewer = { model: { materials: [material('Birch plywood face'),material('Studio lamp base engraving'),material('Studio lamp front panel')] }, createCanvasTexture: vi.fn(() => resources[next++].texture) };
  return { viewer, ...resources[0], resources, adapter: createLampModel(viewer as unknown as ModelViewerElement) };
}
const value = { ...defaultLettering('lamp'), text: 'Luma' };

it('uses exact local prepared model assets and shares the 2D/base mapping', () => {
  expect(lampModelSource('forest')).toBe('/product/studio-lamp-forest.glb');
  expect(lampBasePoint(240,278)).toEqual({ x: lampBaseMapping.centerX,y: lampBaseMapping.centerY });
  expect(lampBasePoint(250,280)).toEqual({ x: 810,y: 268.4 });
});
it('paints only the outward base, reuses one bounded canvas and respects text position', () => {
  const { viewer, adapter, canvas, context, texture } = fixture();
  expect(adapter.update()).toBe(true); expect(viewer.createCanvasTexture).not.toHaveBeenCalled();
  expect(adapter.update({ value })).toBe(true);
  expect([canvas.width,canvas.height]).toEqual([1536,520]);
  expect(context.fillText).toHaveBeenLastCalledWith('Luma',0,0);
  expect(context.translate).toHaveBeenLastCalledWith(768,260);
  expect(context.fillStyle).toBe('#302219');
  expect(viewer.model.materials[0].pbrMetallicRoughness.setBaseColorFactor).not.toHaveBeenCalled();
  adapter.update({ value: { ...value } }); expect(texture.source.update).toHaveBeenCalledTimes(1);
  adapter.update({ value: { ...value,text:'New',x:250 } });
  expect(context.translate).toHaveBeenLastCalledWith(810,260); expect(texture.source.update).toHaveBeenCalledTimes(2);
  expect(viewer.createCanvasTexture).toHaveBeenCalledTimes(1);
  adapter.destroy(); expect([canvas.width,canvas.height]).toEqual([1,1]); expect(adapter.update({ value })).toBe(false);
});
it('renders a decoded local image without replacing the front cut pattern or requiring text', () => {
  const { adapter,context } = fixture(), draft = emptyCreativeDraft();
  const imageCanvas = document.createElement('canvas');
  const artwork = { ...draft.artwork,image: { id:1,src:'data:image/png;base64,a',canvas:imageCanvas,aspect:2 } };
  expect(adapter.update({ value: { ...value,text:'' },artwork })).toBe(true);
  expect(context.drawImage).toHaveBeenCalledWith(imageCanvas,expect.any(Number),expect.any(Number),expect.any(Number),expect.any(Number));
  adapter.update({ value: { ...value,text:'' },artwork: { ...artwork,image: { ...artwork.image,id:2 } } });
  expect(context.drawImage).toHaveBeenCalledTimes(2);
});
it('restores the original material when removed, without changing the mesh', () => {
  const { adapter,viewer } = fixture(); adapter.update({ value }); adapter.update();
  expect(viewer.model.materials[1].pbrMetallicRoughness.baseColorTexture.setTexture).toHaveBeenLastCalledWith(null);
  expect(viewer.model.materials[1].pbrMetallicRoughness.setBaseColorFactor).toHaveBeenLastCalledWith([.7454,.4969,.2307,1]);
});
it('fails locally when the prepared material is absent, without allocating a texture', () => {
  const { adapter,viewer } = fixture(); viewer.model.materials.length=1;
  expect(adapter.update({ value })).toBe(false); expect(viewer.createCanvasTexture).not.toHaveBeenCalled();
  expect(adapter.update()).toBe(true);
});
it('releases the canvas and restores the original material on a paint failure', () => {
  const { adapter,texture,canvas,viewer } = fixture();
  texture.source.update.mockImplementation(() => { throw new Error('fixture'); });
  expect(adapter.update({ value })).toBe(false); expect([canvas.width,canvas.height]).toEqual([1,1]);
  expect(viewer.model.materials[1].pbrMetallicRoughness.baseColorTexture.setTexture).toHaveBeenLastCalledWith(null);
  expect(adapter.update({ value })).toBe(false);
});
it('2D base view keeps the front pattern and fixed connections while displaying engraving', () => {
  const draft = { ...emptyCreativeDraft(),pattern:'forest' as const,bridges:false };
  const { container,rerender } = render(<LampZoneSketch value={value} draft={draft} zone="base" label="Lamp" />);
  expect(container.querySelector('[data-lamp-active-zone]')).toHaveAttribute('data-lamp-active-zone','base');
  expect(container.querySelector('[data-cut-pattern]')).toHaveAttribute('data-cut-pattern','forest');
  expect(container.querySelectorAll('[data-bridge-for]')).toHaveLength(3);
  expect(container.textContent).toContain('Luma');
  rerender(<LampZoneSketch value={value} draft={draft} zone="panel" label="Lamp" />);
  expect(container.querySelector('[data-lamp-active-zone]')).toHaveAttribute('data-lamp-active-zone','panel');
  expect(container.textContent).toContain('Luma');
});

const panelText = { ...defaultLettering('lamp'),font:'stencil' as const,text:'HOME' };

// Source texture contrast, before model lighting and display tone mapping.
function luminance(hex: string) {
  expect(hex).toMatch(/^#[0-9a-f]{6}$/i);
  const [r, g, b] = [1, 3, 5].map(i => parseInt(hex.slice(i, i + 2), 16) / 255)
    .map(c => c <= .04045 ? c / 12.92 : ((c + .055) / 1.055) ** 2.4);
  return .2126 * r + .7152 * g + .0722 * b;
}
it.each(['composition', 'scalar'] as const)('paints both %s labels with a high-contrast preview ink before and after switching area guides', path => {
  const { adapter, resources } = fixture();
  const base = { ...value, text: 'ATELIER', color: '#ffffff' as const };
  const panel = { ...panelText, text: 'LUMA', color: '#ffffff' as const };
  const objects = path === 'composition' ? {
    baseObjects: [{ id: 'base', kind: 'text' as const, value: base }],
    panelObjects: [{ id: 'panel', kind: 'text' as const, value: panel }],
  } : {};
  const passes = resources.map(({ context }) => {
    const wood: string[] = [], glyphs: string[] = [];
    context.fillRect.mockImplementation(() => wood.push(context.fillStyle));
    context.fillText.mockImplementation(() => glyphs.push(context.fillStyle));
    return { wood, glyphs };
  });
  for (const showBaseArea of [false, true]) {
    for (const { wood, glyphs } of passes) { wood.length = 0; glyphs.length = 0; }
    expect(adapter.update({ value: base, panelText: panel, ...objects, showBaseArea, showPanelTextArea: !showBaseArea })).toBe(true);
    for (const { wood, glyphs } of passes) {
      expect(wood).toHaveLength(1);
      expect(glyphs).toEqual(['#302219']);
      expect((luminance(wood[0]) + .05) / (luminance(glyphs[0]) + .05)).toBeGreaterThanOrEqual(7);
    }
  }
});

it('renders independent ordered compositions on both prepared lamp faces without using legacy content', () => {
  const { adapter, resources } = fixture();
  const image = { id: 33, src: 'data:image/png;base64,a', canvas: document.createElement('canvas'), aspect: 3 };
  const picture: StudioObject = { id: 'panel-image', kind: 'image', value: { image, size: 85, x: 240, y: 278 }, fit: 'contain' };
  const panelObjects: StudioObject[] = [picture, { id: 'panel-text', kind: 'text', value: panelText }];
  const baseObjects: StudioObject[] = [{ id: 'base-text', kind: 'text', value: { ...value, text: 'Base only' } }];
  expect(adapter.update({ value, panelText: { ...panelText, text: 'Legacy' }, baseObjects, panelObjects })).toBe(true);
  expect(resources[0].context.fillText).toHaveBeenCalledExactlyOnceWith('Base only', value.x, value.y);
  expect(resources[1].context.fillText).toHaveBeenCalledExactlyOnceWith('HOME', panelText.x, panelText.y);
  const box = imageLayout(picture.value, picture.fit, studioArea('lamp-panel'));
  expect(resources[1].context.drawImage).toHaveBeenCalledWith(image.canvas, box.x - box.width / 2, box.y - box.height / 2, box.width, box.height);
  expect(resources[1].context.drawImage.mock.invocationCallOrder[0]).toBeLessThan(resources[1].context.fillText.mock.invocationCallOrder[0]);
  const { container } = render(<LampZoneSketch value={value} panelText={{ ...panelText, text: 'Legacy' }} draft={emptyCreativeDraft()} zone="panel" label="Composition" baseObjects={baseObjects} panelObjects={panelObjects} />);
  expect(container.textContent).toContain('Base only'); expect(container.textContent).toContain('HOME'); expect(container.textContent).not.toContain('Legacy');
  expect([...container.querySelectorAll('[data-studio-object]')].map(node => node.getAttribute('data-studio-object'))).toEqual(['base-text', 'panel-image', 'panel-text']);
  const shownImage = container.querySelector('[data-studio-object="panel-image"] image')!;
  expect(Number(shownImage.getAttribute('width'))).toBe(box.width);
  expect(Number(shownImage.getAttribute('height'))).toBe(box.height);
  expect(shownImage.closest('[data-lamp-cut-text]')).toHaveAttribute('transform', 'translate(135 30.25) scale(.875) translate(-120 8)');
});

it('clears canonical lamp compositions even with stale scalar content and rejects unsupported panel objects', () => {
  const { adapter, viewer, resources } = fixture();
  expect(adapter.update({ value, panelText, baseObjects: [{ id: 'base', kind: 'text', value }], panelObjects: [{ id: 'panel', kind: 'text', value: panelText }] })).toBe(true);
  expect(adapter.update({ value, panelText, baseObjects: [], panelObjects: [] })).toBe(true);
  for (const material of viewer.model.materials.slice(1)) expect(material.pbrMetallicRoughness.baseColorTexture.setTexture).toHaveBeenLastCalledWith(null);
  expect(adapter.update({ value, baseObjects: [], panelObjects: [{ id: 'invalid', kind: 'text', value: { ...panelText, font: 'sans' } }] })).toBe(false);
  expect(resources[1].texture.source.update).toHaveBeenCalledTimes(1);
});

it('maps panel lettering to the reserved lower band independently of base coordinates', () => {
  expect(lampPanelTextPoint(240,278)).toEqual({ x:120,y:286 });
  expect(lampPanelTextPoint(248,280)).toEqual({ x:128,y:288 });
  expect(lampPanelTextMapping).toMatchObject({ left:37,top:268,bandWidth:166,bandHeight:40 });
});
it.each(['left', 'right'] as const)('maps the full %s base endpoint identically onto its 2D face and 3D texture', side => {
  const x = lampBaseLetteringArea[side], y = lampBaseLetteringArea.top;
  const point = lampBasePoint(x,y), { adapter,context } = fixture();
  expect(point.x).toBeCloseTo(side === 'left' ? 0 : lampBaseMapping.width); expect(point.y).toBeCloseTo(0);
  expect(lampBasePoint(x,lampBaseLetteringArea.bottom).y).toBeCloseTo(lampBaseMapping.height);
  adapter.update({ value: { ...value,x,y } });
  expect(context.translate).toHaveBeenLastCalledWith(point.x,point.y);
  const { container } = render(<LampZoneSketch value={{ ...value,x,y }} draft={emptyCreativeDraft()} zone="base" label="Lamp" />);
  const text = container.querySelector('tspan')!;
  expect(Number(text.getAttribute('x'))).toBeCloseTo(point.x);
  expect(Number(text.getAttribute('y'))).toBeCloseTo(point.y);
  expect(container.querySelector('[data-lamp-base-decoration]')).toHaveAttribute('transform',`translate(${lampBaseFace.x} ${lampBaseFace.y}) scale(${lampBaseFace.width/lampBaseMapping.width} ${lampBaseFace.height/lampBaseMapping.height})`);
  expect(container.querySelector('[data-lamp-active-zone]')).toHaveAttribute('width',String(lampBaseFace.width));
});
it('renders selected base and fixed cut-band guides without text and clears exact materials afterwards', () => {
  const { adapter,viewer,resources } = fixture(), emptyValue = { ...value,text:'' };
  expect(adapter.update({ value:emptyValue,showBaseArea:true,showPanelTextArea:true })).toBe(true);
  expect(resources[0].context.strokeRect).toHaveBeenCalledWith(4,4,1528,512);
  expect(resources[1].context.strokeRect).toHaveBeenCalledWith(37.75,268.75,164.5,38.5);
  expect(resources[1].context.fillText).not.toHaveBeenCalled();
  expect(resources[1].texture.sampler.setScale).toHaveBeenCalledWith({ u:1 / 3840,v:1 / 5120 });
  expect(adapter.update({ value:emptyValue })).toBe(true);
  for (const material of viewer.model.materials.slice(1)) expect(material.pbrMetallicRoughness.baseColorTexture.setTexture).toHaveBeenLastCalledWith(null);
  adapter.destroy();
  for (const { canvas } of resources) expect([canvas.width,canvas.height]).toEqual([1,1]);
});
it('toggles guides without losing independent text and never renders unsupported stencil text through a guide', () => {
  const { adapter,resources } = fixture();
  expect(adapter.update({ value,panelText,showBaseArea:true,showPanelTextArea:true })).toBe(true);
  expect(adapter.update({ value,panelText })).toBe(true);
  expect(resources[0].texture.source.update).toHaveBeenCalledTimes(2);
  expect(resources[1].texture.source.update).toHaveBeenCalledTimes(2);
  expect(resources[0].context.fillText).toHaveBeenLastCalledWith('Luma',0,0);
  expect(resources[1].context.fillText).toHaveBeenLastCalledWith('HOME',120,286);
  resources[1].context.fillText.mockClear();
  expect(adapter.update({ value,panelText:{ ...panelText,font:'sans' },showPanelTextArea:true })).toBe(false);
  expect(resources[1].context.fillText).not.toHaveBeenCalled();
});
it('renders base engraving and panel cut-text together on independent canvases without changing selection', () => {
  const { adapter,viewer,resources } = fixture();
  expect(adapter.update({ value,panelText })).toBe(true);
  const [base,panel] = resources;
  expect(viewer.createCanvasTexture).toHaveBeenCalledTimes(2);
  expect(base.context.fillText).toHaveBeenLastCalledWith('Luma',0,0);
  expect([panel.canvas.width,panel.canvas.height]).toEqual([960,1280]);
  expect(panel.context.rect).toHaveBeenCalledWith(37,268,166,40);
  expect(panel.context.clip).toHaveBeenCalledOnce();
  expect(panel.context.fillText).toHaveBeenCalledExactlyOnceWith('HOME',120,286);
  expect(panel.context.font).toContain('Stencil');
  expect(panel.texture.sampler.setScale).toHaveBeenCalledWith({ u:1 / 3840,v:1 / 5120 });
  expect(panel.texture.sampler.setOffset).toHaveBeenCalledWith({ u:.5,v:0 });
  expect(viewer.model.materials[2].pbrMetallicRoughness.baseColorTexture.setTexture.mock.invocationCallOrder[0]).toBeLessThan(panel.texture.sampler.setScale.mock.invocationCallOrder[0]);
  for (const material of viewer.model.materials) expect(material.setEmissiveFactor).not.toHaveBeenCalled();
  adapter.update({ value,panelText: { ...panelText,text:'LIGHT' } });
  expect(base.texture.source.update).toHaveBeenCalledTimes(1);
  expect(panel.texture.source.update).toHaveBeenCalledTimes(2);
  expect(viewer.createCanvasTexture).toHaveBeenCalledTimes(2);
});
it('clears either decoration without clearing the other and restores both exact materials on destroy', () => {
  const { adapter,viewer,resources } = fixture();
  adapter.update({ value,panelText });
  adapter.update({ value,panelText: { ...panelText,text:'' } });
  expect(viewer.model.materials[2].pbrMetallicRoughness.baseColorTexture.setTexture).toHaveBeenLastCalledWith(null);
  expect(resources[0].texture.source.update).toHaveBeenCalledTimes(1);
  adapter.update({ value: { ...value,text:'' },panelText });
  expect(viewer.model.materials[1].pbrMetallicRoughness.baseColorTexture.setTexture).toHaveBeenLastCalledWith(null);
  expect(viewer.model.materials[2].pbrMetallicRoughness.baseColorTexture.setTexture).toHaveBeenLastCalledWith(resources[1].texture);
  expect(resources[1].texture.source.update).toHaveBeenCalledTimes(2);
  adapter.destroy(); adapter.destroy();
  for (const { canvas } of resources) expect([canvas.width,canvas.height]).toEqual([1,1]);
  for (const material of viewer.model.materials.slice(1)) {
    expect(material.pbrMetallicRoughness.baseColorTexture.setTexture).toHaveBeenLastCalledWith(null);
    expect(material.pbrMetallicRoughness.setBaseColorFactor).toHaveBeenLastCalledWith([.7454,.4969,.2307,1]);
  }
});
it('restores both owned surfaces when panel painting fails and never republishes a failed canvas', () => {
  const { adapter,viewer,resources } = fixture();
  resources[1].texture.sampler.setScale.mockImplementation(() => { throw new Error('fixture'); });
  expect(adapter.update({ value,panelText })).toBe(false);
  expect(adapter.update({ value,panelText })).toBe(false);
  for (const { canvas } of resources) expect([canvas.width,canvas.height]).toEqual([1,1]);
  for (const material of viewer.model.materials.slice(1)) expect(material.pbrMetallicRoughness.baseColorTexture.setTexture).toHaveBeenLastCalledWith(null);
  expect(adapter.update()).toBe(true);
});
it.each([{ font:'sans' as const,text:'HOME' }, { font:'stencil' as const,text:'Дом' }])('rejects unsupported cut text and restores a previously valid panel (%j)', candidate => {
  const { adapter,viewer,resources }=fixture();
  expect(adapter.update({ value,panelText })).toBe(true);
  expect(adapter.update({ value,panelText:{ ...panelText,...candidate } })).toBe(false);
  expect(viewer.model.materials[2].pbrMetallicRoughness.baseColorTexture.setTexture).toHaveBeenLastCalledWith(null);
  expect(resources[1].texture.source.update).toHaveBeenCalledTimes(1);
  expect(resources[0].texture.source.update).toHaveBeenCalledTimes(1);
  expect(adapter.update({ value,panelText })).toBe(true);
  expect(resources[1].texture.source.update).toHaveBeenCalledTimes(2);
});
