import type {StorefrontLocale} from '../locale';

export const shelfCopy:Record<StorefrontLocale,readonly [string,string,string,string,string,string]>={
  en:['Loading saved products…','Saved products could not be synced.','Your guest favorites could not be transferred.','Try again','Product no longer available','The guest selection was not transferred. It is kept on this device. You can edit your saved products.'],
  de:['Gespeicherte Produkte werden geladen…','Gespeicherte Produkte konnten nicht synchronisiert werden.','Deine Gastfavoriten konnten nicht übernommen werden.','Erneut versuchen','Produkt nicht mehr verfügbar','Die Gastauswahl wurde nicht übertragen und bleibt auf diesem Gerät. Du kannst deine Favoriten bearbeiten.'],
  ru:['Загружаем избранное…','Не удалось синхронизировать избранное.','Не удалось перенести гостевое избранное.','Повторить','Товар больше недоступен','Гостевое избранное не перенесено и осталось на этом устройстве. Избранное аккаунта можно менять.'],
  pl:['Wczytywanie ulubionych…','Nie udało się zsynchronizować ulubionych.','Nie udało się przenieść ulubionych gościa.','Spróbuj ponownie','Produkt jest już niedostępny','Nie przeniesiono ulubionych gościa. Pozostają na tym urządzeniu. Możesz edytować ulubione konta.'],
  es:['Cargando tus favoritos…','No se pudieron sincronizar los favoritos.','No se pudieron transferir tus favoritos de invitado.','Reintentar','Producto no disponible','Los favoritos de invitado no se han transferido y siguen en este dispositivo. Puedes editar tus favoritos.'],
  fr:['Chargement des favoris…','Impossible de synchroniser les favoris.','Impossible de transférer vos favoris invités.','Réessayer','Produit indisponible','Les favoris invités n’ont pas été transférés et restent sur cet appareil. Vous pouvez modifier vos favoris.'],
  it:['Caricamento dei preferiti…','Impossibile sincronizzare i preferiti.','Impossibile trasferire i preferiti salvati come ospite.','Riprova','Prodotto non più disponibile','I preferiti salvati come ospite non sono stati trasferiti e restano sul dispositivo. Puoi modificare i tuoi preferiti.'],
};
