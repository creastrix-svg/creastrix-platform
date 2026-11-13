import type {StorefrontLocale} from '../locale';

type Copy={title:string;back:string;intro:string;empty:string;save:string;saved:string;saving:string;loading:string;
  open:string;copy:string;remove:string;confirm:string;cancel:string;confirmText:string;unavailable:string;
  failed:string;conflict:string;retry:string;guestReady:string;signIn:string;product:string};
export const designLibraryCopy:Record<StorefrontLocale,Copy>={
  en:{title:'My designs',back:'Back to account',intro:'Your personal designs, ready to open and use again.',empty:'No saved designs yet. Save a design from the personalisation studio.',
    save:'Save design',saved:'Saved in My designs',saving:'Saving…',loading:'Loading your designs…',open:'Open design',copy:'Create a copy',remove:'Delete',confirm:'Delete design',cancel:'Cancel',
    confirmText:'Delete this saved design? Items already in your cart will not change.',unavailable:'This design cannot currently be opened for personalisation.',
    failed:'We could not complete this action.',conflict:'This design changed or is no longer available. Reopen it from My designs.',retry:'Try again',guestReady:'Your draft from the studio is ready. Save it to this account?',signIn:'Sign in',product:'Back to product'},
  de:{title:'Meine Designs',back:'Zurück zum Konto',intro:'Deine persönlichen Designs zum Öffnen und Wiederverwenden.',empty:'Noch keine gespeicherten Designs. Speichere ein Design im Personalisierungsstudio.',
    save:'Design speichern',saved:'Unter „Meine Designs“ gespeichert',saving:'Wird gespeichert…',loading:'Deine Designs werden geladen…',open:'Design öffnen',copy:'Kopie erstellen',remove:'Löschen',confirm:'Design löschen',cancel:'Abbrechen',
    confirmText:'Dieses gespeicherte Design löschen? Artikel in deinem Warenkorb bleiben unverändert.',unavailable:'Dieses Design kann derzeit nicht zur Personalisierung geöffnet werden.',
    failed:'Die Aktion konnte nicht abgeschlossen werden.',conflict:'Das Design wurde geändert oder ist nicht mehr verfügbar. Öffne es erneut unter „Meine Designs“.',retry:'Erneut versuchen',guestReady:'Dein Entwurf aus dem Studio ist bereit. In diesem Konto speichern?',signIn:'Anmelden',product:'Zurück zum Produkt'},
  ru:{title:'Мои дизайны',back:'В аккаунт',intro:'Здесь можно открыть свои дизайны, изменить их или создать копию.',empty:'У вас пока нет сохранённых дизайнов. Создайте дизайн в студии и нажмите «Сохранить дизайн».',
    save:'Сохранить дизайн',saved:'Сохранено в «Моих дизайнах»',saving:'Сохраняем…',loading:'Загружаем ваши дизайны…',open:'Открыть дизайн',copy:'Создать копию',remove:'Удалить',confirm:'Удалить дизайн',cancel:'Отмена',
    confirmText:'Удалить этот сохранённый дизайн? Товары, уже добавленные в корзину, не изменятся.',unavailable:'Этот дизайн сейчас нельзя открыть для персонализации.',
    failed:'Не удалось завершить действие.',conflict:'Дизайн изменился или больше недоступен. Откройте его заново из «Моих дизайнов».',retry:'Повторить',guestReady:'Черновик из студии готов. Сохранить его в этом аккаунте?',signIn:'Войти',product:'К товару'},
  pl:{title:'Moje projekty',back:'Wróć do konta',intro:'Otwieraj i wykorzystuj ponownie swoje projekty.',empty:'Nie ma jeszcze zapisanych projektów. Zapisz projekt w studiu personalizacji.',
    save:'Zapisz projekt',saved:'Zapisano w sekcji „Moje projekty”',saving:'Zapisywanie…',loading:'Ładowanie projektów…',open:'Otwórz projekt',copy:'Utwórz kopię',remove:'Usuń',confirm:'Usuń projekt',cancel:'Anuluj',
    confirmText:'Usunąć zapisany projekt? Produkty w koszyku się nie zmienią.',unavailable:'Tego projektu nie można teraz otworzyć do personalizacji.',
    failed:'Nie udało się ukończyć działania.',conflict:'Projekt się zmienił lub nie jest już dostępny. Otwórz go ponownie w sekcji „Moje projekty”.',retry:'Spróbuj ponownie',guestReady:'Szkic ze studia jest gotowy. Zapisać go na tym koncie?',signIn:'Zaloguj się',product:'Wróć do produktu'},
  es:{title:'Mis diseños',back:'Volver a mi cuenta',intro:'Abre tus diseños personales y vuelve a utilizarlos.',empty:'Todavía no tienes diseños guardados. Guarda uno en el estudio de personalización.',
    save:'Guardar diseño',saved:'Guardado en Mis diseños',saving:'Guardando…',loading:'Cargando tus diseños…',open:'Abrir diseño',copy:'Crear una copia',remove:'Eliminar',confirm:'Eliminar diseño',cancel:'Cancelar',
    confirmText:'¿Eliminar este diseño guardado? Los artículos de tu cesta no cambiarán.',unavailable:'Este diseño no se puede abrir para personalizarlo en este momento.',
    failed:'No se ha podido completar la acción.',conflict:'El diseño ha cambiado o ya no está disponible. Ábrelo de nuevo desde Mis diseños.',retry:'Reintentar',guestReady:'Tu borrador del estudio está listo. ¿Guardarlo en esta cuenta?',signIn:'Iniciar sesión',product:'Volver al producto'},
  fr:{title:'Mes créations',back:'Retour au compte',intro:'Retrouvez vos créations pour les modifier ou les réutiliser.',empty:'Aucune création enregistrée. Enregistrez une création depuis le studio de personnalisation.',
    save:'Enregistrer la création',saved:'Enregistrée dans Mes créations',saving:'Enregistrement…',loading:'Chargement de vos créations…',open:'Ouvrir la création',copy:'Créer une copie',remove:'Supprimer',confirm:'Supprimer la création',cancel:'Annuler',
    confirmText:'Supprimer cette création ? Les articles déjà dans votre panier ne seront pas modifiés.',unavailable:'Cette création ne peut pas être ouverte dans le studio pour le moment.',
    failed:'Impossible de terminer cette action.',conflict:'Cette création a changé ou n’est plus disponible. Rouvrez-la depuis Mes créations.',retry:'Réessayer',guestReady:'Votre brouillon du studio est prêt. L’enregistrer dans ce compte ?',signIn:'Se connecter',product:'Retour au produit'},
  it:{title:'I miei design',back:'Torna all’account',intro:'Apri e riutilizza i tuoi design personali.',empty:'Non ci sono ancora design salvati. Salva un design nello studio di personalizzazione.',
    save:'Salva design',saved:'Salvato in I miei design',saving:'Salvataggio…',loading:'Caricamento dei design…',open:'Apri design',copy:'Crea una copia',remove:'Elimina',confirm:'Elimina design',cancel:'Annulla',
    confirmText:'Eliminare questo design salvato? Gli articoli già nel carrello non cambieranno.',unavailable:'Al momento non è possibile aprire questo design per personalizzarlo.',
    failed:'Non è stato possibile completare l’azione.',conflict:'Il design è cambiato o non è più disponibile. Riaprilo da I miei design.',retry:'Riprova',guestReady:'La bozza dello studio è pronta. Salvarla in questo account?',signIn:'Accedi',product:'Torna al prodotto'},
};
