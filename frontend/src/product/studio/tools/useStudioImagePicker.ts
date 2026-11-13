import { useContext, useLayoutEffect, useRef, useState } from 'react';
import { loadStudioImage, StudioImageError } from '../design/studioImage';
import type { StudioImage, StudioImageErrorCode } from '../design/studioImage';
import { StudioChooserContext } from '../session/studioChooserContext';

type Intent = { mode: 'add' | 'replace'; publish: (image: StudioImage) => void; controller: AbortController;
  ready: () => Promise<boolean> };
type Status = 'idle' | 'choosing' | 'pending' | 'added' | 'replaced' | 'chooseAgain' | StudioImageErrorCode;

/** No history or selection changes until a decoded file is accepted. An intent
 * belongs to the exact opener, not whichever layer is selected at completion. */
export function useStudioImagePicker() {
  const input = useRef<HTMLInputElement>(null), intent = useRef<Intent | null>(null);
  const gate = useContext(StudioChooserContext);
  const [status, setStatus] = useState<Status>('idle');
  useLayoutEffect(() => () => { intent.current?.controller.abort(); intent.current = null; }, []);
  function invalidate() {
    intent.current?.controller.abort();
    intent.current = null;
    setStatus('idle');
  }
  function open(mode: Intent['mode'], publish: Intent['publish']) {
    if (status === 'pending') return;
    if (!input.current) { setStatus('chooseAgain'); return; }
    // A fresh deliberate click can also recover from a chooser that did not
    // deliver its cancel event. It never attaches the old file to a new target.
    invalidate();
    const controller = new AbortController();
    const ready=gate ? gate.open(controller.signal) : async()=>true;
    if (!ready) { controller.abort(); setStatus('chooseAgain'); return; }
    const owner={mode,publish,controller,ready};
    intent.current = owner;
    setStatus('choosing');
    input.current.addEventListener('cancel', ()=>{void settle(owner,'idle');}, { once: true, signal: controller.signal });
    input.current.value = '';
    try {
      input.current.click(); // Must remain inside the initiating user gesture.
    } catch {
      void settle(owner,'chooseAgain');
    }
  }
  async function settle(owner:Intent, outcome:Status, image?:StudioImage) {
    if (intent.current !== owner) return;
    // Restore the chooser's original session before publishing either its file
    // or its result message. A late result never populates the next account.
    try {
      const admitted = await owner.ready().catch(()=>false);
      if (!admitted || intent.current !== owner || owner.controller.signal.aborted) return;
      if (image) {
        try { owner.publish(image); }
        catch { setStatus('decode'); return; }
      }
      setStatus(outcome);
    } finally {
      owner.controller.abort();
      if (intent.current === owner) intent.current = null;
    }
  }
  async function select(file?: File) {
    const owner = intent.current;
    if (!owner) {
      // A previous chooser can complete after the target was superseded.
      // Ask for a deliberate retry; never attach its file to the current layer.
      if (file) setStatus('chooseAgain');
      return;
    }
    if (!file) { await settle(owner,'idle'); return; }
    setStatus('pending');
    try {
      const image = await loadStudioImage(file, owner.controller.signal);
      await settle(owner,owner.mode === 'replace' ? 'replaced' : 'added',image);
    } catch (cause) {
      await settle(owner,cause instanceof StudioImageError ? cause.code : 'decode');
    }
  }
  return { input, open, select, invalidate, status };
}
