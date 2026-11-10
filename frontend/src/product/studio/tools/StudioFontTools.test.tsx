import { act, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ComponentProps } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import StudioFontTools from './StudioFontTools';
import { StudioCreativeProvider } from '../session/studioCreativeSession';
import { creativeDraft, sameArtwork, useStudioCreative } from '../session/studioCreativeContext';
import { StudioFontError } from '../design/studioFonts';
import type { BuiltinStudioFont, LoadedStudioFont } from '../design/studioFonts';
import { studioExtensionCopy } from '../studioExtensionCopy';
import { personalizationCopy } from '../personalizationCopy';
import { studioZoneCopy } from '../studioZoneCopy';

const fontMocks = vi.hoisted(() => ({ loadBuiltin: vi.fn(), loadCustom: vi.fn(), dispose: vi.fn(), create: vi.fn() }));
vi.mock('../../useProductLocale', () => ({ useProductLocale: () => 'en' }));
vi.mock('../design/studioFonts', async importOriginal => ({
  ...await importOriginal<typeof import('../design/studioFonts')>(),
  createStudioFontLibrary: () => {
    fontMocks.create();
    return { loadBuiltin: fontMocks.loadBuiltin, loadCustom: fontMocks.loadCustom, dispose: fontMocks.dispose };
  },
}));

const e = studioExtensionCopy.en, p = personalizationCopy.en;
const loaded = (id: LoadedStudioFont['id'], label: string = id): LoadedStudioFont => ({ id, label, family: 'Fixture Family' });
const file = () => new File([new Uint8Array(32)], 'My Font.ttf', { type: 'font/ttf' });
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(complete => { resolve = complete; });
  return { promise, resolve };
}
const tree = (props: ComponentProps<typeof StudioFontTools>, key = props.target) => <StudioCreativeProvider><StudioFontTools key={key} {...props} /></StudioCreativeProvider>;
const upload = () => screen.getByLabelText(e.uploadFont, { selector: 'input' });
const fontButton = (label: string) => screen.getByRole('button', { name: 'Aa ' + label });

beforeEach(() => {
  vi.clearAllMocks();
  fontMocks.loadBuiltin.mockImplementation(async (id: BuiltinStudioFont) => loaded(id));
  fontMocks.loadCustom.mockResolvedValue(loaded('custom-901', 'My Font'));
});

