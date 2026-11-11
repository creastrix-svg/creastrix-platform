import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { Provider } from 'react-redux';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { createAppStore } from '../../../store';
import { cartAdded, cartLineRemoved, cartLineRestored, cartQuantityChanged, favoriteToggled } from '../../demoStore';
import PersistentStudio from './PersistentStudio';
import { usePersonalizationSession } from './personalizationSession';
import { compositionHistory, initialStudioComposition } from '../design/studioComposition';
import { defaultLettering } from '../design/personalization';
import { serializeStudio, studioStorage } from './studioStorage';
import { createStudioFontLibrary, studioFontFamily, studioFontResource } from '../design/studioFonts';
import { decodedDesign, saveDesign } from './designSnapshot';

const disk = vi.hoisted(() => ({ raw: null as string | null, writes: [] as string[] }));
vi.mock('./studioStorage', async original => ({
  ...await original<typeof import('./studioStorage')>(),
  // Only the IDB transport is controlled. Production serialization, hydration,
  // reducer, gate lifecycle and expected-value handling all remain unchanged.
  studioStorage: vi.fn(async (operation: 'read' | 'write', value?: string, expected?: string | null) => {
    const current = disk.raw;
    if (operation === 'write') {
      if (expected !== undefined && expected !== current) throw new Error('STUDIO_STORAGE_TRANSACTION');
      disk.raw = value!;
      disk.writes.push(value!);
    }
    return current;
  }),
}));
beforeEach(() => {
  vi.stubGlobal('indexedDB', {});
  // Happy DOM has no native FontFace; initial Luma stencil is a bundled font.
  Object.defineProperty(document, 'fonts', { configurable: true, value: { add: vi.fn(), delete: vi.fn() } });
  vi.stubGlobal('FontFace', class { async load() { return this; } });
  disk.raw = null; disk.writes = [];
});
afterEach(() => vi.unstubAllGlobals());

function DraftProbe() {
  const { state, dispatch } = usePersonalizationSession();
  const object = compositionHistory(state, 'coral').present.objects[0];
  return <><span>READY</span><span data-testid="draft" style={{ fontFamily: object.kind === 'text' ? studioFontFamily(object.value.font) : undefined }}>{object.kind === 'text' ? object.value.text : ''}</span>
    {['MY DRAFT', 'MY NEXT'].map(text => <button key={text} onClick={() => dispatch({ type: 'update', variant: 'coral', id: object.id, object: { id: object.id, kind: 'text', value: { ...defaultLettering('coral'), text } } })}>{text === 'MY DRAFT' ? 'Change draft' : 'Change again'}</button>)}</>;
}
function show(store = createAppStore('en')) {
  return render(<Provider store={store}><MemoryRouter initialEntries={['/preview/cart']}><PersistentStudio><DraftProbe /></PersistentStudio></MemoryRouter></Provider>);
}
const ready = () => screen.findByText('READY');
const saved = () => waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Saved in this browser'));
const failed = () => waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Not saved locally'));

// Pause only the next write. On release the ordinary mock transport still checks
// the expected bytes and commits the actual serialized record; rejection commits nothing.
function holdNextWrite() {
  const transport = vi.mocked(studioStorage), commit = transport.getMockImplementation()!;
  let release!: (success: boolean) => void;
  const barrier = new Promise<boolean>(resolve => { release = resolve; });
  transport.mockImplementationOnce(async (...args) => {
    if (args[0] !== 'write' || !await barrier) throw new Error('STUDIO_STORAGE_TRANSACTION');
    return commit(...args);
  });
  return (success: boolean) => act(async () => { release(success); });
}

it.each([true, false])('waits for the latest byte-identical A intent (commit succeeds: %s)', async success => {
  const store = createAppStore('en');
  const quantity = (next: number) => act(() => { store.dispatch(cartQuantityChanged({ variant: 'coral', quantity: next })); });
  store.dispatch(cartAdded({ variant: 'coral', quantity: 1 }));
  show(store); await ready(); await saved();
  const a = disk.raw!;
  expect(JSON.parse(a).lines).toEqual([{ variant: 'coral', quantity: 1 }]);

  const finishB = holdNextWrite();
  quantity(2);
  expect(screen.getByRole('status')).toHaveTextContent('Saving locally');
  const finishA = holdNextWrite();
  const callsWithBPending = vi.mocked(studioStorage).mock.calls.length;
  quantity(1);
  expect(studioStorage).toHaveBeenCalledTimes(callsWithBPending);
  expect.soft(screen.getByRole('status')).toHaveTextContent('Saving locally');
  await finishB(true);
  const b = disk.raw!;
  expect(JSON.parse(b).lines).toEqual([{ variant: 'coral', quantity: 2 }]);
  expect(store.getState().productDemo.cart).toEqual([{ variant: 'coral', quantity: 1 }]);
  expect(studioStorage).toHaveBeenLastCalledWith('write', a, b);
  expect.soft(screen.getByRole('status')).toHaveTextContent('Saving locally');

  await finishA(success);
  if (success) {
    await saved();
    expect(disk.raw).toBe(a);
  } else {
    await failed();
    expect(disk.raw).toBe(b);
    quantity(3); await saved();
    expect(studioStorage).toHaveBeenLastCalledWith('write', disk.raw, b);
    expect(JSON.parse(disk.raw!).lines).toEqual([{ variant: 'coral', quantity: 3 }]);
  }
});

