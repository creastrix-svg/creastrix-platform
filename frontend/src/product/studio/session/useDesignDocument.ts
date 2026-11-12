import { useEffect, useState } from 'react';
import type { SavedDesign } from './designSnapshot';
import { decodedDesign, loadDesign } from './designSnapshot';
import type { CompositionDocument } from '../design/studioComposition';

export function useDesignDocument(design?: SavedDesign) {
  const [result, setResult] = useState<{ design?: SavedDesign; document?: CompositionDocument; error?: boolean }>(() => ({ design, document: design && decodedDesign(design) }));
  const current = result.design === design ? result : { design, document: design && decodedDesign(design) };
  useEffect(() => {
    if (!design) return;
    const controller = new AbortController();
    void loadDesign(design, undefined, controller.signal).then(document => {
      if (!controller.signal.aborted) setResult({ design, document });
    }, () => { if (!controller.signal.aborted) setResult({ design, error: true }); });
    return () => controller.abort();
  }, [design]);
  return current;
}
