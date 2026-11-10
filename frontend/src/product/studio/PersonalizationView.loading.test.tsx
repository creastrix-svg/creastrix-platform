import { useReducer } from 'react';
import { createPortal } from 'react-dom';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { personalizationCopy } from './personalizationCopy';
import { productCopy } from '../copy';

const p = personalizationCopy.en, t = productCopy.en;
function ImportedPreview({ target, source }: { target: HTMLElement | null; source?: string }) {
  return target ? createPortal(<p data-testid="imported-studio-preview">{source}</p>, target) : null;
}

afterEach(() => {
  vi.doUnmock('../ModelPreview'); vi.doUnmock('@google/model-viewer');
  vi.restoreAllMocks(); vi.useRealTimers();
});

async function mountFreshStudio() {
  // Load the providers together after resetModules so this test exercises the
  // actual studio session, without a second copy of a provider's context.
  const [{ default: PersonalizationView }, { PersonalizationSession }, { compositionReducer, initialStudioComposition }, { Provider }, { MemoryRouter }, { createAppStore }, { StudioCreativeProvider }] = await Promise.all([
    import('./PersonalizationView'), import('./session/personalizationSession'), import('./design/studioComposition'), import('react-redux'), import('react-router'), import('../../store'), import('./session/studioCreativeSession'),
  ]);
  const store = createAppStore('en');
  function SessionView() {
    const [state, dispatch] = useReducer(compositionReducer, undefined, initialStudioComposition);
    return <PersonalizationSession.Provider value={{ state, dispatch }}><StudioCreativeProvider><PersonalizationView /></StudioCreativeProvider></PersonalizationSession.Provider>;
  }
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(() => ({
    font: '', measureText(this: { font: string }, text: string) { return { width: [...text].length * parseFloat(this.font) * .6 }; },
  }) as unknown as CanvasRenderingContext2D);
  return render(<Provider store={store}><MemoryRouter initialEntries={['/products/loop/personalize?variant=cobalt']}><SessionView /></MemoryRouter></Provider>);
}

it.each(['fast', 'stalled', 'rejected'] as const)('bounds a %s studio chunk without delaying success, blocking exit, or reviving a failed import', async mode => {
  vi.resetModules();
  let finish!: (module: { default: typeof ImportedPreview }) => void;
  let reject!: (error: Error) => void;
  const module = new Promise<{ default: typeof ImportedPreview }>((resolve, fail) => { finish = resolve; reject = fail; });
  vi.doMock('../ModelPreview', () => module);
  const view = await mountFreshStudio();
  vi.useFakeTimers(); vi.setSystemTime(new Date('2026-10-19T00:00:00Z'));
  const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
  fireEvent.change(screen.getByRole('textbox', { name: p.textLabel }), { target: { value: 'KEEP' } });
  fireEvent.click(screen.getByRole('button', { name: p.model }));
  expect(screen.getByText(t.loadingModel)).toBeVisible();
  expect(screen.getByRole('textbox', { name: p.textLabel })).toBeEnabled();
  fireEvent.click(screen.getByRole('button', { name: p.expand }));
  expect(within(screen.getByRole('dialog')).getByRole('button', { name: t.close })).toBeEnabled();
  expect(within(screen.getByRole('dialog')).getByText(t.loadingModel)).toBeVisible();
  fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: t.close }));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

  if (mode === 'fast') {
    const started = Date.now();
    await act(async () => { finish({ default: ImportedPreview }); await vi.dynamicImportSettled(); });
    expect(Date.now()).toBe(started);
    expect(screen.getByTestId('imported-studio-preview')).toHaveTextContent('/product/studio-loop-cobalt.glb');
    expect(screen.queryByText(t.loadingModel)).not.toBeInTheDocument();
    expect(screen.queryByText(p.modelUnavailable)).not.toBeInTheDocument();
    expect(consoleError).not.toHaveBeenCalled();
  } else {
    if (mode === 'stalled') {
      await act(async () => { await vi.advanceTimersByTimeAsync(29_999); });
      expect(screen.getByText(t.loadingModel)).toBeVisible();
      expect(screen.queryByText(p.modelUnavailable)).not.toBeInTheDocument();
      await act(async () => { await vi.advanceTimersByTimeAsync(1); });
    } else {
      const started = Date.now();
      await act(async () => { reject(new Error('MODEL_PREVIEW_CHUNK_UNAVAILABLE')); await vi.dynamicImportSettled(); });
      expect(Date.now()).toBe(started);
    }
    expect(screen.getByText(p.modelUnavailable)).toBeVisible();
    expect(screen.queryByText(t.loadingModel)).not.toBeInTheDocument();
    expect(document.querySelector('.product-spinner')).toBeNull();
    expect(consoleError).toHaveBeenCalled();
    if (mode === 'stalled') expect(consoleError.mock.calls.flat().some(value => String(value).includes('MODEL_PREVIEW_IMPORT_TIMEOUT'))).toBe(true);
    if (mode === 'stalled') {
      await act(async () => { finish({ default: ImportedPreview }); await vi.dynamicImportSettled(); });
      expect(screen.queryByTestId('imported-studio-preview')).not.toBeInTheDocument();
      expect(screen.getByText(p.modelUnavailable)).toBeVisible();
    }
    const failure = document.querySelector<HTMLElement>('.studio-model-error')!;
    fireEvent.click(within(failure).getByRole('button', { name: p.edit }));
    expect(document.querySelector('.studio-sketch')).toBeVisible();
    expect(document.querySelector('.studio-sketch')).toHaveTextContent('KEEP');
    expect(screen.getByRole('textbox', { name: p.textLabel })).toHaveValue('KEEP');
    expect(screen.queryByText(p.modelUnavailable)).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: p.model }));
    expect(screen.getByText(p.modelUnavailable)).toBeVisible();
    expect(screen.queryByTestId('imported-studio-preview')).not.toBeInTheDocument();
  }
  expect(screen.getByRole('link', { name: p.back })).toHaveAttribute('href', '/products/loop?variant=cobalt');
  expect(screen.getByRole('button', { name: p.apply })).toBeEnabled();
  view.unmount(); expect(vi.getTimerCount()).toBe(0);
});

