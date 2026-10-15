import { describe, expect, it } from 'vitest';
import { additionalCopy } from './copy.additional';
import { productCopy } from './copy';
import { storefrontLocales } from './locale';

describe('complete storefront translations', () => {
  it('keeps the seven storefront locales separate from the authentication language contract', () => {
    expect(storefrontLocales).toEqual(['de', 'en', 'ru', 'pl', 'es', 'fr', 'it']);
    expect(Object.keys(productCopy).sort()).toEqual([...storefrontLocales].sort());
  });

  it.each(Object.entries(additionalCopy))('%s provides every copy key and all three translated features', (_locale, copy) => {
    expect(Object.keys(copy).sort()).toEqual(Object.keys(productCopy.en).sort());
    for (const value of Object.values(copy)) {
      if (Array.isArray(value)) {
        expect(value).toHaveLength(3);
        expect(value.every(text => text.trim().length > 0)).toBe(true);
        expect(value).not.toEqual(productCopy.en.features);
      } else {
        expect(value.trim().length).toBeGreaterThan(0);
      }
    }
  });

  it.each([
    ['ru', 'Ваша корзина', 'Язык', '3D-модель', 'С вашим характером.'],
    ['pl', 'Twój koszyk', 'Język', 'Model 3D', 'Z twoim charakterem.'],
    ['es', 'Tu cesta', 'Idioma', 'Modelo 3D', 'Tu toque personal.'],
    ['fr', 'Votre panier', 'Langue', 'Modèle 3D', 'Votre touche personnelle.'],
    ['it', 'Il tuo carrello', 'Lingua', 'Modello 3D', 'Il tuo tocco personale.'],
  ] as const)('%s has localised navigation, media and the selected slogan', (locale, cart, language, model, slogan) => {
    expect(productCopy[locale]).toMatchObject({ cart, language, model, personalise: slogan });
    expect(productCopy[locale].seller).toContain('Creastrix');
    expect(productCopy[locale].studio).toContain('Loop Studio');
    expect(productCopy[locale].cartBoundary).not.toBe(productCopy.en.cartBoundary);
    expect(productCopy[locale].personalBody).not.toBe(productCopy.en.personalBody);
  });
});
