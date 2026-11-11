import { isVariantId } from '../fixtures/loop';
import type { VariantId } from '../fixtures/loop';
import { tideOfferPath } from '../routes';

export type ReviewProduct = 'loop' | 'tide';
export function reviewLinks(product: ReviewProduct, params: URLSearchParams) {
  const base = product === 'tide' ? tideOfferPath : '/products/loop';
  const query = product === 'loop' ? '?variant=' + reviewVariant(params) : '';
  return { product: base + query, form: base + '/reviews/new' + query };
}

function reviewVariant(params: URLSearchParams): VariantId {
  const values = params.getAll('variant');
  return values.length === 1 && isVariantId(values[0]) ? values[0] : 'cobalt';
}
