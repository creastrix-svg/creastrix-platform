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
  en:'Only the design you add to your cart is saved to your account, including its images and fonts.',
  de:'Nur das Design, das du in den Warenkorb legst, wird mit seinen Bildern und Schriften in deinem Konto gespeichert.',
  ru:'В аккаунте сохраняется только добавленный в корзину дизайн, вместе с его изображениями и шрифтами.',
  pl:'Na koncie zapisujemy tylko projekt dodany do koszyka, wraz z jego obrazami i czcionkami.',
  es:'Solo se guarda en tu cuenta el diseño que añades a la cesta, con sus imágenes y fuentes.',
  fr:'Seule la création ajoutée au panier est enregistrée dans votre compte, avec ses images et ses polices.',
  it:'Nel tuo account viene salvato solo il design aggiunto al carrello, con le sue immagini e i suoi caratteri.',
} as const;
