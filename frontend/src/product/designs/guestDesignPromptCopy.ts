import type {StorefrontLocale} from '../locale';

/** Only the synthetic local storefront currently admits design-library writes. */
export const guestDesignPromptCopy:Record<StorefrontLocale,{message:string;register:string;localOnly:string}>={
  ru:{message:'Чтобы сохранить дизайн, нужен аккаунт. Ваш рисунок и текст не пропадут при входе.',register:'Зарегистрироваться',localOnly:'В локальной витрине регистрация пока недоступна. Можно войти с тестовым аккаунтом.'},
  en:{message:'You need an account to save your design. Your artwork and text stay with you through sign-in.',register:'Create account',localOnly:'Registration is not available in the local storefront yet. You can sign in with a test account.'},
  de:{message:'Zum Speichern brauchst du ein Konto. Dein Bild und dein Text bleiben bei der Anmeldung erhalten.',register:'Registrieren',localOnly:'In der lokalen Version ist die Registrierung noch nicht verfügbar. Du kannst dich mit einem Testkonto anmelden.'},
  pl:{message:'Aby zapisać projekt, potrzebujesz konta. Rysunek i tekst nie znikną podczas logowania.',register:'Załóż konto',localOnly:'W lokalnej wersji sklepu rejestracja nie jest jeszcze dostępna. Możesz zalogować się na konto testowe.'},
  es:{message:'Necesitas una cuenta para guardar tu diseño. El dibujo y el texto se conservarán al iniciar sesión.',register:'Crear una cuenta',localOnly:'El registro aún no está disponible en la tienda local. Puedes iniciar sesión con una cuenta de prueba.'},
  fr:{message:'Un compte est nécessaire pour enregistrer votre création. Votre dessin et votre texte seront conservés pendant la connexion.',register:'Créer un compte',localOnly:'Les inscriptions ne sont pas encore ouvertes dans la boutique locale. Vous pouvez vous connecter avec un compte de test.'},
  it:{message:'Per salvare il tuo design serve un account. Il disegno e il testo saranno conservati durante l’accesso.',register:'Crea un account',localOnly:'La registrazione non è ancora disponibile nel negozio locale. Puoi accedere con un account di prova.'},
};
