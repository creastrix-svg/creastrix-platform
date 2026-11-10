import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import StudioLampPreview from './StudioLampPreview';
import { defaultLettering } from '../design/personalization';
import { emptyCreativeDraft } from '../session/studioCreativeContext';
import { studioZoneCopy } from '../studioZoneCopy';

const observe = vi.hoisted(() => vi.fn());
vi.mock('../../useProductLocale', () => ({ useProductLocale: () => 'en' }));
vi.mock('../../ModelPreview', async () => {
  const { createPortal } = await import('react-dom');
  return { default: (props: { target: HTMLElement | null; expanded: boolean; source: string }) => {
    observe(props);
    return props.target ? createPortal(<div data-testid="retained-lamp-model" data-expanded={props.expanded}>{props.source}</div>, props.target) : null;
  } };
});
const flush = async () => act(async () => { for (let i=0;i<20;i++) await Promise.resolve(); });
const props = { value:defaultLettering('lamp'),draft:emptyCreativeDraft(),zone:'panel' as const,onSelectZone:vi.fn(),expanded:false,onExpand:vi.fn(),onClose:vi.fn() };

it('retains the same attached viewer target through 3D → area → 3D rather than disposing its scene', async () => {
  render(<StudioLampPreview {...props} />); await flush();
  const element = screen.getByTestId('retained-lamp-model'), host = element.parentElement;
  expect(host).not.toBeNull(); expect(host).not.toHaveAttribute('hidden');
  observe.mockClear();
  fireEvent.click(screen.getByRole('button',{ name:studioZoneCopy.en.flatView })); await flush();
  expect(host).toHaveAttribute('hidden'); expect(screen.getByTestId('retained-lamp-model')).toBe(element);
  expect(observe.mock.calls.every(([next]) => next.target===host && !next.expanded)).toBe(true);
  fireEvent.click(screen.getByRole('button',{ name:studioZoneCopy.en.modelView })); await flush();
  expect(host).not.toHaveAttribute('hidden'); expect(screen.getByTestId('retained-lamp-model')).toBe(element);
  expect(observe.mock.calls.every(([next]) => next.target===host)).toBe(true);
});
it('expanded selected-area mode retains its own hidden model host and never enables free-rotation controls', async () => {
  render(<StudioLampPreview {...props} expanded />); await flush();
  const dialog = screen.getByRole('dialog'), element=within(dialog).getByTestId('retained-lamp-model'), host=element.parentElement;
  fireEvent.click(within(dialog).getByRole('button',{ name:studioZoneCopy.en.flatView })); await flush();
  expect(host).toHaveAttribute('hidden'); expect(within(dialog).getByTestId('retained-lamp-model')).toBe(element);
  expect(observe.mock.lastCall?.[0].target).toBe(host);
  expect(observe.mock.lastCall?.[0].expanded).toBe(false);
  expect(observe.mock.lastCall?.[0].lampZoneSelection).toBeUndefined();
  fireEvent.click(within(dialog).getByRole('button',{ name:studioZoneCopy.en.modelView })); await flush();
  expect(host).not.toHaveAttribute('hidden');
  expect(observe.mock.lastCall?.[0].target).toBe(host);
  expect(observe.mock.lastCall?.[0].expanded).toBe(true);
  expect(observe.mock.lastCall?.[0].lampZoneSelection.selected).toBe('panel');
});

