import { concepts } from './fixtures/loop';
import type { ProductCopy } from './copy';

export type CarouselItem = { name: string; note: string; href: string; photo: string };
export type CarouselContent = { items: readonly CarouselItem[]; href: string };

export function loopCarouselContent(t: ProductCopy): CarouselContent {
  return { href: '/catalog', items: concepts.map(item => ({
    name: item.name, note: t.concept, href: '/preview/items/' + item.id, photo: `/product/reference-asset-${item.image}.webp`
  })) };
}
