import type { StorefrontLocale } from './locale';

type PricingLabels = {
  from: (amount: string) => string;
  fee: (amount: string) => string;
  unitTotal: string;
  base: string;
};

export const loopPricingCopy: Record<StorefrontLocale, PricingLabels> = {
  de: { from: a => `Personalisierung ab ${a} pro Stück`, fee: a => `Personalisierung: +${a} pro Stück`, unitTotal: 'Preis pro Stück', base: 'Grundpreis' },
  en: { from: a => `Personalisation from ${a} per item`, fee: a => `Personalisation: +${a} per item`, unitTotal: 'Price per item', base: 'Base price' },
  ru: { from: a => `Персонализация от ${a} за штуку`, fee: a => `Персонализация: +${a} за штуку`, unitTotal: 'Цена за штуку', base: 'Базовая цена' },
  pl: { from: a => `Personalizacja od ${a} za sztukę`, fee: a => `Personalizacja: +${a} za sztukę`, unitTotal: 'Cena za sztukę', base: 'Cena bazowa' },
  es: { from: a => `Personalización desde ${a} por unidad`, fee: a => `Personalización: +${a} por unidad`, unitTotal: 'Precio por unidad', base: 'Precio base' },
  fr: { from: a => `Personnalisation à partir de ${a} par article`, fee: a => `Personnalisation : +${a} par article`, unitTotal: 'Prix par article', base: 'Prix de base' },
  it: { from: a => `Personalizzazione da ${a} al pezzo`, fee: a => `Personalizzazione: +${a} al pezzo`, unitTotal: 'Prezzo al pezzo', base: 'Prezzo base' },
};
