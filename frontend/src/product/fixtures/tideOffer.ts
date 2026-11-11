import { tideOfferPath } from '../routes';

// Local storefront fixture, not inventory or a quote from a commerce backend.
export const tideOffer = {
  id: 'tide', sku: 'tide-cobalt', colorId: 'cobalt', unitCents: 1800, stock: 18,
  href: tideOfferPath, photo: '/shop-edition/tide.webp', thumb: '/shop-edition/tide-small.webp',
} as const;
