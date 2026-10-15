import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import MediaImage from './MediaImage';

const props = { src: '/sample-a.webp', alt: 'Sample A', width: 1200, height: 802, loadingLabel: 'Loading image', errorLabel: 'No image', announce: true };
afterEach(() => { vi.restoreAllMocks(); vi.useRealTimers(); });

it('reports natural pending/ready, settles once, and never waits an artificial minimum duration', () => {
  vi.useFakeTimers(); const ready = vi.fn(), phase = vi.fn();
  render(<MediaImage {...props} onReady={ready} onPhase={phase} />);
  const image = screen.getByRole('img', { name: props.alt }), frame = image.closest('.product-photo-frame')!;
  expect(frame).toHaveAttribute('aria-busy', 'true'); expect(screen.getByRole('status')).toHaveTextContent(props.loadingLabel);
  fireEvent.load(image);
  expect(frame).toHaveAttribute('data-phase', 'ready'); expect(frame).toHaveAttribute('aria-busy', 'false');
  expect(screen.queryByRole('status')).not.toBeInTheDocument(); expect(ready).toHaveBeenCalledTimes(1);
  fireEvent.load(image); expect(ready).toHaveBeenCalledTimes(1); expect(vi.getTimerCount()).toBe(0);
  expect(phase.mock.calls.map(call => call[0])).toEqual(['loading', 'ready']);
});
it.each(['error', 'deadline'])('finishes %s without an endless spinner and cannot be revived by a late load', mode => {
  vi.useFakeTimers(); const failed = vi.fn(), ready = vi.fn();
  render(<MediaImage {...props} onFailure={failed} onReady={ready} />);
  const old = screen.getByRole('img', { name: props.alt });
  if (mode === 'error') fireEvent.error(old); else act(() => vi.advanceTimersByTime(30_000));
  expect(screen.getByRole('img', { name: props.errorLabel })).toBeVisible();
  expect(document.querySelector('.product-spinner')).toBeNull(); expect(failed).toHaveBeenCalledTimes(1);
  fireEvent.load(old); expect(ready).not.toHaveBeenCalled(); expect(vi.getTimerCount()).toBe(0);
});
it('recognizes a cached complete image without forcing a delay or spinner', () => {
  vi.useFakeTimers(); vi.spyOn(HTMLImageElement.prototype, 'complete', 'get').mockReturnValue(true);
  vi.spyOn(HTMLImageElement.prototype, 'naturalWidth', 'get').mockReturnValue(1200);
  const ready = vi.fn(); render(<MediaImage {...props} onReady={ready} />);
  expect(screen.getByRole('img', { name: props.alt }).closest('.product-photo-frame')).toHaveAttribute('data-phase', 'ready');
  expect(ready).toHaveBeenCalledTimes(1); expect(document.querySelector('.product-spinner')).toBeNull(); expect(vi.getTimerCount()).toBe(0);
});
it('gives a new source its own pending state and ignores stale completion/errors from the detached source', () => {
  vi.useFakeTimers(); const ready = vi.fn(), failed = vi.fn();
  const { rerender, unmount } = render(<MediaImage {...props} onReady={ready} onFailure={failed} />);
  const old = screen.getByRole('img', { name: props.alt });
  rerender(<MediaImage {...props} src="/sample-b.webp" alt="Sample B" onReady={ready} onFailure={failed} />);
  const current = screen.getByRole('img', { name: 'Sample B' });
  fireEvent.load(old); fireEvent.error(old);
  expect(current.closest('.product-photo-frame')).toHaveAttribute('data-phase', 'loading'); expect(ready).not.toHaveBeenCalled(); expect(failed).not.toHaveBeenCalled();
  fireEvent.load(current); expect(ready).toHaveBeenCalledTimes(1);
  unmount(); expect(vi.getTimerCount()).toBe(0);
});
