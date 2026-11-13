import type {StudioObject} from '../design/studioComposition';
import {loopLetteringArea as area} from '../design/studioGeometry';
import StudioObjectsSketch, {type StudioObjectInteraction} from './StudioObjectsSketch';

/** This template defines a print surface, not the 3D shape of a new SKU.
 * Show its true drawing coordinates without inventing a mapping onto a photo. */
export function FlatSurfaceSketch({objects,fill,label,...interaction}:StudioObjectInteraction & {
  objects:readonly StudioObject[];fill:string;label:string;
}){
  return <svg role={interaction.onSelectObject?'group':'img'} aria-label={label} viewBox="147 213 186 129" width="100%">
    <rect x={area.left} y={area.top} width={area.right-area.left} height={area.bottom-area.top} fill={fill} stroke="currentColor" strokeWidth=".5"/>
    <StudioObjectsSketch target="cobalt" objects={objects} {...interaction}/>
  </svg>;
}
