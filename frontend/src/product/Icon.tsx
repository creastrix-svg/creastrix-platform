const paths = {
  search: 'm21 21-5-5 M19 10a9 9 0 1 1-18 0 9 9 0 0 1 18 0',
  heart: 'M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8L12 21l8.8-8.6a5.5 5.5 0 0 0 0-7.8Z',
  bag: 'M5 7h14l1 14H4L5 7 M8 8V5a4 4 0 0 1 8 0v3',
  user: 'M19 21v-2a7 7 0 0 0-14 0v2 M16 6a4 4 0 1 1-8 0 4 4 0 0 1 8 0',
  arrow: 'M5 12h14 m-6-6 6 6-6 6',
  chevron: 'm9 5 7 7-7 7',
  close: 'm6 6 12 12 M6 18 18 6',
  trash: 'M3 6h18 M9 6V3h6v3 M5 6l1 15h12l1-15 M10 10v7 M14 10v7',
  expand: 'M8 3H3v5 M16 3h5v5 M3 16v5h5 M21 16v5h-5',
  cube: 'm12 2 9 5v10l-9 5-9-5V7l9-5 M3 7l9 5 9-5 M12 12v10',
  image: 'M3 3h18v18H3V3 M3 16l5-5 4 4 3-3 6 6 M17 7h.01',
  spark: 'm12 3 2.8 6.2L21 12l-6.2 2.8L12 21l-2.8-6.2L3 12l6.2-2.8L12 3',
  star: 'm12 2 3.1 6.4 7.1 1-5.1 5 1.2 7.1-6.3-3.3-6.3 3.3 1.2-7.1-5.1-5 7.1-1L12 2',
  layers: 'm12 3 10 5-10 5L2 8l10-5 M2 12l10 5 10-5 M2 16l10 5 10-5',
  check: 'm5 12 4 4L19 6',
  sun: 'M12 2v2 M12 20v2 M2 12h2 M20 12h2 M5 5l1.5 1.5 M17.5 17.5 19 19 M5 19l1.5-1.5 M17.5 6.5 19 5 M17 12a5 5 0 1 1-10 0 5 5 0 0 1 10 0',
  help: 'M9.1 9a3 3 0 0 1 5.8 1c0 2-3 2-3 4 M12 17h.01 M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0',
  menu: 'M3 6h18 M3 12h18 M3 18h18',
  store: 'M3 9h18l-2-6H5L3 9 M4 9v12h16V9 M9 21v-7h6v7 M3 9a3 3 0 0 0 6 0 3 3 0 0 0 6 0 3 3 0 0 0 6 0',
  moon: 'M21 12.8A9 9 0 0 1 11.2 3 9 9 0 1 0 21 12.8',
  monitor: 'M3 3h18v14H3V3 M8 21h8 M12 17v4',
  reset: 'M8 3H3v5 M16 3h5v5 M3 16v5h5 M21 16v5h-5 M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0',
  rotateLeft: 'M5 4v5h5 M5 9a8 8 0 1 1 0 9',
  rotateRight: 'M19 4v5h-5 M19 9a8 8 0 1 0 0 9',
  plus: 'M12 5v14 M5 12h14', minus: 'M5 12h14',
};
export function Icon({ name, filled = false }: { name: keyof typeof paths; filled?: boolean }) {
  return <svg viewBox="0 0 24 24" fill={filled ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={paths[name]} /></svg>;
}
export function Spinner() { return <span className="product-spinner" aria-hidden="true" />; }
