import type { StorefrontLocale } from '../locale';
import type { StudioOperation, StudioZoneId } from './studioProducts';

export type StudioZoneCopy = {
  intro: string;
  zoneLabel: string;
  zoneNames: Record<StudioZoneId, string>;
  zoneHints: Record<StudioZoneId, string>;
  operations: Record<StudioOperation, string>;
  unavailable: string;
  patternTab: string;
  sellerConfigured: string;
  panelRestriction: string;
  modelView: string;
  flatView: string;
  modelAlt: string;
  pickHint: string;
  flatPickHint: string;
  fontStencil: string;
  cutTextLabel: string;
  cutTextHint: string;
  cutUnsupported: string;
  cutFontHint: string;
  cutFontLoading: string;
  cutFontFailed: string;
};

/** Buyer copy describes available actions, never a manufacturing approval. */
export const studioZoneCopy: Record<StorefrontLocale, StudioZoneCopy> = {
  en: {
    intro: 'Choose an area. Add a personal touch.',
    zoneLabel: 'Where would you like to personalise?',
    zoneNames: { surface: 'Front surface', panel: 'Front panel', base: 'Base' },
    zoneHints: {
      surface: 'Add text or a picture to the marked area.',
      panel: "Choose a cut-out pattern. You can also add text or a picture to the preview.",
      base: 'Add text or a picture to the wooden base.',
    },
    operations: { print: 'Print', engrave: 'Engraving', cut: 'Cut-out' },
    unavailable: 'Personalisation is not available for this product.',
    patternTab: 'Patterns',
    sellerConfigured: 'Options available for this area',
    panelRestriction: 'Choose one of the prepared patterns for this area.',
    modelView: '3D model', flatView: '2D view',
    modelAlt: 'Interactive 3D product preview',
    pickHint: 'Tap the front panel or base to select. Drag to rotate.',
    flatPickHint: 'Tap a marked area to choose its tools.',
    fontStencil: 'Stencil',
    cutTextLabel: 'Text to cut out',
    cutTextHint: 'Your text sits in the reserved band below the pattern.',
    cutUnsupported: 'Use Latin letters A–Z, numbers, spaces or hyphens.',
    cutFontHint: 'Stencil font selected by the product author.',
    cutFontLoading: 'Loading the stencil font…',
    cutFontFailed: 'The stencil font could not be loaded. Select it below to retry.',
  },
  de: {
    intro: 'Wähle einen Bereich. Gib ihm deine persönliche Note.',
    zoneLabel: 'Welchen Bereich möchtest du gestalten?',
    zoneNames: { surface: 'Vorderseite', panel: 'Frontpaneel', base: 'Sockel' },
    zoneHints: {
      surface: 'Füge Text oder ein Bild im markierten Bereich hinzu.',
      panel: "Wähle ein Ausschnittmuster. In der Vorschau kannst du auch Text oder ein Bild hinzufügen.",
      base: 'Füge Text oder ein Bild auf dem Holzsockel hinzu.',
    },
    operations: { print: 'Druck', engrave: 'Gravur', cut: 'Ausschnitt' },
    unavailable: 'Für dieses Produkt ist keine Personalisierung verfügbar.',
    patternTab: 'Muster',
    sellerConfigured: 'Verfügbare Optionen für diesen Bereich',
    panelRestriction: 'Wähle eines der vorbereiteten Muster für diesen Bereich.',
    modelView: '3D-Modell', flatView: '2D-Ansicht',
    modelAlt: 'Interaktive 3D-Produktvorschau',
    pickHint: 'Frontpaneel oder Sockel zum Auswählen antippen. Zum Drehen ziehen.',
    flatPickHint: 'Tippe auf einen markierten Bereich, um seine Werkzeuge zu öffnen.',
    fontStencil: 'Schablone',
    cutTextLabel: 'Text zum Ausschneiden',
    cutTextHint: 'Dein Text steht im reservierten Streifen unter dem Muster.',
    cutUnsupported: 'Verwende lateinische Buchstaben A–Z, Zahlen, Leerzeichen oder Bindestriche.',
    cutFontHint: 'Vom Produktautor ausgewählte Schablonenschrift.',
    cutFontLoading: 'Schablonenschrift wird geladen…',
    cutFontFailed: 'Die Schablonenschrift konnte nicht geladen werden. Wähle sie unten aus, um es erneut zu versuchen.',
  },
  ru: {
    intro: 'Выберите область. Добавьте индивидуальности.',
    zoneLabel: 'Где добавим ваш дизайн?',
    zoneNames: { surface: 'Передняя поверхность', panel: 'Передняя панель', base: 'Основание' },
    zoneHints: {
      surface: 'Добавьте текст или изображение в отмеченную область.',
      panel: "Выберите узор для вырезания. В предпросмотр можно добавить текст или изображение.",
      base: 'Добавьте текст или изображение на деревянное основание.',
    },
    operations: { print: 'Нанесение', engrave: 'Гравировка', cut: 'Резка' },
    unavailable: 'Для этого товара персонализация недоступна.',
    patternTab: 'Узоры',
    sellerConfigured: 'Возможности этой области',
    panelRestriction: 'Выберите один из готовых узоров для этой области.',
    modelView: '3D-модель', flatView: '2D-эскиз',
    modelAlt: 'Интерактивный 3D-просмотр товара',
    pickHint: 'Нажмите на переднюю панель или основание. Потяните, чтобы повернуть.',
    flatPickHint: 'Нажмите на отмеченную область, чтобы открыть её инструменты.',
    fontStencil: 'Трафаретный',
    cutTextLabel: 'Текст для вырезания',
    cutTextHint: 'Текст размещается в отведённой полосе под узором.',
    cutUnsupported: 'Используйте латинские буквы A–Z, цифры, пробелы или дефисы.',
    cutFontHint: 'Трафаретный шрифт, выбранный автором товара.',
    cutFontLoading: 'Загружаем трафаретный шрифт…',
    cutFontFailed: 'Не удалось загрузить трафаретный шрифт. Выберите его ниже, чтобы повторить попытку.',
  },
  pl: {
    intro: 'Wybierz obszar. Dodaj osobisty akcent.',
    zoneLabel: 'Który obszar chcesz spersonalizować?',
    zoneNames: { surface: 'Przednia powierzchnia', panel: 'Przedni panel', base: 'Podstawa' },
    zoneHints: {
      surface: 'Dodaj tekst lub obraz w zaznaczonym obszarze.',
      panel: "Wybierz wzór do wycięcia. W podglądzie możesz też dodać tekst lub obraz.",
      base: 'Dodaj tekst lub obraz na drewnianej podstawie.',
    },
    operations: { print: 'Nadruk', engrave: 'Grawerowanie', cut: 'Wycinany wzór' },
    unavailable: 'Personalizacja tego produktu jest niedostępna.',
    patternTab: 'Wzory',
    sellerConfigured: 'Opcje dostępne dla tego obszaru',
    panelRestriction: 'Wybierz jeden z gotowych wzorów dla tego obszaru.',
    modelView: 'Model 3D', flatView: 'Widok 2D',
    modelAlt: 'Interaktywny podgląd produktu 3D',
    pickHint: 'Dotknij przedniego panelu lub podstawy, aby wybrać. Przeciągnij, aby obrócić.',
    flatPickHint: 'Dotknij zaznaczonego obszaru, aby otworzyć jego narzędzia.',
    fontStencil: 'Szablonowy',
    cutTextLabel: 'Tekst do wycięcia',
    cutTextHint: 'Tekst znajduje się w zarezerwowanym pasku pod wzorem.',
    cutUnsupported: 'Użyj liter łacińskich A–Z, cyfr, spacji lub łączników.',
    cutFontHint: 'Krój szablonowy wybrany przez autora produktu.',
    cutFontLoading: 'Ładowanie kroju szablonowego…',
    cutFontFailed: 'Nie udało się załadować kroju szablonowego. Wybierz go poniżej, aby spróbować ponownie.',
  },
  es: {
    intro: 'Elige una zona. Dale tu toque personal.',
    zoneLabel: '¿Qué zona quieres personalizar?',
    zoneNames: { surface: 'Superficie frontal', panel: 'Panel frontal', base: 'Base' },
    zoneHints: {
      surface: 'Añade texto o una imagen dentro de la zona marcada.',
      panel: "Elige un patrón de corte. También puedes añadir texto o una imagen a la vista previa.",
      base: 'Añade texto o una imagen a la base de madera.',
    },
    operations: { print: 'Impresión', engrave: 'Grabado', cut: 'Recorte' },
    unavailable: 'La personalización no está disponible para este producto.',
    patternTab: 'Diseños',
    sellerConfigured: 'Opciones disponibles para esta zona',
    panelRestriction: 'Elige uno de los diseños preparados para esta zona.',
    modelView: 'Modelo 3D', flatView: 'Vista 2D',
    modelAlt: 'Vista previa interactiva del producto en 3D',
    pickHint: 'Toca el panel frontal o la base para seleccionar. Arrastra para girar.',
    flatPickHint: 'Toca una zona marcada para abrir sus herramientas.',
    fontStencil: 'Plantilla',
    cutTextLabel: 'Texto para recortar',
    cutTextHint: 'El texto ocupa la franja reservada bajo el diseño.',
    cutUnsupported: 'Usa letras latinas A–Z, números, espacios o guiones.',
    cutFontHint: 'Tipografía de plantilla elegida por el autor del producto.',
    cutFontLoading: 'Cargando la tipografía de plantilla…',
    cutFontFailed: 'No se pudo cargar la tipografía de plantilla. Selecciónala abajo para volver a intentarlo.',
  },
  fr: {
    intro: 'Choisissez une zone. Ajoutez votre touche personnelle.',
    zoneLabel: 'Quelle zone souhaitez-vous personnaliser ?',
    zoneNames: { surface: 'Surface avant', panel: 'Panneau avant', base: 'Socle' },
    zoneHints: {
      surface: 'Ajoutez du texte ou une image dans la zone indiquée.',
      panel: "Choisissez un motif à découper. Vous pouvez aussi ajouter du texte ou une image à l’aperçu.",
      base: 'Ajoutez du texte ou une image sur le socle en bois.',
    },
    operations: { print: 'Impression', engrave: 'Gravure', cut: 'Découpe' },
    unavailable: 'La personnalisation est indisponible pour ce produit.',
    patternTab: 'Motifs',
    sellerConfigured: 'Options disponibles pour cette zone',
    panelRestriction: 'Choisissez un des motifs préparés pour cette zone.',
    modelView: 'Modèle 3D', flatView: 'Vue 2D',
    modelAlt: 'Aperçu interactif du produit en 3D',
    pickHint: 'Touchez le panneau avant ou le socle pour sélectionner. Faites glisser pour tourner.',
    flatPickHint: 'Touchez une zone indiquée pour ouvrir ses outils.',
    fontStencil: 'Pochoir',
    cutTextLabel: 'Texte à découper',
    cutTextHint: 'Le texte occupe la bande réservée sous le motif.',
    cutUnsupported: 'Utilisez les lettres latines A–Z, des chiffres, des espaces ou des traits d’union.',
    cutFontHint: 'Police pochoir choisie par l’auteur du produit.',
    cutFontLoading: 'Chargement de la police pochoir…',
    cutFontFailed: 'La police pochoir n’a pas pu être chargée. Sélectionnez-la ci-dessous pour réessayer.',
  },
  it: {
    intro: 'Scegli un’area. Aggiungi il tuo tocco personale.',
    zoneLabel: 'Quale area vuoi personalizzare?',
    zoneNames: { surface: 'Superficie anteriore', panel: 'Pannello anteriore', base: 'Base' },
    zoneHints: {
      surface: 'Aggiungi testo o un’immagine nell’area indicata.',
      panel: "Scegli un motivo da ritagliare. Puoi anche aggiungere testo o un’immagine all’anteprima.",
      base: 'Aggiungi testo o un’immagine alla base in legno.',
    },
    operations: { print: 'Stampa', engrave: 'Incisione', cut: 'Traforo' },
    unavailable: 'La personalizzazione non è disponibile per questo prodotto.',
    patternTab: 'Motivi',
    sellerConfigured: 'Opzioni disponibili per questa area',
    panelRestriction: 'Scegli uno dei motivi preparati per questa area.',
    modelView: 'Modello 3D', flatView: 'Vista 2D',
    modelAlt: 'Anteprima interattiva del prodotto in 3D',
    pickHint: 'Tocca il pannello anteriore o la base per selezionare. Trascina per ruotare.',
    flatPickHint: 'Tocca un’area indicata per aprire i suoi strumenti.',
    fontStencil: 'Stencil',
    cutTextLabel: 'Testo da ritagliare',
    cutTextHint: 'Il testo occupa la fascia riservata sotto il motivo.',
    cutUnsupported: 'Usa lettere latine A–Z, numeri, spazi o trattini.',
    cutFontHint: 'Carattere stencil scelto dall’autore del prodotto.',
    cutFontLoading: 'Caricamento del carattere stencil…',
    cutFontFailed: 'Impossibile caricare il carattere stencil. Selezionalo sotto per riprovare.',
  },
};
