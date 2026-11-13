import {useEffect,useState} from 'react';
import type {AccountCartAdmission} from '../cart/accountCartAdmission';
import {decodePrivateLayers} from '../cart/readPrivateCartDesign';
import {isLoopFinish} from '../cart/appliedLoopDesign';
import {firstLettering,type StudioObject} from '../studio/design/studioComposition';
import {createStudioFontLibrary} from '../studio/design/studioFonts';
import LetteringSketch from '../studio/preview/LetteringSketch';
import {FlatSurfaceSketch} from '../studio/preview/FlatSurfaceSketch';
import {personalizationCopy} from '../studio/personalizationCopy';
import {productCopy} from '../copy';
import Modal from '../Modal';
import {useProductLocale} from '../useProductLocale';
import {designLibraryCopy} from './designLibraryCopy';
import {openDesign,type LibraryItem} from './designLibraryApi';
import type {SwatchColors} from '../productDisplay';

/** Download source layers only after an explicit preview. The shelf uses public
 * thumbnails; closing this dialog releases its private font library and pixels. */
export default function LibraryPreview({admission,item,colors,close}:{admission:AccountCartAdmission;item:LibraryItem;colors?:SwatchColors;close:()=>void}){
  const locale=useProductLocale(),labels=designLibraryCopy[locale],preview=personalizationCopy[locale].preview;
  const [layers,setLayers]=useState<StudioObject[]|'failed'|null>(null);
  useEffect(()=>{
    const op=new AbortController(),lib=createStudioFontLibrary(),signal=AbortSignal.any([op.signal,AbortSignal.timeout(30000)]);
    const current=()=>!signal.aborted&&admission.access.ownsDisplay();
    void openDesign(admission,item.id,signal).then(async saved=>{
      if(saved.item.version!==item.version||saved.item.sku!==item.sku)throw Error('DESIGN_CHANGED');
      const result=await decodePrivateLayers(saved.design,lib,signal,current);if(current())setLayers(result);
    }).catch(()=>{if(!op.signal.aborted)setLayers('failed');});
    return ()=>{op.abort();lib.dispose();};
  },[admission,item]);
  return <Modal title={preview} closeLabel={productCopy[locale].close} onClose={close}>
    <div className="library-preview" data-private-account>{!layers||layers==='failed'?<p role="status">{layers===null?labels.loading:labels.failed}</p>:
      item.offerId==='loop'&&isLoopFinish(item.variantId)?<LetteringSketch variant={item.variantId} colors={colors}
        value={firstLettering({objects:layers,selectedId:null},item.variantId)} objects={layers} label={preview}/>:
      <FlatSurfaceSketch objects={layers} fill={colors?.[0]??'#e5e5e5'} label={preview}/>}</div>
  </Modal>;
}
