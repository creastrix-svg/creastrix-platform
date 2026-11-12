import { variantById } from './fixtures/loop';
import type { VariantId } from './fixtures/loop';
import { connectedProduct } from './catalog/localCatalog';

// Loop's local storefront fixture, not a seller tariff or a checkout quote.
export const LOOP_DESIGN_FEE_CENTS = 500;
export const loopDesignFee = () => connectedProduct('loop')?.designFeeCents ?? LOOP_DESIGN_FEE_CENTS;

export function loopPrice(variant: VariantId, personalized: boolean) {
  const baseCents = variantById(variant).cents;
  const feeCents = personalized ? loopDesignFee() : 0;
  return { baseCents, feeCents, unitCents: baseCents + feeCents };
}
