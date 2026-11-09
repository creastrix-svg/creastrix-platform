import type { StorefrontLocale } from './locale';

interface CatalogCopy {
  customOrMade: string;
  title: string; intro: string; finishes: string;
  sale: string; sort: string; featured: string; priceAsc: string; priceDesc: string;
  reset: string; conceptsTitle: string; conceptsIntro: string;
  pageHeading: string; allItems: string; filters: string; activeFilters: string; results: string; showResults: string;
  format: string; custom: string; technique: string; color: string;
  ready: string; madeToOrder: string; productionFile: string; concept: string;
  withPersonalization: string; withoutPersonalization: string; print3d: string;
  stockDesc: string; nameAsc: string; emptyTitle: string; emptyHint: string; productDetails: string;
}

export const catalogCopy: Record<StorefrontLocale, CatalogCopy> = {
  en: {
    customOrMade: 'To personalise or make',
    showResults: 'Show results',
    activeFilters: 'Active filters',
    pageHeading: 'Products & designs', allItems: 'All products & designs', filters: 'Filters', results: 'Results',
    format: 'Item type', custom: 'Personalisation', technique: 'Making technique', color: 'Colour',
    ready: 'Ready-made', madeToOrder: 'Made to order', productionFile: 'Production files', concept: 'Design concepts',
    withPersonalization: 'Can be personalised', withoutPersonalization: 'No personalisation', print3d: '3D printing',
    stockDesc: 'Most in stock', nameAsc: 'Name: A–Z', emptyTitle: 'No matches', emptyHint: 'Try removing a filter to see more.', productDetails: 'Ø 13 × 12 cm',
    title: 'Catalog', intro: 'Browse products and design concepts by colour, price or personalisation options.',
    finishes: 'Colour options',
    sale: 'On sale', sort: 'Sort by', featured: 'Featured', priceAsc: 'Price: low to high', priceDesc: 'Price: high to low',
    reset: 'Clear filters', conceptsTitle: 'Design concepts', conceptsIntro: 'Explore the materials and shapes. These designs are not available to order.',
  },
  de: {
    customOrMade: 'Zum Personalisieren oder Fertigen',
    showResults: 'Ergebnisse anzeigen',
    activeFilters: 'Aktive Filter',
    pageHeading: 'Produkte & Entwürfe', allItems: 'Alle Produkte & Entwürfe', filters: 'Filter', results: 'Ergebnisse',
    format: 'Angebotsart', custom: 'Personalisierung', technique: 'Fertigungsverfahren', color: 'Farbe',
    ready: 'Fertige Produkte', madeToOrder: 'Auf Bestellung gefertigt', productionFile: 'Fertigungsdateien', concept: 'Designentwürfe',
    withPersonalization: 'Personalisierbar', withoutPersonalization: 'Ohne Personalisierung', print3d: '3D-Druck',
    stockDesc: 'Höchster Bestand zuerst', nameAsc: 'Name: A–Z', emptyTitle: 'Keine Treffer', emptyHint: 'Entferne einen Filter, um mehr zu sehen.', productDetails: 'Ø 13 × 12 cm',
    title: 'Katalog', intro: 'Entdecke Produkte und Designentwürfe nach Farbe, Preis oder Personalisierung.',
    finishes: 'Farbvarianten',
    sale: 'Reduziert', sort: 'Sortieren nach', featured: 'Empfohlen', priceAsc: 'Preis aufsteigend', priceDesc: 'Preis absteigend',
    reset: 'Filter zurücksetzen', conceptsTitle: 'Designkonzepte', conceptsIntro: 'Entdecke Formen und Materialien. Diese Entwürfe stehen nicht zum Verkauf.',
  },
  ru: {
    customOrMade: 'Для персонализации или изготовления',
    showResults: 'Показать результаты',
    activeFilters: 'Выбрано фильтров',
    pageHeading: 'Товары и идеи', allItems: 'Все товары и идеи', filters: 'Фильтры', results: 'Найдено',
    format: 'Тип предложения', custom: 'Персонализация', technique: 'Способ изготовления', color: 'Цвет',
    ready: 'Готовые изделия', madeToOrder: 'Под заказ', productionFile: 'Файлы для изготовления', concept: 'Дизайн-концепты',
    withPersonalization: 'Можно персонализировать', withoutPersonalization: 'Без персонализации', print3d: '3D-печать',
    stockDesc: 'Сначала с большим запасом', nameAsc: 'По алфавиту', emptyTitle: 'Ничего не найдено', emptyHint: 'Снимите один из фильтров, чтобы увидеть больше.', productDetails: 'Ø 13 × 12 см',
    title: 'Каталог', intro: 'Ищите товары и дизайнерские идеи по цвету, цене и возможностям персонализации.',
    finishes: 'Варианты цвета',
    sale: 'Со скидкой', sort: 'Сортировка', featured: 'Рекомендуемые', priceAsc: 'Сначала дешевле', priceDesc: 'Сначала дороже',
    reset: 'Сбросить фильтры', conceptsTitle: 'Дизайн-концепты', conceptsIntro: 'Рассмотрите формы и материалы. Эти концепты пока нельзя заказать.',
  },
  pl: {
    customOrMade: 'Do personalizacji lub wykonania',
    showResults: 'Pokaż wyniki',
    activeFilters: 'Aktywne filtry',
    pageHeading: 'Produkty i projekty', allItems: 'Wszystkie produkty i projekty', filters: 'Filtry', results: 'Wyniki',
    format: 'Rodzaj oferty', custom: 'Personalizacja', technique: 'Technika wykonania', color: 'Kolor',
    ready: 'Gotowe produkty', madeToOrder: 'Na zamówienie', productionFile: 'Pliki produkcyjne', concept: 'Projekty koncepcyjne',
    withPersonalization: 'Z opcją personalizacji', withoutPersonalization: 'Bez personalizacji', print3d: 'Druk 3D',
    stockDesc: 'Największy zapas', nameAsc: 'Nazwa: A–Z', emptyTitle: 'Brak wyników', emptyHint: 'Usuń jeden z filtrów, aby zobaczyć więcej.', productDetails: 'Ø 13 × 12 cm',
    title: 'Katalog', intro: 'Przeglądaj produkty i projekty według koloru, ceny lub opcji personalizacji.',
    finishes: 'Warianty kolorystyczne',
    sale: 'Promocja', sort: 'Sortuj według', featured: 'Polecane', priceAsc: 'Cena rosnąco', priceDesc: 'Cena malejąco',
    reset: 'Wyczyść filtry', conceptsTitle: 'Projekty koncepcyjne', conceptsIntro: 'Poznaj formy i materiały. Te projekty nie są dostępne w sprzedaży.',
  },
  es: {
    customOrMade: 'Para personalizar o fabricar',
    showResults: 'Ver resultados',
    activeFilters: 'Filtros activos',
    pageHeading: 'Productos y diseños', allItems: 'Todos los productos y diseños', filters: 'Filtros', results: 'Resultados',
    format: 'Tipo de artículo', custom: 'Personalización', technique: 'Técnica de fabricación', color: 'Color',
    ready: 'Productos terminados', madeToOrder: 'Fabricados por encargo', productionFile: 'Archivos de fabricación', concept: 'Conceptos de diseño',
    withPersonalization: 'Personalizable', withoutPersonalization: 'Sin personalización', print3d: 'Impresión 3D',
    stockDesc: 'Más existencias primero', nameAsc: 'Nombre: A–Z', emptyTitle: 'No hay resultados', emptyHint: 'Quita un filtro para ver más opciones.', productDetails: 'Ø 13 × 12 cm',
    title: 'Catálogo', intro: 'Explora productos y diseños por color, precio u opciones de personalización.',
    finishes: 'Opciones de color',
    sale: 'En oferta', sort: 'Ordenar por', featured: 'Destacados', priceAsc: 'De menor a mayor precio', priceDesc: 'De mayor a menor precio',
    reset: 'Borrar filtros', conceptsTitle: 'Conceptos de diseño', conceptsIntro: 'Descubre formas y materiales. Estos diseños no están a la venta.',
  },
  fr: {
    customOrMade: 'À personnaliser ou à fabriquer',
    showResults: 'Voir les résultats',
    activeFilters: 'Filtres actifs',
    pageHeading: 'Produits et créations', allItems: 'Tous les produits et créations', filters: 'Filtres', results: 'Résultats',
    format: 'Type d’article', custom: 'Personnalisation', technique: 'Technique de fabrication', color: 'Couleur',
    ready: 'Produits finis', madeToOrder: 'Fabriqués sur commande', productionFile: 'Fichiers de fabrication', concept: 'Concepts de design',
    withPersonalization: 'Personnalisable', withoutPersonalization: 'Sans personnalisation', print3d: 'Impression 3D',
    stockDesc: 'Stock décroissant', nameAsc: 'Nom : A–Z', emptyTitle: 'Aucun résultat', emptyHint: 'Retirez un filtre pour voir plus de choix.', productDetails: 'Ø 13 × 12 cm',
    title: 'Catalogue', intro: 'Découvrez les produits et les créations par coloris, prix ou possibilités de personnalisation.',
    finishes: 'Coloris',
    sale: 'En promotion', sort: 'Trier par', featured: 'Notre sélection', priceAsc: 'Prix croissant', priceDesc: 'Prix décroissant',
    reset: 'Effacer les filtres', conceptsTitle: 'Concepts de design', conceptsIntro: 'Découvrez les formes et les matières. Ces créations ne sont pas en vente.',
  },
  it: {
    customOrMade: 'Da personalizzare o realizzare',
    showResults: 'Mostra risultati',
    activeFilters: 'Filtri attivi',
    pageHeading: 'Prodotti e progetti', allItems: 'Tutti i prodotti e i progetti', filters: 'Filtri', results: 'Risultati',
    format: 'Tipo di articolo', custom: 'Personalizzazione', technique: 'Tecnica di produzione', color: 'Colore',
    ready: 'Prodotti finiti', madeToOrder: 'Realizzati su ordinazione', productionFile: 'File di produzione', concept: 'Concept di design',
    withPersonalization: 'Personalizzabile', withoutPersonalization: 'Senza personalizzazione', print3d: 'Stampa 3D',
    stockDesc: 'Maggiore disponibilità', nameAsc: 'Nome: A–Z', emptyTitle: 'Nessun risultato', emptyHint: 'Rimuovi un filtro per vedere altre opzioni.', productDetails: 'Ø 13 × 12 cm',
    title: 'Catalogo', intro: 'Esplora prodotti e progetti per colore, prezzo o possibilità di personalizzazione.',
    finishes: 'Varianti di colore',
    sale: 'In offerta', sort: 'Ordina per', featured: 'In evidenza', priceAsc: 'Prezzo crescente', priceDesc: 'Prezzo decrescente',
    reset: 'Cancella filtri', conceptsTitle: 'Concept di design', conceptsIntro: 'Esplora forme e materiali. Questi progetti non sono in vendita.',
  },
};
