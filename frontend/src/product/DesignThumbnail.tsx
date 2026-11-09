import type { SavedDesign } from './designSnapshot';
import { firstLettering } from './studioComposition';
import LetteringSketch from './LetteringSketch';
import type { VariantId } from './catalog';
import { Spinner } from './Icon';
import { studioCartCopy } from './studioCartCopy';
import { useProductLocale } from './useProductLocale';
import { useDesignDocument } from './useDesignDocument';
export default function DesignThumbnail({ design, variant, label }: { design: SavedDesign; variant: VariantId; label?: string }) {
  const { document, error } = useDesignDocument(design), c = studioCartCopy[useProductLocale()];
  return <div className="product-design-thumbnail">{document ? <LetteringSketch variant={variant} objects={document.objects} value={firstLettering(document, variant)} label={label ?? c.personalized} detail />
    : <span role="status">{!error && <Spinner />}{error ? c.restoreFailed : c.loading}</span>}</div>;
}
