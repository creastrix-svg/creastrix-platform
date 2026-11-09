import { createContext, useState } from 'react';
import { useLocation, useNavigationType } from 'react-router';

export const StorefrontOrigin = createContext<string | null>(null);

// React Router exposes the current entry, not a safe previous URL for an anchor.
// Remember only local storefront visits; direct links never guess a prior product.
export function useStorefrontOrigin() {
  const { key, pathname, search, hash } = useLocation(), action = useNavigationType();
  const current = { key, href: pathname + search + hash };
  const [trail, setTrail] = useState(() => ({ entries: [current], index: 0 }));
  if (trail.entries[trail.index].key !== key) {
    const known = trail.entries.findIndex(entry => entry.key === key);
    if (action === 'POP' && known >= 0) setTrail({ entries: trail.entries, index: known });
    else {
      const prefix = action === 'POP' ? [] : trail.entries.slice(0, trail.index + (action === 'PUSH' ? 1 : 0));
      const entries = [...prefix, current].slice(-60);
      setTrail({ entries, index: entries.length - 1 });
    }
  }
  return trail.entries[trail.index - 1]?.href ?? null;
}
