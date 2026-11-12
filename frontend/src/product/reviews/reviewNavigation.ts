import { isVariantId } from '../fixtures/loop';
import { tideOfferPath } from '../routes';

export type ReviewProduct = 'loop' | 'tide';
export function reviewLinks(product: ReviewProduct, params: URLSearchParams) {
  const base = product === 'tide' ? tideOfferPath : '/products/loop';
  const query = product === 'loop' ? '?' + loopReturnQuery(params) : '';
  return { product: base + query, list: base + '/reviews' + query, form: base + '/reviews/new' + query };
}

/** Reviews belong to the product, but their back link may belong to one local
 * cart item. Keep that identity even when the cart changes during the review. */
function loopReturnQuery(params: URLSearchParams) {
  const variants = params.getAll('variant'), ids = params.getAll('cartLine');
  const variant = variants.length === 1 && isVariantId(variants[0]) ? variants[0] : null;
  if (!ids.length) return 'variant=' + (variant ?? 'cobalt');
  const id = ids[0];
  if (!variant || ids.length !== 1 || (id !== variant && !/^design-[1-9][0-9]{0,15}$/.test(id))) {
    // The detail route treats this as unavailable rather than showing an unrelated draft.
    return 'cartLine=';
  }
  return new URLSearchParams({ variant, cartLine:id }).toString();
}
