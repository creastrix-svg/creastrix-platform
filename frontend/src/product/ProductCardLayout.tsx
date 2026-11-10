import { useContext, useState, type ReactNode } from 'react';
import { Link, useNavigate } from 'react-router';
import type { SwatchColors } from './productDisplay';
import { catalogCopy } from './browse/catalogCopy';
import { productCopy } from './copy';
import { Icon } from './Icon';
import type { StorefrontLocale } from './locale';
import { isCatalogPath } from './routes';
import { StorefrontOrigin } from './storefrontOrigin';
import Carousel from './Carousel';
import type { CarouselContent } from './carouselContent';

type Finish<Id extends string> = { id: Id; name: string; colors: SwatchColors; checkTone: 'light' | 'dark' };
type TextPairs = readonly (readonly [string, string])[];

type ProductCardProps<Id extends string> = {
  locale: StorefrontLocale; name: string; subtitle: string; category: { label: string; href: string };
  gallery: ReactNode; reviews: { list: string; write: (score: number) => string }; price: ReactNode; purchase: ReactNode;
  finishes: readonly Finish<Id>[]; selected: Id; onSelect?: (id: Id) => void;
  // Only the Loop route currently serializes a selected finish into its URL.
  urlVariant?: string;
  seller: { name: string; byline: string; href: string; label: string };
  features: readonly [string, string, string];
  about: { title: string; body: string; facts: TextPairs };
  description: { intro: string; sections: TextPairs; facts: TextPairs };
  recommendations: { studio: CarouselContent; related: CarouselContent };
  personalization?: ReactNode;
  descriptionOpen?: boolean; onDescriptionChange?: (open: boolean) => void;
};

function FactRows({ rows }: { rows: TextPairs }) {
  return rows.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>);
}

/** Every purchasable offer fills the same card; only the studio entry is optional. */
export default function ProductCardLayout<Id extends string>(props: ProductCardProps<Id>) {
  const { locale, name, subtitle, category, finishes, selected } = props;
  const navigate = useNavigate(), origin = useContext(StorefrontOrigin);
  const t = productCopy[locale];
  const [starPreview, setStarPreview] = useState(0);
  const [localOpen, setLocalOpen] = useState(false);
  const isOpen = props.descriptionOpen ?? localOpen;
  const setOpen = props.onDescriptionChange ?? setLocalOpen;
  const originPath = origin?.split(/[?#]/, 1)[0].replace(/\/+$/, '') ?? '';
  const fromSearch = originPath === '/preview/search';
  const browseOrigin = fromSearch || isCatalogPath(originPath) ? origin : null;
  const features = <div className="product-features">{props.features.map((feature, index) =>
    <div key={feature}><Icon name={(['cube', 'layers', 'spark'] as const)[index]} /><span>{feature}</span></div>
  )}</div>;
  return <>
    <nav className="product-breadcrumb" aria-label={t.navigation}><Link to={browseOrigin ?? '/catalog'} onClick={event => {
      if (!browseOrigin || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
      event.preventDefault(); navigate(-1);
    }}>{fromSearch ? t.results : catalogCopy[locale].title}</Link><Icon name="chevron" /><Link to={category.href}>{category.label}</Link><Icon name="chevron" /><span aria-current="page">{name}</span></nav>
    <div className="product-layout" data-personalizable={Boolean(props.personalization)}>
      {props.personalization ? props.gallery : <div className="product-media-column">{props.gallery}{features}</div>}
      <section className="product-information" data-product-variant={props.urlVariant} aria-label={t.choose}>
        <h1>{name}</h1><p className="product-subtitle">{subtitle}</p>
        <div className="product-rating" onBlur={() => setStarPreview(0)} onPointerDown={event => { if (event.pointerType !== 'mouse') setStarPreview(0); }}>
          <span className="product-stars" role="group" aria-label={t.reviewRating} onPointerLeave={() => setStarPreview(0)}>{Array.from({ length: 5 }, (_, i) => <button type="button" key={i} className="product-star-choice" aria-label={`${t.reviewRating}: ${i + 1} ${t.ratingOutOf}`} data-highlighted={i < starPreview} onPointerEnter={event => { if (event.pointerType === 'mouse' && window.matchMedia('(hover: hover) and (pointer: fine)').matches) setStarPreview(i + 1); }} onClick={() => navigate(props.reviews.write(i + 1))}><Icon name="star" /></button>)}</span><Link to={props.reviews.list}>{t.noReviews}</Link>
        </div>
        {props.price}
        <fieldset className="product-colors"><legend>{t.color} <strong>{finishes.find(color => color.id === selected)?.name}</strong></legend><div>
          {finishes.map(color => <button type="button" key={color.id} className="product-swatch" data-check-tone={color.checkTone}
            aria-label={color.name} aria-pressed={selected === color.id} aria-disabled={!props.onSelect || undefined}
            onClick={props.onSelect ? () => props.onSelect?.(color.id) : undefined}>
            <span><svg className="product-swatch-color" viewBox="0 0 28 28" aria-hidden="true">
              <circle cx="14" cy="14" r="13.5" fill={color.colors[0]} />
              {/* A rotated half-disc keeps both finishes inside the circular contour. */}
              {color.colors.length === 2 && <path d="M14 .5a13.5 13.5 0 0 1 0 27Z" transform="rotate(45 14 14)" fill={color.colors[1]} />}
              <circle cx="14" cy="14" r="13.5" fill="none" stroke="#8885" />
            </svg>{selected === color.id && <Icon name="check" />}</span>
          </button>)}
        </div></fieldset>
        {props.purchase}
        <div className="product-seller"><span className="product-studio-mark" aria-hidden="true"><Icon name="store" /></span>
          <div><strong>{props.seller.name}</strong><small>{props.seller.byline}</small></div>
          <Link to={props.seller.href} aria-label={props.seller.label}><Icon name="arrow" /></Link>
        </div>
        <Carousel title={t.moreStudio} mini t={t} content={props.recommendations.studio} />
      </section>
      {props.personalization && <>{props.personalization}{features}</>}
      <section className="product-about"><h2>{props.about.title}</h2><p>{props.about.body}</p>
        <dl><FactRows rows={props.about.facts} /></dl>
      </section>
    </div>
    <section className="product-description"><h2>{t.description}</h2><p className="product-description-intro">{props.description.intro}</p>
      <details open={isOpen} onToggle={event => setOpen(event.currentTarget.open)}>
        <summary><span className="product-summary-label" data-label={t.readMore} data-label-less={t.readLess}><span>{isOpen ? t.readLess : t.readMore}</span></span><Icon name="chevron" /></summary>
        <div className="product-description-content"><div className="product-description-prose">
          {props.description.sections.map(([title, body]) => <DescriptionParagraph key={title} title={title} body={body} />)}
        </div><div><h3>{t.specsTitle}</h3><dl className="product-description-specs"><FactRows rows={props.description.facts} /></dl></div></div>
      </details>
    </section>
    <Carousel title={t.related} t={t} content={props.recommendations.related} />
  </>;
}

function DescriptionParagraph({ title, body }: { title: string; body: string }) {
  return <><h3>{title}</h3><p>{body}</p></>;
}
