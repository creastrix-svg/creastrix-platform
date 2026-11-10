import type { StorefrontLocale } from '../locale';

export const shelfFilters = ['all', 'gifts', 'home', 'carry', 'desk', 'kitchen', 'decor'] as const;
export type ShelfFilter = typeof shelfFilters[number];
type ShelfCopy = Record<ShelfFilter, string> & {
  types: string; selected: string; preview: string; shop: string;
};
export const shelfCopy: Record<StorefrontLocale, ShelfCopy> = {
  en: { gifts: 'Gifts', kitchen: 'Kitchen', decor: 'Décor', all: 'All', home: 'Home', carry: 'Accessories', desk: 'Desk', types: 'Filter the selection', selected: 'Items shown', preview: 'Design concept', shop: 'View product' },
  de: { gifts: 'Geschenke', kitchen: 'Küche', decor: 'Deko', all: 'Alle', home: 'Wohnen', carry: 'Accessoires', desk: 'Schreibtisch', types: 'Auswahl filtern', selected: 'Angezeigte Artikel', preview: 'Designentwurf', shop: 'Produkt ansehen' },
  ru: { gifts: 'Подарки', kitchen: 'Кухня', decor: 'Декор', all: 'Все', home: 'Для дома', carry: 'Аксессуары', desk: 'Рабочий стол', types: 'Фильтр подборки', selected: 'Показано предметов', preview: 'Дизайн-концепт', shop: 'К товару' },
  pl: { gifts: 'Prezenty', kitchen: 'Kuchnia', decor: 'Dekoracje', all: 'Wszystkie', home: 'Do domu', carry: 'Akcesoria', desk: 'Na biurko', types: 'Filtruj wybór', selected: 'Liczba pozycji', preview: 'Projekt koncepcyjny', shop: 'Zobacz produkt' },
  es: { gifts: 'Regalos', kitchen: 'Cocina', decor: 'Decoración', all: 'Todo', home: 'Hogar', carry: 'Accesorios', desk: 'Escritorio', types: 'Filtrar selección', selected: 'Artículos mostrados', preview: 'Concepto de diseño', shop: 'Ver producto' },
  fr: { gifts: 'Cadeaux', kitchen: 'Cuisine', decor: 'Déco', all: 'Tout', home: 'Maison', carry: 'Accessoires', desk: 'Bureau', types: 'Filtrer la sélection', selected: 'Articles affichés', preview: 'Projet de design', shop: 'Voir le produit' },
  it: { gifts: 'Regali', kitchen: 'Cucina', decor: 'Decorazioni', all: 'Tutti', home: 'Casa', carry: 'Accessori', desk: 'Scrivania', types: 'Filtra la selezione', selected: 'Articoli mostrati', preview: 'Concept di design', shop: 'Vedi il prodotto' },
};
