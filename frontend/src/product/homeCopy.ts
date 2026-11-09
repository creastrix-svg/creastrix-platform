import type { StorefrontLocale } from './locale';

interface HomeCopy {
  title: string; headline: string; description: string; browse: string;
  ready: string; readyHint: string; personal: string; personalHint: string;
  concepts: string; conceptHint: string;
}

export const homeCopy = {
  en: {
    title: 'Objects & accessories', headline: 'Shop by category',
    description: 'Ready-made products, personal touches and design concepts.',
    browse: 'View full catalog',
    ready: 'Ready-made', readyHint: 'Choose a finished piece',
    personal: 'To personalise', personalHint: 'Add a name, image or motif',
    concepts: 'Design concepts', conceptHint: 'Explore sketches and design studies',
  },
  de: {
    title: 'Designobjekte und Accessoires', headline: 'Kategorien entdecken',
    description: 'Fertige Produkte, personalisierbare Artikel und Designentwürfe.',
    browse: 'Zum gesamten Katalog',
    ready: 'Fertige Produkte', readyHint: 'Direkt aus dem Sortiment',
    personal: 'Zum Gestalten', personalHint: 'Mit Namen, Bild oder Muster',
    concepts: 'Designentwürfe', conceptHint: 'Skizzen und Designstudien entdecken',
  },
  ru: {
    title: 'Дизайнерские предметы и аксессуары', headline: 'Разделы каталога',
    description: 'Готовые товары, персонализация и эскизы дизайнеров.',
    browse: 'Весь каталог',
    ready: 'Готовые товары', readyHint: 'Уже изготовлены',
    personal: 'С персонализацией', personalHint: 'Добавьте надпись или рисунок',
    concepts: 'Дизайн-концепты', conceptHint: 'Эскизы и идеи дизайнеров',
  },
  pl: {
    title: 'Przedmioty i akcesoria', headline: 'Kategorie produktów',
    description: 'Gotowe produkty, personalizacja i projekty koncepcyjne.',
    browse: 'Cały katalog',
    ready: 'Gotowe produkty', readyHint: 'Wybierz gotowy przedmiot',
    personal: 'Do personalizacji', personalHint: 'Dodaj imię, obraz lub wzór',
    concepts: 'Projekty koncepcyjne', conceptHint: 'Poznaj szkice i pomysły projektantów',
  },
  es: {
    title: 'Objetos de diseño y accesorios', headline: 'Explora por categoría',
    description: 'Productos terminados, personalización y conceptos de diseño.',
    browse: 'Ver todo el catálogo',
    ready: 'Productos terminados', readyHint: 'Elige una pieza ya terminada',
    personal: 'Para personalizar', personalHint: 'Añade un nombre, una imagen o un motivo',
    concepts: 'Conceptos de diseño', conceptHint: 'Descubre bocetos e ideas de diseño',
  },
  fr: {
    title: 'Objets design et accessoires', headline: 'Explorer par catégorie',
    description: 'Produits finis, personnalisation et concepts de design.',
    browse: 'Tout le catalogue',
    ready: 'Produits finis', readyHint: 'Choisissez un objet déjà réalisé',
    personal: 'À personnaliser', personalHint: 'Ajoutez un nom, une image ou un motif',
    concepts: 'Concepts de design', conceptHint: 'Explorez les esquisses des créateurs',
  },
  it: {
    title: 'Oggetti di design e accessori', headline: 'Esplora per categoria',
    description: 'Prodotti finiti, personalizzazione e proposte di design.',
    browse: 'Tutto il catalogo',
    ready: 'Prodotti finiti', readyHint: 'Scegli un oggetto già realizzato',
    personal: 'Da personalizzare', personalHint: 'Aggiungi un nome, un’immagine o un motivo',
    concepts: 'Concept di design', conceptHint: 'Scopri schizzi e idee di design',
  },
} satisfies Record<StorefrontLocale, HomeCopy>;
