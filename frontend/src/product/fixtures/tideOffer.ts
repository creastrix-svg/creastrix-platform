import { tideOfferPath } from '../routes';
import { connectedProduct } from '../catalog/localCatalog';
import { variantById } from './loop';

// Local storefront fixture, not inventory or a quote from a commerce backend.
export const tideOffer = {
  id: 'tide', sku: 'tide-cobalt', colorId: 'cobalt',
  get name() { return connectedProduct('tide')?.name ?? 'Tide'; },
  get unitCents() { return connectedProduct('tide')?.variants[0].cents ?? 1800; },
  get previousCents() { return connectedProduct('tide')?.variants[0].previousCents; },
  get stock() { return connectedProduct('tide')?.variants[0].stock ?? 18; },
  get finish() { return connectedProduct('tide')?.variants[0] ?? variantById('cobalt'); },
  href: tideOfferPath,
  get media() { return connectedProduct('tide')?.variants[0] ?? {photos: ['/shop-edition/tide.webp'], model: null}; },
  get photo() { return this.media.photos[0] ?? ''; },
  get thumb() { return connectedProduct('tide') ? this.photo : '/shop-edition/tide-small.webp'; },
} as const;
