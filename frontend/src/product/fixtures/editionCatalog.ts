import type { StorefrontLocale } from '../locale';
import { editionAdditions } from './editionAdditions';
import { editionCollectionPath } from '../routes';
export { editionCollectionPath };

const namedObjects = [
  { id: 'halo', name: 'Halo' }, { id: 'arc', name: 'Arc' }, { id: 'tide', name: 'Tide' },
  { id: 'folio', name: 'Folio' }, { id: 'dune', name: 'Dune' }, { id: 'iris', name: 'Iris' }, { id: 'kestrel', name: 'Kestrel' },
  { id: 'moss', name: 'Moss' }, { id: 'fold', name: 'Fold' }, { id: 'prism', name: 'Prism' }, { id: 'loom', name: 'Loom' },
  { id: 'rook', name: 'Rook' }, { id: 'cove', name: 'Cove' }, { id: 'flute', name: 'Flute' }, { id: 'dot', name: 'Dot' },
  { id: 'tempo', name: 'Tempo' }, { id: 'lunar', name: 'Lunar' }, { id: 'wave', name: 'Wave' }, { id: 'reed', name: 'Reed' },
  { id: 'pin', name: 'Pin' }, { id: 'bend', name: 'Bend' }, { id: 'linea', name: 'Linea' }, { id: 'hex', name: 'Hex' },
  { id: 'trail', name: 'Trail' }, { id: 'stripe', name: 'Stripe' }, { id: 'orbit', name: 'Orbit' }, { id: 'ridge', name: 'Ridge' },
  { id: 'nook', name: 'Nook' }, { id: 'coil', name: 'Coil' }, { id: 'pebble', name: 'Pebble' }, { id: 'rail', name: 'Rail' },
  { id: 'drift', name: 'Drift' },
] as const;
export type EditionId = typeof namedObjects[number]['id'];
export type EditionItem = Readonly<{
  id: EditionId; name: string; title: string; material: string; description: string;
  href: string; photo: string; thumb: string;
}>;

