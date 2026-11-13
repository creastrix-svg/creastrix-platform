import { defaultLettering, normalizeLettering } from './personalization';
import type { Lettering, LetteringTarget } from './personalization';
import { emptyImagePlacement } from './studioImage';
import type { ImagePlacement, StudioImage } from './studioImage';
import { lampBaseLetteringArea, lampPanelTextMapping, loopLetteringArea } from './studioGeometry';
import type { StudioArea } from './studioGeometry';

export type ImageFit = 'free' | 'contain' | 'cover';
export type StudioObject =
  | Readonly<{ id: string; kind: 'text'; value: Lettering }>
  | Readonly<{ id: string; kind: 'image'; value: ImagePlacement; fit: ImageFit }>;
export type CompositionDocument = Readonly<{ objects: readonly StudioObject[]; selectedId: string | null }>;
export type CompositionHistory = Readonly<{
  past: readonly CompositionDocument[]; present: CompositionDocument;
  future: readonly CompositionDocument[]; group: string | null;
}>;
export type CompositionSession = Readonly<{
  histories: Partial<Record<LetteringTarget, CompositionHistory>>;
  applied: Partial<Record<LetteringTarget, CompositionDocument>>;
  nextId: number;
}>;
export type CompositionAction =
  | { type: 'restore'; state: CompositionSession }
  | { type: 'openPurchased'; variant:LetteringTarget; objects:readonly StudioObject[] }
  | { type: 'openLibraryDraft'; variant:LetteringTarget; objects:readonly StudioObject[] }
  | { type: 'add'; variant: LetteringTarget; kind: 'text' }
  | { type: 'add'; variant: LetteringTarget; kind: 'image'; image: StudioImage }
  | { type: 'replaceImage'; variant: LetteringTarget; id: string; image: StudioImage }
  | { type: 'update'; variant: LetteringTarget; id: string; object: StudioObject; group?: string }
  | { type: 'select'; variant: LetteringTarget; id: string | null }
  | { type: 'delete'; variant: LetteringTarget; id: string }
  | { type: 'reorder'; variant: LetteringTarget; id: string; direction: -1 | 1 }
  | { type: 'reset'; variant: LetteringTarget; id?: string }
  | { type: 'apply'; variant: LetteringTarget; objects?: readonly StudioObject[] }
  | { type: 'undo'; variant: LetteringTarget }
  | { type: 'redo'; variant: LetteringTarget }
  | { type: 'endGroup'; variant: LetteringTarget }
  | { type: 'remove'; variant: LetteringTarget };

export const MAX_STUDIO_OBJECTS = 8;
const HISTORY_LIMIT = 40;
const clamp = (value: number, min: number, max: number, fallback: number) => Number.isFinite(value) ? Math.min(max, Math.max(min, value)) : fallback;

/** These bounds describe prepared visual surfaces, not manufacturing geometry. */
export function studioArea(target: LetteringTarget): StudioArea {
  if (target === 'lamp') return lampBaseLetteringArea;
  if (target !== 'lamp-panel') return loopLetteringArea;
  const m = lampPanelTextMapping;
  return {
    left: m.left - m.centerX + m.editorX,
    right: m.left + m.bandWidth - m.centerX + m.editorX,
    top: m.top - m.centerY + m.editorY,
    bottom: m.top + m.bandHeight - m.centerY + m.editorY,
  };
}

/** Free scaling preserves the historical editor-pixel width. Contain/cover
 * instead fit the complete area; cover requires clipping to this same area.
 * All renderers consume these exact bounds, without stretching source pixels. */
export function imageLayout(value: ImagePlacement, fit: ImageFit, area: StudioArea) {
  const validArea = Object.values(area).every(Number.isFinite) && area.right > area.left && area.bottom > area.top;
  const bounds = validArea ? area : loopLetteringArea;
  const width = bounds.right - bounds.left, height = bounds.bottom - bounds.top;
  const centerX = bounds.left + width / 2, centerY = bounds.top + height / 2;
  const candidate = value.image?.aspect ?? 1;
  const aspect = Number.isFinite(candidate) && candidate >= 1 / 8192 && candidate <= 8192 ? candidate : 1;
  let w: number, h: number;
  if (fit === 'contain' || fit === 'cover') {
    w = fit === 'cover' ? Math.max(width, height * aspect) : Math.min(width, height * aspect);
    h = w / aspect;
  } else {
    w = Math.min(clamp(value.size, 20, 150, 85), width, height * aspect);
    h = w / aspect;
  }
  return {
    width: w, height: h,
    x: fit === 'free' ? clamp(value.x, bounds.left + w / 2, bounds.right - w / 2, centerX) : centerX,
    y: fit === 'free' ? clamp(value.y, bounds.top + h / 2, bounds.bottom - h / 2, centerY) : centerY,
  };
}

