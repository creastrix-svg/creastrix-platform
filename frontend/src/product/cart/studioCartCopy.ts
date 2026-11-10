import type { StorefrontLocale } from '../locale';
import type { CartLine } from '../demoStore';
import { characters } from '../studio/design/personalization';
export function cartDesignLabel(line: Pick<CartLine, 'id' | 'design'>, locale: StorefrontLocale): string {
  if (!line.design) return '';
  const text = line.design.objects.find(object => object.kind === 'text' && object.value.text.trim());
  const brief = text?.kind === 'text' ? characters(text.value.text.trim().replace(/\s+/g, ' ')).slice(0, 20).join('') : '';
  return `${studioCartCopy[locale].personalized} ${line.id?.replace(/^design-/, '') ?? ''}${brief ? ' · ' + brief : ''}`.trim();
}
export const cartEditNote: Record<StorefrontLocale, string> = {
  en: 'Cart edits are kept only when you choose “Save this cart design”.',
  de: 'Warenkorbänderungen bleiben erst mit „Dieses Warenkorbdesign speichern“ erhalten.',
  ru: 'Правки этой позиции сохранятся только после «Сохранить дизайн этой позиции».',
  pl: 'Zmiany pozycji zostaną zachowane dopiero po wybraniu „Zapisz projekt tej pozycji”.',
  es: 'Los cambios se conservan al elegir «Guardar el diseño de esta línea».',
  fr: 'Les modifications sont conservées après «Enregistrer cette création du panier».',
  it: 'Le modifiche vengono conservate solo con «Salva il design di questa riga».',
};
const en = {
  personalized: 'Your design', edit: 'Edit personalisation', save: 'Save this cart design', cancel: 'Back to cart — discard edits',
  missing: 'This cart design has changed or is no longer available.', loading: 'Restoring local designs…',
  saved: 'Saved in this browser', saving: 'Saving locally…', restoreFailed: 'Local designs could not be restored. The stored copy has not been overwritten.',
  saveFailed: 'Not saved locally. Keep this tab open; your current designs remain here.', memory: 'Continue without local saving',
  localNote: 'Designs and this preview cart are saved only in this browser, not in your account. This is not an order or a production quote.',
};
type Copy = typeof en;
export const studioCartCopy: Record<StorefrontLocale, Copy> = {
  en,
  de: { personalized:'Dein Design', edit:'Personalisierung bearbeiten', save:'Dieses Warenkorbdesign speichern', cancel:'Zum Warenkorb — Änderungen verwerfen', missing:'Dieses Design wurde geändert oder ist nicht mehr verfügbar.', loading:'Lokale Designs werden geladen…', saved:'In diesem Browser gespeichert', saving:'Lokal speichern…', restoreFailed:'Lokale Designs konnten nicht geladen werden. Die gespeicherte Kopie wurde nicht überschrieben.', saveFailed:'Nicht lokal gespeichert. Lass diesen Tab offen; deine Designs bleiben hier.', memory:'Ohne lokales Speichern fortfahren', localNote:'Designs und dieser Vorschau-Warenkorb werden nur in diesem Browser gespeichert, nicht im Konto. Keine Bestellung und kein Produktionsangebot.' },
  ru: { personalized:'Ваш дизайн', edit:'Изменить персонализацию', save:'Сохранить дизайн этой позиции', cancel:'В корзину — отменить изменения', missing:'Эта позиция изменилась или больше недоступна.', loading:'Восстанавливаем локальные дизайны…', saved:'Сохранено в этом браузере', saving:'Сохраняем локально…', restoreFailed:'Не удалось восстановить дизайны. Сохранённая копия не перезаписана.', saveFailed:'Не сохранено локально. Не закрывайте вкладку: текущие дизайны остаются здесь.', memory:'Продолжить без локального сохранения', localNote:'Дизайны и эта демонстрационная корзина сохраняются только в этом браузере, не в аккаунте. Это не заказ и не производственный расчёт.' },
  pl: { personalized:'Twój projekt', edit:'Edytuj personalizację', save:'Zapisz projekt tej pozycji', cancel:'Do koszyka — odrzuć zmiany', missing:'Ta pozycja zmieniła się lub jest niedostępna.', loading:'Przywracanie lokalnych projektów…', saved:'Zapisano w tej przeglądarce', saving:'Zapisywanie lokalne…', restoreFailed:'Nie można przywrócić projektów. Zapisana kopia nie została nadpisana.', saveFailed:'Nie zapisano lokalnie. Nie zamykaj karty; projekty pozostają tutaj.', memory:'Kontynuuj bez zapisu lokalnego', localNote:'Projekty i koszyk podglądowy są zapisywane tylko w tej przeglądarce, nie na koncie. To nie zamówienie ani wycena produkcji.' },
  es: { personalized:'Tu diseño', edit:'Editar personalización', save:'Guardar el diseño de esta línea', cancel:'Volver al carrito — descartar cambios', missing:'Esta línea ha cambiado o ya no está disponible.', loading:'Restaurando diseños locales…', saved:'Guardado en este navegador', saving:'Guardando localmente…', restoreFailed:'No se pudieron restaurar los diseños. La copia guardada no se sobrescribió.', saveFailed:'No se guardó localmente. Mantén esta pestaña abierta; tus diseños siguen aquí.', memory:'Continuar sin guardar localmente', localNote:'Los diseños y este carrito de vista previa se guardan solo en este navegador, no en tu cuenta. No es un pedido ni un presupuesto de producción.' },
  fr: { personalized:'Votre création', edit:'Modifier la personnalisation', save:'Enregistrer cette création du panier', cancel:'Retour au panier — abandonner les modifications', missing:'Cette ligne a changé ou n’est plus disponible.', loading:'Restauration des créations locales…', saved:'Enregistré dans ce navigateur', saving:'Enregistrement local…', restoreFailed:'Impossible de restaurer les créations. La copie enregistrée n’a pas été écrasée.', saveFailed:'Non enregistré localement. Gardez cet onglet ouvert ; vos créations restent ici.', memory:'Continuer sans enregistrement local', localNote:'Les créations et ce panier de démonstration sont enregistrés uniquement dans ce navigateur, pas dans votre compte. Ce n’est ni une commande ni un devis de fabrication.' },
  it: { personalized:'Il tuo design', edit:'Modifica personalizzazione', save:'Salva il design di questa riga', cancel:'Torna al carrello — annulla modifiche', missing:'Questa riga è cambiata o non è più disponibile.', loading:'Ripristino dei design locali…', saved:'Salvato in questo browser', saving:'Salvataggio locale…', restoreFailed:'Impossibile ripristinare i design. La copia salvata non è stata sovrascritta.', saveFailed:'Non salvato localmente. Tieni aperta questa scheda; i design restano qui.', memory:'Continua senza salvataggio locale', localNote:'I design e questo carrello di anteprima sono salvati solo in questo browser, non nel tuo account. Non è un ordine né un preventivo di produzione.' },
};