it.each([false, true])('connects 2D surface selection to the shared area and restores it in 3D (expanded=%s)', async expanded => {
  const onSelectZone = vi.fn();
  const view = render(<StudioLampPreview {...props} onSelectZone={onSelectZone} expanded={expanded} />); await flush();
  const scope = expanded ? within(screen.getByRole('dialog')) : within(view.container);
  fireEvent.click(scope.getByRole('button', { name: studioZoneCopy.en.flatView })); await flush();
  expect(observe.mock.lastCall?.[0].lampZoneSelection).toBeUndefined();
  const sketch = scope.getByRole('group', { name: 'Luma · 2D sketch' });
  fireEvent.click(within(sketch).getByRole('button', { name: 'Base' }));
  expect(onSelectZone).toHaveBeenCalledWith('base');
  view.rerender(<StudioLampPreview {...props} onSelectZone={onSelectZone} zone="base" expanded={expanded} />); await flush();
  expect(within(sketch).getByRole('button', { name: 'Base' })).toHaveAttribute('aria-pressed', 'true');
  fireEvent.click(scope.getByRole('button', { name: studioZoneCopy.en.modelView })); await flush();
  expect(observe.mock.lastCall?.[0].lampZoneSelection.selected).toBe('base');
});

it('forwards the selected area and picking callback in both inline and expanded views', async () => {
  const selected = vi.fn();
  const view = render(<StudioLampPreview {...props} onSelectZone={selected} />); await flush();
  expect(observe.mock.lastCall?.[0].lampZoneSelection.selected).toBe('panel');
  act(() => observe.mock.lastCall?.[0].lampZoneSelection.onSelect('base'));
  expect(selected).toHaveBeenCalledWith('base');
  view.rerender(<StudioLampPreview {...props} zone="base" onSelectZone={selected} expanded />); await flush();
  expect(observe.mock.lastCall?.[0].lampZoneSelection.selected).toBe('base');
  expect(within(screen.getByRole('dialog')).getByRole('button', { name: 'Base' })).toHaveAttribute('aria-pressed', 'true');
  expect(within(screen.getByRole('dialog')).getByText(studioZoneCopy.en.pickHint)).toBeVisible();
  fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Front panel' }));
  expect(selected).toHaveBeenLastCalledWith('panel');
});

it('forwards independent panel and base text through pattern changes, 2D and expanded views', async () => {
  const value = { ...props.value,text:'For Alex' }, panelText = { ...props.value,font:'stencil' as const,text:'HOME' };
  const view = render(<StudioLampPreview {...props} value={value} panelText={panelText} />); await flush();
  expect(observe.mock.lastCall?.[0].lampDecoration).toMatchObject({ value,panelText });
  fireEvent.click(screen.getByRole('button',{ name:studioZoneCopy.en.flatView })); await flush();
  const sketch=screen.getByRole('group',{ name:'Luma · 2D sketch' });
  expect(sketch).toHaveTextContent('HOME'); expect(sketch).toHaveTextContent('For Alex');
  view.rerender(<StudioLampPreview {...props} value={value} panelText={panelText} draft={{ ...props.draft,pattern:'orbit' }} expanded />); await flush();
  expect(observe.mock.lastCall?.[0].source).toBe('/product/studio-lamp-orbit.glb');
  expect(observe.mock.lastCall?.[0].lampDecoration).toMatchObject({ value,panelText });
  view.rerender(<StudioLampPreview {...props} value={value} expanded />); await flush();
  expect(observe.mock.lastCall?.[0].lampDecoration.panelText).toBeUndefined();
  expect(observe.mock.lastCall?.[0].lampDecoration.value).toEqual(value);
});
it('shows only the selected writable guide in 2D and 3D, including a still-empty text band', async () => {
  const view=render(<StudioLampPreview {...props} highlightTextBand />); await flush();
  expect(observe.mock.lastCall?.[0].lampDecoration).toMatchObject({ showBaseArea:false,showPanelTextArea:true });
  fireEvent.click(screen.getByRole('button',{ name:studioZoneCopy.en.flatView })); await flush();
  expect(view.container.querySelector('[data-lamp-text-band]')).not.toBeNull();
  view.rerender(<StudioLampPreview {...props} zone="base" highlightTextBand />); await flush();
  expect(observe.mock.lastCall?.[0].lampDecoration).toMatchObject({ showBaseArea:true,showPanelTextArea:false });
  expect(view.container.querySelector('[data-lamp-text-band]')).toBeNull();
});