export const emptyComposition = (): CompositionSession => ({ histories: {}, applied: {}, nextId: 1 });
const emptyDocument = (): CompositionDocument => ({ objects: [], selectedId: null });
export function compositionHistory(state: CompositionSession, variant: LetteringTarget): CompositionHistory {
  return state.histories[variant] ?? { past: [], present: emptyDocument(), future: [], group: null };
}

/** IDs are session-local and monotonic across undo/deletion. They are not saved
 * in URLs, storage, Redux or network payloads. Decoded images remain immutable
 * shared assets; snapshots never copy or serialize their canvas contents. */
export function initialStudioComposition(): CompositionSession {
  let state = emptyComposition();
  const targets: LetteringTarget[] = ['cobalt', 'coral', 'chalk', 'cobalt-chalk', 'lamp', 'lamp-panel'];
  for (const variant of targets) {
    state = compositionReducer(state, { type: 'add', variant, kind: 'text' });
    state = { ...state, histories: { ...state.histories, [variant]: { ...compositionHistory(state, variant), past: [] } } };
  }
  return state;
}

/** Selection is transient UI state and never makes an applied design dirty. */
export function compositionKey(value: CompositionDocument | readonly StudioObject[]): string {
  const objects = 'objects' in value ? value.objects : value;
  return JSON.stringify(objects.map(object => object.kind === 'text' ? object
    : { ...object, value: { ...object.value, image: object.value.image?.id ?? null } }));
}
export function firstLettering(document: CompositionDocument | undefined, target: LetteringTarget): Lettering {
  const object = document?.objects.find(item => item.kind === 'text');
  return object?.kind === 'text' ? object.value : defaultLettering(target);
}
export function firstArtwork(document: CompositionDocument | undefined): ImagePlacement {
  const object = document?.objects.find(item => item.kind === 'image' && item.value.image);
  return object?.kind === 'image' ? object.value : emptyImagePlacement();
}

function normalizeObject(object: StudioObject, target: LetteringTarget): StudioObject | null {
  if (object.kind === 'text') {
    const value = normalizeLettering(object.value, target), area = studioArea(target);
    // The former one-text cut draft was centered. Visual compositions may move
    // within the reserved band; measured glyph/fit checks remain the UI gate.
    return { id: object.id, kind: 'text', value: target === 'lamp-panel' ? {
      ...value, x: clamp(object.value.x, area.left, area.right, 240), y: clamp(object.value.y, area.top, area.bottom, 278),
    } : value };
  }
  const image = object.value.image;
  if (!image || !Number.isFinite(image.aspect) || image.aspect < 1 / 8192 || image.aspect > 8192) return null;
  const fit = ['free', 'contain', 'cover'].includes(object.fit) ? object.fit : 'free';
  const area = studioArea(target), box = imageLayout(object.value, 'free', area);
  return { id: object.id, kind: 'image', fit, value: { image, size: clamp(object.value.size, 20, 150, 85), x: box.x, y: box.y } };
}

