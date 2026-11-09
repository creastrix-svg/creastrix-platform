import { fireEvent, render } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import LetteringSketch from './LetteringSketch';
import { defaultLettering } from './personalization';
import { loopLetteringArea } from './studioGeometry';
import { sketchPointer } from './useSketchDrag';
import { imageLayout, studioArea } from './studioComposition';
import type { StudioObject } from './studioComposition';
import { loopProjection, projectLoopRing } from './loopSketchProjection';
import { variants } from './catalog';

const value = { ...defaultLettering('cobalt'),text:'Hello' };
it('matches independent proportions measured on the primary product photograph', () => {
  // reference-asset-01.webp (850 × 567): maximum width ~374 px, apparent
  // height ~330 px, outside mouth ~294 × 49 px. These are photo landmarks,
  // not manufacturing dimensions or absolute SVG pixels.
  const sections = Array.from({ length: 101 }, (_, i) => projectLoopRing(i / 100));
  const widest = sections.reduce((w, section) => section.rx > w.rx ? section : w);
  const { rim, foot, opening } = loopProjection;
  const top = rim.cy - rim.ry, fullHeight = foot.cy + foot.ry - top;
  expect(Math.abs(fullHeight / (2 * widest.rx) - 330 / 374)).toBeLessThan(.025);
  expect(Math.abs(rim.rx / widest.rx - 294 / 374)).toBeLessThan(.025);
  expect(Math.abs(rim.ry / rim.rx - 49 / 294)).toBeLessThan(.02);
  expect(Math.abs((widest.cy - top) / fullHeight - (286 - 128) / 330)).toBeLessThan(.06);
  expect(opening.rx / rim.rx).toBeGreaterThan(.9);
  expect(opening.rx).toBeLessThan(rim.rx);
});
it('joins the pot to its oval foot along a tangent, without a horizontal ledge', () => {
  const { foot } = loopProjection;
  for (const outline of [loopProjection.body, loopProjection.lower]) {
    const firstSide = /^M ([\d.]+) ([\d.]+) L ([\d.]+) ([\d.]+)/.exec(outline)!;
    const [x, y, nextX, nextY] = firstSide.slice(1).map(Number);
    // In a tilted view the contact point belongs on the front of the foot.
    // The side continuing out of it must be perpendicular to the ellipse normal.
    const nx = (x - foot.cx) / foot.rx ** 2, ny = (y - foot.cy) / foot.ry ** 2;
    const dx = nextX - x, dy = nextY - y;
    expect(y).toBeGreaterThan(foot.cy);
    expect(Math.abs((dx * nx + dy * ny) / (Math.hypot(dx, dy) * Math.hypot(nx, ny)))).toBeLessThan(.01);
  }
});
it.each(variants.map(v => [v.id] as const))('uses the shared pot silhouette and lip even after applying a design (%s)', variant => {
  const { container, rerender } = render(<LetteringSketch value={value} variant={variant} label="Design" />);
  const v = variants.find(item => item.id === variant)!;
  const insistOnGeometry = () => {
    const surface = container.querySelector('svg > g[aria-hidden="true"]')!;
    expect(surface.querySelector(`path[fill="${v.colors[0]}"]`)).toHaveAttribute('d', loopProjection.body);
    if (v.colors.length === 2) expect(surface.querySelector(`path[fill="${v.colors[1]}"]`)).toHaveAttribute('d', loopProjection.lower);
    const lip = surface.querySelector('ellipse')!;
    expect(lip).toHaveAttribute('rx', String(loopProjection.rim.rx));
    expect(lip).toHaveAttribute('ry', String(loopProjection.rim.ry));
  };
  insistOnGeometry();
  rerender(<LetteringSketch value={value} variant={variant} label="Design" detail guides objects={[{ id: 'initial', kind: 'text', value }]} />);
  insistOnGeometry();
  expect(container.querySelector('[data-studio-object="initial"]')).toHaveTextContent('Hello');
});
it('draws the exact shared allowed area without rounded corners excluding valid text', () => {
  const { container } = render(<LetteringSketch value={value} variant="cobalt" label="Design" guides />);
  const guide = container.querySelector('.studio-area rect')!;
  expect(guide).toHaveAttribute('x',String(loopLetteringArea.left));
  expect(guide).toHaveAttribute('y',String(loopLetteringArea.top));
  expect(guide).toHaveAttribute('width',String(loopLetteringArea.right-loopLetteringArea.left));
  expect(guide).toHaveAttribute('height',String(loopLetteringArea.bottom-loopLetteringArea.top));
  expect(guide).not.toHaveAttribute('rx');
});
it.each([false,true])('converts pointer travel through a letterboxed SVG without changing text scale (detail=%s)', detail => {
  const onMove=vi.fn(), onMoveEnd=vi.fn();
  const { container } = render(<LetteringSketch value={value} variant="cobalt" label="Design" detail={detail} onMove={onMove} onMoveEnd={onMoveEnd} />);
  const svg=container.querySelector('svg')!, target=container.querySelector('.studio-movable-text')!;
  vi.spyOn(svg,'getBoundingClientRect').mockReturnValue({ left:10,top:20,width:600,height:240 } as DOMRect);
  const scale=240/(detail?330:480);
  fireEvent.pointerDown(target,{ button:0,pointerId:2,clientX:250,clientY:100 });
  fireEvent.pointerMove(target,{ pointerId:3,clientX:260,clientY:110 }); expect(onMove).not.toHaveBeenCalled();
  fireEvent.pointerMove(target,{ pointerId:2,clientX:250+20*scale,clientY:100+10*scale });
  expect(onMove).toHaveBeenLastCalledWith(value.x+20,value.y+10);
  fireEvent.pointerCancel(target,{ pointerId:2 }); fireEvent.lostPointerCapture(target,{ pointerId:2 });
  expect(onMoveEnd).toHaveBeenCalledOnce();
  fireEvent.pointerMove(target,{ pointerId:2,clientX:300,clientY:200 }); expect(onMove).toHaveBeenCalledTimes(1);
});
it('prefers the inverse screen transform and ignores detached zero-size SVGs', () => {
  const svg=document.createElementNS('http://www.w3.org/2000/svg','svg'); svg.setAttribute('viewBox','0 0 480 480');
  expect(sketchPointer(svg,20,30)).toBeNull();
  const inverse={ a:2 }, invert=vi.fn(()=>inverse), transform=vi.fn(()=>({ x:7,y:9 }));
  Object.defineProperty(svg,'getScreenCTM',{ value:()=>({ inverse:invert }) });
  Object.defineProperty(svg,'createSVGPoint',{ value:()=>({ x:0,y:0,matrixTransform:transform }) });
  expect(sketchPointer(svg,20,30)).toEqual({ x:7,y:9 }); expect(transform).toHaveBeenCalledWith(inverse);
});
it('finishes active drag when the preview unmounts', () => {
  const onMoveEnd=vi.fn();
  const { container,unmount }=render(<LetteringSketch value={value} variant="cobalt" label="Design" onMove={vi.fn()} onMoveEnd={onMoveEnd} />);
  vi.spyOn(container.querySelector('svg')!,'getBoundingClientRect').mockReturnValue({ left:0,top:0,width:480,height:480 } as DOMRect);
  fireEvent.pointerDown(container.querySelector('.studio-movable-text')!,{ button:0,pointerId:1,clientX:240,clientY:278 });
  unmount(); expect(onMoveEnd).toHaveBeenCalledOnce();
});

