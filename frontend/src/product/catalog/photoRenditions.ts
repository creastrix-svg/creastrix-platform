// The source object has no web URL. A publication exposes only these two
// role-specific renditions, named by the same opaque publication ID.
export const mediaNamespace = '/api/local-catalog/media/';
const id = '[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}';
const publication = new RegExp('^' + mediaNamespace + '(' + id + ')/(thumb|detail)\\.jpg$');
type Rendition = 'thumb' | 'detail';

export function publishedPhotoId(source: unknown, rendition: Rendition): string | undefined {
  if (typeof source !== 'string') return undefined;
  const match = publication.exec(source);
  return match && match[0] === source && match[2] === rendition ? match[1] : undefined;
}

/** Keep historic/static photographs as supplied, including their placeholders. */
export function thumbnailFor(source: string): string {
  const key = publishedPhotoId(source, 'detail');
  return key ? mediaNamespace + key + '/thumb.jpg' : source;
}
