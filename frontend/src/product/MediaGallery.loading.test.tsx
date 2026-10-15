import { useLayoutEffect } from 'react';
import { createPortal } from 'react-dom';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { productCopy } from './copy';

type PreviewProps = { target: HTMLElement | null; source?: string; onPhase?: (phase: 'ready') => void };
function ImportedPreview({ target, source, onPhase }: PreviewProps) {
  useLayoutEffect(() => { onPhase?.('ready'); }, [onPhase]);
  return target ? createPortal(<p data-testid="imported-preview">{source}</p>, target) : null;
}
afterEach(() => { vi.doUnmock('./ModelPreview'); vi.restoreAllMocks(); vi.useRealTimers(); });

it.each(['fast', 'stalled'] as const)('bounds a %s outer model import without a minimum delay, blocked exit or late viewer revival', async mode => {
  vi.resetModules();
  let finish!: (module: { default: typeof ImportedPreview }) => void;
  const module = new Promise<{ default: typeof ImportedPreview }>(resolve => { finish = resolve; });
  vi.doMock('./ModelPreview', () => module);
  const { default: MediaGallery } = await import('./MediaGallery');
  vi.useFakeTimers(); vi.setSystemTime(new Date('2026-10-15T00:00:00Z'));
  const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
  const t = productCopy.en;
  const view = (variant: 'cobalt' | 'coral') => <><a href="/products/loop">Product navigation</a><button type="button">Cart action</button><MediaGallery variant={variant} favorite={false} toggleFavorite={() => {}} t={t} /></>;
  const { rerender } = render(view('cobalt'));
  const readyPhotos = () => { for (const image of document.querySelectorAll('img')) fireEvent.load(image); };
  readyPhotos(); fireEvent.click(screen.getByRole('button', { name: t.enlarge }));
  fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: t.model }));
  expect(screen.getByText(t.loadingModel)).toBeVisible();
  expect(within(screen.getByRole('dialog')).getByRole('button', { name: t.close })).toBeEnabled();
  if (mode === 'fast') {
    const started = Date.now();
    await act(async () => { finish({ default: ImportedPreview }); await vi.dynamicImportSettled(); });
    expect(Date.now()).toBe(started);
    expect(screen.getByTestId('imported-preview')).toHaveTextContent('/product/loop-shaped-cobalt.glb');
    expect(screen.queryByText(t.loadingModel)).not.toBeInTheDocument();
    expect(screen.queryByText(t.modelFailed)).not.toBeInTheDocument();
    expect(consoleError).not.toHaveBeenCalled();
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: t.close }));
    expect(screen.getByRole('button', { name: t.enlarge })).toBeEnabled();
  } else {
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: t.close }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    rerender(view('coral')); readyPhotos();
    await act(async () => { await vi.advanceTimersByTimeAsync(29_999); });
    expect(screen.getByText(t.loadingModel)).toBeVisible();
    expect(screen.queryByText(t.modelFailed)).not.toBeInTheDocument();
    await act(async () => { await vi.advanceTimersByTimeAsync(1); });
    expect(screen.getByText(t.modelFailed)).toBeVisible();
    expect(screen.queryByText(t.loadingModel)).not.toBeInTheDocument();
    expect(document.querySelector('.product-spinner')).toBeNull();
    expect(consoleError.mock.calls.flat().some(value => String(value).includes('MODEL_PREVIEW_IMPORT_TIMEOUT'))).toBe(true);
    await act(async () => { finish({ default: ImportedPreview }); await vi.dynamicImportSettled(); });
    expect(screen.queryByTestId('imported-preview')).not.toBeInTheDocument();
    expect(screen.getByText(t.modelFailed)).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: t.backToPhotos })); readyPhotos();
    expect(screen.getByRole('img', { name: t.photoAlt })).toHaveAttribute('src', '/product/loop-coral-01.webp');
    fireEvent.click(screen.getByRole('button', { name: t.model }));
    expect(screen.getByText(t.modelFailed)).toBeVisible();
    expect(screen.queryByTestId('imported-preview')).not.toBeInTheDocument();
  }
  expect(screen.getByRole('link', { name: 'Product navigation' })).toHaveAttribute('href', '/products/loop');
  expect(screen.getByRole('button', { name: 'Cart action' })).toBeEnabled();
  expect(vi.getTimerCount()).toBe(0);
});
