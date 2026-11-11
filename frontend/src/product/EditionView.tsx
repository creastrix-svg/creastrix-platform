import { useContext, useState } from 'react';
import { Link, useParams } from 'react-router';
import { editionCollectionPath, editionCopy, editionItems } from './fixtures/editionCatalog';
import type { EditionItem } from './fixtures/editionCatalog';
import { Icon } from './Icon';
import { NotFoundView, StorefrontBack } from './PreviewViews';
import { productCopy } from './copy';
import { StorefrontOrigin } from './storefrontOrigin';
import { useProductLocale } from './useProductLocale';
import './edition.css';

function EditionPhoto({ item, large = false }: { item: EditionItem; large?: boolean }) {
  const t = productCopy[useProductLocale()];
  const [failed, setFailed] = useState<string>();
  const src = large ? item.photo : item.thumb;
  return <span className="edition-photo">{failed === src
    ? <span className="edition-image-error"><Icon name="image" />{t.noPhotoShort}</span>
    : <img src={src} alt={large ? item.name + ' · ' + item.title + ' · ' + item.material : ''} width="800" height="800"
      loading={large ? 'eager' : 'lazy'} decoding="async" onError={() => setFailed(src)} />}</span>;
}

function EditionTiles({ items }: { items: readonly EditionItem[] }) {
  return <ul className="edition-grid">{items.map(item =>
    <li key={item.id}><Link to={item.href} className="edition-tile">
      <EditionPhoto item={item} />
      <span className="edition-tile-title"><strong>{item.name}</strong><span>{item.title}</span></span>
      <span className="edition-tile-material">{item.material}</span>
    </Link></li>)}</ul>;
}

export default function EditionView() {
  const { id } = useParams(), locale = useProductLocale(), t = productCopy[locale], c = editionCopy[locale];
  const origin = useContext(StorefrontOrigin), items = editionItems(locale);
  const position = items.findIndex(record => record.id === id), item = items[position];
  if (id && !item) return <NotFoundView />;
  const related = item ? [...items.slice(position + 1), ...items.slice(0, position)].slice(0, 4) : [];
  const goBack = origin ? <StorefrontBack /> :
    <Link className="product-back" to={item ? editionCollectionPath : '/'}><span className="product-flip"><Icon name="arrow" /></span>{item ? c.index : t.home}</Link>;
  return <div className="edition-page">
    {goBack}
    {item ? <>
      <div className="edition-focus">
        <EditionPhoto item={item} large />
        <div className="edition-details">
          <span className="edition-tag">{c.status}</span>
          <h1>{item.name}</h1><p className="edition-subtitle">{item.title}</p>
          <p className="edition-story">{item.description}</p>
          <dl><dt>{c.material}</dt><dd>{item.material}</dd></dl>
          <p className="edition-note">{c.note}</p>
        </div>
      </div>
      <section className="edition-related" aria-labelledby="edition-related-title"><h2 id="edition-related-title">{c.more}</h2>
        <EditionTiles items={related} /></section>
    </> : <>
      <header className="edition-heading"><span className="edition-tag">{c.status}</span><h1>{c.title}</h1><p>{c.intro}</p></header>
      <EditionTiles items={items} />
    </>}
  </div>;
}