type Text = readonly [title: string, material: string, description: string];
const objects: Record<EditionId, Record<StorefrontLocale, Text>> = {
  ...editionAdditions,
  halo: {
    en: ['Table lamp', 'Amber glass', 'The broad dome spreads a pool of amber light across the table. A short stem keeps the silhouette low.'],
    de: ['Tischleuchte', 'Bernsteinfarbenes Glas', 'Die breite Kuppel wirft warmes Licht auf den Tisch. Durch den kurzen Fuß bleibt die Silhouette niedrig.'],
    ru: ['Настольная лампа', 'Янтарное стекло', 'Широкий купол отбрасывает на стол тёплый янтарный свет. Короткая ножка подчёркивает округлую форму абажура.'],
    pl: ['Lampa stołowa', 'Bursztynowe szkło', 'Szeroki klosz rozlewa na stole bursztynowe światło. Krótka nóżka podkreśla jego zaokrągloną formę.'],
    es: ['Lámpara de mesa', 'Vidrio ámbar', 'La pantalla abombada baña la mesa de luz ámbar. El pie corto acentúa su perfil redondeado.'],
    fr: ['Lampe de table', 'Verre ambré', 'Le dôme diffuse une lumière ambrée sur la table. Le pied court souligne la courbe de l’abat-jour.'],
    it: ['Lampada da tavolo', 'Vetro ambrato', 'La cupola diffonde una luce ambrata sul tavolo. La base bassa mette in risalto il profilo arrotondato.'],
  },
  arc: {
    en: ['Sculptural hoops', 'Polished steel', 'Two sculptural hoops with a rounded profile. The polished steel picks up reflections along the curves.'],
    de: ['Skulpturale Creolen', 'Polierter Stahl', 'Zwei Creolen mit rundem Profil. An den geschwungenen Flächen des polierten Stahls spiegelt sich das Licht.'],
    ru: ['Серьги-кольца', 'Полированная сталь', 'Пара скульптурных колец с округлым профилем. Изгибы полированной стали ловят блики при движении.'],
    pl: ['Kolczyki koła', 'Polerowana stal', 'Para kolczyków o zaokrąglonym profilu. Polerowana stal odbija światło na wypukłych powierzchniach.'],
    es: ['Pendientes de aro', 'Acero pulido', 'Dos aros escultóricos de perfil redondeado. El acero pulido refleja la luz a lo largo de sus curvas.'],
    fr: ['Créoles sculpturales', 'Acier poli', 'Deux créoles au profil arrondi. L’acier poli accroche la lumière le long des courbes.'],
    it: ['Orecchini a cerchio', 'Acciaio lucido', 'Due cerchi scultorei dal profilo arrotondato. L’acciaio lucido cattura i riflessi lungo le curve.'],
  },
  linea: {
    en: ['Woven scarf', 'Striped cotton', 'Cream and terracotta stripes break up the navy blue weave. The pattern changes as the scarf folds and drapes.'],
    de: ['Gewebter Schal', 'Gestreifte Baumwolle', 'Cremefarbene und terrakottarote Streifen durchziehen das dunkelblaue Gewebe. In den Falten entsteht ein neues Muster.'],
    ru: ['Полосатый шарф', 'Тканый хлопок', 'Кремовые и терракотовые полосы пересекают тёмно-синее полотно. Рисунок меняется в зависимости от того, как сложен шарф.'],
    pl: ['Tkany szal', 'Bawełna w paski', 'Kremowe i terakotowe pasy przecinają granatowy splot. Układ wzoru zmienia się w fałdach szala.'],
    es: ['Pañuelo tejido', 'Algodón a rayas', 'Las rayas crema y terracota recorren el tejido azul marino. El dibujo cambia con los pliegues.'],
    fr: ['Écharpe tissée', 'Coton rayé', 'Des bandes crème et terre cuite traversent la trame bleu marine. Le motif se transforme dans les plis de l’écharpe.'],
    it: ['Sciarpa tessuta', 'Cotone a righe', 'Righe crema e terracotta attraversano il tessuto blu marino. Il motivo cambia nelle pieghe della sciarpa.'],
  },
  tide: {
    en: ['Espresso cup', 'Cobalt-glazed ceramic', 'Deep blue glaze catches the light along the rim. A rounded handle echoes the shape of the little espresso cup.'],
    de: ['Espressotasse', 'Kobaltblau glasierte Keramik', 'Die tiefblaue Glasur glänzt am Rand. Der runde Henkel greift die Form der kleinen Espressotasse auf.'],
    ru: ['Чашка для эспрессо', 'Керамика с кобальтовой глазурью', 'Глубокая синяя глазурь блестит по краю. Небольшая округлая ручка повторяет очертания чашки.'],
    pl: ['Filiżanka do espresso', 'Ceramika z kobaltowym szkliwem', 'Kobaltowe szkliwo łapie światło na krawędzi. Zaokrąglone uszko powtarza kształt niewielkiej filiżanki.'],
    es: ['Taza de espresso', 'Cerámica con esmalte cobalto', 'El esmalte azul intenso refleja la luz en el borde. El asa redondeada sigue la curva de la tacita.'],
    fr: ['Tasse à expresso', 'Céramique émaillée bleu cobalt', 'L’émail bleu profond accroche la lumière sur le bord. L’anse arrondie reprend la courbe de la petite tasse.'],
    it: ['Tazzina da espresso', 'Ceramica smaltata blu cobalto', 'Lo smalto blu intenso riflette la luce sul bordo. Il manico arrotondato riprende la curva della tazzina.'],
  },
  fold: {
    en: ['Cardholder', 'Saddle leather', 'Folded brown leather forms a slim pocket for cards. The exposed edge and stitching follow the outline of the fold.'],
    de: ['Kartenetui', 'Sattelleder', 'Gefaltetes braunes Leder bildet ein flaches Fach für Karten. Naht und offene Lederkante zeichnen die Form nach.'],
    ru: ['Картхолдер', 'Шорно-седельная кожа', 'Сложенная коричневая кожа образует тонкий карман для карт. Строчка и открытый срез подчёркивают линию сгиба.'],
    pl: ['Etui na karty', 'Skóra rymarska', 'Złożona brązowa skóra tworzy płaską kieszonkę na karty. Szew i widoczna krawędź podkreślają linię zgięcia.'],
    es: ['Tarjetero', 'Cuero de guarnicionería', 'El cuero marrón se pliega en un bolsillo fino para tarjetas. La costura y el canto visto siguen la línea del pliegue.'],
    fr: ['Porte-cartes', 'Cuir de sellerie', 'Le cuir brun se replie pour former une fine poche à cartes. La couture et la tranche apparente soulignent le pli.'],
    it: ['Portacarte', 'Cuoio da selleria', 'Il cuoio marrone si piega a formare una tasca sottile per le carte. Il bordo a vista e la cucitura seguono la piega.'],
  },
  drift: {
    en: ['Valet tray', 'Walnut', 'A shallow recess gives keys and small essentials a place to land. The walnut grain runs across the tray and into the curved rim.'],
    de: ['Ablageschale', 'Walnussholz', 'In der flachen Mulde finden Schlüssel und Kleinigkeiten Platz. Die Maserung des Walnussholzes setzt sich bis in den geschwungenen Rand fort.'],
    ru: ['Лоток для мелочей', 'Ореховое дерево', 'В неглубокую выемку удобно положить ключи и мелочи. Рисунок древесины плавно переходит со дна на изогнутый край.'],
    pl: ['Tacka na drobiazgi', 'Drewno orzechowe', 'W płytkim zagłębieniu zmieszczą się klucze i drobiazgi. Słoje orzecha biegną po dnie i przechodzą w zaokrąglony brzeg.'],
    es: ['Bandeja vaciabolsillos', 'Nogal', 'La cavidad poco profunda recoge llaves y objetos pequeños. La veta del nogal recorre el fondo y continúa por el borde curvo.'],
    fr: ['Vide-poches', 'Bois de noyer', 'Une cuvette peu profonde accueille les clés et les petits objets. Les veines du noyer remontent du fond vers le bord arrondi.'],
    it: ['Vassoio svuotatasche', 'Legno di noce', 'Il leggero incavo raccoglie chiavi e piccoli oggetti. Le venature del noce salgono dal fondo fino al bordo curvo.'],
  },
  tempo: {
    en: ['Desk clock', 'Wood & metal', 'Slim hands stand out against the ochre dial. The round face sits just above the desk.'],
    de: ['Tischuhr', 'Holz und Metall', 'Schmale Zeiger heben sich vom ockerfarbenen Zifferblatt ab. Das runde Zifferblatt sitzt knapp über der Tischplatte.'],
    ru: ['Настольные часы', 'Дерево и металл', 'Тонкие стрелки хорошо видны на охристом циферблате. Круглый корпус чуть приподнят над столом.'],
    pl: ['Zegar biurkowy', 'Drewno i metal', 'Smukłe wskazówki odcinają się od tarczy w kolorze ochry. Okrągła tarcza znajduje się tuż nad blatem.'],
    es: ['Reloj de sobremesa', 'Madera y metal', 'Las agujas finas contrastan con la esfera ocre. La esfera redonda se eleva ligeramente sobre la mesa.'],
    fr: ['Horloge de bureau', 'Bois et métal', 'Les fines aiguilles se détachent sur le cadran ocre. Le cadran rond se trouve juste au-dessus du bureau.'],
    it: ['Orologio da tavolo', 'Legno e metallo', 'Le lancette sottili risaltano sul quadrante ocra. Il quadrante rotondo è appena sollevato dal piano del tavolo.'],
  },
  folio: {
    en: ['Clothbound notebook', 'Forest-green linen', 'A forest-green linen cover wraps around the spine. The fine weave contrasts with the pale edges of the pages.'],
    de: ['Notizbuch', 'Waldgrünes Leinen', 'Der waldgrüne Leineneinband reicht über den Buchrücken. Das feine Gewebe hebt sich vom hellen Buchschnitt ab.'],
    ru: ['Блокнот', 'Тёмно-зелёный лён', 'Обложка и корешок обтянуты тёмно-зелёным льном. На его фоне хорошо виден светлый срез страниц.'],
    pl: ['Notes w płótnie', 'Ciemnozielony len', 'Okładkę i grzbiet pokrywa ciemnozielony len. Drobny splot kontrastuje z jasnymi krawędziami stron.'],
    es: ['Cuaderno de tela', 'Lino verde bosque', 'El lino verde bosque envuelve la cubierta y el lomo. Su trama fina contrasta con el canto claro de las hojas.'],
    fr: ['Carnet toilé', 'Lin vert forêt', 'Le lin vert forêt enveloppe la couverture et le dos. Sa trame fine contraste avec la tranche claire des pages.'],
    it: ['Taccuino in tela', 'Lino verde bosco', 'Il lino verde bosco avvolge la copertina e il dorso. La trama fine contrasta con il taglio chiaro delle pagine.'],
  },
};