// This fixture implements the existing viewer's public camera API. The actual
// ModelPreview and modelControls stay unmocked; no WebGL rendering is claimed.
class PublicStudioViewer extends HTMLElement {
  interpolationDecay = 50;
  orbit = { theta: Math.PI / 6, phi: 68 * Math.PI / 180, radius: 1 };
  target = { x: 0, y: .06, z: 0 };
  getCameraOrbit() { return { ...this.orbit }; }
  getCameraTarget() { return { ...this.target }; }
  jumpCameraToGoal() {
    const orbit = this.getAttribute('camera-orbit')?.split(' ').map(parseFloat);
    if (orbit) this.orbit = { theta: orbit[0], phi: orbit[1], radius: orbit[2] };
    const target = this.getAttribute('camera-target')?.split(' ').map(parseFloat);
    if (target) this.target = { x: target[0], y: target[1], z: target[2] };
  }
}
if (!customElements.get('model-viewer')) customElements.define('model-viewer', PublicStudioViewer);

it('preserves the real viewer owner and actual inline pose through the first expand/close host transition', async () => {
  vi.resetModules(); vi.doUnmock('../ModelPreview'); vi.doMock('@google/model-viewer', () => ({}));
  const view = await mountFreshStudio();
  vi.useFakeTimers();
  fireEvent.click(screen.getByRole('button', { name: p.model }));
  await act(async () => { await vi.dynamicImportSettled(); });
  const viewer = document.querySelector('model-viewer') as unknown as PublicStudioViewer;
  expect(viewer).toHaveAttribute('src', '/product/studio-loop-cobalt.glb');
  fireEvent(viewer, new Event('load'));
  expect(screen.queryByText(t.loadingModel)).not.toBeInTheDocument();
  const selected = { theta: .8, phi: .9, radius: 1.35 }, target = { x: .2, y: .1, z: .05 };
  viewer.orbit = { ...selected }; viewer.target = { ...target };
  fireEvent.click(screen.getByRole('button', { name: p.expand }));
  const dialog = screen.getByRole('dialog');
  expect(dialog.querySelector('model-viewer')).toBe(viewer);
  expect(document.querySelectorAll('model-viewer')).toHaveLength(1);
  expect(viewer.orbit.radius).toBeCloseTo(selected.radius * 1.2);
  fireEvent.click(within(dialog).getByRole('button', { name: t.rotateLeft }));
  act(() => vi.advanceTimersByTime(25));
  expect(viewer.getAttribute('orientation')).not.toBe('0rad 0rad 0rad');
  // A queued final gesture must not repaint after exit restores the inline pose.
  fireEvent.click(within(dialog).getByRole('button', { name: t.rotateRight }));
  fireEvent.click(within(dialog).getByRole('button', { name: t.close }));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(document.querySelector('model-viewer')).toBe(viewer);
  expect(viewer.orbit).toEqual(selected); expect(viewer.target).toEqual(target);
  expect(viewer).toHaveAttribute('orientation', '0rad 0rad 0rad');
  expect(viewer).toHaveAttribute('camera-controls');
  expect(viewer).toHaveAttribute('min-camera-orbit', 'auto 15deg 65%');
  expect(viewer).toHaveAttribute('max-camera-orbit', 'auto 170deg 160%');
  act(() => vi.advanceTimersByTime(25));
  expect(viewer.orbit).toEqual(selected); expect(viewer.target).toEqual(target);
  expect(vi.getTimerCount()).toBe(0);
  view.unmount(); expect(viewer.isConnected).toBe(false);
});
