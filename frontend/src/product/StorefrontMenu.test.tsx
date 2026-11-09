import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { MemoryRouter, useLocation } from 'react-router';
import { expect, it, vi } from 'vitest';
import { createAppStore } from '../store';
import { productLocaleSelected } from './demoStore';
import { storefrontLocales, type StorefrontLocale } from './locale';
import StorefrontNavigation from './StorefrontNavigation';

const names: Record<StorefrontLocale, string> = {
  de: 'Menü', en: 'Menu', ru: 'Меню', pl: 'Menu', es: 'Menú', fr: 'Menu', it: 'Menu',
};
function CurrentAddress() {
  const { pathname, search } = useLocation();
  return <output>{pathname + search}</output>;
}
function mount(locale: StorefrontLocale) {
  const store = createAppStore('en');
  store.dispatch(productLocaleSelected(locale));
  render(<Provider store={store}><MemoryRouter>
    <div data-storefront-content><StorefrontNavigation appearance="light" onAppearance={() => {}}/><CurrentAddress/></div>
    <div data-storefront-modal/>
  </MemoryRouter></Provider>);
  return userEvent.setup();
}

it.each(storefrontLocales)('names the trigger and its dialog Menu in %s and keeps keyboard dismissal usable', async locale => {
  const user = mount(locale), name = names[locale];
  const trigger = screen.getByRole('button', { name });
  expect(trigger).toHaveAttribute('aria-expanded', 'false');
  trigger.focus();
  await user.keyboard('{Enter}');
  const dialog = screen.getByRole('dialog', { name });
  expect(trigger).toHaveAttribute('aria-expanded', 'true');
  expect(dialog).toContainElement(document.activeElement as HTMLElement);
  expect(within(dialog).getByRole('navigation', { name })).toBeVisible();
  await user.keyboard('{Escape}');
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  expect(trigger).toHaveAttribute('aria-expanded', 'false');
  expect(trigger).toHaveFocus();
});

it('dismisses with either the close control or backdrop, then navigates to the selected section', async () => {
  const user = mount('ru'), trigger = screen.getByRole('button', { name: 'Меню' });
  for (const dismiss of ['close', 'backdrop'] as const) {
    await user.click(trigger);
    const dialog = screen.getByRole('dialog', { name: 'Меню' });
    await user.click(dismiss === 'close' ? within(dialog).getByRole('button', { name: 'Закрыть' }) : dialog.parentElement!);
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(trigger, dismiss).toHaveFocus();
  }
  await user.click(trigger);
  const gifts = within(screen.getByRole('dialog', { name: 'Меню' })).getByRole('link', { name: 'Подарки' });
  expect(gifts).toHaveAttribute('href', '/catalog?category=gifts');
  await user.click(gifts);
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(screen.getByRole('status').textContent).toBe('/catalog?category=gifts');
  await user.click(trigger);
  expect(within(screen.getByRole('dialog')).getByRole('link', { name: 'Подарки' })).toHaveAttribute('aria-current', 'page');
});

it.each([false, true])('keeps focus inside until the exit finishes unless reduced motion is requested (%s)', async reduced => {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: query === '(prefers-reduced-motion: reduce)' ? reduced : !reduced,
  }));
  try {
    const user = mount('ru'), trigger = screen.getByRole('button', { name: 'Меню' });
    await user.click(trigger);
    const panel = screen.getByRole('dialog');
    let finishPanel!: () => void, finishBackdrop!: () => void;
    const panelDone = new Promise<void>(resolve => { finishPanel = resolve; });
    const backdropDone = new Promise<void>(resolve => { finishBackdrop = resolve; });
    // happy-dom doesn't run CSS transitions. Expose the browser's per-element
    // completion signals without coupling this assertion to animation duration.
    Object.defineProperty(panel, 'getAnimations', { value: () => [{ finished: panelDone }] });
    Object.defineProperty(panel.parentElement!, 'getAnimations', { value: () => [{ finished: backdropDone }] });
    await user.keyboard('{Escape}');
    if (!reduced) {
      expect(panel).toContainElement(document.activeElement as HTMLElement);
      expect(document.body.style.overflow).toBe('hidden');
      await act(async () => { finishPanel(); });
      expect(panel).toBeInTheDocument();
      await act(async () => { finishBackdrop(); });
    }
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(document.body.style.overflow).not.toBe('hidden');
    expect(trigger).toHaveFocus();
  } finally {
    vi.unstubAllGlobals();
  }
});