type EditionCopy = { title: string; intro: string; note: string; status: string; material: string; more: string; index: string };
export const editionCopy: Record<StorefrontLocale, EditionCopy> = {
  en: { title: 'The object edit', intro: 'From a glass table lamp to a woven scarf.', note: 'Part of the design collection. Not available to order.', status: 'Design preview', material: 'Material', more: 'Also in the collection', index: 'View the collection' },
  de: { title: 'Die Objektkollektion', intro: 'Von der Glasleuchte bis zum gewebten Schal.', note: 'Ein Entwurf aus der Kollektion. Nicht bestellbar.', status: 'Designvorschau', material: 'Material', more: 'Mehr aus der Kollektion', index: 'Zur Kollektion' },
  ru: { title: 'Коллекция предметов', intro: 'От стеклянной лампы до тканого шарфа.', note: 'Дизайн из коллекции. Заказ пока недоступен.', status: 'Дизайн-концепт', material: 'Материал', more: 'Ещё в коллекции', index: 'К коллекции' },
  pl: { title: 'Kolekcja przedmiotów', intro: 'Od szklanej lampy po tkany szal.', note: 'Projekt z kolekcji. Niedostępny w sprzedaży.', status: 'Projekt koncepcyjny', material: 'Materiał', more: 'Również w kolekcji', index: 'Zobacz kolekcję' },
  es: { title: 'Colección de objetos', intro: 'Desde una lámpara de vidrio hasta un pañuelo tejido.', note: 'Diseño de la colección. No disponible para pedidos.', status: 'Vista previa', material: 'Material', more: 'Más de la colección', index: 'Ver la colección' },
  fr: { title: 'La collection d’objets', intro: 'De la lampe en verre à l’écharpe tissée.', note: 'Un projet de la collection, non disponible à la commande.', status: 'Aperçu du design', material: 'Matière', more: 'Dans la même collection', index: 'Voir la collection' },
  it: { title: 'Collezione di oggetti', intro: 'Dalla lampada in vetro alla sciarpa tessuta.', note: 'Un progetto della collezione, non disponibile per l’ordine.', status: 'Anteprima del progetto', material: 'Materiale', more: 'Altri oggetti della collezione', index: 'Vai alla collezione' },
};

// These are editorial previews. The absence of price, stock, cart and seller
// fields is deliberate: a design photograph is not an offer to sell.
export function editionItems(locale: StorefrontLocale): EditionItem[] {
  return namedObjects.map(({ id, name }) => {
    const [title, material, description] = objects[id][locale];
    return { id, name, title, material, description, href: editionCollectionPath + '/' + id,
      photo: '/shop-edition/' + id + '.webp', thumb: '/shop-edition/' + id + '-small.webp' };
  });
}
