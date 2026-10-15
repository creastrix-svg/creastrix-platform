export const storefrontLocales = ['de', 'en', 'ru', 'pl', 'es', 'fr', 'it'] as const;
export type StorefrontLocale = typeof storefrontLocales[number];
export const isStorefrontLocale = (value: string): value is StorefrontLocale => storefrontLocales.some(locale => locale === value);
