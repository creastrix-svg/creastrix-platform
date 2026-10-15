import type { StorefrontLocale } from './locale';
import { concepts } from './catalog';
import { productCopy } from './copy';

const descriptions: Record<string, Record<StorefrontLocale, string>> = {
  'loop-mini': { en: 'Compact plant pot', de: 'Kompakter Pflanztopf', ru: 'Компактный горшок', pl: 'Mała doniczka', es: 'Maceta compacta', fr: 'Pot compact', it: 'Vaso compatto' },
  'loop-wide': { en: 'Wide plant pot', de: 'Breiter Pflanztopf', ru: 'Широкий горшок', pl: 'Szeroka doniczka', es: 'Maceta ancha', fr: 'Pot large', it: 'Vaso largo' },
  'loop-tray': { en: 'Plant saucer', de: 'Untersetzer', ru: 'Подставка для горшка', pl: 'Podstawka pod doniczkę', es: 'Plato para maceta', fr: 'Soucoupe', it: 'Sottovaso' },
  'loop-tall': { en: 'Tall plant pot', de: 'Hoher Pflanztopf', ru: 'Высокий горшок', pl: 'Wysoka doniczka', es: 'Maceta alta', fr: 'Pot haut', it: 'Vaso alto' },
  'loop-duo': { en: 'Double plant pot', de: 'Doppelter Pflanztopf', ru: 'Двойной горшок', pl: 'Podwójna doniczka', es: 'Maceta doble', fr: 'Pot double', it: 'Vaso doppio' },
  'loop-stand': { en: 'Plant stand', de: 'Pflanzenständer', ru: 'Подставка для растений', pl: 'Stojak na rośliny', es: 'Soporte para plantas', fr: 'Support pour plantes', it: 'Supporto per piante' },
};
const normal = (value: string, language: StorefrontLocale) => value.normalize('NFKD').replace(/\p{M}/gu, '').toLocaleLowerCase(language).trim();
export function searchExamples(query: string, language: StorefrontLocale, limit = 5) {
  const term = normal(query.slice(0, 100), language), words = term.split(/\s+/).filter(Boolean);
  const catalog = [{ id: 'loop', name: 'Loop', image: '01', description: productCopy[language].subtitle }, ...concepts.map(item => ({ ...item, description: descriptions[item.id][language] }))];
  return catalog.map((item, index) => {
    const name = normal(item.name, language), description = normal(item.description, language);
    const score = name === term ? 0 : name.startsWith(term) ? 1 : words.every(word => name.split(/\s+/).some(part => part.startsWith(word))) ? 2
      : name.includes(term) ? 3 : words.every(word => `${name} ${description}`.includes(word)) ? 4 : Infinity;
    return { item, score, index };
  }).filter(result => Number.isFinite(result.score)).sort((a, b) => a.score - b.score || a.index - b.index).slice(0, Math.max(0, Math.min(7, limit))).map(result => result.item);
}
