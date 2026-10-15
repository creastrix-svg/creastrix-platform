import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import MediaGallery from './MediaGallery';
import { productCopy } from './copy';

// Reject the real lazy import, before a renderer/GLB/WebGL instance can exist.
vi.mock('./ModelPreview', () => { throw new Error('TEST_REJECTED_LAZY_3D_IMPORT'); });
it('keeps the product/cart and an explicit photo return usable when the lazy 3D module rejects', async () => {
  const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
  const t = productCopy.en;
  const user = userEvent.setup();
  render(<><h1>Loop</h1><button type="button">{t.add}</button><MediaGallery variant="cobalt" favorite={false} toggleFavorite={() => {}} t={t} /></>);
  await user.click(screen.getByRole('button', { name: t.model }));
  expect(await screen.findByText(t.modelFailed)).toBeVisible();
  expect(screen.getByRole('heading', { level: 1, name: 'Loop' })).toBeVisible();
  expect(screen.getByRole('button', { name: t.add })).toBeEnabled();
  await user.click(screen.getByRole('button', { name: t.backToPhotos }));
  expect(screen.getByRole('img', { name: t.photoAlt })).toBeVisible();
  expect(screen.queryByText(t.modelFailed)).not.toBeInTheDocument();
  consoleError.mockRestore();
});
