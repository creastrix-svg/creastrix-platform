import { Link } from 'react-router';
import { campaignCopy } from './campaignCopy';
import { homeCopy } from './homeCopy';
import HomeSpotlight from './HomeSpotlight';
import { Icon } from './Icon';
import { useProductLocale } from './useProductLocale';
import './home.css';

const departments = [
  { label:'ready', hint:'readyHint', href:'/catalog?format=ready', image:'gift-denim-ribbon', height:391 },
  { label:'personal', hint:'personalHint', href:'/catalog?custom=yes', image:'precision', height:600 },
  { label:'concepts', hint:'conceptHint', href:'/catalog?format=concept', image:'amber-keeper-drafting', height:300 },
] as const;
export default function StorefrontHome() {
  const locale = useProductLocale(), h = homeCopy[locale], c = campaignCopy[locale];
  const studio = <Link className="home-studio-campaign" to="/catalog?group=custom-or-made">
    <img className="studio-campaign-picture" src="/shop-edition/studio-grain.webp" srcSet="/shop-edition/studio-grain-small.webp 400w, /shop-edition/studio-grain.webp 800w" sizes="(max-width: 680px) 95vw, (max-width: 1600px) 33vw, 500px" width="800" height="533" alt=""/>
    <span className="studio-campaign-copy"><span className="campaign-label"><i aria-hidden="true"/>{c.studio}</span>
      <strong>{c.studioTitle}</strong><span className="studio-campaign-go">{c.studioAction}<Icon name="arrow"/></span></span>
  </Link>;
  return <div className="buyer-home">
    <section className="home-campaign" aria-labelledby="home-campaign-title">
      <img className="campaign-picture" src="/shop-edition/campaign.webp" srcSet="/shop-edition/campaign-small.webp 780w, /shop-edition/campaign.webp 1680w"
        sizes="(max-width: 680px) 100vw, (max-width: 1600px) 66vw, 1010px" alt={c.photoAlt} width="1680" height="705" fetchPriority="high"/>
      <div className="campaign-copy"><span className="campaign-label"><i aria-hidden="true"/>{c.edition}</span>
        <h1 id="home-campaign-title">{c.title}</h1><p>{c.material}</p>
        <Link className="campaign-action" to="/catalog">{h.browse}<Icon name="arrow"/></Link>
      </div>
    </section>
    {studio}
    <nav className="home-departments" aria-label={h.headline}>
      {departments.map(({ label, hint, href, image, height }) => <Link className="home-department" key={href} to={href}>
        <span className="department-photo"><img alt="" decoding="async" src={'/shop-edition/' + image + '.webp'}
          srcSet={'/shop-edition/' + image + '-small.webp 450w, /shop-edition/' + image + '.webp 900w'}
          sizes="(max-width: 440px) 92px, (max-width: 1600px) 33vw, 500px" width="900" height={height}/></span>
        <span className="department-caption"><span><strong>{h[label]}</strong><small>{h[hint]}</small></span><Icon name="arrow"/></span>
      </Link>)}
    </nav>
    <HomeSpotlight/>
  </div>;
}
