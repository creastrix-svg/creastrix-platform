import { variantById } from './catalog';
import type { VariantId } from './catalog';

// Loop's local storefront fixture, not a seller tariff or a checkout quote.
export const LOOP_DESIGN_FEE_CENTS = 500;

export function loopPrice(variant: VariantId, personalized: boolean) {
  const baseCents = variantById(variant).cents;
  const feeCents = personalized ? LOOP_DESIGN_FEE_CENTS : 0;
  return { baseCents, feeCents, unitCents: baseCents + feeCents };
}
