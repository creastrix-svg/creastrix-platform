import type { SavedDesign } from '../session/designSnapshot';
import { firstLettering } from '../design/studioComposition';
import LetteringSketch from './LetteringSketch';
import type { VariantId } from '../../fixtures/loop';
import { Spinner } from '../../Icon';
import { studioCartCopy } from '../../cart/studioCartCopy';
import { useProductLocale } from '../../useProductLocale';
import { useDesignDocument } from '../session/useDesignDocument';
import { connectedSku } from '../../catalog/localCatalog';
export default function DesignThumbnail({ design, variant, label }: { design: SavedDesign; variant: VariantId; label?: string }) {
  const { document, error } = useDesignDocument(design), c = studioCartCopy[useProductLocale()];
  return <div className="product-design-thumbnail">{document ? <LetteringSketch variant={variant} colors={connectedSku(variant)?.variant.colors} objects={document.objects} value={firstLettering(document, variant)} label={label ?? c.personalized} detail />
    : <span role="status">{!error && <Spinner />}{error ? c.restoreFailed : c.loading}</span>}</div>;
}
