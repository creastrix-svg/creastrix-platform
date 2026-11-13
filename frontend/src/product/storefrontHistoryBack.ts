import {createContext, useCallback, useLayoutEffect, useRef, type MouseEvent} from 'react';
import {useLocation, useNavigate} from 'react-router';

type BackClick = (event: MouseEvent<HTMLAnchorElement>, origin: string | null) => void;
export const StorefrontHistoryBack = createContext<BackClick>(() => {});

/** One gate for every Back link in this storefront, scoped to the committed
 * history entry. A browser traversal returns before POP; a second click in
 * that interval must not ask the browser to consume another entry. */
export function useStorefrontHistoryBack(): BackClick {
  const {key} = useLocation(), navigate = useNavigate();
  const pending = useRef<string | null>(null);
  useLayoutEffect(() => { pending.current = null; }, [key]);
  useLayoutEffect(() => {
    const restored = (event: PageTransitionEvent) => { if (event.persisted) pending.current = null; };
    window.addEventListener('pageshow', restored);
    return () => window.removeEventListener('pageshow', restored);
  }, []);
  return useCallback((event, origin) => {
    if (!origin || event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey
        || (event.currentTarget.target && event.currentTarget.target !== '_self')) return;
    event.preventDefault();
    if (pending.current === key) return;
    pending.current = key;
    void navigate(-1);
  }, [key, navigate]);
}
