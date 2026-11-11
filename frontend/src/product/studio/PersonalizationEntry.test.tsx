import { render, screen } from '@testing-library/react';
import { Provider } from 'react-redux';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { createAppStore } from '../../store';
import PersonalizationEntry from './PersonalizationEntry';
import { getStudioProduct } from './studioProducts';
import type { StudioProduct } from './studioProducts';
import { studioZoneCopy } from './studioZoneCopy';
import { productCopy } from '../copy';

// Applied artwork has its own integration tests. Here the entry gate must not
// depend on a mounted editor session or let a disabled capability expose a link.
vi.mock('./PersonalizationView', () => ({
  AppliedPersonalization: ({ variant }: { variant: string }) => <div data-testid="applied-preview">{variant}</div>,
}));

function show(profile: StudioProduct) {
  return render(<Provider store={createAppStore('en')}><MemoryRouter>
    <PersonalizationEntry profile={profile} variant="cobalt-chalk" />
  </MemoryRouter></Provider>);
}

describe('configured personalization entry', () => {
  it('offers a configured ready-made product without changing its chosen colour', () => {
    const product = getStudioProduct('loop'); expect(product.readyMade).toBe(true);
    show(product);
    expect(screen.getByRole('link', { name: productCopy.en.personalButton }))
      .toHaveAttribute('href', '/products/loop/personalize?variant=cobalt-chalk');
    expect(screen.getByTestId('applied-preview')).toHaveTextContent('cobalt-chalk');
    expect(screen.queryByText(studioZoneCopy.en.unavailable)).toBeNull();
  });

  it('routes the lamp capability to the lamp without reusing Loop applied artwork', () => {
    show(getStudioProduct('lamp'));
    expect(screen.getByRole('link', { name: productCopy.en.personalButton }))
      .toHaveAttribute('href', '/products/loop/personalize?variant=cobalt-chalk&product=lamp');
    expect(screen.queryByTestId('applied-preview')).toBeNull();
  });

  it.each(['disabled', 'missing areas', 'empty tools'] as const)('omits the entry completely for %s', state => {
    const source = getStudioProduct('loop');
    const product = state === 'disabled' ? { ...source, enabled: false }
      : state === 'missing areas' ? { ...source, zones: [] }
      : { ...source, zones: [{ ...source.zones[0], tools: [] }] };
    show(product);
    expect(screen.queryByText(studioZoneCopy.en.unavailable)).toBeNull();
    expect(screen.queryByRole('region')).toBeNull();
    expect(screen.queryByRole('link')).toBeNull();
    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.queryByTestId('applied-preview')).toBeNull();
  });
});