describe('font picker loading and ownership', () => {
  it('offers only configured fonts and reports a failed selection even when uploads are disabled', async () => {
    const change = vi.fn();
    fontMocks.loadBuiltin.mockRejectedValue(new StudioFontError('invalid'));
    render(tree({ target: 'restricted', value: 'sans', onChange: change, allowedFonts: ['sans'], allowCustomFonts: false }));
    expect(fontButton(p.sans)).toBeEnabled();
    expect(screen.queryByRole('button', { name: 'Aa ' + p.serif })).toBeNull();
    expect(screen.queryByLabelText(e.uploadFont)).toBeNull();
    await userEvent.setup().click(fontButton(p.sans));
    expect(screen.getByRole('status')).toHaveTextContent(e.fontInvalid);
    expect(change).not.toHaveBeenCalled();
  });

  it('offers six labeled styles and bounded local-file upload without selecting a preload', async () => {
    const change = vi.fn();
    render(tree({ target: 'cobalt', value: 'sans', onChange: change }));
    for (const label of [p.sans, p.serif, p.mono, e.fontRounded, e.fontEditorial, e.fontHandwritten]) expect(fontButton(label)).toBeEnabled();
    expect(fontButton(p.sans)).toHaveAttribute('aria-pressed', 'true');
    expect(upload()).toHaveAttribute('accept', '.ttf,.otf,.woff2');
    expect(screen.getByRole('status')).toHaveTextContent(e.fontFormats);
    expect(screen.getByText(e.fontPrivacy + ' ' + e.fontRights)).toBeInTheDocument();
    await act(async () => { await Promise.resolve(); });
    expect(change).not.toHaveBeenCalled();
    expect(fontMocks.loadBuiltin.mock.calls.map(call => call[0])).toEqual(['rounded', 'editorial', 'handwritten']);
  });

  it('preloads only the bundled fonts explicitly configured for the selected zone', async () => {
    const change = vi.fn();
    const view = render(tree({ target: 'panel', value: 'stencil', onChange: change, allowedFonts: ['stencil'], allowCustomFonts: false }));
    await act(async () => { await Promise.resolve(); });
    expect(fontMocks.loadBuiltin.mock.calls.map(call => call[0])).toEqual(['stencil']);
    expect(screen.getAllByRole('button')).toHaveLength(1);
    expect(fontButton(studioZoneCopy.en.fontStencil)).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByLabelText(e.uploadFont)).toBeNull();
    expect(change).not.toHaveBeenCalled();
    view.rerender(tree({ target: 'base', value: 'sans', onChange: change, allowedFonts: ['sans', 'serif', 'mono'], allowCustomFonts: false }));
    await act(async () => { await Promise.resolve(); });
    expect(fontMocks.loadBuiltin.mock.calls.map(call => call[0])).toEqual(['stencil']);
    expect(screen.queryByRole('button', { name: 'Aa ' + studioZoneCopy.en.fontStencil })).toBeNull();
  });

  it('reports a failed prepared-font choice without suggesting an unavailable upload', async () => {
    const change = vi.fn(); fontMocks.loadBuiltin.mockRejectedValue(new StudioFontError('invalid'));
    render(tree({ target: 'panel', value: 'stencil', onChange: change, allowedFonts: ['stencil'], allowCustomFonts: false }));
    await userEvent.setup().click(fontButton(studioZoneCopy.en.fontStencil));
    expect(screen.getByRole('status')).toHaveTextContent(studioZoneCopy.en.cutFontFailed);
    expect(screen.getByRole('status')).not.toHaveTextContent(e.fontInvalid);
    expect(change).not.toHaveBeenCalled();
    expect(fontButton(studioZoneCopy.en.fontStencil)).toBeEnabled();
  });

  it('cancels a delayed choice when font permissions change without a product remount', async () => {
    const wait = deferred<LoadedStudioFont>(), original = vi.fn(), latest = vi.fn();
    const view = render(tree({ target: 'same', value: 'sans', onChange: original, allowedFonts: ['sans', 'serif'], allowCustomFonts: false }, 'same'));
    fontMocks.loadBuiltin.mockReturnValue(wait.promise);
    await userEvent.setup().click(fontButton(p.serif));
    const signal = fontMocks.loadBuiltin.mock.calls[0][1].signal as AbortSignal;
    view.rerender(tree({ target: 'same', value: 'sans', onChange: latest, allowedFonts: ['sans'], allowCustomFonts: false }, 'same'));
    expect(signal.aborted).toBe(true);
    expect(fontButton(p.sans)).toBeEnabled();
    await act(async () => { wait.resolve(loaded('serif')); await wait.promise; });
    expect(original).not.toHaveBeenCalled(); expect(latest).not.toHaveBeenCalled();
    expect(fontButton(p.sans)).toHaveAttribute('aria-pressed', 'true');
  });

  it('does not cancel an active choice merely because identical configured font arrays are recreated', async () => {
    const wait = deferred<LoadedStudioFont>(), change = vi.fn();
    const view = render(tree({ target: 'same', value: 'sans', onChange: change, allowedFonts: ['sans', 'serif'], allowCustomFonts: false }));
    fontMocks.loadBuiltin.mockReturnValue(wait.promise);
    await userEvent.setup().click(fontButton(p.serif));
    const signal = fontMocks.loadBuiltin.mock.calls[0][1].signal as AbortSignal;
    view.rerender(tree({ target: 'same', value: 'sans', onChange: change, allowedFonts: ['sans', 'serif'], allowCustomFonts: false }));
    expect(signal.aborted).toBe(false);
    await act(async () => { wait.resolve(loaded('serif')); await wait.promise; });
    expect(change).toHaveBeenCalledExactlyOnceWith('serif');
    expect(fontButton(p.serif)).toBeEnabled();
  });

  it('hides retained custom fonts and cancels pending upload when moving into a restricted policy', async () => {
    const change = vi.fn();
    const view = render(tree({ target: 'same', value: 'sans', onChange: change }));
    fireEvent.change(upload(), { target: { files: [file()] } });
    await act(async () => { await Promise.resolve(); });
    expect(fontButton('My Font')).toBeInTheDocument();
    const wait = deferred<LoadedStudioFont>(); fontMocks.loadCustom.mockReturnValue(wait.promise);
    fireEvent.change(upload(), { target: { files: [file()] } });
    const signal = fontMocks.loadCustom.mock.calls[1][1].signal as AbortSignal;
    change.mockClear();
    view.rerender(tree({ target: 'same', value: 'sans', onChange: change, allowedFonts: ['sans'], allowCustomFonts: false }));
    expect(signal.aborted).toBe(true);
    expect(screen.queryByRole('button', { name: 'Aa My Font' })).toBeNull();
    expect(screen.queryByLabelText(e.uploadFont)).toBeNull();
    await act(async () => { wait.resolve(loaded('custom-902', 'Late')); await wait.promise; });
    expect(change).not.toHaveBeenCalled();
    view.rerender(tree({ target: 'same', value: 'sans', onChange: change }));
    expect(fontButton('My Font')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Aa Late' })).toBeNull();
  });

  it('applies a delayed font to the newest callback instead of replacing later text edits', async () => {
    const wait = deferred<LoadedStudioFont>(), original = vi.fn(), latest = vi.fn();
    const view = render(tree({ target: 'cobalt', value: 'sans', onChange: original }));
    fontMocks.loadBuiltin.mockImplementation((id: BuiltinStudioFont) => id === 'serif' ? wait.promise : Promise.resolve(loaded(id)));
    await userEvent.setup().click(fontButton(p.serif));
    expect(fontButton(p.serif)).toBeDisabled(); expect(upload()).toBeDisabled();
    view.rerender(tree({ target: 'cobalt', value: 'sans', onChange: latest }));
    await act(async () => { wait.resolve(loaded('serif')); await wait.promise; });
    expect(original).not.toHaveBeenCalled(); expect(latest).toHaveBeenCalledExactlyOnceWith('serif');
    expect(fontButton(p.serif)).toBeEnabled(); expect(upload()).toBeEnabled();
  });

  it('cancels a pending selection when a newer font intent remounts the picker for the same product', async () => {
    const wait = deferred<LoadedStudioFont>(), original = vi.fn(), latest = vi.fn();
    const view = render(tree({ target: 'cobalt', value: 'sans', onChange: original }, 'cobalt:0'));
    fontMocks.loadBuiltin.mockImplementation((id: BuiltinStudioFont) => id === 'serif' ? wait.promise : Promise.resolve(loaded(id)));
    await userEvent.setup().click(fontButton(p.serif));
    const signal = fontMocks.loadBuiltin.mock.calls.find(call => call[0] === 'serif')![1].signal as AbortSignal;
    expect(signal.aborted).toBe(false); expect(upload()).toBeDisabled();

    // Preset/reset/history intent supersedes the pending selection. Ordinary
    // typing deliberately keeps the same key, covered by the preceding test.
    view.rerender(tree({ target: 'cobalt', value: 'mono', onChange: latest }, 'cobalt:1'));
    expect(signal.aborted).toBe(true);
    expect(fontButton(p.mono)).toHaveAttribute('aria-pressed', 'true'); expect(upload()).toBeEnabled();
    await act(async () => { wait.resolve(loaded('serif')); await wait.promise; });
    expect(original).not.toHaveBeenCalled(); expect(latest).not.toHaveBeenCalled();
    expect(fontButton(p.mono)).toHaveAttribute('aria-pressed', 'true');
    expect(fontButton(p.serif)).toHaveAttribute('aria-pressed', 'false');
  });

  it('registers a decoded custom font once, then reselects it without loading again', async () => {
    const change = vi.fn(), user = userEvent.setup(), wait = deferred<LoadedStudioFont>();
    fontMocks.loadCustom.mockReturnValue(wait.promise);
    render(tree({ target: 'cobalt', value: 'sans', onChange: change }));
    fireEvent.change(upload(), { target: { files: [file()] } });
    expect(change).not.toHaveBeenCalled(); expect(upload()).toBeDisabled();
    await act(async () => { wait.resolve(loaded('custom-901', 'My Font')); await wait.promise; });
    expect(change).toHaveBeenCalledExactlyOnceWith('custom-901');
    expect(screen.getByRole('status')).toHaveTextContent(e.fontReady);
    await user.click(fontButton('My Font'));
    expect(change).toHaveBeenLastCalledWith('custom-901');
    expect(fontMocks.loadCustom).toHaveBeenCalledTimes(1);
    expect(screen.getAllByRole('button', { name: 'Aa My Font' })).toHaveLength(1);
  });

  it.each([['tooLarge', e.fontLimit], ['invalid', e.fontInvalid], ['timeout', e.fontInvalid]] as const)('reports %s without replacing the selected font, then permits retry', async (code, message) => {
    const change = vi.fn(); fontMocks.loadCustom.mockRejectedValueOnce(new StudioFontError(code));
    render(tree({ target: 'cobalt', value: 'serif', onChange: change }));
    fireEvent.change(upload(), { target: { files: [file()] } });
    await act(async () => { await Promise.resolve(); });
    expect(screen.getByRole('status')).toHaveTextContent(message);
    expect(change).not.toHaveBeenCalled(); expect(fontButton(p.serif)).toHaveAttribute('aria-pressed', 'true');
    expect(upload()).toBeEnabled();
    fireEvent.change(upload(), { target: { files: [file()] } });
    await act(async () => { await Promise.resolve(); });
    expect(change).toHaveBeenCalledExactlyOnceWith('custom-901');
  });

  it('cancels an obsolete target and ignores a late custom decode without disabling the new target', async () => {
    const change = vi.fn(), nextChange = vi.fn(), wait = deferred<LoadedStudioFont>();
    fontMocks.loadCustom.mockReturnValue(wait.promise);
    const view = render(tree({ target: 'cobalt', value: 'sans', onChange: change }));
    fireEvent.change(upload(), { target: { files: [file()] } });
    const signal = fontMocks.loadCustom.mock.calls[0][1].signal as AbortSignal;
    expect(signal.aborted).toBe(false);
    view.rerender(tree({ target: 'lamp', value: 'serif', onChange: nextChange }));
    expect(signal.aborted).toBe(true); expect(upload()).toBeEnabled();
    await act(async () => { wait.resolve(loaded('custom-901', 'Obsolete')); await wait.promise; });
    expect(change).not.toHaveBeenCalled(); expect(nextChange).not.toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: 'Aa Obsolete' })).not.toBeInTheDocument();
  });

  it('aborts selected and preload work on unmount and disposes its library', async () => {
    const change = vi.fn(), wait = deferred<LoadedStudioFont>();
    fontMocks.loadCustom.mockReturnValue(wait.promise);
    const view = render(tree({ target: 'cobalt', value: 'sans', onChange: change }));
    const preloadSignal = fontMocks.loadBuiltin.mock.calls[0][1].signal as AbortSignal;
    fireEvent.change(upload(), { target: { files: [file()] } });
    const selectedSignal = fontMocks.loadCustom.mock.calls[0][1].signal as AbortSignal;
    view.unmount();
    expect(selectedSignal.aborted).toBe(true); expect(preloadSignal.aborted).toBe(true);
    expect(fontMocks.dispose).toHaveBeenCalledTimes(1);
    await act(async () => { wait.resolve(loaded('custom-901')); await wait.promise; });
    expect(change).not.toHaveBeenCalled();
  });

  it('stops custom upload at three retained fonts while leaving built-in selection available', async () => {
    let number = 0;
    fontMocks.loadCustom.mockImplementation(async () => loaded(`custom-${++number}`, 'Font ' + number));
    render(tree({ target: 'cobalt', value: 'sans', onChange: vi.fn() }));
    for (let index = 0; index < 3; index++) {
      fireEvent.change(upload(), { target: { files: [file()] } });
      await act(async () => { await Promise.resolve(); });
    }
    expect(upload()).toBeDisabled();
    expect(screen.getByRole('button', { name: e.uploadFont + ' 3/3' })).toBeDisabled();
    expect(fontButton(p.sans)).toBeEnabled(); expect(fontButton('Font 3')).toBeEnabled();
  });
});

