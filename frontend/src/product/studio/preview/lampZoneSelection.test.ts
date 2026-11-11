import type { ModelViewerElement } from '@google/model-viewer';
import { fireEvent } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { createLampZoneSelection } from './lampZoneSelection';

const cleanup: (() => void)[] = [];
function fixture() {
  const material = (name: string) => ({ name, emissiveFactor: [.01,.02,.03], setEmissiveFactor: vi.fn(), pbrMetallicRoughness: { setBaseColorFactor: vi.fn(), baseColorTexture: { texture: { id: 'engraving' }, setTexture: vi.fn() } } });
  const front = material('Studio lamp front panel'), edge = material('Studio lamp front cut edge'), base = material('Studio lamp base engraving');
  const fixed = material('Birch plywood face'), light = material('Warm inner diffuser');
  const viewer = document.createElement('div') as unknown as ModelViewerElement;
  const pick = vi.fn(() => front);
  Object.assign(viewer, { model: { materials: [front,edge,base,fixed,light] }, materialFromPoint: pick });
  const input = document.createElement('div'); document.body.append(viewer,input);
  vi.spyOn(input,'getBoundingClientRect').mockReturnValue(new DOMRect(20,30,400,300));
  const marker = document.createElement('span'); marker.hidden = true;
  const adapter = createLampZoneSelection(viewer,input,marker), onSelect = vi.fn();
  adapter.update({ selected:'base',onSelect });
  cleanup.push(() => { adapter.destroy(); viewer.remove(); input.remove(); });
  return { viewer,input,marker,adapter,onSelect,pick,front,edge,base,fixed,light };
}
function pointer(input: HTMLElement, type: string, overrides: Partial<PointerEventInit> = {}) {
  const event = new PointerEvent(type,{ pointerId:1,pointerType:'mouse',clientX:100,clientY:130,button:0,isPrimary:true,bubbles:true,composed:true,cancelable:true,...overrides });
  fireEvent(input,event); return event;
}
function tap(input: HTMLElement, props: Partial<PointerEventInit> = {}) {
  pointer(input,'pointerdown',props); pointer(input,'pointerup',props);
}
afterEach(() => { cleanup.splice(0).forEach(fn => fn()); vi.useRealTimers(); });

