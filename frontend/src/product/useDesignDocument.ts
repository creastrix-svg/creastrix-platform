import { useEffect, useState } from 'react';
import type { SavedDesign } from './designSnapshot';
import { decodedDesign, loadDesign } from './designSnapshot';
import type { CompositionDocument } from './studioComposition';

export function useDesignDocument(design: SavedDesign) {
  const [result, setResult] = useState<{ design: SavedDesign; document?: CompositionDocument; error?: boolean }>(() => ({ design, document: decodedDesign(design) }));
  const current = result.design === design ? result : { design, document: decodedDesign(design) };
  useEffect(() => {
    const controller = new AbortController();
    void loadDesign(design, undefined, controller.signal).then(document => {
      if (!controller.signal.aborted) setResult({ design, document });
    }, () => { if (!controller.signal.aborted) setResult({ design, error: true }); });
    return () => controller.abort();
  }, [design]);
  return current;
}