it('renders the ordered composition, selects exact objects and overrides legacy single content', () => {
  const image = { id: 99, src: 'data:image/png;base64,a', canvas: document.createElement('canvas'), aspect: 2 };
  const objects: StudioObject[] = [{ id: 'text-1', kind: 'text', value }, { id: 'image-1', kind: 'image', value: { image, size: 80, x: 240, y: 278 }, fit: 'cover' }, { id: 'text-2', kind: 'text', value: { ...value, text: 'Second' } }];
  const select = vi.fn(), move = vi.fn();
  const { container, rerender } = render(<LetteringSketch value={{ ...value, text: 'Legacy' }} variant="cobalt" label="Composition" objects={objects} selectedId="image-1" onSelectObject={select} onMoveObject={move} />);
  expect(container.textContent).not.toContain('Legacy');
  expect([...container.querySelectorAll('[data-studio-object]')].map(node => node.getAttribute('data-studio-object'))).toEqual(['text-1', 'image-1', 'text-2']);
  const second = container.querySelector('[data-studio-object="text-2"]')!;
  fireEvent.keyDown(second, { key: 'Enter' }); expect(select).toHaveBeenLastCalledWith('text-2');
  fireEvent.click(second); expect(select).toHaveBeenLastCalledWith('text-2');
  const renderedImage = container.querySelector('image')!, box = imageLayout(objects[1].value as { image: typeof image; size: number; x: number; y: number }, 'cover', studioArea('cobalt'));
  expect(Number(renderedImage.getAttribute('width'))).toBe(box.width);
  expect(Number(renderedImage.getAttribute('height'))).toBe(box.height);
  expect(renderedImage.closest('[clip-path]')).not.toBeNull();
  rerender(<LetteringSketch value={value} variant="cobalt" label="Composition" objects={[]} />);
  expect(container.querySelector('text')).toBeNull();
});

it('drags a selected composition text by exact id without moving its sibling', () => {
  const objects: StudioObject[] = [{ id: 'first', kind: 'text', value }, { id: 'second', kind: 'text', value: { ...value, text: 'Second', x: 245 } }];
  const move = vi.fn(), end = vi.fn(), select = vi.fn();
  const { container } = render(<LetteringSketch value={value} variant="cobalt" label="Composition" objects={objects} onSelectObject={select} onMoveObject={move} onMoveEnd={end} />);
  vi.spyOn(container.querySelector('svg')!, 'getBoundingClientRect').mockReturnValue({ left: 0, top: 0, width: 480, height: 480 } as DOMRect);
  const second = container.querySelector('[data-studio-object="second"]')!;
  fireEvent.pointerDown(second, { button: 0, pointerId: 1, clientX: 245, clientY: 278 });
  fireEvent.pointerMove(second, { pointerId: 1, clientX: 250, clientY: 280 });
  fireEvent.pointerUp(second, { pointerId: 1 });
  expect(select).toHaveBeenCalledWith('second');
  expect(move).toHaveBeenCalledExactlyOnceWith('second', 250, 280);
  expect(end).toHaveBeenCalledOnce();
});
