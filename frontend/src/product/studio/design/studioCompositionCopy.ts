import type { StorefrontLocale } from '../../locale';

const en = {
  objects: 'Objects in this area', text: 'Text', image: 'Picture', emptyText: 'Empty text', loadingImage: 'Loading picture…',
  addText: 'Add text', addImage: 'Add picture', deleteObject: 'Delete selected object',
  forward: 'Move one layer forward', backward: 'Move one layer backward',
  empty: 'Add text or a picture to begin.', limit: 'Up to 8 objects per area in this preview.',
  fit: 'Picture fit', free: 'Free size', contain: 'Fit inside', cover: 'Fill and crop',
  fitHint: 'Fit inside shows the whole picture. Fill and crop covers the area without stretching.',
  panelPreview: 'Text and pictures on this panel are a visual composition only. No cutting paths or bridge-strength checks are generated.',
};
export type StudioCompositionCopy = { [Key in keyof typeof en]: string };

export const studioCompositionCopy: Record<StorefrontLocale, StudioCompositionCopy> = {
  en,
  de: {
    objects: 'Objekte in diesem Bereich', text: 'Text', image: 'Bild', emptyText: 'Leerer Text', loadingImage: 'Bild wird geladen…',
    addText: 'Text hinzufügen', addImage: 'Bild hinzufügen', deleteObject: 'Ausgewähltes Objekt löschen',
    forward: 'Eine Ebene nach vorne', backward: 'Eine Ebene nach hinten',
    empty: 'Füge zunächst Text oder ein Bild hinzu.', limit: 'Bis zu 8 Objekte pro Bereich in dieser Vorschau.',
    fit: 'Bildanpassung', free: 'Freie Größe', contain: 'Einpassen', cover: 'Füllen und zuschneiden',
    fitHint: 'Einpassen zeigt das ganze Bild. Füllen und zuschneiden deckt den Bereich ohne Verzerrung ab.',
    panelPreview: 'Text und Bilder auf diesem Panel sind nur eine visuelle Komposition. Es werden weder Schnittpfade erzeugt noch die Festigkeit der Verbindungen geprüft.',
  },
  ru: {
    objects: 'Объекты в этой зоне', text: 'Текст', image: 'Изображение', emptyText: 'Пустой текст', loadingImage: 'Загрузка изображения…',
    addText: 'Добавить текст', addImage: 'Добавить изображение', deleteObject: 'Удалить выбранный объект',
    forward: 'На один слой вперёд', backward: 'На один слой назад',
    empty: 'Добавьте текст или изображение.', limit: 'В этом предпросмотре — до 8 объектов на зону.',
    fit: 'Размещение изображения', free: 'Свободный размер', contain: 'Вписать целиком', cover: 'Заполнить с обрезкой',
    fitHint: '«Вписать целиком» показывает всё изображение. Заполнение обрезает края без искажения пропорций.',
    panelPreview: 'Текст и изображения на панели — только визуальная композиция. Контуры для резки не создаются, прочность перемычек не проверяется.',
  },
  pl: {
    objects: 'Obiekty w tym obszarze', text: 'Tekst', image: 'Obraz', emptyText: 'Pusty tekst', loadingImage: 'Wczytywanie obrazu…',
    addText: 'Dodaj tekst', addImage: 'Dodaj obraz', deleteObject: 'Usuń wybrany obiekt',
    forward: 'O warstwę do przodu', backward: 'O warstwę do tyłu',
    empty: 'Dodaj tekst lub obraz, aby zacząć.', limit: 'Do 8 obiektów na obszar w tym podglądzie.',
    fit: 'Dopasowanie obrazu', free: 'Dowolny rozmiar', contain: 'Zmieść w całości', cover: 'Wypełnij i przytnij',
    fitHint: 'Dopasowanie pokazuje cały obraz. Wypełnienie pokrywa obszar bez rozciągania, przycinając krawędzie.',
    panelPreview: 'Tekst i obrazy na panelu są jedynie kompozycją wizualną. Ścieżki cięcia nie są tworzone, a wytrzymałość mostków nie jest sprawdzana.',
  },
  es: {
    objects: 'Objetos en esta zona', text: 'Texto', image: 'Imagen', emptyText: 'Texto vacío', loadingImage: 'Cargando imagen…',
    addText: 'Añadir texto', addImage: 'Añadir imagen', deleteObject: 'Eliminar objeto seleccionado',
    forward: 'Avanzar una capa', backward: 'Retroceder una capa',
    empty: 'Añade texto o una imagen para empezar.', limit: 'Hasta 8 objetos por zona en esta vista previa.',
    fit: 'Ajuste de imagen', free: 'Tamaño libre', contain: 'Encajar completa', cover: 'Rellenar y recortar',
    fitHint: 'Encajar muestra la imagen completa. Rellenar cubre la zona sin deformar, recortando los bordes.',
    panelPreview: 'El texto y las imágenes del panel son solo una composición visual. No se generan trazados de corte ni se comprueba la resistencia de los puentes.',
  },
  fr: {
    objects: 'Objets dans cette zone', text: 'Texte', image: 'Image', emptyText: 'Texte vide', loadingImage: 'Chargement de l’image…',
    addText: 'Ajouter du texte', addImage: 'Ajouter une image', deleteObject: 'Supprimer l’objet sélectionné',
    forward: 'Avancer d’un calque', backward: 'Reculer d’un calque',
    empty: 'Ajoutez du texte ou une image pour commencer.', limit: 'Jusqu’à 8 objets par zone dans cet aperçu.',
    fit: 'Ajustement de l’image', free: 'Taille libre', contain: 'Afficher en entier', cover: 'Remplir et recadrer',
    fitHint: 'L’ajustement affiche toute l’image. Le remplissage couvre la zone sans déformation en recadrant les bords.',
    panelPreview: 'Le texte et les images sur ce panneau sont uniquement une composition visuelle. Aucun tracé de découpe ni contrôle de résistance des ponts n’est généré.',
  },
  it: {
    objects: 'Oggetti in quest’area', text: 'Testo', image: 'Immagine', emptyText: 'Testo vuoto', loadingImage: 'Caricamento immagine…',
    addText: 'Aggiungi testo', addImage: 'Aggiungi immagine', deleteObject: 'Elimina l’oggetto selezionato',
    forward: 'Avanti di un livello', backward: 'Indietro di un livello',
    empty: 'Aggiungi testo o un’immagine per iniziare.', limit: 'Fino a 8 oggetti per area in questa anteprima.',
    fit: 'Adattamento immagine', free: 'Dimensione libera', contain: 'Mostra intera', cover: 'Riempi e ritaglia',
    fitHint: 'L’adattamento mostra l’intera immagine. Il riempimento copre l’area senza deformare, ritagliando i bordi.',
    panelPreview: 'Testo e immagini su questo pannello sono solo una composizione visiva. Non vengono generati tracciati di taglio né verificata la resistenza dei ponti.',
  },
};
