import { concepts, variants } from '../fixtures/loop';
import { readCartSku, type CartSku } from '../fixtures/cartSkus';
import { discountPercent, swatchBackground } from '../productDisplay';
import { productCopy } from '../copy';
import { editionItems } from '../fixtures/editionCatalog';
import { editionDepartments, offerDepartments, shelfCategorySlug } from '../home/homeFeatured';
import { shelfCopy, shelfFilters } from '../home/homeShelfCopy';
import type { StorefrontLocale } from '../locale';
import { conceptDescription } from '../search';
import { canPersonalize, studioProducts } from '../studio/studioProducts';
import { tideOffer } from '../fixtures/tideOffer';
import { matchCatalog, type CatalogData } from './catalogBrowse';
import { catalogCopy } from './catalogCopy';

/** Project the existing demo facts and routes into browse data. The matcher
 * and cards never have to recognise a particular product or SKU. */
function catalogFixtures(locale: StorefrontLocale): CatalogData {
  const edition = editionItems(locale), tide = edition.find(item => item.id === tideOffer.id)!;
  const loop = studioProducts.loop;
  const finish = (sku: CartSku) => {
    const { color, cents, previousCents, stock, photo, search } = readCartSku(sku);
    return { color: { id: color.id, label: color.name[locale], swatch: swatchBackground(color.colors) },
      cents, previousCents, discount: discountPercent(cents, previousCents), stock, photo, search };
  };
  return {
    categories: shelfFilters.filter(id => id !== 'all').map(id => ({ id: shelfCategorySlug(id), label: shelfCopy[locale][id] })),
    techniques: [{ id: '3d-print', label: catalogCopy[locale].print3d }],
    offers: [
      {
        href: '/products/loop', reviewsHref: '/products/loop/reviews', name: loop.name,
        details: productCopy[locale].subtitle + ' · ' + catalogCopy[locale].productDetails,
        categories: offerDepartments.map(shelfCategorySlug), format: loop.readyMade ? 'ready' : 'made-to-order',
        personalizable: canPersonalize(loop), technique: '3d-print',
        finishes: variants.map(v => finish(v.id)),
      }, {
        href: tideOffer.href, reviewsHref: tideOffer.href + '/reviews', name: tide.name, details: tide.title + ' · ' + tide.material,
        categories: editionDepartments(tide.id).map(shelfCategorySlug), format: 'ready', personalizable: false,
        finishes: [finish(tideOffer.sku)],
      },
    ],
    concepts: [
      ...edition.filter(item => item.id !== tideOffer.id).map(item => ({
        href: item.href, name: item.name, caption: item.title, photo: item.thumb, categories: editionDepartments(item.id).map(shelfCategorySlug),
      })),
      ...concepts.map(item => ({ href: '/preview/items/' + item.id, name: item.name, caption: conceptDescription(item.id, locale),
        photo: '/product/reference-asset-' + item.image + '.webp', categories: [shelfCategorySlug('home')] })),
    ],
  };
}

export function browseCatalog(params: URLSearchParams, locale: StorefrontLocale) {
  return matchCatalog(params, locale, catalogFixtures(locale));
}
