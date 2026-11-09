import { fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import StudioIdeaTools from './StudioIdeaTools';
import { studioExtensionCopy } from './studioExtensionCopy';

vi.mock('./useProductLocale', () => ({ useProductLocale: () => 'en' }));
const e = studioExtensionCopy.en;
afterEach(() => { vi.unstubAllGlobals(); });

function Draft({ observePrompt = vi.fn() }: { observePrompt?: (value: string) => void }) {
  const [prompt, setPrompt] = useState('');
  return <StudioIdeaTools prompt={prompt} onPrompt={value => { setPrompt(value); observePrompt(value); }} />;
}

describe('local AI brief without a connected generator', () => {
  it('explains availability accessibly without fake generated results or demo actions', () => {
    render(<Draft />);
    const input = screen.getByRole('textbox', { name: e.aiPrompt });
    expect(input).toHaveAttribute('placeholder', e.aiPlaceholder);
    expect(input).toHaveAccessibleDescription(e.aiUnavailable);
    expect(screen.getByText(e.aiUnavailable)).toBeVisible();
    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.queryByText(e.examplePatternsNote)).toBeNull();
  });

  it('bounds the description locally without a provider call', () => {
    const observePrompt = vi.fn(), fetch = vi.fn();
    vi.stubGlobal('fetch', fetch);
    render(<Draft observePrompt={observePrompt} />);
    const input = screen.getByRole('textbox', { name: e.aiPrompt });
    expect(input).toHaveAttribute('maxlength', '600');
    fireEvent.change(input, { target: { value: 'A'.repeat(601) } });
    expect(input).toHaveValue('A'.repeat(600));
    expect(screen.getByText('600/600')).toBeInTheDocument();
    expect(observePrompt).toHaveBeenLastCalledWith('A'.repeat(600));
    expect(fetch).not.toHaveBeenCalled();
  });

  it('keeps an existing brief editable without sending it or offering prepared results', () => {
    const onPrompt = vi.fn(), fetch = vi.fn();
    vi.stubGlobal('fetch', fetch);
    const { rerender } = render(<StudioIdeaTools prompt="A fox beneath the moon" onPrompt={onPrompt} />);
    const input = screen.getByRole('textbox', { name: e.aiPrompt });
    expect(input).toHaveValue('A fox beneath the moon');
    fireEvent.change(input, { target: { value: 'A quiet forest' } });
    expect(onPrompt).toHaveBeenLastCalledWith('A quiet forest');
    rerender(<StudioIdeaTools prompt="A quiet forest" onPrompt={onPrompt} />);
    expect(input).toHaveValue('A quiet forest');
    expect(screen.queryByRole('img')).toBeNull();
    expect(fetch).not.toHaveBeenCalled();
  });
});
