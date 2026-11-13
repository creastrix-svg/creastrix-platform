import {render,screen} from '@testing-library/react';
import {expect,it} from 'vitest';
import html from '../index.html?raw';
import LoadingFrame from './LoadingFrame';

it('keeps the document and React loading frames passive and visually identical before a route is ready',()=>{
  const template=document.createElement('template');
  template.innerHTML=html;
  const first=template.content.querySelector('#root [role="status"]');
  expect(first?.getAttribute('class')).toBe('app-loading-frame');
  expect(first?.textContent?.trim()).toBe('');
  expect(template.content.querySelector('link[rel="stylesheet"][href="/src/loadingFrame.css"]')).toBeTruthy();
  render(<LoadingFrame label="Загружаем каталог…"/>);
  const next=screen.getByRole('status');
  expect(next).toHaveClass('app-loading-frame');
  expect(next.querySelector('[aria-hidden="true"]')?.className).toBe(first?.firstElementChild?.className);
  expect(next.querySelector('.app-loading-label')).toHaveTextContent('Загружаем каталог…');
  expect(next.querySelector('button,a,input')).toBeNull();
});