describe('creative session boundaries', () => {
  function mountSession() {
    let session!: ReturnType<typeof useStudioCreative>;
    function Probe() { session = useStudioCreative(); return null; }
    return { ...render(<StudioCreativeProvider><Probe /></StudioCreativeProvider>), current: () => session };
  }

  it('isolates each product draft and applied snapshot while retaining an image reference', () => {
    const probe = mountSession();
    const initial = creativeDraft(probe.current(), 'cobalt');
    const image = { id: 1, src: 'data:image/png;base64,fixture', canvas: document.createElement('canvas'), aspect: 1 };
    const value = { ...initial, artwork: { ...initial.artwork, image, size: 70 }, prompt: 'Private idea' };
    act(() => { probe.current().edit('cobalt', value); probe.current().apply('cobalt', value); });
    const applied = probe.current().applied.cobalt!;
    expect(applied).not.toBe(value); expect(applied.artwork).not.toBe(value.artwork);
    expect(applied.artwork.image).toBe(image);
    act(() => probe.current().edit('cobalt', previous => ({ ...previous, artwork: { ...previous.artwork, size: 100 }, prompt: 'New idea' })));
    expect(probe.current().applied.cobalt).toBe(applied); expect(applied.artwork.size).toBe(70);
    expect(creativeDraft(probe.current(), 'lamp').prompt).toBe('');
    expect(creativeDraft(probe.current(), 'coral').artwork.image).toBeNull();
    expect(sameArtwork(creativeDraft(probe.current(), 'cobalt'), applied)).toBe(false);
  });

  it('removes only the applied snapshot and does not treat an unconnected prompt as a visual change', () => {
    const probe = mountSession(), value = creativeDraft(probe.current(), 'lamp');
    act(() => { probe.current().edit('lamp', value); probe.current().apply('lamp', value); });
    expect(sameArtwork({ ...value, prompt: 'A different private idea' }, probe.current().applied.lamp)).toBe(true);
    act(() => probe.current().remove('lamp'));
    expect(probe.current().applied.lamp).toBeUndefined();
    expect(creativeDraft(probe.current(), 'lamp')).toBe(value);
  });

  it('treats switching between a retained image and a pattern as a visual change', () => {
    const probe = mountSession(), value = creativeDraft(probe.current(), 'lamp');
    expect(value.content).toBe('pattern');
    expect(sameArtwork({ ...value, content: 'image' }, value)).toBe(false);
    expect(sameArtwork({ ...value, pattern: 'forest' }, value)).toBe(false);
    expect(sameArtwork({ ...value, bridges: false }, value)).toBe(false);
  });

  it('owns one font library per provider and forgets drafts and custom choices after exit', () => {
    const probe = mountSession();
    expect(probe.current().fonts()).toBe(probe.current().fonts());
    expect(fontMocks.create).toHaveBeenCalledTimes(1);
    act(() => {
      probe.current().edit('lamp', previous => ({ ...previous, prompt: 'Private idea' }));
      probe.current().rememberFont(loaded('custom-901', 'My Font'));
      probe.current().rememberFont(loaded('custom-901', 'My Font'));
    });
    expect(probe.current().customFonts).toHaveLength(1);
    probe.unmount(); expect(fontMocks.dispose).toHaveBeenCalledTimes(1);
    const fresh = mountSession();
    expect(fresh.current().customFonts).toEqual([]);
    expect(fresh.current().applied).toEqual({});
    expect(creativeDraft(fresh.current(), 'lamp').prompt).toBe('');
  });
});
