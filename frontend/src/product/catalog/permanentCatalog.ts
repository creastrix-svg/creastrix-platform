import { storefrontLocales } from '../locale';

export const permanentCatalogMode = import.meta.env.DEV && import.meta.env.MODE === 'account-cart-local';
const object = (v: unknown): Record<string, unknown> => {
  if (!v || typeof v !== 'object' || Array.isArray(v)) throw new Error('PERMANENT_CATALOG_INVALID');
  return v as Record<string, unknown>;
};
const translations = (value: unknown) => Object.fromEntries(storefrontLocales.map(locale => [locale, value]));
const loopFinishes = ['cobalt','coral','chalk','cobalt-chalk'];

/** Adapter for the common product card. Prices, units, stable identities, display
 * labels and swatches come from PostgreSQL. No media/ratings/template is invented. */
export function permanentPresentation(raw: unknown) {
  if (!Array.isArray(raw) || raw.length > 1000) throw new Error('PERMANENT_CATALOG_INVALID');
  const products = raw.map(value => {
    const o = object(value);
    if (typeof o.id !== 'string' || !Array.isArray(o.variants)) throw new Error('PERMANENT_CATALOG_INVALID');
    if (o.templateRevision !== null && !(typeof o.templateRevision === 'string' && /^[1-9][0-9]{0,18}$/.test(o.templateRevision)))
      throw new Error('PERMANENT_CATALOG_INVALID');
    const rows = o.variants.map(object);
    const studio = o.id === 'loop' && o.templateRevision === '2' && o.studioFormat === 'loop-studio-v1'
      && rows.length === loopFinishes.length && loopFinishes.every(id => rows.some(v => v.id === id && v.sku === id));
    if (studio) rows.sort((a,b) => loopFinishes.indexOf(String(a.id)) - loopFinishes.indexOf(String(b.id)));
    return { id: o.id, href: '/products/' + o.id, currency: o.currency, name: o.label, templateRevision: o.templateRevision,
      personalizable: studio, designFeeCents: studio ? o.studioFeeCents : 0, variants: rows.map(v => {
        return { id: v.id, sku: v.sku, name: translations(v.label), cents: v.cents,
          stock: v.stock, previousCents: v.previousCents ?? null, colors: v.secondSwatch == null ? [v.swatch] : [v.swatch,v.secondSwatch],
          checkTone: v.checkTone ?? 'dark', photos: v.photos ?? [], model: v.model ?? null,
          // The archived template is an import/display capability, not an editor. Its
          // preview uses the offer's current published fee, never a historical guess.
          ...(o.id === 'loop' && typeof o.templateRevision==='string' && BigInt(o.templateRevision)>=2n
            && v.id === v.sku && loopFinishes.includes(String(v.id)) && v.retainedStudioRevision === '2'
            ? { retainedLoopV2FeeCents: o.studioFeeCents } : {}) };
      }) };
  });
  return { products, entries: products.map(p => ({ id: p.id, href: p.href, name: p.name, kind: 'offer',
    title: translations(p.name), material: translations(''), description: translations(''), categories: [],
    collectionId: null, collectionName: null, photo: Array.isArray(p.variants[0]?.photos) ? p.variants[0].photos[0] ?? null : null,
    thumb: Array.isArray(p.variants[0]?.photos) ? p.variants[0].photos[0] ?? null : null, editionOrder: null, homeOrder: null,
    rating: { count: 0, average: null } })), recommendations: [] };
}