it.each(['mouse','touch','pen'])('selects prepared front/base through public viewport picking for a %s tap', pointerType => {
  const { input,onSelect,pick,base,adapter } = fixture();
  tap(input,{pointerType}); expect(pick).toHaveBeenCalledWith(100,130); expect(onSelect).toHaveBeenLastCalledWith('panel');
  adapter.update({selected:'panel',onSelect}); pick.mockReturnValue(base);
  tap(input,{pointerType}); expect(onSelect).toHaveBeenLastCalledWith('base'); expect(onSelect).toHaveBeenCalledTimes(2);
});
it('selects a prepared front cut edge but not fixed wood, diffuser, a missing hit or an unowned lookalike', () => {
  const { input,onSelect,pick,edge,fixed,light,front } = fixture();
  pick.mockReturnValue(edge); tap(input); expect(onSelect).toHaveBeenCalledOnce();
  for (const candidate of [fixed,light,null,{...front}]) { pick.mockReturnValue(candidate!); tap(input); }
  expect(onSelect).toHaveBeenCalledOnce();
});
it('does not reselect the same zone or leak a stale callback after updating', () => {
  const { input,adapter,onSelect } = fixture(), next = vi.fn();
  adapter.update({selected:'panel',onSelect:next}); tap(input); expect(next).not.toHaveBeenCalled();
  adapter.update({selected:'base',onSelect:next}); tap(input); expect(next).toHaveBeenCalledWith('panel'); expect(onSelect).not.toHaveBeenCalled();
});
it('never writes emission, engraving textures or base colors for selection, hover or cleanup', () => {
  vi.useFakeTimers();
  const { adapter,input,marker,pick,front,edge,base,fixed,light,onSelect } = fixture();
  expect(marker.hidden).toBe(true);
  pointer(input,'pointermove'); frame(); expect(marker.dataset.hoverZone).toBe('panel');
  adapter.update({selected:'panel',onSelect});
  pick.mockReturnValue(base); pointer(input,'pointermove'); frame();
  expect(marker.dataset.hoverZone).toBe('base'); adapter.destroy(); expect(marker.hidden).toBe(true);
  for (const material of [front,edge,base,fixed,light]) {
    expect(material.setEmissiveFactor).not.toHaveBeenCalled();
    expect(material.pbrMetallicRoughness.setBaseColorFactor).not.toHaveBeenCalled();
    expect(material.pbrMetallicRoughness.baseColorTexture.setTexture).not.toHaveBeenCalled();
    expect(material.pbrMetallicRoughness.baseColorTexture.texture).toEqual({id:'engraving'});
  }
});
it('never converts a drag into a tap, even when it returns to its initial point', () => {
  const { input,pick } = fixture();
  pointer(input,'pointerdown'); pointer(input,'pointermove',{clientX:120}); pointer(input,'pointermove'); pointer(input,'pointerup');
  pointer(input,'pointerdown'); pointer(input,'pointerup',{clientX:110});
  expect(pick).not.toHaveBeenCalled();
});
it('allows small pointer jitter but rejects release outside the input or without a matching down', () => {
  const { input,pick } = fixture();
  pointer(input,'pointerdown'); pointer(input,'pointermove',{clientX:102}); pointer(input,'pointerup',{clientX:103}); expect(pick).toHaveBeenCalledOnce();
  pointer(input,'pointerup'); tap(input,{clientX:19});
  pointer(input,'pointerdown'); pointer(document.body,'pointerup');
  expect(pick).toHaveBeenCalledOnce();
});
it('ignores right-click and non-primary touch starts', () => {
  const { input,pick } = fixture(); tap(input,{button:2}); tap(input,{pointerType:'touch',isPrimary:false}); expect(pick).not.toHaveBeenCalled();
});
it('ignores a long press', () => {
  const { input,pick } = fixture();
  const down = new PointerEvent('pointerdown',{pointerId:1,clientX:100,clientY:130,button:0,isPrimary:true,bubbles:true});
  const up = new PointerEvent('pointerup',{pointerId:1,clientX:100,clientY:130,button:0,isPrimary:true,bubbles:true});
  Object.defineProperty(down,'timeStamp',{value:100}); Object.defineProperty(up,'timeStamp',{value:1000});
  fireEvent(input,down); fireEvent(input,up); expect(pick).not.toHaveBeenCalled();
});
it('cancels pinch/twist even after the second finger leaves, including a finger outside the viewer', () => {
  const { input,pick } = fixture();
  for (const secondTarget of [input,document.body]) {
    pointer(input,'pointerdown',{pointerType:'touch'});
    pointer(secondTarget,'pointerdown',{pointerType:'touch',pointerId:2,isPrimary:false});
    pointer(secondTarget,'pointerup',{pointerType:'touch',pointerId:2,isPrimary:false});
    pointer(input,'pointerup',{pointerType:'touch'});
  }
  expect(pick).not.toHaveBeenCalled(); tap(input); expect(pick).toHaveBeenCalledOnce();
});
it.each(['pointercancel','lostpointercapture'])('ignores a later pointerup after %s', type => {
  const { input,pick } = fixture(); pointer(input,'pointerdown'); pointer(input,type); pointer(input,'pointerup'); expect(pick).not.toHaveBeenCalled();
});
it.each(['wheel','scroll','blur','keydown'])('cancels the tap when %s occurs', type => {
  const { input,pick } = fixture(); pointer(input,'pointerdown'); fireEvent(window,new Event(type)); pointer(input,'pointerup'); expect(pick).not.toHaveBeenCalled();
});
it('ignores embedded tool buttons and never prevents camera gestures', () => {
  const { input,pick } = fixture(), button = document.createElement('button'); input.append(button); tap(button); expect(pick).not.toHaveBeenCalled();
  const down = pointer(input,'pointerdown'), move = pointer(input,'pointermove',{clientX:150}), up = pointer(input,'pointerup',{clientX:150});
  expect([down,move,up].some(event => event.defaultPrevented)).toBe(false);
});
it('observes a valid tap before expanded controls stop propagation and release capture', () => {
  const { input,pick } = fixture();
  for (const type of ['pointerdown','pointermove','pointerup']) input.addEventListener(type,event => { event.stopPropagation(); event.preventDefault(); if (type==='pointerup') pointer(input,'lostpointercapture'); });
  tap(input); expect(pick).toHaveBeenCalledOnce();
});
it('treats a picking error as unavailable selection and removes listeners/highlights on cleanup', () => {
  const { input,pick,adapter,onSelect,front } = fixture();
  pick.mockImplementationOnce(() => { throw new Error('renderer unavailable'); }); tap(input); expect(onSelect).not.toHaveBeenCalled();
  pointer(input,'pointerdown'); adapter.destroy(); pointer(input,'pointerup'); tap(input); expect(pick).toHaveBeenCalledOnce();
  const calls = front.setEmissiveFactor.mock.calls.length; adapter.update({selected:'panel',onSelect}); adapter.destroy(); expect(front.setEmissiveFactor).toHaveBeenCalledTimes(calls);
});

