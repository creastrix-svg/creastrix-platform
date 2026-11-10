import { createContext, useContext } from 'react';
import type { Dispatch } from 'react';
import type { CompositionAction, CompositionSession } from '../design/studioComposition';

// Runtime editing state; persistence serializes only current/applied documents,
// without undo history, account association, analytics or network transmission.
export const PersonalizationSession = createContext<{ state: CompositionSession; dispatch: Dispatch<CompositionAction> } | null>(null);
export function usePersonalizationSession() {
  const session = useContext(PersonalizationSession);
  if (!session) throw new Error('Personalization session provider is required.');
  return session;
}
