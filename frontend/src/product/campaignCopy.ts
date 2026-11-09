import type { StorefrontLocale } from './locale';

type CampaignCopy = {
  edition: string; title: string; material: string; photoAlt: string;
  studio: string; studioTitle: string; studioAction: string;
  featured: string; seeSelection: string;
};

export const campaignCopy: Record<StorefrontLocale, CampaignCopy> = {
  en: {
    edition: 'Discover', title: 'Find your new favourite', material: 'Ready-made pieces, designs to personalise and ideas to make.',
    photoAlt: 'Amber table lamp, blue ceramic cup and a dark walnut tray on a terracotta set',
    studio: 'Personalised & made to order', studioTitle: 'Give it your signature', studioAction: 'Explore the options',
    featured: 'Popular picks', seeSelection: 'Browse the catalog',
  },
  de: {
    edition: 'Entdecken', title: 'Finde dein neues Lieblingsstück', material: 'Fertige Produkte, Stücke zum Gestalten und Entwürfe zum Anfertigen.',
    photoAlt: 'Bernsteinfarbene Tischleuchte, blaue Keramiktasse und ein Tablett aus Walnussholz vor Terrakotta',
    studio: 'Personalisierung & Anfertigung', studioTitle: 'Mit deiner Handschrift', studioAction: 'Möglichkeiten entdecken',
    featured: 'Beliebte Stücke', seeSelection: 'Im Katalog stöbern',
  },
  ru: {
    edition: 'Открытия', title: 'Найдите новую любимую вещь', material: 'Готовые вещи, предметы для персонализации и проекты для изготовления.',
    photoAlt: 'Янтарная настольная лампа, синяя керамическая чашка и ореховый поднос на терракотовом фоне',
    studio: 'Персонализация и изготовление', studioTitle: 'С вашим характером', studioAction: 'Посмотреть варианты',
    featured: 'Популярное', seeSelection: 'Посмотреть в каталоге',
  },
  fr: {
    edition: 'Découvertes', title: 'Votre prochain coup de cœur', material: 'Des objets prêts à acheter, à personnaliser ou à faire fabriquer.',
    photoAlt: 'Lampe en verre ambré, tasse bleue et plateau en noyer sur fond terracotta',
    studio: 'Personnalisation et fabrication', studioTitle: 'À votre image', studioAction: 'Découvrir les possibilités',
    featured: 'Les favoris', seeSelection: 'Parcourir le catalogue',
  },
  es: {
    edition: 'Descubre', title: 'Encuentra tu próxima pieza favorita', material: 'Piezas listas para comprar, objetos para personalizar y diseños para fabricar.',
    photoAlt: 'Lámpara de vidrio ámbar, taza azul y bandeja de nogal sobre un fondo terracota',
    studio: 'Personalización y fabricación', studioTitle: 'Con tu toque personal', studioAction: 'Explorar opciones',
    featured: 'Destacados', seeSelection: 'Explorar el catálogo',
  },
  it: {
    edition: 'Scoperte', title: 'Trova il tuo prossimo preferito', material: 'Oggetti già pronti, pezzi da personalizzare e progetti da realizzare.',
    photoAlt: 'Lampada da tavolo ambrata, tazzina blu e vassoio in noce su fondo terracotta',
    studio: 'Personalizzazione e produzione', studioTitle: 'Con la tua firma', studioAction: 'Esplora le possibilità',
    featured: 'In evidenza', seeSelection: 'Esplora il catalogo',
  },
  pl: {
    edition: 'Odkryj', title: 'Znajdź coś, co pokochasz', material: 'Gotowe produkty, przedmioty do personalizacji i projekty do wykonania.',
    photoAlt: 'Bursztynowa lampa, kobaltowa filiżanka i orzechowa taca na terakotowym tle',
    studio: 'Personalizacja i wykonanie', studioTitle: 'Z Twoim akcentem', studioAction: 'Zobacz możliwości',
    featured: 'Popularne', seeSelection: 'Przeglądaj katalog',
  },
};
