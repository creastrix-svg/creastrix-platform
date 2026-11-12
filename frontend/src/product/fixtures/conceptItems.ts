import type { StorefrontLocale } from '../locale';
import { productCopy } from '../copy';
import { connectedEntries } from '../catalog/localCatalog';
import { concepts } from './loop';
import { localizedEntry } from './editionCatalog';

const descriptions: Record<string, Record<StorefrontLocale, string>> = {
  'loop-mini': { en: 'Compact plant pot', de: 'Kompakter Pflanztopf', ru: 'Компактный горшок', pl: 'Mała doniczka', es: 'Maceta compacta', fr: 'Pot compact', it: 'Vaso compatto' },
  'loop-wide': { en: 'Wide plant pot', de: 'Breiter Pflanztopf', ru: 'Широкий горшок', pl: 'Szeroka doniczka', es: 'Maceta ancha', fr: 'Pot large', it: 'Vaso largo' },
  'loop-tray': { en: 'Plant saucer', de: 'Untersetzer', ru: 'Подставка для горшка', pl: 'Podstawka pod doniczkę', es: 'Plato para maceta', fr: 'Soucoupe', it: 'Sottovaso' },
  'loop-tall': { en: 'Tall plant pot', de: 'Hoher Pflanztopf', ru: 'Высокий горшок', pl: 'Wysoka doniczka', es: 'Maceta alta', fr: 'Pot haut', it: 'Vaso alto' },
  'loop-duo': { en: 'Double plant pot', de: 'Doppelter Pflanztopf', ru: 'Двойной горшок', pl: 'Podwójna doniczka', es: 'Maceta doble', fr: 'Pot double', it: 'Vaso doppio' },
  'loop-stand': { en: 'Plant stand', de: 'Pflanzenständer', ru: 'Подставка для растений', pl: 'Stojak na rośliny', es: 'Soporte para plantas', fr: 'Support pour plantes', it: 'Supporto per piante' },
};
export const conceptDescription = (id: string, locale: StorefrontLocale) => descriptions[id]?.[locale] ?? productCopy[locale].concept;

export function conceptItems(locale: StorefrontLocale) {
  const live = connectedEntries();
  if (live) return live.filter(e => e.kind === 'concept').map(e => localizedEntry(e, locale));
  return concepts.map(item => {
    const photo = '/product/reference-asset-' + item.image + '.webp';
    return {id:item.id, name:item.name, title:conceptDescription(item.id, locale), material:'',
      description:productCopy[locale].conceptNote, href:'/preview/items/' + item.id, photo, thumb:photo};
  });
}