function frame() { vi.advanceTimersByTime(20); }
it('coalesces mouse discovery to one ray per frame and never changes selection/tools', () => {
  vi.useFakeTimers();
  const { input,marker,edge,onSelect,pick } = fixture();
  for (let x=100;x<130;x++) pointer(input,'pointermove',{clientX:x});
  expect(pick).not.toHaveBeenCalled(); frame(); expect(pick).toHaveBeenCalledOnce(); expect(pick).toHaveBeenLastCalledWith(129,130);
  expect(marker.hidden).toBe(false); expect(marker.dataset.hoverZone).toBe('panel');
  expect(marker.style.left).toBe('109px'); expect(marker.style.top).toBe('100px');
  expect(input.style.cursor).toBe('pointer'); expect(onSelect).not.toHaveBeenCalled();
  pick.mockReturnValue(edge); pointer(input,'pointermove'); frame();
  expect(pick).toHaveBeenCalledTimes(2); expect(marker.dataset.hoverZone).toBe('panel');
  expect(marker.style.left).toBe('80px');
});
it('tracks the hover hit independently even when selection changes and removes only hover on leave', () => {
  vi.useFakeTimers();
  const { input,marker,base,pick,adapter,onSelect } = fixture();
  pointer(input,'pointermove'); frame();
  adapter.update({selected:'panel',onSelect}); expect(marker.dataset.hoverZone).toBe('panel');
  adapter.update({selected:'panel',onSelect}); expect(marker.hidden).toBe(false);
  pick.mockReturnValue(base); pointer(input,'pointermove'); frame();
  expect(marker.dataset.hoverZone).toBe('base'); expect(onSelect).not.toHaveBeenCalled();
  pointer(input,'pointerleave'); expect(marker.hidden).toBe(true); expect(input.style.cursor).toBe('');
  tap(input); expect(onSelect).toHaveBeenCalledWith('base');
});
it.each(['touch','pen'])('never leaves sticky %s hover or queues a ray', pointerType => {
  vi.useFakeTimers();
  const { input,pick,marker } = fixture(); pointer(input,'pointermove'); frame();
  pointer(input,'pointermove',{pointerType}); frame();
  expect(pick).toHaveBeenCalledOnce(); expect(marker.hidden).toBe(true); expect(input.style.cursor).toBe('');
});
it('clears queued/current hover on pointer down and never hovers during drag or pinch', () => {
  vi.useFakeTimers();
  const { input,pick,marker } = fixture();
  pointer(input,'pointermove'); pointer(input,'pointerdown'); frame(); expect(pick).not.toHaveBeenCalled();
  pointer(input,'pointermove',{clientX:140,buttons:1}); frame(); expect(pick).not.toHaveBeenCalled();
  pointer(input,'pointerdown',{pointerId:2,isPrimary:false,pointerType:'touch'});
  pointer(input,'pointermove',{clientX:100}); frame(); expect(pick).not.toHaveBeenCalled();
  pointer(input,'pointerup'); pointer(input,'pointerup',{pointerId:2,isPrimary:false,pointerType:'touch'});
  pointer(input,'pointermove'); frame(); expect(pick).toHaveBeenCalledOnce();
  expect(marker.dataset.hoverZone).toBe('panel');
  pointer(input,'pointermove',{buttons:1}); expect(input.style.cursor).toBe(''); expect(marker.hidden).toBe(true);
});
it.each(['pointerleave','pointercancel','lostpointercapture','wheel','scroll','resize','blur','keydown','visibilitychange'])('clears queued/current discovery on %s while preserving selection', type => {
  vi.useFakeTimers();
  const { input,pick,marker } = fixture();
  const clearEvent = () => {
    if (type==='visibilitychange') fireEvent(document,new Event(type));
    else if (type.startsWith('pointer') || type==='lostpointercapture') pointer(input,type);
    else fireEvent(window,new Event(type));
  };
  pointer(input,'pointermove'); clearEvent(); frame(); expect(pick).not.toHaveBeenCalled();
  pointer(input,'pointermove'); frame(); expect(pick).toHaveBeenCalledOnce();
  clearEvent(); expect(input.style.cursor).toBe('');
  expect(marker.hidden).toBe(true); expect(marker.dataset.hoverZone).toBeUndefined();
});
it('clears discovery during camera movement and waits for a fresh mouse movement after settling', () => {
  vi.useFakeTimers();
  const { viewer,input,pick,marker } = fixture(); pointer(input,'pointermove'); frame();
  fireEvent(viewer,new CustomEvent('camera-change')); expect(input.style.cursor).toBe('');
  pointer(input,'pointermove'); frame(); expect(pick).toHaveBeenCalledOnce();
  vi.advanceTimersByTime(120); expect(pick).toHaveBeenCalledOnce();
  pointer(input,'pointermove'); frame(); expect(pick).toHaveBeenCalledTimes(2); expect(marker.dataset.hoverZone).toBe('panel');
});
it('discards queued rays when the model or containing host is hidden', () => {
  vi.useFakeTimers();
  const { viewer,input,pick } = fixture();
  pointer(input,'pointermove'); fireEvent(viewer,new CustomEvent('model-visibility',{detail:{visible:false}})); frame();
  pointer(input,'pointermove'); frame(); expect(pick).not.toHaveBeenCalled();
  fireEvent(viewer,new CustomEvent('model-visibility',{detail:{visible:true}})); input.hidden=true;
  pointer(input,'pointermove'); frame(); expect(pick).not.toHaveBeenCalled();
  input.hidden=false; pointer(input,'pointermove'); frame(); expect(pick).toHaveBeenCalledOnce();
});
it('restores cursor value and priority on the actual event hit element without querying shadow internals', () => {
  vi.useFakeTimers();
  const { input,pick,fixed } = fixture(), first=document.createElement('div'), second=document.createElement('div');
  input.append(first,second); first.style.setProperty('cursor','crosshair','important'); second.style.cursor='grab';
  pointer(first,'pointermove'); frame(); expect(first.style.cursor).toBe('pointer'); expect(input.style.cursor).toBe('');
  pointer(second,'pointermove'); frame(); expect(first.style.getPropertyValue('cursor')).toBe('crosshair'); expect(first.style.getPropertyPriority('cursor')).toBe('important'); expect(second.style.cursor).toBe('pointer');
  pick.mockReturnValue(fixed); pointer(second,'pointermove'); frame(); expect(second.style.cursor).toBe('grab');
});
it('does not overwrite a camera-owned grabbing cursor during cleanup', () => {
  vi.useFakeTimers();
  const { input,adapter } = fixture(); input.style.cursor='grab'; pointer(input,'pointermove'); frame(); expect(input.style.cursor).toBe('pointer');
  input.style.cursor='grabbing'; adapter.destroy(); expect(input.style.cursor).toBe('grabbing');
});
it('clears cursor and hover marker on fixed wood, no hit or a picking error', () => {
  vi.useFakeTimers();
  const { input,pick,marker,front,fixed } = fixture();
  for (const missing of ['fixed','none','error']) {
    pick.mockReturnValue(front); pointer(input,'pointermove'); frame(); expect(input.style.cursor).toBe('pointer');
    if (missing==='error') pick.mockImplementationOnce(() => { throw new Error('unavailable'); }); else pick.mockReturnValue(missing==='fixed' ? fixed : null!);
    pointer(input,'pointermove'); frame(); expect(input.style.cursor).toBe(''); expect(marker.hidden).toBe(true);
  }
});
it('never raycasts hovered control buttons and cancels all pending frames on destroy', () => {
  vi.useFakeTimers();
  const { input,pick,adapter } = fixture(), button=document.createElement('button'); input.append(button);
  pointer(button,'pointermove'); frame(); expect(pick).not.toHaveBeenCalled();
  pointer(input,'pointermove'); adapter.destroy(); frame(); expect(pick).not.toHaveBeenCalled(); expect(vi.getTimerCount()).toBe(0);
  pointer(input,'pointermove'); frame(); expect(pick).not.toHaveBeenCalled();
});