export function compositionReducer(state: CompositionSession, action: CompositionAction): CompositionSession {
  if (action.type === 'restore') return action.state;
  if(action.type==='openPurchased'||action.type==='openLibraryDraft'){
    const objects=action.objects.map((object,index)=>({...object,id:'studio-object-'+(state.nextId+index)}));
    const present={objects,selectedId:objects[0]?.id??null};
    return {...state,nextId:state.nextId+objects.length,applied:action.type==='openPurchased'?{...state.applied,[action.variant]:present}:state.applied,
      histories:{...state.histories,[action.variant]:{present,past:[],future:[],group:null}}};
  }
  const history = compositionHistory(state, action.variant), document = history.present;
  const replace = (next: CompositionHistory): CompositionSession => ({ ...state, histories: { ...state.histories, [action.variant]: next } });
  const change = (next: CompositionDocument, group: string | null = null): CompositionSession => {
    if (compositionKey(next) === compositionKey(document)) return state;
    return replace({ present: next, future: [], group,
      past: group && group === history.group ? history.past : [...history.past, document].slice(-HISTORY_LIMIT) });
  };
  if (action.type === 'endGroup') return history.group ? replace({ ...history, group: null }) : state;
  if (action.type === 'select') {
    if (action.id !== null && !document.objects.some(object => object.id === action.id)) return state;
    return action.id === document.selectedId && !history.group ? state
      : replace({ ...history, present: { ...document, selectedId: action.id }, group: null });
  }
  if (action.type === 'add') {
    if (document.objects.length >= MAX_STUDIO_OBJECTS || !Number.isSafeInteger(state.nextId) || state.nextId >= Number.MAX_SAFE_INTEGER) return state;
    const id = `studio-object-${state.nextId}`;
    const area = studioArea(action.variant);
    const object: StudioObject | null = action.kind === 'text'
      ? { id, kind: 'text', value: defaultLettering(action.variant) }
      : normalizeObject({ id, kind: 'image', fit: 'free', value: {
        ...emptyImagePlacement(), image: action.image, x: (area.left + area.right) / 2, y: (area.top + area.bottom) / 2,
      } }, action.variant);
    if (!object) return state;
    return { ...change({ objects: [...document.objects, object], selectedId: id }), nextId: state.nextId + 1 };
  }
  if (action.type === 'undo') {
    const present = history.past.at(-1);
    return present ? replace({ present, past: history.past.slice(0, -1), future: [document, ...history.future].slice(0, HISTORY_LIMIT), group: null }) : state;
  }
  if (action.type === 'redo') {
    const present = history.future[0];
    return present ? replace({ present, past: [...history.past, document].slice(-HISTORY_LIMIT), future: history.future.slice(1), group: null }) : state;
  }
  if (action.type === 'remove') {
    if (!state.applied[action.variant]) return state;
    const applied = { ...state.applied }; delete applied[action.variant];
    return { ...state, applied };
  }
  if (action.type === 'apply') {
    const source = action.objects ?? document.objects;
    if (source.length !== document.objects.length || source.some((object, index) => object.id !== document.objects[index].id || object.kind !== document.objects[index].kind)) return state;
    const objects = source.map(object => normalizeObject(object, action.variant));
    if (objects.some(object => !object)) return state;
    const applied: CompositionDocument = { objects: objects as StudioObject[], selectedId: document.selectedId };
    const next = change(applied);
    return { ...next, histories: { ...next.histories, [action.variant]: { ...compositionHistory(next, action.variant), group: null } },
      applied: { ...next.applied, [action.variant]: applied } };
  }
  const id = action.type === 'reset' ? action.id ?? document.selectedId : action.id;
  const index = document.objects.findIndex(object => object.id === id), current = document.objects[index];
  if (!current) return state;
  if (action.type === 'delete') {
    const objects = document.objects.filter(object => object.id !== id);
    return change({ objects, selectedId: document.selectedId === id ? objects[Math.min(index, objects.length - 1)]?.id ?? null : document.selectedId });
  }
  if (action.type === 'reorder') {
    const destination = index + action.direction;
    if (destination < 0 || destination >= document.objects.length) return state;
    const objects = [...document.objects]; [objects[index], objects[destination]] = [objects[destination], objects[index]];
    return change({ ...document, objects });
  }
  let candidate: StudioObject;
  if (action.type === 'replaceImage') {
    // Placement may have changed during decoding; replace only the bitmap on
    // its still-selected owner. Re-run bounds for the new aspect ratio.
    if (current.kind !== 'image' || document.selectedId !== id) return state;
    candidate = { ...current, value: { ...current.value, image: action.image } };
  } else if (action.type === 'reset') {
    candidate = current.kind === 'text' ? { ...current, value: defaultLettering(action.variant) }
      : { ...current, value: { ...emptyImagePlacement(), image: current.value.image }, fit: 'free' };
  } else {
    candidate = action.object;
  }
  if (candidate.id !== current.id || candidate.kind !== current.kind) return state;
  const object = normalizeObject(candidate, action.variant);
  if (!object) return state;
  const objects = [...document.objects]; objects[index] = object;
  return change({ ...document, objects }, action.type === 'update' && action.group ? `${id}:${action.group}` : null);
}
