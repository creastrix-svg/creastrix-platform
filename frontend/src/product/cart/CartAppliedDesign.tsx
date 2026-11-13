import { useEffect, useRef, useState } from 'react';
import { firstLettering, type StudioObject } from '../studio/design/studioComposition';
import { createStudioFontLibrary } from '../studio/design/studioFonts';
import LetteringSketch from '../studio/preview/LetteringSketch';
import {FlatSurfaceSketch} from '../studio/preview/FlatSurfaceSketch';
import { useProductLocale } from '../useProductLocale';
import type { VariantId } from '../fixtures/loop';
import type { AccountCartAdmission } from './accountCartAdmission';
import { accountProductLabels, designLabels } from './accountProductLabels';

import {readPrivateCartDesign} from './readPrivateCartDesign';

// Account preview owns a fresh, short-lived font library. Private objects
// never enter the guest editor, Redux, IndexedDB or any public catalog cache.
export default function CartAppliedDesign({admission,lineId,variant,sku=variant,swatch,secondSwatch,flat=false}:{
  admission:AccountCartAdmission;lineId:string;variant:VariantId;sku?:string;swatch?:string;secondSwatch?:string;flat?:boolean;
}) {
  const locale=useProductLocale(), label=designLabels[locale][0];
  const active=useRef<AbortController|null>(null), fonts=useRef<ReturnType<typeof createStudioFontLibrary>|null>(null);
  const [state,setState]=useState<'idle'|'reading'|'failed'|StudioObject[]>('idle');
  useEffect(()=>()=>{ active.current?.abort();active.current=null;fonts.current?.dispose();fonts.current=null; },[admission,lineId]);
  async function show() {
    if(active.current || !admission.access.ownsDisplay()) return;
    const op=new AbortController(), signal=AbortSignal.any([op.signal,AbortSignal.timeout(30000)]);
    active.current=op;
    const lib=createStudioFontLibrary(); fonts.current=lib;
    const current=()=>active.current===op && !op.signal.aborted && admission.access.ownsDisplay();
    setState('reading');
    try {
      const objects=await readPrivateCartDesign(admission,lineId,sku,lib,signal);
      if(!current())throw Error('DESIGN_UNAVAILABLE');
      setState(objects);
    } catch {
      lib.dispose();
      if(current()) { active.current=null;setState('failed'); }
    }
  }
  return <div data-applied-design>{Array.isArray(state)
    ? <div data-private-design-preview className="product-design-thumbnail">
        {flat ? <FlatSurfaceSketch fill={swatch??'#89918d'} objects={state} label={label}/>
          : <LetteringSketch variant={variant} colors={swatch && secondSwatch ? [swatch,secondSwatch] : [swatch??'#89918d']}
              objects={state} value={firstLettering({objects:state,selectedId:null},variant)} label={label} detail/>}
      </div>
    : <>{state==='failed' && <span role="status">{accountProductLabels[locale][2]} · </span>}
      <button type="button" className="product-text-button" disabled={state==='reading'} onClick={()=>void show()}>{label}</button></>}
  </div>;
}
