import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Icon, Spinner } from './Icon';

type Props = {
  src: string; alt: string; width: number; height: number; loadingLabel: string; errorLabel: string;
  className?: string; priority?: 'high'; announce?: boolean; onReady?: () => void; onFailure?: () => void;
  onPhase?: (phase: 'loading' | 'ready' | 'failed') => void;
};
export default function MediaImage(props: Props) { return <ImageState key={props.src} {...props} />; }

function ImageState({ src, alt, width, height, loadingLabel, errorLabel, className, priority, announce = false, onReady, onFailure, onPhase }: Props) {
  const [phase, setPhase] = useState<'loading' | 'ready' | 'failed'>('loading');
  const current = useRef<'loading' | 'ready' | 'failed'>('loading'), active = useRef(true);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null), callbacks = useRef({ onReady, onFailure, onPhase });
  useEffect(() => { callbacks.current = { onReady, onFailure, onPhase }; }, [onReady, onFailure, onPhase]);
  useLayoutEffect(() => { callbacks.current.onPhase?.(current.current); }, []);
  const finish = useCallback((failed: boolean) => {
    if (!active.current || current.current === 'failed' || !failed && current.current === 'ready') return;
    current.current = failed ? 'failed' : 'ready';
    if (timer.current !== null) { clearTimeout(timer.current); timer.current = null; }
    setPhase(current.current);
    callbacks.current.onPhase?.(current.current);
    if (failed) callbacks.current.onFailure?.(); else callbacks.current.onReady?.();
  }, []);
  const cached = useCallback((node: HTMLImageElement | null) => {
    if (node?.complete && node.naturalWidth > 0) finish(false);
  }, [finish]);
  useEffect(() => {
    active.current = true;
    if (current.current === 'loading') timer.current = setTimeout(() => finish(true), 30_000);
    return () => { active.current = false; if (timer.current !== null) clearTimeout(timer.current); timer.current = null; };
  }, [finish]);
  return <span className="product-photo-frame" data-phase={phase} aria-busy={phase === 'loading'}>
    {phase === 'failed' ? <span className="product-photo-error" role="img" aria-label={errorLabel}><Icon name="image" /><span>{errorLabel}</span></span> : <img ref={cached} src={src} alt={alt} width={width} height={height} loading="eager" fetchPriority={priority} className={className} onLoad={() => finish(false)} onError={() => finish(true)} />}
    {phase === 'loading' && <span className="product-photo-busy"><Spinner /><span className="product-sr-only" role={announce ? 'status' : undefined}>{loadingLabel}</span></span>}
  </span>;
}
