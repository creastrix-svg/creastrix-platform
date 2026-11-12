import type { EditionItem } from './editionCatalog';
import { loopName, mediaForVariant } from './loop';
import { railItems, sellerDestination } from '../detail/carouselContent';
import { productCopy } from '../copy';
import type { StorefrontLocale } from '../locale';

// Listing content for the local cup fixture. Dimensions, capacity and care
// instructions have not been supplied; the photo is not a basis for those claims.
type CupCopy = {
  features: readonly [string, string, string];
  process: string; about: string; shape: string; surface: string; colour: string;
};
const copy: Record<StorefrontLocale, CupCopy> = {
  en: {
    features: ['Ceramic', 'Cobalt glaze', 'Round handle'], process: 'Glazing',
    about: 'A small espresso cup with a broad rim and a ring-shaped handle.',
    shape: 'The sides taper towards the base. The round handle sits high on the cup.',
    surface: 'A glossy glaze covers the rim and the handle.',
    colour: 'The blue appears darker in the shade and brighter where the light catches the glaze.',
  },
  de: {
    features: ['Keramik', 'Kobaltblaue Glasur', 'Runder Henkel'], process: 'Glasieren',
    about: 'Eine kleine Espressotasse mit breitem Rand und ringförmigem Henkel.',
    shape: 'Zum Boden hin wird die Tasse schmaler. Der runde Henkel setzt weit oben an.',
    surface: 'Eine glänzende Glasur überzieht Rand und Henkel.',
    colour: 'Im Schatten wirkt das Blau dunkler; wo das Licht auf die Glasur trifft, leuchtet es heller.',
  },
  ru: {
    features: ['Керамика', 'Кобальтовая глазурь', 'Круглая ручка'], process: 'Глазурование',
    about: 'Небольшая чашка для эспрессо с широким краем и ручкой в форме кольца.',
    shape: 'Стенки сужаются к основанию. Круглая ручка расположена ближе к краю чашки.',
    surface: 'Блестящая глазурь покрывает и край чашки, и ручку.',
    colour: 'В тени синий кажется темнее, а на освещённом краю глазурь даёт яркие блики.',
  },
  pl: {
    features: ['Ceramika', 'Kobaltowe szkliwo', 'Okrągłe uszko'], process: 'Szkliwienie',
    about: 'Niewielka filiżanka do espresso z szerokim brzegiem i okrągłym uszkiem.',
    shape: 'Ścianki zwężają się ku podstawie. Okrągłe uszko znajduje się tuż pod brzegiem.',
    surface: 'Brzeg i uszko pokrywa błyszczące szkliwo.',
    colour: 'W cieniu błękit ciemnieje, a w świetle na szkliwie widać jaśniejsze refleksy.',
  },
  es: {
    features: ['Cerámica', 'Esmalte cobalto', 'Asa redonda'], process: 'Esmaltado',
    about: 'Una taza pequeña de espresso con borde ancho y asa en forma de anillo.',
    shape: 'La taza se estrecha hacia la base. El asa redonda queda cerca del borde.',
    surface: 'Un esmalte brillante recubre tanto el borde como el asa.',
    colour: 'El azul se oscurece en las zonas de sombra y se ilumina donde la luz da en el esmalte.',
  },
  fr: {
    features: ['Céramique', 'Émail bleu cobalt', 'Anse ronde'], process: 'Émaillage',
    about: 'Une petite tasse à expresso au bord large et à l’anse en forme d’anneau.',
    shape: 'La tasse se resserre vers sa base. L’anse ronde est placée près du bord.',
    surface: 'Un émail brillant recouvre le bord et l’anse.',
    colour: 'Le bleu s’assombrit dans l’ombre et s’éclaircit là où la lumière accroche l’émail.',
  },
  it: {
    features: ['Ceramica', 'Smalto blu cobalto', 'Manico tondo'], process: 'Smaltatura',
    about: 'Una tazzina da espresso con bordo ampio e manico ad anello.',
    shape: 'La tazzina si restringe verso la base. Il manico tondo si trova vicino al bordo.',
    surface: 'Uno smalto lucido riveste sia il bordo sia il manico.',
    colour: 'Il blu si fa più scuro in ombra e più chiaro dove la luce si riflette sullo smalto.',
  },
};

const studioEdit = ['halo', 'flute', 'reed', 'prism'] as const;

export function tideDetails(locale: StorefrontLocale, item: EditionItem, edition: readonly EditionItem[], finish: string) {
  const t = productCopy[locale], x = copy[locale];
  const tiles = (ids: readonly EditionItem['id'][]) => ids.map(id => {
    const entry = edition.find(value => value.id === id)!;
    return { name: entry.name, note: entry.title, href: entry.href, photo: entry.thumb };
  });
  const technicalFacts = [[t.material, item.material], [t.dimensions, '—'], [t.process, x.process]] as const;
  return {
    seller: { name: 'Creastrix', byline: t.seller, href: '/preview/info/about', label: t.about },
    features: x.features,
    about: { title: t.aboutTitle, body: x.about, facts: technicalFacts },
    description: {
      intro: item.description,
      sections: [[t.descriptionDesignTitle, x.shape], [t.material, x.surface], [t.color, x.colour]],
      facts: [[t.specsModel, item.name], ...technicalFacts, [t.specsColors, finish], [t.specsPersonal, t.without]],
    },
    recommendations: {
      studio: { items: railItems('tide', 'studio', locale) ?? [
        ...tiles(studioEdit), {name:loopName(), note:t.subtitle, href:'/products/loop', photo:mediaForVariant('cobalt').photos[0] ?? ''},
      ], ...sellerDestination('tide') },
      // The offline assortment has no alternative espresso cup.
      related: { items: railItems('tide', 'related', locale) ?? [], href: '/catalog' },
      section: { items: railItems('tide', 'section', locale) ?? [], href: '/catalog' },
    },
  } as const;
}
