import { useLayoutEffect, useRef, useState } from 'react';
import { loadStudioImage, StudioImageError } from '../design/studioImage';
import type { StudioImage, StudioImageErrorCode } from '../design/studioImage';

type Intent = { mode: 'add' | 'replace'; publish: (image: StudioImage) => void; controller: AbortController };
type Status = 'idle' | 'pending' | 'added' | 'replaced' | StudioImageErrorCode;

/** No history or selection changes until a decoded file is accepted. An intent
 * belongs to the exact opener, not whichever layer is selected at completion. */
export function useStudioImagePicker() {
  const input = useRef<HTMLInputElement>(null), intent = useRef<Intent | null>(null);
  const [status, setStatus] = useState<Status>('idle');
  useLayoutEffect(() => () => { intent.current?.controller.abort(); intent.current = null; }, []);
  function invalidate() {
    intent.current?.controller.abort();
    intent.current = null;
    setStatus('idle');
  }
  function open(mode: Intent['mode'], publish: Intent['publish']) {
    if (!input.current || status === 'pending') return;
    invalidate();
    const controller = new AbortController();
    intent.current = { mode, publish, controller };
    input.current.addEventListener('cancel', invalidate, { once: true, signal: controller.signal });
    input.current.value = '';
    input.current.click(); // Must remain inside the initiating user gesture.
  }
  async function select(file?: File) {
    const owner = intent.current;
    if (!owner) return;
    if (!file) { invalidate(); return; }
    setStatus('pending');
    try {
      const image = await loadStudioImage(file, owner.controller.signal);
      if (intent.current !== owner) return;
      owner.publish(image);
      setStatus(owner.mode === 'replace' ? 'replaced' : 'added');
    } catch (cause) {
      if (intent.current === owner) setStatus(cause instanceof StudioImageError ? cause.code : 'decode');
    } finally {
      owner.controller.abort(); // Release the native chooser listener as well.
      if (intent.current === owner) intent.current = null;
    }
  }
  return { input, open, select, invalidate, status };
}
