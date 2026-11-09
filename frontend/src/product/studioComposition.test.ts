import { describe, expect, it } from 'vitest';
import { compositionHistory, compositionKey, compositionReducer, emptyComposition, firstArtwork, firstLettering, imageLayout, initialStudioComposition, MAX_STUDIO_OBJECTS, studioArea } from './studioComposition';
import type { StudioObject } from './studioComposition';
import { defaultLettering } from './personalization';

const decoded = (id = 1, aspect = 2) => ({ id, src: 'data:image/png;base64,preview', canvas: {} as HTMLCanvasElement, aspect });

describe('zone composition', () => {
  it.each(['cobalt', 'lamp', 'lamp-panel'] as const)('adds only a decoded image as one undoable layer in %s', variant => {
    const before = initialStudioComposition(), initial = compositionHistory(before, variant);
    const image = decoded(), area = studioArea(variant);
    expect(compositionReducer(before, { type: 'add', variant, kind: 'image', image: decoded(2, NaN) })).toBe(before);
    const after = compositionReducer(before, { type: 'add', variant, kind: 'image', image });
    const history = compositionHistory(after, variant), added = history.present.objects[1];
    expect(history.past).toEqual([initial.present]);
    expect(history.present.objects[0]).toBe(initial.present.objects[0]);
    expect(added).toMatchObject({ id: history.present.selectedId, kind: 'image', fit: 'free', value: {
      image, size: 85, x: (area.left + area.right) / 2, y: (area.top + area.bottom) / 2,
    } });
    expect(compositionHistory(after, 'coral')).toBe(compositionHistory(before, 'coral'));
    const undone = compositionReducer(after, { type: 'undo', variant });
    expect(compositionHistory(undone, variant).present).toBe(initial.present);
    expect(compositionReducer(undone, { type: 'add', variant, kind: 'image', image: decoded(2, 0) })).toBe(undone);
    expect(compositionHistory(compositionReducer(undone, { type: 'redo', variant }), variant).present).toBe(history.present);
    const newAttempt = compositionReducer(undone, { type: 'add', variant, kind: 'image', image });
    expect(compositionHistory(newAttempt, variant).present.selectedId).not.toBe(added.id);
  });
  it('replaces only the selected bitmap, using live placement, fit and stacking order; undo restores the original', () => {
    const variant = 'lamp';
    let state = compositionReducer(initialStudioComposition(), { type: 'add', variant, kind: 'image', image: decoded() });
    const original = compositionHistory(state, variant).present.objects[1];
    if (original.kind !== 'image') throw new Error('Expected decoded image');
    const replacement = decoded(2);
    state = compositionReducer(state, { type: 'update', variant, id: original.id, object: {
      ...original, fit: 'cover', value: { ...original.value, x: 180, y: 260, size: 50 },
    } });
    state = compositionReducer(state, { type: 'reorder', variant, id: original.id, direction: -1 });
    const previous = compositionHistory(state, variant), current = previous.present.objects[0];
    const replace = { type: 'replaceImage', variant, id: original.id, image: replacement } as const;
    const replaced = compositionReducer(state, replace), next = compositionHistory(replaced, variant);
    expect(next.past).toHaveLength(previous.past.length + 1);
    expect(next.present.objects[0]).toEqual({ ...current, value: { ...current.value, image: replacement } });
    expect(next.present.objects[1]).toBe(previous.present.objects[1]);
    expect(compositionReducer(replaced, replace)).toBe(replaced);
    expect(compositionReducer(state, { ...replace, image: decoded(3, Infinity) })).toBe(state);
    expect(compositionHistory(compositionReducer(replaced, { type: 'undo', variant }), variant).present).toBe(previous.present);
    const unselected = compositionReducer(state, { type: 'select', variant, id: previous.present.objects[1].id });
    const deleted = compositionReducer(state, { type: 'delete', variant, id: original.id });
    expect(compositionReducer(unselected, replace)).toBe(unselected);
    expect(compositionReducer(deleted, replace)).toBe(deleted);
    expect(compositionReducer(state, { ...replace, variant: 'cobalt' })).toBe(state);
  });
  it('adds independent text objects and shares undo across text and image actions', () => {
    let state = emptyComposition();
    state = compositionReducer(state, { type: 'add', variant: 'cobalt', kind: 'text' });
    const first = compositionHistory(state, 'cobalt').present.objects[0];
    state = compositionReducer(state, { type: 'add', variant: 'cobalt', kind: 'image', image: decoded() });
    const image = compositionHistory(state, 'cobalt').present.objects[1];
    state = compositionReducer(state, { type: 'update', variant: 'cobalt', id: first.id, object: { ...first, kind: 'text', value: { ...defaultLettering('cobalt'), text: 'One' } } });
    expect(compositionHistory(state, 'cobalt').present.objects[1]).toEqual(image);
    state = compositionReducer(state, { type: 'undo', variant: 'cobalt' });
    expect(compositionHistory(state, 'cobalt').present.objects[0]).toEqual(first);
    state = compositionReducer(state, { type: 'undo', variant: 'cobalt' });
    expect(compositionHistory(state, 'cobalt').present.objects).toHaveLength(1);
    state = compositionReducer(state, { type: 'redo', variant: 'cobalt' });
    expect(compositionHistory(state, 'cobalt').present.objects[1]).toEqual(image);
  });
  it('groups only one object gesture, bounds history, and never reuses deleted ids', () => {
    let state = compositionReducer(emptyComposition(), { type: 'add', variant: 'lamp', kind: 'text' });
    const first = compositionHistory(state, 'lamp').present.objects[0];
    for (let i = 0; i < 60; i++) state = compositionReducer(state, { type: 'update', variant: 'lamp', id: first.id, object: { ...first, kind: 'text', value: { ...defaultLettering('lamp'), text: String(i) } }, group: 'typing' });
    expect(compositionHistory(state, 'lamp').past).toHaveLength(2);
    state = compositionReducer(state, { type: 'delete', variant: 'lamp', id: first.id });
    state = compositionReducer(state, { type: 'add', variant: 'lamp', kind: 'text' });
    expect(compositionHistory(state, 'lamp').present.objects[0].id).not.toBe(first.id);
  });
  it('keeps zones, ordered applied snapshots and drafts independent', () => {
    let state = compositionReducer(emptyComposition(), { type: 'add', variant: 'lamp', kind: 'text' });
    state = compositionReducer(state, { type: 'add', variant: 'lamp-panel', kind: 'image', image: decoded() });
    state = compositionReducer(state, { type: 'apply', variant: 'lamp' });
    const applied = state.applied.lamp;
    state = compositionReducer(state, { type: 'add', variant: 'lamp', kind: 'text' });
    expect(state.applied.lamp).toBe(applied);
    expect(compositionHistory(state, 'lamp-panel').present.objects).toHaveLength(1);
    const id = compositionHistory(state, 'lamp').present.objects[0].id;
    state = compositionReducer(state, { type: 'reorder', variant: 'lamp', id, direction: 1 });
    expect(compositionHistory(state, 'lamp').present.objects.at(-1)?.id).toBe(id);
  });
  it('rejects stale ownership and caps objects without losing existing ones', () => {
    let state = emptyComposition();
    for (let i = 0; i < MAX_STUDIO_OBJECTS; i++) state = compositionReducer(state, { type: 'add', variant: 'cobalt', kind: 'image', image: decoded(i + 1) });
    expect(compositionHistory(state, 'cobalt').present.objects).toHaveLength(MAX_STUDIO_OBJECTS);
    expect(compositionReducer(state, { type: 'add', variant: 'cobalt', kind: 'image', image: decoded(9) })).toBe(state);
    expect(compositionReducer(state, { type: 'delete', variant: 'lamp', id: 'missing' })).toBe(state);
  });
  it('seeds independent empty text objects without an undo entry', () => {
    const state = initialStudioComposition();
    const histories = Object.values(state.histories);
    expect(histories).toHaveLength(6);
    expect(new Set(histories.flatMap(history => history.present.objects.map(object => object.id))).size).toBe(6);
    expect(histories.every(history => !history.past.length && !history.future.length && history.present.objects.length === 1)).toBe(true);
    expect(firstLettering(compositionHistory(state, 'lamp-panel').present, 'lamp-panel').font).toBe('stencil');
  });
  it('does not merge gestures across objects or explicit selection boundaries', () => {
    let state = initialStudioComposition();
    const first = compositionHistory(state, 'cobalt').present.objects[0];
    state = compositionReducer(state, { type: 'add', variant: 'cobalt', kind: 'text' });
    const second = compositionHistory(state, 'cobalt').present.objects[1];
    const edit = (object: StudioObject, text: string) => {
      state = compositionReducer(state, { type: 'update', variant: 'cobalt', id: object.id, object: { id: object.id, kind: 'text', value: { ...defaultLettering('cobalt'), text } }, group: 'typing' });
    };
    edit(first, 'First'); edit(second, 'Second');
    expect(compositionHistory(state, 'cobalt').past).toHaveLength(3);
    state = compositionReducer(state, { type: 'select', variant: 'cobalt', id: second.id });
    edit(second, 'Another gesture');
    expect(compositionHistory(state, 'cobalt').past).toHaveLength(4);
    state = compositionReducer(state, { type: 'undo', variant: 'cobalt' });
    expect((compositionHistory(state, 'cobalt').present.objects[1].value as ReturnType<typeof defaultLettering>).text).toBe('Second');
  });
  it('bounds independent history entries and cuts redo only for content changes', () => {
    let state = initialStudioComposition();
    const object = compositionHistory(state, 'cobalt').present.objects[0];
    for (let i = 0; i < 75; i++) state = compositionReducer(state, { type: 'update', variant: 'cobalt', id: object.id, object: { id: object.id, kind: 'text', value: { ...defaultLettering('cobalt'), text: String(i) } } });
    expect(compositionHistory(state, 'cobalt').past).toHaveLength(40);
    state = compositionReducer(state, { type: 'undo', variant: 'cobalt' });
    const key = compositionKey(compositionHistory(state, 'cobalt').present);
    state = compositionReducer(state, { type: 'select', variant: 'cobalt', id: null });
    expect(compositionKey(compositionHistory(state, 'cobalt').present)).toBe(key);
    expect(compositionHistory(state, 'cobalt').future).toHaveLength(1);
    state = compositionReducer(state, { type: 'add', variant: 'cobalt', kind: 'image', image: decoded() });
    expect(compositionHistory(state, 'cobalt').future).toHaveLength(0);
  });
  it('applies all normalized objects together without accepting foreign ids or kinds', () => {
    let state = initialStudioComposition();
    state = compositionReducer(state, { type: 'add', variant: 'lamp', kind: 'image', image: decoded() });
    const document = compositionHistory(state, 'lamp').present;
    expect(compositionReducer(state, { type: 'apply', variant: 'lamp', objects: document.objects.slice().reverse() })).toBe(state);
    expect(compositionReducer(state, { type: 'apply', variant: 'lamp', objects: [{ ...document.objects[0], id: 'foreign' }, document.objects[1]] })).toBe(state);
    const objects = document.objects.map(object => object.kind === 'text' ? { ...object, value: { ...object.value, text: 'Placed', x: -Infinity } } : object);
    state = compositionReducer(state, { type: 'apply', variant: 'lamp', objects });
    expect(firstLettering(state.applied.lamp, 'lamp').text).toBe('Placed');
    expect(firstLettering(state.applied.lamp, 'lamp').x).toBe(240);
    expect(compositionKey(state.applied.lamp!)).toBe(compositionKey(compositionHistory(state, 'lamp').present));
    const snapshot = state.applied.lamp;
    state = compositionReducer(state, { type: 'reset', variant: 'lamp', id: document.objects[0].id });
    expect(firstLettering(state.applied.lamp, 'lamp').text).toBe('Placed');
    expect(state.applied.lamp).toBe(snapshot);
    state = compositionReducer(state, { type: 'remove', variant: 'lamp' });
    expect(state.applied.lamp).toBeUndefined();
    expect(compositionHistory(state, 'lamp').present.objects).toHaveLength(2);
  });
  it('rejects mismatched id/kind and deleted-target updates without resurrecting objects', () => {
    let state = initialStudioComposition();
    const object = compositionHistory(state, 'cobalt').present.objects[0];
    expect(compositionReducer(state, { type: 'update', variant: 'cobalt', id: object.id, object: { ...object, id: 'foreign' } })).toBe(state);
    expect(compositionReducer(state, { type: 'update', variant: 'cobalt', id: object.id, object: { id: object.id, kind: 'image', value: { image: null, x: 240, y: 278, size: 85 }, fit: 'free' } })).toBe(state);
    state = compositionReducer(state, { type: 'delete', variant: 'cobalt', id: object.id });
    expect(compositionReducer(state, { type: 'update', variant: 'cobalt', id: object.id, object })).toBe(state);
    expect(compositionHistory(state, 'cobalt').present.selectedId).toBeNull();
  });
  it('keeps image assets shared but placement snapshots isolated and rejects invalid aspect', () => {
    let state = compositionReducer(emptyComposition(), { type: 'add', variant: 'cobalt', kind: 'image', image: decoded() });
    const object = compositionHistory(state, 'cobalt').present.objects[0];
    const image = { id: 1, src: 'data:image/png;base64,preview', canvas: {} as HTMLCanvasElement, aspect: 2 };
    const value = { image, size: 85, x: 240, y: 278 };
    state = compositionReducer(state, { type: 'update', variant: 'cobalt', id: object.id, object: { ...object, kind: 'image', value, fit: 'contain' } });
    state = compositionReducer(state, { type: 'apply', variant: 'cobalt' });
    expect(firstArtwork(state.applied.cobalt).image).toBe(image);
    expect(firstArtwork(state.applied.cobalt)).not.toBe(value);
    value.x = 299;
    expect(firstArtwork(state.applied.cobalt).x).toBe(240);
    expect(compositionReducer(state, { type: 'update', variant: 'cobalt', id: object.id, object: { ...object, kind: 'image', value: { ...value, image: { ...image, aspect: NaN } }, fit: 'cover' } })).toBe(state);
    expect(compositionKey(state.applied.cobalt!)).not.toContain('data:image');
  });
  it('keeps free placement when switching to fit modes and resets only one selected object', () => {
    const image = decoded();
    let state = compositionReducer(initialStudioComposition(), { type: 'add', variant: 'lamp-panel', kind: 'image', image });
    const object = compositionHistory(state, 'lamp-panel').present.objects[1];
    if (object.kind !== 'image') throw new Error('Expected image fixture');
    state = compositionReducer(state, { type: 'update', variant: 'lamp-panel', id: object.id, object: { ...object, fit: 'cover', value: { ...object.value, size: 20, x: 180, y: 278 } } });
    const value = firstArtwork(compositionHistory(state, 'lamp-panel').present);
    expect(value.image).toBe(image);
    expect((compositionHistory(state, 'lamp-panel').present.objects[1].value).x).toBe(180);
    const first = compositionHistory(state, 'lamp-panel').present.objects[0];
    state = compositionReducer(state, { type: 'reset', variant: 'lamp-panel' });
    expect(compositionHistory(state, 'lamp-panel').present.objects[0]).toBe(first);
    expect(compositionHistory(state, 'lamp-panel').present.objects[1]).toMatchObject({ id: object.id, fit: 'free', value: { size: 85, image } });
  });
  it.each(['cobalt', 'lamp', 'lamp-panel'] as const)('fits contain/cover and free images to %s without distortion', target => {
    const area = studioArea(target), placement = { image: { id: 2, src: '', canvas: {} as HTMLCanvasElement, aspect: 4 }, size: 85, x: -Infinity, y: Infinity };
    for (const fit of ['free', 'contain', 'cover'] as const) {
      const box = imageLayout(placement, fit, area);
      expect(box.width / box.height).toBeCloseTo(4);
      expect(Object.values(box).every(Number.isFinite)).toBe(true);
      if (fit !== 'cover') {
        expect(box.x - box.width / 2).toBeGreaterThanOrEqual(area.left);
        expect(box.y + box.height / 2).toBeLessThanOrEqual(area.bottom);
      } else expect(box.width).toBeGreaterThanOrEqual(area.right - area.left);
    }
  });
});
