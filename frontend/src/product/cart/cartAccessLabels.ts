import type {StorefrontLocale} from '../locale';
import {useProductLocale} from '../useProductLocale';
const prompts:Record<StorefrontLocale,readonly [string,string,string]> = {
  en:['Sign in again','To use your account cart here, sign in again. Saved items stay in your account.','Retry cart access'],
  de:['Erneut anmelden','Melde dich erneut an, um deinen Warenkorb hier zu nutzen. Gespeicherte Artikel bleiben in deinem Konto.','Warenkorb erneut laden'],
  ru:['Войти снова','Чтобы пользоваться корзиной здесь, войдите снова. Сохранённые товары останутся в аккаунте.','Повторить доступ к корзине'],
  fr:['Se reconnecter','Reconnectez-vous pour utiliser votre panier ici. Les articles enregistrés restent dans votre compte.','Réessayer d’ouvrir le panier'],
  it:['Accedi di nuovo','Accedi di nuovo per usare il carrello qui. I prodotti salvati restano nel tuo account.','Riprova ad aprire il carrello'],
  es:['Volver a iniciar sesión','Vuelve a iniciar sesión para usar tu cesta aquí. Los artículos guardados siguen en tu cuenta.','Volver a abrir la cesta'],
  pl:['Zaloguj się ponownie','Zaloguj się ponownie, aby skorzystać tutaj z koszyka. Zapisane produkty pozostaną na koncie.','Spróbuj ponownie otworzyć koszyk'],
};
export function useCartAccessLabels(){return prompts[useProductLocale()];}