it('preserves the acknowledged studio save without serializing or writing when only favourites change', async () => {
  const serialize = vi.spyOn(await import('./studioStorage'), 'serializeStudio');
  const store = createAppStore('en');
  show(store); await ready(); await saved();
  expect(serialize).toHaveBeenCalled();
  serialize.mockClear();
  const calls = vi.mocked(studioStorage).mock.calls.length;
  act(() => { store.dispatch(favoriteToggled('coral')); });
  expect(store.getState().productDemo.favorites).toContain('coral');
  expect(serialize).not.toHaveBeenCalled();
  expect(studioStorage).toHaveBeenCalledTimes(calls);
  expect(screen.getByRole('status')).toHaveTextContent('Saved in this browser');
});

it('SCART-001: leaving the storefront and returning retains our cart without overwriting another tab or claiming Saved', async () => {
  const store = createAppStore('en');
  let view = show(store);
  await screen.findByText('READY');
  act(() => { store.dispatch(cartAdded({ variant: 'coral', quantity: 1 })); });
  await waitFor(() => expect(JSON.parse(disk.raw!).lines).toEqual([{ variant: 'coral', quantity: 1 }]));
  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Saved in this browser'));

  // A second tab commits an ordinary quantity change after this tab's snapshot.
  const secondTab = JSON.parse(disk.raw!);
  secondTab.lines[0].quantity = 4;
  const committedByOtherTab = JSON.stringify(secondTab);
  disk.raw = committedByOtherTab;
  const count = disk.writes.length;

  // ProductExperience is absent on account routes; Redux remains mounted above it.
  view.unmount();
  view = show(store);
  await screen.findByText('READY');
  // These are the fixed-contract assertions, not the original probe's
  // bug-demonstrating Saved/write preconditions (retained in RED evidence).
  await failed();
  expect(store.getState().productDemo.cart).toEqual([{ variant: 'coral', quantity: 1 }]);
  expect(disk.writes).toHaveLength(count);
  // Desired contract: do not acknowledge/write the stale retained cart over B.
  expect(JSON.parse(disk.raw!).lines).toEqual([{ variant: 'coral', quantity: 4 }]);
  view.unmount();
});

it('reviewer control: same mounted gate refuses a stale update after another tab commits', async () => {
  const store = createAppStore('en');
  const view = render(<Provider store={store}><MemoryRouter initialEntries={['/preview/cart']}><PersistentStudio><span>READY</span></PersistentStudio></MemoryRouter></Provider>);
  await screen.findByText('READY');
  act(() => { store.dispatch(cartAdded({ variant: 'coral', quantity: 1 })); });
  await waitFor(() => expect(JSON.parse(disk.raw!).lines).toEqual([{ variant: 'coral', quantity: 1 }]));
  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Saved in this browser'));
  const secondTab = JSON.parse(disk.raw!); secondTab.lines[0].quantity = 4;
  disk.raw = JSON.stringify(secondTab);
  act(() => { store.dispatch(cartAdded({ variant: 'coral', quantity: 1 })); });
  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Not saved locally'));
  expect(store.getState().productDemo.cart).toEqual([{ variant: 'coral', quantity: 2 }]);
  expect(JSON.parse(disk.raw!).lines).toEqual([{ variant: 'coral', quantity: 4 }]);
  view.unmount();
  const returned = show(store); await ready(); await failed();
  expect(store.getState().productDemo.cart).toEqual([{ variant: 'coral', quantity: 2 }]);
  expect(JSON.parse(disk.raw!).lines).toEqual([{ variant: 'coral', quantity: 4 }]);
  returned.unmount();
});

it('keeps our draft and continued edits through repeated conflicting remounts, while a fresh store loads B', async () => {
  const store = createAppStore('en');
  let view = show(store); await ready();
  act(() => { store.dispatch(cartAdded({ variant: 'coral', quantity: 1 })); });
  fireEvent.click(screen.getByRole('button', { name: 'Change draft' })); await saved();
  const own = disk.raw!;
  const other = JSON.parse(own); other.lines[0].quantity = 4;
  other.drafts.coral.objects[0].value.text = 'THEIR DRAFT';
  const otherRaw = JSON.stringify(other); disk.raw = otherRaw;
  const count = disk.writes.length;
  for (let pass = 0; pass < 2; pass++) {
    view.unmount(); view = show(store); await ready(); await failed();
    expect(screen.getByTestId('draft')).toHaveTextContent(pass === 0 ? 'MY DRAFT' : 'MY NEXT');
    expect(store.getState().productDemo.cart[0].quantity).toBe(1 + pass);
    act(() => { store.dispatch(cartAdded({ variant: 'coral', quantity: 1 })); });
    fireEvent.click(screen.getByRole('button', { name: 'Change again' }));
    await failed();
    expect(disk.raw).toBe(otherRaw); expect(disk.writes).toHaveLength(count);
    expect(studioStorage).toHaveBeenLastCalledWith('write', expect.any(String), own);
  }
  view.unmount();
  const fresh = createAppStore('en'); view = show(fresh); await ready(); await saved();
  expect(fresh.getState().productDemo.cart[0].quantity).toBe(4);
  expect(screen.getByTestId('draft')).toHaveTextContent('THEIR DRAFT');
  view.unmount();
});

