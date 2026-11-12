import { concepts as loopConcepts, loopVariants } from '../fixtures/loop';
import { conceptItems } from '../fixtures/conceptItems';
import { connectedEntries, connectedOffer } from '../catalog/localCatalog';
import { permanentCatalogMode } from '../catalog/permanentCatalog';
import { readCartSku, type CartSku } from '../fixtures/cartSkus';
import { discountPercent, swatchBackground } from '../productDisplay';
import { productCopy } from '../copy';
import { editionItem, editionItems } from '../fixtures/editionCatalog';
import { editionDepartments, offerDepartments, shelfCategorySlug } from '../home/homeFeatured';
import { shelfCopy, shelfFilters } from '../home/homeShelfCopy';
import type { StorefrontLocale } from '../locale';
import { canPersonalize, studioProducts } from '../studio/studioProducts';
import { tideOffer } from '../fixtures/tideOffer';
import { matchCatalog, type CatalogData } from './catalogBrowse';
import { catalogCopy } from './catalogCopy';

/** Project the existing demo facts and routes into browse data. The matcher
 * and cards never have to recognise a particular product or SKU. */
function catalogFixtures(locale: StorefrontLocale): CatalogData {
  const categories = shelfFilters.filter(id => id !== 'all').map(id => ({ id: shelfCategorySlug(id), label: shelfCopy[locale][id] }));
  const techniques = [{ id:'3d-print', label:catalogCopy[locale].print3d }];
  const live = connectedEntries();
  if (live) return {
    categories, techniques,
    offers: live.filter(e => e.kind === 'offer').map(e => {
      const product = connectedOffer(e.id)!;
      return {href:e.href, reviewsHref:e.href + '/reviews', name:product.name,
        collectionId:e.collectionId, collectionName:e.collectionName,
        details:[e.title[locale], !permanentCatalogMode && e.id === 'loop' ? catalogCopy[locale].productDetails : e.material[locale]].filter(Boolean).join(' · '),
        categories:e.categories, format:'ready', personalizable:product.personalizable, technique:!permanentCatalogMode && e.id === 'loop' ? '3d-print' : undefined,
        finishes:product.variants.map(v => ({color:{id:v.id,label:v.name[locale],swatch:swatchBackground(v.colors)},
          cents:v.cents, previousCents:v.previousCents, discount:discountPercent(v.cents,v.previousCents), stock:v.stock,
          photo:v.photos[0] ?? '', search:product.variants.length > 1 ? '?variant=' + v.id : ''}))};
    }),
    concepts: live.filter(e => e.kind !== 'offer').map(e => ({
      href:e.href, name:e.name, caption:e.title[locale], photo:e.thumb ?? '', categories:e.categories,
      collectionId:e.collectionId, collectionName:e.collectionName,
    })),
  };
  const edition = editionItems(locale), tide = editionItem(tideOffer.id, locale)!;
  const loop = studioProducts.loop;
  const loopCollection = { collectionId: 'loop', collectionName: 'Loop' };
  const finish = (sku: CartSku) => {
    const { color, cents, previousCents, stock, photo, search } = readCartSku(sku);
    return { color: { id: color.id, label: color.name[locale], swatch: swatchBackground(color.colors) },
      cents, previousCents, discount: discountPercent(cents, previousCents), stock, photo, search };
  };
  return {
    categories, techniques,
    offers: [
      {
        ...loopCollection, href: '/products/loop', reviewsHref: '/products/loop/reviews', name: loop.name,
        details: productCopy[locale].subtitle + ' · ' + catalogCopy[locale].productDetails,
        categories: offerDepartments.map(shelfCategorySlug), format: loop.readyMade ? 'ready' : 'made-to-order',
        personalizable: canPersonalize(loop), technique: '3d-print',
        finishes: loopVariants().map(v => finish(v.id)),
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
      ...conceptItems(locale).map(item => ({ ...(loopConcepts.some(member => member.id === item.id) ? loopCollection : {}),
        href: item.href, name: item.name, caption: item.title,
        photo: item.thumb, categories: [shelfCategorySlug('home')] })),
    ],
  };
}

export function browseCatalog(params: URLSearchParams, locale: StorefrontLocale) {
  return matchCatalog(params, locale, catalogFixtures(locale));
}
