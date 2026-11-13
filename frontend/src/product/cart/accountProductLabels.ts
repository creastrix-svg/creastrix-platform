export const accountProductLabels = {
  en: ['Template version', 'Source image', 'Unavailable'], de: ['Vorlagenversion', 'Quellbild', 'Nicht verfügbar'],
  ru: ['Версия шаблона', 'Исходное изображение', 'Недоступно'], pl: ['Wersja szablonu', 'Obraz źródłowy', 'Niedostępne'],
  es: ['Versión de la plantilla', 'Imagen original', 'No disponible'], fr: ['Version du modèle', 'Image source', 'Indisponible'],
  it: ['Versione del modello', 'Immagine originale', 'Non disponibile'],
} as const;

export const designLabels={
  en:['View applied design','When you sign in, designs in your guest cart transfer to your test account with their pictures and custom TTF/OTF fonts. Drafts stay in this browser.'],
  de:['Angewandtes Design ansehen','Bei der Anmeldung werden Designs im Gastwarenkorb mit Bildern und eigenen TTF-/OTF-Schriften ins Testkonto übertragen. Entwürfe bleiben in diesem Browser.'],
  ru:['Посмотреть применённый дизайн','При входе дизайны из гостевой корзины переносятся в тестовый аккаунт вместе с изображениями и шрифтами TTF/OTF. Черновики остаются в этом браузере.'],
  pl:['Zobacz zastosowany projekt','Po zalogowaniu projekty z koszyka gościa wraz z obrazami i własnymi czcionkami TTF/OTF trafiają na konto testowe. Szkice zostają w przeglądarce.'],
  es:['Ver diseño aplicado','Al iniciar sesión, los diseños de tu cesta de invitado se transfieren con sus imágenes y fuentes TTF/OTF a tu cuenta de prueba. Los borradores se quedan en este navegador.'],
  fr:['Voir la création appliquée','À la connexion, les créations du panier invité sont transférées avec leurs images et polices TTF/OTF dans le compte de test. Les brouillons restent dans ce navigateur.'],
  it:['Visualizza il design applicato','Quando accedi, i design del carrello ospite vengono trasferiti con immagini e font TTF/OTF al tuo account di prova. Le bozze restano in questo browser.'],
} as const;

export const designFontHint={
  en:'Choose a studio font or a valid TTF/OTF file (up to 2 MB).',
  de:'Wähle eine Studioschrift oder eine gültige TTF-/OTF-Datei (bis 2 MB).',
  ru:'Выберите шрифт студии или корректный файл TTF/OTF до 2 МБ.',
  pl:'Wybierz czcionkę studia lub poprawny plik TTF/OTF (do 2 MB).',
  es:'Elige una fuente del estudio o un archivo TTF/OTF válido (hasta 2 MB).',
  fr:'Choisissez une police du studio ou un fichier TTF/OTF valide (2 Mo maximum).',
  it:'Scegli un font dello studio o un file TTF/OTF valido (fino a 2 MB).',
} as const;

export const accountDesignNote={
  en:'Your images and fonts stay private. Save a design to use it later; adding it to your cart keeps a separate snapshot.',
  de:'Deine Bilder und Schriften bleiben privat. Speichere das Design für später; der Warenkorb behält eine separate Fassung.',
  ru:'Ваши рисунки и шрифты приватны. Дизайн можно сохранить на потом; в корзине останется отдельная версия.',
  pl:'Twoje obrazy i czcionki pozostają prywatne. Zapisz projekt na później; w koszyku pozostanie osobna wersja.',
  es:'Tus imágenes y fuentes son privadas. Guarda el diseño para más tarde; la cesta conserva una versión independiente.',
  fr:'Vos images et polices restent privées. Enregistrez la création pour plus tard ; le panier conserve une version indépendante.',
  it:'Le tue immagini e i tuoi caratteri restano privati. Salva il design per dopo; nel carrello rimane una versione separata.',
} as const;
