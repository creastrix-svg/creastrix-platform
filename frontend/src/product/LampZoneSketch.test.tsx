import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import LampZoneSketch from './LampZoneSketch';
import { defaultLettering } from './personalization';
import { emptyCreativeDraft } from './studioCreativeContext';
import { lampPanel } from './lampPatterns';
import type { LampZone } from './lampZoneSelection';
import { lampBaseFace, lampBaseMapping, lampPanelTextMapping } from './studioGeometry';
import type { StudioObject } from './studioComposition';

const label = 'Luma selected area';
const zoneNames = { panel: 'Front panel', base: 'Wooden base' };
const fixture = () => ({ value: defaultLettering('lamp'), draft: emptyCreativeDraft(), label });

function InteractiveExample({ onSelect = () => undefined }: { onSelect?: (zone: LampZone) => void }) {
  const [zone, setZone] = useState<LampZone>('panel');
  return <LampZoneSketch {...fixture()} zone={zone} zoneNames={zoneNames} onSelectZone={next => { onSelect(next); setZone(next); }} />;
}

describe('prepared 2D lamp area selection', () => {
  it('keeps inactive composition pointer-transparent and maps active object dragging to editor coordinates', () => {
    const props = fixture(), move = vi.fn(), select = vi.fn();
    const baseObjects: StudioObject[] = [{ id: 'base-object', kind: 'text', value: { ...props.value, text: 'Home' } }];
    const panelObjects: StudioObject[] = [{ id: 'panel-object', kind: 'text', value: { ...defaultLettering('lamp-panel'), text: 'LIGHT' } }];
    const { container } = render(<LampZoneSketch {...props} baseObjects={baseObjects} panelObjects={panelObjects} zone="base" zoneNames={zoneNames} onSelectZone={vi.fn()} onSelectObject={select} onMoveObject={move} />);
    const svg = container.querySelector('svg')!;
    vi.spyOn(svg, 'getBoundingClientRect').mockReturnValue({ left: 0, top: 0, width: 480, height: 480 } as DOMRect);
    const base = container.querySelector('[data-studio-object="base-object"]')!, panel = container.querySelector('[data-studio-object="panel-object"]')!;
    expect(panel).toHaveAttribute('pointer-events', 'none'); expect(panel).not.toHaveAttribute('tabindex');
    const sx = lampBaseMapping.scale * lampBaseFace.width / lampBaseMapping.width, sy = lampBaseMapping.scale * lampBaseFace.height / lampBaseMapping.height;
    fireEvent.pointerDown(base, { button: 0, pointerId: 4, clientX: 240, clientY: 350 });
    fireEvent.pointerMove(base, { pointerId: 4, clientX: 240 + 10 * sx, clientY: 350 + 5 * sy });
    expect(move.mock.lastCall?.[0]).toBe('base-object');
    expect(move.mock.lastCall?.[1]).toBeCloseTo(250); expect(move.mock.lastCall?.[2]).toBeCloseTo(283);
    expect(select).toHaveBeenCalledExactlyOnceWith('base-object');
  });
  it('keeps the default thumbnail a single image without nested interactive controls', () => {
    render(<button type="button" aria-label="Open preview"><LampZoneSketch {...fixture()} zone="panel" /></button>);
    const image = screen.getByRole('img', { name: label });
    expect(screen.getAllByRole('button')).toHaveLength(1);
    expect(image.querySelector('[role="button"], [tabindex]')).toBeNull();
    expect(image.querySelector('[data-lamp-active-zone]')).toHaveAttribute('data-lamp-active-zone', 'panel');
  });

  it.each(['panel', 'base'] as const)('keeps both composition labels dark when %s is selected and the other face is hovered', zone => {
    const baseObjects: StudioObject[] = [{ id: 'base-word', kind: 'text', value: { ...defaultLettering('lamp'), text: 'ATELIER', color: '#ffffff' } }];
    const panelObjects: StudioObject[] = [{ id: 'panel-word', kind: 'text', value: { ...defaultLettering('lamp-panel'), text: 'LUMA', color: '#ffffff' } }];
    const { container } = render(<LampZoneSketch {...fixture()} zone={zone} zoneNames={zoneNames} onSelectZone={vi.fn()}
      selectedId={zone + '-word'} baseObjects={baseObjects} panelObjects={panelObjects} />);
    const hovered = screen.getByRole('button', { name: zoneNames[zone === 'panel' ? 'base' : 'panel'] });
    fireEvent.pointerEnter(hovered, { pointerType: 'mouse' });
    expect(hovered).toHaveAttribute('data-hovered', 'true');
    expect(hovered).toHaveAttribute('aria-pressed', 'false');
    for (const [id, word] of [['base-word', 'ATELIER'], ['panel-word', 'LUMA']]) {
      const glyphs = container.querySelector(`[data-studio-object="${id}"] text`);
      expect(glyphs).toHaveTextContent(word);
      expect(glyphs).toHaveAttribute('fill', '#302219');
    }
  });

  it('shows independent base engraving and stencil cut-preview text without changing pattern or target bounds', () => {
    const props = fixture(), panelText = { ...props.value,text:'HOME',font:'stencil' as const };
    props.value.text = 'Family'; props.draft.pattern = 'orbit';
    const { container,rerender } = render(<LampZoneSketch {...props} panelText={panelText} zone="panel" />);
    const preview = container.querySelector('[data-lamp-cut-text="preview"]')!;
    expect(preview).toHaveAttribute('transform','translate(135 30.25) scale(.875)');
    expect(preview.querySelectorAll('text')).toHaveLength(1);
    const text = preview.querySelector('text');
    expect(text).toHaveTextContent('HOME'); expect(text).toHaveAttribute('x','120'); expect(text).toHaveAttribute('y','286');
    expect(text).toHaveAttribute('fill','#302219'); expect(text).toHaveAttribute('font-family','"Creastrix Allerta Stencil", sans-serif');
    expect(container.querySelector('[data-lamp-base-decoration] text')).toHaveAttribute('fill','#302219');
    expect(container).toHaveTextContent('Family');
    expect(container.querySelector('[data-cut-pattern]')).toHaveAttribute('data-cut-pattern','orbit');
    expect(container.querySelectorAll('[data-bridge-for]')).toHaveLength(3);
    expect(container.querySelector('[role="button"], [tabindex]')).toBeNull();
    rerender(<LampZoneSketch {...props} zone="base" />);
    expect(container.querySelector('[data-lamp-cut-text]')).toBeNull(); expect(container).toHaveTextContent('Family');
  });

  it.each([{ font:'sans' as const,text:'HOME' }, { font:'stencil' as const,text:'Дом' }])('does not display unsupported cut-text as a fallback font (%j)', candidate => {
    const props=fixture();
    const { container }=render(<LampZoneSketch {...props} panelText={{ ...props.value,...candidate }} zone="panel" />);
    expect(container.querySelector('[data-lamp-cut-text]')).toBeNull();
  });

  it('requires supplied accessible area names before enabling interaction', () => {
    const select = vi.fn();
    render(<LampZoneSketch {...fixture()} zone="base" onSelectZone={select} />);
    fireEvent.click(screen.getByRole('img', { name: label }));
    expect(screen.queryByRole('button')).toBeNull();
    expect(select).not.toHaveBeenCalled();
  });

  it('exposes named area buttons outside image semantics and marks only the selected area', () => {
    render(<InteractiveExample />);
    const illustration = screen.getByRole('group', { name: label });
    expect(within(illustration).getAllByRole('button')).toHaveLength(2);
    const panel = screen.getByRole('button', { name: zoneNames.panel });
    const base = screen.getByRole('button', { name: zoneNames.base });
    expect(panel).toHaveAttribute('aria-pressed', 'true');
    expect(base).toHaveAttribute('aria-pressed', 'false');
    expect(panel.closest('[role="img"]')).toBeNull();
    expect(base.closest('[aria-hidden="true"]')).toBeNull();
    expect(panel.querySelector('.studio-sketch-zone-check')).not.toBeNull();
    expect(base.querySelector('.studio-sketch-zone-check')).toBeNull();
    expect(illustration.querySelectorAll('[data-lamp-active-zone]')).toHaveLength(1);
  });

  it('previews hover without selecting and does not create a sticky touch hover', () => {
    const select = vi.fn(); render(<InteractiveExample onSelect={select} />);
    const panel = screen.getByRole('button', { name: zoneNames.panel }), base = screen.getByRole('button', { name: zoneNames.base });
    fireEvent.pointerEnter(base, { pointerType: 'mouse' });
    expect(base).toHaveAttribute('data-hovered', 'true');
    expect(base).toHaveAttribute('aria-pressed', 'false');
    expect(panel).toHaveAttribute('aria-pressed', 'true');
    expect(select).not.toHaveBeenCalled();
    fireEvent.pointerLeave(base);
    expect(base).toHaveAttribute('data-hovered', 'false');
    fireEvent.pointerEnter(base, { pointerType: 'touch' });
    expect(base).toHaveAttribute('data-hovered', 'false');
    expect(select).not.toHaveBeenCalled();
  });

  it('selects either face by click or tap without changing the supplied design', async () => {
    const select = vi.fn(), user = userEvent.setup();
    const props = fixture(); props.value.text = 'OUR HOME'; props.draft.pattern = 'forest';
    const initial = structuredClone(props);
    const { rerender } = render(<LampZoneSketch {...props} zone="panel" zoneNames={zoneNames} onSelectZone={select} />);
    const base = screen.getByRole('button', { name: zoneNames.base });
    await user.click(base.querySelector('.studio-sketch-zone-surface')!);
    expect(select).toHaveBeenLastCalledWith('base');
    rerender(<LampZoneSketch {...props} zone="base" zoneNames={zoneNames} onSelectZone={select} />);
    expect(base).toHaveAttribute('aria-pressed', 'true');
    const panel = screen.getByRole('button', { name: zoneNames.panel });
    await user.pointer([{ keys: '[TouchA>]', target: panel.querySelector('.studio-sketch-zone-surface')! }, { keys: '[/TouchA]' }]);
    expect(select).toHaveBeenLastCalledWith('panel');
    expect(select).toHaveBeenCalledTimes(2);
    expect(props).toEqual(initial);
  });

  it('uses the prepared face outlines, leaving surrounding wood and background noninteractive', () => {
    const select = vi.fn(); render(<InteractiveExample onSelect={select} />);
    const illustration = screen.getByRole('group', { name: label });
    const panel = screen.getByRole('button', { name: zoneNames.panel }).querySelector('.studio-sketch-zone-surface')!;
    const base = screen.getByRole('button', { name: zoneNames.base }).querySelector('.studio-sketch-zone-surface')!;
    expect(panel).toHaveAttribute('d', lampPanel.shape);
    expect(panel).toHaveAttribute('transform', 'translate(135 30.25) scale(.875)');
    expect(base).toHaveAttribute('x', '126.25'); expect(base).toHaveAttribute('y', '319.5');
    expect(base).toHaveAttribute('width', '227.5'); expect(base).toHaveAttribute('height', '77');
    fireEvent.click(illustration);
    fireEvent.click(illustration.querySelector('ellipse')!);
    fireEvent.click(illustration.querySelector('rect[x="107"]')!);
    expect(select).not.toHaveBeenCalled();
  });

  it('supports Enter and Space activation without repeating or scrolling on Space', () => {
    const select = vi.fn(); render(<InteractiveExample onSelect={select} />);
    const panel = screen.getByRole('button', { name: zoneNames.panel }), base = screen.getByRole('button', { name: zoneNames.base });
    expect(panel).toHaveAttribute('tabindex', '0'); expect(base).toHaveAttribute('tabindex', '0');
    fireEvent.keyDown(base, { key: 'Enter' });
    fireEvent.keyDown(base, { key: 'Enter', repeat: true });
    fireEvent.keyUp(base, { key: 'Enter' });
    expect(select).toHaveBeenCalledExactlyOnceWith('base');
    expect(base).toHaveAttribute('aria-pressed', 'true');
    expect(fireEvent.keyDown(panel, { key: ' ', cancelable: true })).toBe(false);
    expect(select).toHaveBeenCalledTimes(1);
    fireEvent.keyDown(panel, { key: ' ', repeat: true });
    fireEvent.keyUp(panel, { key: ' ' });
    expect(select).toHaveBeenLastCalledWith('panel');
    expect(select).toHaveBeenCalledTimes(2);
    expect(panel).toHaveAttribute('aria-pressed', 'true');
    fireEvent.keyDown(base, { key: ' ' }); fireEvent.blur(base); fireEvent.keyUp(base, { key: ' ' });
    expect(select).toHaveBeenCalledTimes(2);
  });

  it('offers both areas in the keyboard tab order', async () => {
    const user = userEvent.setup(); render(<InteractiveExample />);
    const panel = screen.getByRole('button', { name: zoneNames.panel }), base = screen.getByRole('button', { name: zoneNames.base });
    await user.tab(); expect(panel).toHaveFocus();
    await user.tab(); expect(base).toHaveFocus();
    await user.keyboard('{Enter}'); expect(base).toHaveAttribute('aria-pressed', 'true');
    await user.tab({ shift: true }); expect(panel).toHaveFocus();
    await user.keyboard(' '); expect(panel).toHaveAttribute('aria-pressed', 'true');
  });
  it('draws the fixed cut band only while editing panel text, without adding an interactive target', () => {
    const props=fixture();
    const { container,rerender }=render(<LampZoneSketch {...props} zone="panel" highlightTextBand />);
    const band=container.querySelector('[data-lamp-text-band]')!;
    expect(band).toHaveAttribute('x',String(lampPanelTextMapping.left)); expect(band).toHaveAttribute('y',String(lampPanelTextMapping.top));
    expect(band).toHaveAttribute('width',String(lampPanelTextMapping.bandWidth)); expect(band).toHaveAttribute('height',String(lampPanelTextMapping.bandHeight));
    expect(band).toHaveAttribute('pointer-events','none'); expect(container.querySelector('[role="button"]')).toBeNull();
    rerender(<LampZoneSketch {...props} zone="base" highlightTextBand />); expect(container.querySelector('[data-lamp-text-band]')).toBeNull();
  });
  it('drags selected base lettering in editor coordinates and ends once without reselecting the zone', () => {
    const onMove=vi.fn(), onMoveEnd=vi.fn(), onSelectZone=vi.fn(), props=fixture(); props.value.text='Home';
    const { container,rerender }=render(<LampZoneSketch {...props} zone="base" zoneNames={zoneNames} onSelectZone={onSelectZone} onMove={onMove} onMoveEnd={onMoveEnd} />);
    const svg=container.querySelector('svg')!; vi.spyOn(svg,'getBoundingClientRect').mockReturnValue({ left:0,top:0,width:240,height:240 } as DOMRect);
    const base=screen.getByRole('button',{ name:zoneNames.base }), sx=lampBaseMapping.scale*lampBaseFace.width/lampBaseMapping.width/2, sy=lampBaseMapping.scale*lampBaseFace.height/lampBaseMapping.height/2;
    fireEvent.pointerDown(base,{ button:0,pointerId:4,clientX:120,clientY:180 });
    fireEvent.pointerMove(base,{ pointerId:4,clientX:120+30*sx,clientY:180+10*sy });
    expect(onMove.mock.lastCall?.[0]).toBeCloseTo(props.value.x+30); expect(onMove.mock.lastCall?.[1]).toBeCloseTo(props.value.y+10);
    fireEvent.pointerUp(base,{ pointerId:4 }); fireEvent.click(base); expect(onMoveEnd).toHaveBeenCalledOnce(); expect(onSelectZone).not.toHaveBeenCalled();
    rerender(<LampZoneSketch {...props} zone="panel" zoneNames={zoneNames} onSelectZone={onSelectZone} onMove={onMove} onMoveEnd={onMoveEnd} />);
    fireEvent.pointerDown(base,{ button:0,pointerId:5,clientX:120,clientY:180 }); fireEvent.pointerMove(base,{ pointerId:5,clientX:130,clientY:190 });
    expect(onMove).toHaveBeenCalledOnce(); fireEvent.click(base); expect(onSelectZone).toHaveBeenCalledWith('base');
  });
});