it('does not adopt B when our retained cart is empty after removing its last line', async () => {
  const store = createAppStore('en');
  let view = show(store); await ready();
  act(() => { store.dispatch(cartAdded({ variant: 'coral', quantity: 1 })); }); await saved();
  const other = JSON.parse(disk.raw!); other.lines[0].quantity = 4;
  act(() => { store.dispatch(cartLineRemoved('coral')); }); await saved();
  const empty = disk.raw; disk.raw = JSON.stringify(other); const count = disk.writes.length;
  view.unmount(); view = show(store); await ready(); await failed();
  expect(store.getState().productDemo.cart).toEqual([]);
  expect(store.getState().productDemo.cartRemoved.coral?.quantity).toBe(1);
  expect(studioStorage).toHaveBeenLastCalledWith('write', expect.any(String), empty);
  expect(disk.writes).toHaveLength(count); expect(JSON.parse(disk.raw!).lines[0].quantity).toBe(4);
  const removal = store.getState().productDemo.cartRemoved.coral!;
  act(() => { store.dispatch(cartLineRestored({ variant: 'coral', revision: removal.revision })); });
  await failed();
  expect(store.getState().productDemo.cart).toEqual([{ variant: 'coral', quantity: 1 }]);
  expect(store.getState().productDemo.cartRemoved.coral).toBeUndefined();
  expect(disk.writes).toHaveLength(count); expect(JSON.parse(disk.raw!).lines[0].quantity).toBe(4);
  view.unmount();
});

it('still saves after an ordinary remount when the stored baseline is unchanged', async () => {
  const store = createAppStore('en');
  let view = show(store); await ready();
  act(() => { store.dispatch(cartAdded({ variant: 'coral', quantity: 1 })); });
  fireEvent.click(screen.getByRole('button', { name: 'Change draft' })); await saved();
  for (let pass = 0; pass < 2; pass++) {
    const prior = disk.raw;
    view.unmount(); view = show(store); await ready(); await saved();
    expect(studioStorage).toHaveBeenLastCalledWith('write', expect.any(String), prior);
    expect(screen.getByTestId('draft')).toHaveTextContent('MY DRAFT');
    act(() => { store.dispatch(cartAdded({ variant: 'coral', quantity: 1 })); }); await saved();
    expect(JSON.parse(disk.raw!).lines[0].quantity).toBe(2 + pass);
  }
  view.unmount();
});

it('restores our exact custom font through a conflicting remount, without fallback or adopting B’s bytes', async () => {
  const fonts = createStudioFontLibrary();
  const font = await fonts.loadCustom(new File([readFileSync('public/fonts/AllertaStencil.ttf')], 'My font.ttf'));
  const doc = { selectedId: 'studio-object-1', objects: [{ id: 'studio-object-1', kind: 'text' as const, value: { ...defaultLettering('coral'), text: 'MY FONT', font: font.id } }] };
  disk.raw = serializeStudio({ ...initialStudioComposition(), histories: { coral: { present: doc, past: [], future: [], group: null } }, applied: { coral: doc } },
    { drafts: {}, applied: {}, customFonts: [font] }, { cart: [{ id: 'design-1', variant: 'coral', quantity: 1, design: saveDesign(doc) }], cartSerial: 2 });
  fonts.dispose();
  const store = createAppStore('en');
  let view = show(store); await ready(); await saved();
  const own = disk.raw!, other = JSON.parse(own);
  other.fonts[0].resource.data = readFileSync('public/fonts/Caveat.ttf').toString('base64');
  other.lines[0].quantity = 4;
  other.drafts.coral.objects[0].value.text = 'THEIR FONT';
  const otherRaw = JSON.stringify(other); disk.raw = otherRaw; const count = disk.writes.length;
  for (let pass = 0; pass < 2; pass++) {
    view.unmount(); view = show(store); await ready(); await failed();
    const design = store.getState().productDemo.cart[0].design!;
    const text = design.objects[0]; if (text.kind !== 'text') throw new Error('Expected fixture text');
    expect(text.value.text).toBe('MY FONT');
    expect(studioFontResource(text.value.font)).toBe(font.resource!.data);
    expect(decodedDesign(design)).toBeDefined();
    expect(screen.getByTestId('draft')).toHaveTextContent('MY FONT');
    expect(screen.getByTestId('draft')).toHaveStyle({ fontFamily: studioFontFamily(text.value.font) });
    expect(store.getState().productDemo.cart[0].quantity).toBe(1);
    expect(disk.raw).toBe(otherRaw); expect(disk.writes).toHaveLength(count);
    expect(studioStorage).toHaveBeenLastCalledWith('write', expect.any(String), own);
  }
  view.unmount();
});
