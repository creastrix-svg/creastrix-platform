import { render, screen } from '@testing-library/react';
import { expect, it } from 'vitest';
import LampSketch from './LampSketch';
import { lampPatterns } from './lampPatterns';
import type { LampPatternId } from './lampPatterns';

const ids: LampPatternId[] = ['botanical', 'forest', 'orbit'];

it.each(ids)('%s subtracts real openings and adds material bridges from the exact report descriptors', pattern => {
  const { container, rerender } = render(<LampSketch pattern={pattern} bridges={false} highlight label="Panel illustration" />);
  expect(screen.getByRole('img', { name: 'Panel illustration' })).toHaveAttribute('data-lamp-mode', 'panel');
  const mask = container.querySelector('mask')!;
  expect(mask.querySelector('[data-lamp-openings]')).toHaveAttribute('fill', '#000');
  expect(mask.querySelector('[data-lamp-openings]')!.children.length).toBe(lampPatterns[pattern].openings.length);
  expect(mask.querySelector('[data-lamp-islands]')).toHaveAttribute('fill', '#fff');
  expect(mask.querySelector('[data-lamp-islands]')!.children.length).toBe(lampPatterns[pattern].islands.length);
  expect(mask.querySelector('[data-lamp-bridges]')).toBeNull();
  expect(container.querySelector('[data-lamp-guides]')).toHaveAttribute('data-lamp-guides', 'islands');
  rerender(<LampSketch pattern={pattern} bridges highlight label="Panel illustration" />);
  const bridges = mask.querySelector('[data-lamp-bridges]')!;
  expect(bridges).toHaveAttribute('fill', '#fff');
  expect(bridges.children.length).toBe(lampPatterns[pattern].islands.length);
  for (const island of lampPatterns[pattern].islands) {
    const rendered = bridges.querySelector(`[data-bridge-for="${island.id}"]`)!;
    for (const [key, value] of Object.entries(island.bridge)) expect(rendered).toHaveAttribute(key, String(value));
  }
  expect(container.querySelector('[data-lamp-guides]')).toHaveAttribute('data-lamp-guides', 'connections');
});

it('uses unique masks for multiple independent previews and never shares IDs', () => {
  const { container } = render(<><LampSketch pattern="forest" bridges label="First" /><LampSketch pattern="orbit" bridges={false} mode="lamp" label="Assembled illustration" /></>);
  const renderedIds = Array.from(container.querySelectorAll('[id]')).map(element => element.id);
  expect(new Set(renderedIds).size).toBe(renderedIds.length);
  const assembled = screen.getByRole('img', { name: 'Assembled illustration' });
  expect(assembled).toHaveAttribute('data-lamp-mode', 'lamp');
  expect(assembled.querySelectorAll('[data-lamp-panel]')).toHaveLength(2);
  expect(assembled.querySelector('model-viewer')).toBeNull();
});

it('renders local raster only as an engraving reference without manufacturing cutouts', () => {
  const { container } = render(<LampSketch pattern="forest" bridges label="Engraving reference" imageUrl="blob:example-local-image" text={'<script>\nMiro'} fontFamily="Georgia, serif" />);
  expect(container.querySelector('image')).toHaveAttribute('href', 'blob:example-local-image');
  expect(container.querySelector('svg')).toHaveAttribute('data-cut-pattern', 'none');
  expect(container.querySelector('[data-lamp-openings]')).toBeNull();
  expect(container.querySelector('[data-lamp-bridges]')).toBeNull();
  expect(container.querySelector('text')).toHaveTextContent('<script>Miro');
  expect(container.querySelector('script')).toBeNull();
  expect(container.querySelector('[data-lamp-engraving]')).toHaveAttribute('mask');
});

it.each(['https://example.com/picture.png', 'data:image/svg+xml;base64,PHN2Zz4=', 'javascript:alert(1)'])('does not fetch an unapproved raster source %s', imageUrl => {
  const { container } = render(<LampSketch pattern="forest" bridges label="Reference" imageUrl={imageUrl} />);
  expect(container.querySelector('image')).toBeNull();
});

it('lets the caller display a clean engraving-only panel without implying a cut-ready pattern', () => {
  const { container } = render(<LampSketch pattern="orbit" bridges highlight showPattern={false} label="Text engraving" text="Miro" />);
  expect(container.querySelector('text')).toHaveTextContent('Miro');
  expect(container.querySelector('[data-lamp-openings]')).toBeNull();
  expect(container.querySelector('[data-lamp-guides]')).toBeNull();
});

it('scales engraving reference and text within finite local bounds without stretching the raster', () => {
  const { container, rerender } = render(<LampSketch pattern="orbit" bridges label="Reference" imageUrl="blob:local" imageSize={150} text="Miro" fontSize={32} />);
  expect(container.querySelector('image')).toHaveAttribute('width', '186');
  expect(container.querySelector('image')).toHaveAttribute('height', '237');
  expect(container.querySelector('image')).toHaveAttribute('preserveAspectRatio', 'xMidYMid meet');
  expect(container.querySelector('text')).toHaveAttribute('font-size', String(32 * .72));
  rerender(<LampSketch pattern="orbit" bridges label="Reference" imageUrl="blob:local" imageSize={Infinity} text="Miro" fontSize={NaN} />);
  expect(container.querySelector('image')).toHaveAttribute('width', '124');
  expect(container.querySelector('text')).toHaveAttribute('font-size', String(24 * .72));
  rerender(<LampSketch pattern="orbit" bridges label="Reference" imageUrl="blob:local" imageSize={0} text="Miro" fontSize={0} />);
  expect(container.querySelector('image')).toHaveAttribute('width', String(124 * .2));
  expect(container.querySelector('text')).toHaveAttribute('font-size', String(18 * .72));
});
