import { lazy, Suspense, useContext, useEffect, useId, useReducer, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { useAppDispatch, useAppSelector } from '../../hooks';
import { isVariantId, variantById } from '../fixtures/loop';
import { readCartSku } from '../fixtures/cartSkus';
import {connectedSku,type CatalogProduct,type CatalogVariant} from '../catalog/localCatalog';
import {FlatSurfaceSketch} from './preview/FlatSurfaceSketch';
import { money } from '../productDisplay';
import { loopPrice, loopDesignFee } from '../loopPricing';
import { loopPricingCopy } from '../loopPricingCopy';
import type { VariantId } from '../fixtures/loop';
import { productCopy } from '../copy';
import { StorefrontOrigin } from '../storefrontOrigin';
import { Icon, Spinner } from '../Icon';
import LetteringSketch from './preview/LetteringSketch';
import MediaErrorBoundary from '../media/MediaErrorBoundary';
import Modal from '../Modal';
import { characters, cleanLetteringText, defaultLettering, letteringColors, sameLettering } from './design/personalization';
import type { Lettering, LetteringTarget } from './design/personalization';
import { compositionHistory, compositionKey, firstLettering, firstArtwork, imageLayout, MAX_STUDIO_OBJECTS, studioArea } from './design/studioComposition';
import type { StudioObject, ImageFit } from './design/studioComposition';
import StudioObjectTools from './tools/StudioObjectTools';
import { studioCompositionCopy } from './design/studioCompositionCopy';
import { personalizationCopy } from './personalizationCopy';
import { PersonalizationSession, usePersonalizationSession } from './session/personalizationSession';
import { compositionReducer } from './design/studioComposition';
import type { CompositionDocument } from './design/studioComposition';
import { cartDesignChanged, cartLineDesignCleared, cartLineId } from '../demoStore';
import type { CartLine } from '../demoStore';
import { designKey, saveDesign, sessionWithDocument } from './session/designSnapshot';
import { useDesignDocument } from './session/useDesignDocument';
import { studioCartCopy } from '../cart/studioCartCopy';
import { GuestCartActionsEnabled } from '../cart/guestCartActions';
import { ReadOnlyCartNotice } from '../detail/ProductPurchasePanel';
import { permanentCatalogMode,sharedCatalogMode } from '../catalog/permanentCatalog';
import {LoadLibraryStudio,MissingLibraryDesign,SaveLibraryDesign} from '../designs/LibraryStudio';
import {editorScope,isLibraryId} from '../designs/designLibraryApi';
import GuestDesignSave from '../designs/GuestDesignSave';
import {projectAccountDesign} from '../cart/appliedLoopDesign';
import { accountDesignNote, designFontHint, designLabels } from '../cart/accountProductLabels';
import {AccountPurchaseContext} from '../cart/accountPurchaseContext';
import { isLoopV2Font, loopV2Fonts } from './design/loopV2Fonts';
import { transferableLoopFont } from './design/studioFonts';
import { NotFoundView } from '../NotFoundView';
import { useProductLocale } from '../useProductLocale';
import './personalization.css';
import { useStudioCreative, creativeDraft, sameArtwork } from './session/studioCreativeContext';
import { studioExtensionCopy } from './studioExtensionCopy';
import StudioFontTools from './tools/StudioFontTools';
import StudioArtworkTools from './tools/StudioArtworkTools';
import { useStudioImagePicker } from './tools/useStudioImagePicker';
import StudioIdeaTools from './tools/StudioIdeaTools';
import StudioLampPreview from './preview/StudioLampPreview';
import LampSketch from './preview/LampSketch';
import LampZoneSketch from './preview/LampZoneSketch';
import type { LampPatternId } from './preview/lampPatterns';
import type { ImagePlacement } from './design/studioImage';
import { canPersonalize, canUseStudioFont, canUseStudioTool, getStudioProduct, getStudioZone } from './studioProducts';
import type { StudioProductId, StudioZoneId, StudioTool } from './studioProducts';
import { studioZoneCopy } from './studioZoneCopy';
import { inspectZoneLettering } from './design/studioLettering';
import type { LetteringIssue, StencilStatus } from './design/studioLettering';

const ModelPreview = lazy(() => new Promise<typeof import('../media/ModelPreview')>((resolve, reject) => {
  // Bound a stalled chunk, without delaying successful loads or claiming to
  // cancel the browser's module request. Late completion cannot revive failure.
  const deadline = setTimeout(() => reject(new Error('MODEL_PREVIEW_IMPORT_TIMEOUT')), 30_000);
  void import('../media/ModelPreview').then(module => { clearTimeout(deadline); resolve(module); }, error => { clearTimeout(deadline); reject(error); });
}));
function loopPreviewContent(value: Lettering, objects: StudioObject[], artwork: ImagePlacement, original: boolean) {
  return original ? { value: { ...value, text: '' }, objects: [], artwork: undefined } : { value, objects, artwork };
}

const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value));
function measureText(text: string, font: string) {
  const context = document.createElement('canvas').getContext('2d');
  if (!context) return characters(text).length * parseFloat(font) * .65;
  context.font = font;
  context.textAlign = 'center';
  context.textBaseline = 'middle';
  const metrics = context.measureText(text);
  const ink = [metrics.actualBoundingBoxLeft, metrics.actualBoundingBoxRight, metrics.actualBoundingBoxAscent, metrics.actualBoundingBoxDescent];
  // Advance width alone misses overhanging italic/custom glyphs. Measure the
  // same centred envelope used by both SVG and canvas text renderers.
  if (!ink.every(Number.isFinite)) return metrics.width;
  return {
    width: Math.max(metrics.width, 2 * Math.abs(ink[0]), 2 * Math.abs(ink[1])),
    height: 2 * Math.max(Math.abs(ink[2]), Math.abs(ink[3])),
  };
}

export default function PersonalizationView() {
  const guestActions = useContext(GuestCartActionsEnabled);
  const [params] = useSearchParams(), remembered = useAppSelector(state => state.productDemo.selectedVariant);
  const values = params.getAll('variant');
  const variant = values.length === 0 ? remembered : values.length === 1 && isVariantId(values[0]) ? values[0] : 'cobalt';
  const products = params.getAll('product');
  if(permanentCatalogMode && ((products.length>0 && (products.length!==1 || products[0]!=='loop'))
      || (values.length>0 && (values.length!==1 || !isVariantId(values[0]))))) return <NotFoundView/>;
  const initialProduct = products.length === 1 && products[0] === 'lamp' ? 'lamp' : 'loop';
  if(initialProduct==='loop' && permanentCatalogMode && !readCartSku(variant).acceptsDesign)return <NotFoundView/>;
  const editing = params.getAll('edit');
  if(params.has('savedDesign') && (editing.length || products.length && initialProduct!=='loop' || values.length!==1 ||
    params.has('accountLine')||params.has('cartLine')))return <MissingLibraryDesign/>;
  if (editing.length) {
    if (!guestActions) return <ReadOnlyCartNotice />;
    const validTarget = editing.length === 1 && (values.length === 0 || (values.length === 1 && isVariantId(values[0])))
      && (products.length === 0 || (products.length === 1 && products[0] === 'loop'));
    return <CartEditor key={editing[0]} id={validTarget ? editing[0] : ''} expectedVariant={values[0]} />;
  }
  return <StudioEntry key={variant + initialProduct} variant={variant} initialProduct={initialProduct} />;
}

function CartEditor({ id, expectedVariant }: { id: string; expectedVariant?: string }) {
  const line = useAppSelector(state => state.productDemo.cart.find(line => cartLineId(line) === id));
  const [original] = useState(line), c = studioCartCopy[useProductLocale()];
  if (!original?.design || !isVariantId(original.variant) || !line || line.variant !== original.variant ||
    (expectedVariant !== undefined && line.variant !== expectedVariant) || designKey(line.design) !== designKey(original.design))
    return <section><p role="alert">{c.missing}</p><Link to="/preview/cart">{c.cancel}</Link></section>;
  return <LoadCartEditor line={{ ...original, variant: original.variant }} />;
}
function LoadCartEditor({ line }: { line: CartLine<VariantId> }) {
  const { document, error } = useDesignDocument(line.design!), c = studioCartCopy[useProductLocale()];
  return document ? <CartEditorSession line={line} document={document} /> : <p role="status">{error ? c.restoreFailed : c.loading}</p>;
}
function CartEditorSession({ line, document }: { line: CartLine<VariantId>; document: CompositionDocument }) {
  const [state, dispatch] = useReducer(compositionReducer, document, value => sessionWithDocument(line.variant, value));
  const storeDispatch = useAppDispatch(), navigate = useNavigate();
  return <PersonalizationSession.Provider value={{ state, dispatch }}><Studio variant={line.variant} initialProduct="loop" onSave={objects => {
    const target = { id: cartLineId(line), variant: line.variant, expected: designKey(line.design) };
    if (objects.length === 0) storeDispatch(cartLineDesignCleared(target));
    else storeDispatch(cartDesignChanged({ ...target, design: saveDesign({ objects, selectedId: null })! }));
    navigate('/preview/cart');
  }} /></PersonalizationSession.Provider>;
}

type PublishedSurface={offer:CatalogProduct;finish:CatalogVariant};
export function PublishedSurfaceEditor({offer,finish}:PublishedSurface){
  return <StudioEntry key={finish.sku} variant="cobalt" initialProduct="loop" surface={{offer,finish}}/>;
}
type StudioEntryProps={variant:VariantId;initialProduct:StudioProductId;surface?:PublishedSurface};
function StudioEntry(props:StudioEntryProps){
  const admission=useContext(AccountPurchaseContext),[params,setParams]=useSearchParams();
  const [createdHere,setCreatedHere]=useState<{id:string;scope:string}>();
  const ids=params.getAll('savedDesign'),sku=props.surface?.finish.sku??props.variant;
  const item=props.surface??(()=>{const ref=connectedSku(sku);return ref&&{offer:ref.product,finish:ref.variant};})();
  const created=(id:string)=>{
    if(!admission)return;
    setCreatedHere({id,scope:editorScope(admission)});const next=new URLSearchParams(params);next.set('savedDesign',id);next.set('variant',item?.finish.id??props.variant);
    setParams(next,{replace:true,preventScrollReset:true});
  };
  if(ids.length){
    if(!sharedCatalogMode||!admission||ids.length!==1||!isLibraryId(ids[0])||!item||!item.offer.personalizable
      ||params.getAll('variant').length!==1||params.get('variant')!==item.finish.id||params.has('accountLine')||item.finish.studioFormat!=='surface-print-v1'||item.finish.templateRevision!=='2')
      return <MissingLibraryDesign/>;
    if(createdHere?.id!==ids[0]||createdHere.scope!==editorScope(admission))return <LoadLibraryStudio key={ids[0]} id={ids[0]} admission={admission} slot={props.variant}
      sku={sku} variantId={item.finish.id} offerId={item.offer.id}>{version=><Studio {...props} original={{id:ids[0],version}}/>}</LoadLibraryStudio>;
  }
  return <Studio {...props} onCreated={created}/>;
}
function Studio({ variant, initialProduct, onSave,surface,original:libraryItem,onCreated }: StudioEntryProps & {
  onSave?: (objects: readonly StudioObject[]) => void;original?:{id:string;version:string};onCreated?:(id:string)=>void }) {
  const language = useProductLocale(), p = personalizationCopy[language], t = productCopy[language], color = surface?.finish??variantById(variant);
  const e = studioExtensionCopy[language], z = studioZoneCopy[language], creative = useStudioCreative();
  const example = initialProduct;
  const privateStudio=useContext(AccountPurchaseContext);
  const origin = useContext(StorefrontOrigin);
  const sectionOrigin = origin?.startsWith('/preview/') ? origin : null;
  const returnToProduct = !sectionOrigin && example === 'loop';
  const returnPath = onSave ? '/preview/cart' : sectionOrigin ?? (returnToProduct ? '/products/loop?variant=' + variant : '/catalog');
  const cartCopy = studioCartCopy[language];
  const [selectedZone, setSelectedZone] = useState<StudioZoneId>(initialProduct === 'lamp' ? 'panel' : 'surface');
  const [selectedTool, setSelectedTool] = useState<StudioTool>(initialProduct === 'lamp' ? 'pattern' : 'text');
  const original=getStudioProduct(example);
  const profile = surface?{id:original.id,name:surface.offer.name,readyMade:true,zones:original.zones,
    enabled:surface.offer.personalizable && surface.finish.studioFormat==='surface-print-v1' && surface.finish.templateRevision==='2'}:original;
  const available = canPersonalize(profile);
  const zone = getStudioZone(profile, selectedZone) ?? profile.zones[0];
  // The profile drives both visible controls and action admission. A URL, stale
  // tab choice or in-flight file result cannot turn on an unavailable operation.
  // Explicit competing choices supersede a pending file/font operation. Ordinary
  // text edits retain the font request and are preserved by its latest callback.
  const { input: imageInput, open: openImagePicker, select: acceptImageFile, invalidate: invalidateImage, status: imageStatus } = useStudioImagePicker();
  const [fontEpoch, setFontEpoch] = useState(0), activeFontEpoch = useRef(0);
  const supersedeFont = () => setFontEpoch(++activeFontEpoch.current);
  const [compare, setCompare] = useState(false);
  const baseTarget = example === 'lamp' ? 'lamp' : variant;
  const target = example === 'lamp' && zone?.id === 'panel' ? 'lamp-panel' : baseTarget;
  const creativeTarget = target;
  const draft = creativeDraft(creative, creativeTarget), baseDraft = creativeDraft(creative, baseTarget);
  const panelDraft = creativeDraft(creative, 'lamp-panel');
  const { state, dispatch } = usePersonalizationSession(), history = compositionHistory(state, target);
  const document = history.present, selected = document.objects.find(object => object.id === document.selectedId);
  const requestedTool = (selectedTool === 'text' || selectedTool === 'image') && selected ? selected.kind : selectedTool;
  const tool = zone?.tools.includes(requestedTool) ? requestedTool : zone?.tools[0];
  const value = selected?.kind === 'text' ? selected.value : defaultLettering(target);
  const artwork = selected?.kind === 'image' ? selected.value : firstArtwork(document);
  const baseDocument = compositionHistory(state, baseTarget).present, panelDocument = compositionHistory(state, 'lamp-panel').present;
  const lampDraft = { ...baseDraft, artwork: firstArtwork(baseDocument), pattern: panelDraft.pattern, bridges: true };
  const c = studioCompositionCopy[language];
  // Route changes keep the session draft but end the previous editing gesture.
  // Returning to a product must start a fresh, independently undoable change.
  useEffect(() => () => dispatch({ type: 'endGroup', variant: target }), [dispatch, target]);
  const [stencilStatus, setStencilStatus] = useState<StencilStatus>('pending');
  const fontLibrary = creative.fonts(), needsStencil = example === 'lamp' && profile.zones.some(item => item.fonts.includes('stencil'));
  const requiredFaces=permanentCatalogMode ? loopV2Fonts.filter(id=>baseDocument.objects.some(o=>o.kind==='text' && o.value.font===id)).join(',') : '';
  const [fontCheck,setFontCheck]=useState<{key:string;failed:boolean}|null>(null);
  const [fontAttempt,retryFontCheck]=useReducer((n:number)=>n+1,0);
  useEffect(()=>{
    if(!requiredFaces) return;
    const controller=new AbortController(), key=requiredFaces;
    const faces=key.split(',').filter(isLoopV2Font);
    void Promise.all(faces.map(id=>fontLibrary.loadBuiltin(id,{signal:controller.signal}))).then(
      ()=>{if(!controller.signal.aborted) setFontCheck({key,failed:false});},
      ()=>{if(!controller.signal.aborted) setFontCheck({key,failed:true});});
    return ()=>controller.abort();
  },[fontLibrary,requiredFaces,fontAttempt]);
  useEffect(() => {
    if (!needsStencil) return;
    const controller = new AbortController();
    void fontLibrary.loadBuiltin('stencil', { signal: controller.signal }).then(() => {
      if (!controller.signal.aborted) setStencilStatus('ready');
    }, () => {
      // A successful explicit retry wins over a late failure from this initial
      // request. Loaded faces remain owned by this same session's font library.
      if (!controller.signal.aborted) setStencilStatus(previous => previous === 'ready' ? previous : 'failed');
    });
    return () => controller.abort();
  }, [fontLibrary, needsStencil]);
  const [resetOpen, setResetOpen] = useState(false), [appliedMessage, setAppliedMessage] = useState(false);
  const [textLimited, setTextLimited] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const id = useId(), input = useRef<HTMLTextAreaElement>(null);
  const inspected = inspectZoneLettering(profile, zone?.id ?? 'surface', value, measureText, stencilStatus);
  function inspectObjects(objects: readonly StudioObject[], owner: LetteringTarget, zoneId: StudioZoneId) {
    let issue: LetteringIssue | undefined;
    const shownObjects = objects.map(object => {
      if (object.kind === 'image') {
        if (!canUseStudioTool(profile, zoneId, 'image')) issue = 'limits';
        return object;
      }
      const result = inspectZoneLettering(profile, zoneId, object.value, measureText, stencilStatus);
      if(permanentCatalogMode && example==='loop' && owner===variant) {
        const font=object.value.font;
        if(!transferableLoopFont(font)) issue??='font';
        else if(isLoopV2Font(font) && (fontCheck?.key!==requiredFaces || fontCheck.failed)) issue??=fontCheck?.key===requiredFaces ? 'font' : 'loading';
      }
      issue ??= result.issue;
      return { ...object, value: { ...result.shown, text: result.valid ? result.shown.text : '' } };
    });
    return { issue, valid: !issue, objects: shownObjects, shown: firstLettering({ objects: shownObjects, selectedId: null }, owner) };
  }
  const baseInspected = inspectObjects(baseDocument.objects, baseTarget, example === 'lamp' ? 'base' : 'surface');
  const panelInspected = inspectObjects(panelDocument.objects, 'lamp-panel', 'panel');
  const { layout, shown } = inspected, cutText = zone?.operation === 'cut';
  const limits = zone?.textLimits ?? { maxCharacters: 32, maxLines: 2, minSize: 18, maxSize: 32 };
  const limitMessage = (owner: StudioZoneId) => {
    const bounds = getStudioZone(profile, owner)?.textLimits ?? limits;
    return p.textLimited.replace('{characters}', String(bounds.maxCharacters)).replace('{lines}', String(bounds.maxLines));
  };
  const issueText = (issue?: LetteringIssue, owner = selectedZone) => permanentCatalogMode && issue==='font' ? designFontHint[language]
    : permanentCatalogMode && issue==='loading' ? cartCopy.loading : issue === 'unsupported' ? z.cutUnsupported : issue === 'loading' ? z.cutFontLoading : issue === 'font' ? z.cutFontFailed : issue === 'limits' ? limitMessage(owner) : issue === 'fit' ? p.textTooWide : '';
  const invalidZone = example === 'lamp' ? !panelInspected.valid ? 'panel' : !baseInspected.valid ? 'base' : undefined : undefined;
  const wholeIssue = invalidZone ? `${z.zoneNames[invalidZone]}: ${issueText(invalidZone === 'panel' ? panelInspected.issue : baseInspected.issue, invalidZone)}` : issueText(baseInspected.issue);
  // Changing font/size keeps the artwork inside the visual working area. The
  // complete draft is stored only when the user edits; rendering has no writes.
  const hasContent = baseDocument.objects.some(object => object.kind === 'text' ? !!object.value.text.trim() : !!object.value.image);
  const valid = available && (hasContent || example === 'lamp') && !wholeIssue;
  const dbVariant=surface?.finish??(sharedCatalogMode && example==='loop'?connectedSku(variant)?.variant:undefined);
  const libraryEligible=sharedCatalogMode && !onSave && example==='loop' && dbVariant?.studioFormat==='surface-print-v1' && dbVariant.templateRevision==='2';
  const snapshotForLibrary=()=>{
    if(!valid||!libraryEligible||!dbVariant)throw Error('DESIGN_LIBRARY_INVALID');
    const saved=saveDesign({...baseDocument,objects:baseInspected.objects});
    if(!saved)throw Error('DESIGN_LIBRARY_INVALID');
    return projectAccountDesign(dbVariant.sku,dbVariant.templateRevision,saved);
  };
  const priceCopy = loopPricingCopy[language];
  // Only an intentionally empty cart draft may convert the line to a plain SKU;
  // a failed validation or an image still being loaded must never remove a design.
  const resolvingImage = imageStatus === 'choosing' || imageStatus === 'pending';
  const clearingLine = !!onSave && available && !hasContent && !wholeIssue && !resolvingImage;
  const quote = example === 'loop' && (valid || clearingLine || (!onSave && !hasContent)) ? surface
    ? {baseCents:surface.finish.cents,feeCents:valid?surface.offer.designFeeCents:0,unitCents:surface.finish.cents+(valid?surface.offer.designFeeCents:0)}
    : loopPrice(variant, valid) : undefined;
  const applied = state.applied[target];
  const baseApplied = state.applied[baseTarget], panelApplied = state.applied['lamp-panel'];
  const dirty = !baseApplied || compositionKey(baseInspected.objects) !== compositionKey(baseApplied) || !sameArtwork(baseDraft, creative.applied[baseTarget])
    || example === 'lamp' && (!panelApplied || compositionKey(panelInspected.objects) !== compositionKey(panelApplied) || !sameArtwork(panelDraft, creative.applied['lamp-panel']));
  const basePreview = { ...baseInspected.shown, text: baseInspected.valid ? baseInspected.shown.text : '' };
  const panelPreview = panelInspected.valid && stencilStatus === 'ready' ? panelInspected.shown : undefined;
  const update = (patch: Partial<Lettering>, group?: string) => {
    if (!zone || selected?.kind !== 'text' || !canUseStudioTool(profile, zone.id, 'text')) return;
    if (patch.font && !canUseStudioFont(profile, zone.id, patch.font)) return;
    // FontTools calls back only after successful loading. This also allows a
    // deliberate retry to recover a failed initial stencil request.
    if (patch.font === 'stencil') setStencilStatus('ready');
    if (permanentCatalogMode && isLoopV2Font(patch.font)) retryFontCheck();
    setAppliedMessage(false); setTextLimited(false);
    dispatch({ type: 'update', variant: target, id: selected.id, object: { ...selected, value: { ...shown, ...patch,
      ...(permanentCatalogMode ? {font:patch.font??value.font} : {}),
      text: cleanLetteringText(patch.text ?? shown.text, limits),
      size: clamp(patch.size ?? shown.size, limits.minSize, limits.maxSize),
      ...(!zone.allowPosition ? { x: 240, y: 278 } : {}),
      color: zone.inkColors.length === 1 ? zone.inkColors[0] : patch.color ?? shown.color } }, group });
  };
  const endGroup = () => dispatch({ type: 'endGroup', variant: target });
  const move = (x: number, y: number) => update({ x: clamp(x, layout.minX, layout.maxX), y: clamp(y, layout.minY, layout.maxY) }, 'position');
  const changeArtwork = (artwork: ImagePlacement, group?: string) => {
    if (!zone || selected?.kind !== 'image' || !canUseStudioTool(profile, zone.id, 'image')) return;
    dispatch({ type: 'update', variant: target, id: selected.id, object: { ...selected, value: artwork }, group });
    setAppliedMessage(false);
  };
  const changeFit = (fit: ImageFit) => {
    if (selected?.kind === 'image') dispatch({ type: 'update', variant: target, id: selected.id, object: { ...selected, fit } });
    setAppliedMessage(false);
  };
  const pendingImage = imageStatus === 'pending';
  const imageError = ({size:e.imageTooLarge,dimensions:e.imageDimensions,empty:e.imageEmptyFile,type:e.imageType,
    decode:e.imageError,chooseAgain:e.imageChooseAgain,added:'',replaced:'',idle:'',pending:'',choosing:''})[imageStatus];
  const pickImage = (mode: 'add' | 'replace') => {
    if (!zone || !canUseStudioTool(profile, zone.id, 'image') || pendingImage) return;
    if (mode === 'add' ? document.objects.length >= MAX_STUDIO_OBJECTS : selected?.kind !== 'image') return;
    openImagePicker(mode, image => {
      if (mode === 'add') dispatch({ type: 'add', variant: target, kind: 'image', image });
      else if (selected) dispatch({ type: 'replaceImage', variant: target, id: selected.id, image });
      supersedeFont(); setSelectedTool('image'); setCompare(false);
      setAppliedMessage(false); setTextLimited(false); setResetOpen(false);
    });
  };
  const chooseTool = (next: StudioTool) => {
    if (!zone || !canUseStudioTool(profile, zone.id, next) || onSave && next === 'ai') return;
    if (next === 'image' && !document.objects.some(item=>item.kind==='image')) {
      pickImage('add');
      return;
    }
    if (tool === next && (next !== 'text' || selected?.kind === 'text')) return;
    if (next === 'text' || next === 'image') {
      const object = selected?.kind === next ? selected : document.objects.find(item => item.kind === next);
      if (!object && next === 'image') return;
      leaveZone(); setSelectedTool(next);
      if (object) dispatch({ type: 'select', variant: target, id: object.id });
      else dispatch({ type: 'add', variant: target, kind: 'text' });
    } else { leaveZone(); setSelectedTool(next); }
  };
  const leaveZone = () => {
    endGroup(); supersedeFont(); invalidateImage();
    setAppliedMessage(false); setTextLimited(false); setResetOpen(false);
  };
  const chooseZone = (next: StudioZoneId) => {
    const nextZone = getStudioZone(profile, next);
    if (!available || !nextZone || nextZone.id === zone?.id) return;
    leaveZone(); setSelectedZone(next); setSelectedTool(nextZone.tools[0]);
    const nextTarget = example === 'lamp' && next === 'panel' ? 'lamp-panel' : baseTarget;
    const firstTool = nextZone.tools[0];
    if (firstTool === 'text' || firstTool === 'image') {
      const object = compositionHistory(state, nextTarget).present.objects.find(item => item.kind === firstTool);
      if (object) dispatch({ type: 'select', variant: nextTarget, id: object.id });
    }
  };
  const selectObject = (id: string) => {
    const object = document.objects.find(item => item.id === id);
    if (!object || (id === document.selectedId && tool === object.kind)) return;
    leaveZone(); dispatch({ type: 'select', variant: target, id }); setSelectedTool(object.kind);
  };
  const moveObject = (id: string, x: number, y: number) => {
    const object = document.objects.find(item => item.id === id);
    if (!object || !zone) return;
    let moved: StudioObject;
    if (object.kind === 'text') {
      const result = inspectZoneLettering(profile, zone.id, { ...object.value, x, y }, measureText, stencilStatus);
      moved = { ...object, value: result.shown };
    } else {
      if (object.fit !== 'free') return;
      const box = imageLayout({ ...object.value, x, y }, object.fit, studioArea(target));
      moved = { ...object, value: { ...object.value, x: box.x, y: box.y } };
    }
    dispatch({ type: 'update', variant: target, id, object: moved, group: 'drag' }); setAppliedMessage(false);
  };
  function choosePattern(pattern: LampPatternId) {
    if (!zone || !canUseStudioTool(profile, zone.id, 'pattern')) return;
    creative.edit(creativeTarget, previous => ({ ...previous, pattern, bridges: true, content: 'pattern' }));
    setAppliedMessage(false);
  }
  function apply() {
    if (resolvingImage) return;
    if (clearingLine && onSave) { onSave([]); return; }
    if (!valid) { input.current?.focus(); return; }
    if (onSave) { onSave(baseInspected.objects); return; }
    dispatch({ type: 'apply', variant: baseTarget, objects: baseInspected.objects });
    creative.apply(baseTarget, baseDraft);
    if (example === 'lamp') {
      dispatch({ type: 'apply', variant: 'lamp-panel', objects: panelInspected.objects });
      creative.apply('lamp-panel', panelDraft);
    }
    setTextLimited(false);
    setAppliedMessage(true);
  }
  const toolLabel = (item: StudioTool) => ({ text: e.textTab, image: e.imageTab, ai: e.aiTab, pattern: z.patternTab })[item];
  const toolIcon = (item: StudioTool) => ({ text: 'layers', image: 'image', ai: 'spark', pattern: 'layers' })[item] as 'layers' | 'image' | 'spark';
  const flat=surface && <FlatSurfaceSketch label={p.preview} fill={color.colors[0]} objects={baseInspected.objects} selectedId={document.selectedId} onSelectObject={selectObject} onMoveObject={moveObject} onMoveEnd={endGroup}/>;
  const patterns = <div className="studio-pattern-choices" role="group" aria-label={e.cutout}>{(['botanical', 'forest', 'orbit'] as const).map(pattern => <button type="button" key={pattern} aria-label={{ botanical: e.patternBotanical, forest: e.patternForest, orbit: e.patternOrbit }[pattern]} aria-pressed={panelDraft.pattern === pattern} onClick={() => choosePattern(pattern)}><LampSketch pattern={pattern} bridges mode="panel" label={{ botanical: e.patternBotanical, forest: e.patternForest, orbit: e.patternOrbit }[pattern]} /><span>{{ botanical: e.patternBotanical, forest: e.patternForest, orbit: e.patternOrbit }[pattern]}</span></button>)}</div>;
  return <div className="personalization-studio" data-studio-variant={variant} data-studio-product={example} data-studio-zone={zone?.id}>
    <div className="studio-topline"><Link className="product-back" to={surface?surface.offer.href+'?variant='+surface.finish.id:returnPath} state={!onSave && returnToProduct ? { productReturn: true } : null}><span className="product-flip"><Icon name="arrow" /></span>{surface ? p.back : onSave ? cartCopy.cancel : returnToProduct ? p.back : sectionOrigin ? t.back : t.browseCollection}</Link><span className="studio-product-id">{example === 'lamp' ? e.lampName : surface?.offer.name??'Loop'} <span>·</span> {example === 'lamp' ? e.lampDescription : color.name[language]}</span></div>
    <header className="studio-heading"><div><span className="studio-eyebrow"><Icon name="spark" />{p.eyebrow}</span><h1>{p.title}</h1><p>{z.intro}</p></div></header>
    {!available || !zone ? <div className="studio-unavailable" role="status"><Icon name="cube" /><p>{z.unavailable}</p></div> : <div className="studio-layout">
      <section className="studio-visual-column" aria-label={p.preview}>
        {flat || (example === 'lamp' ? <StudioLampPreview value={basePreview} panelText={panelPreview} baseObjects={baseInspected.objects} panelObjects={panelInspected.objects} selectedId={document.selectedId} onSelectObject={selectObject} onMoveObject={moveObject} draft={lampDraft} zone={zone.id === 'panel' ? 'panel' : 'base'} onSelectZone={chooseZone} onMoveEnd={endGroup} highlightTextBand={zone.id === 'panel'} expanded={expanded} onExpand={() => setExpanded(true)} onClose={() => setExpanded(false)} /> : <StudioPreview compare={compare} onCompare={() => setCompare(current => !current)} variant={variant} value={shown} objects={baseInspected.objects} selectedId={document.selectedId} onSelectObject={selectObject} onMoveObject={moveObject} artwork={firstArtwork(baseDocument)} fits={baseInspected.valid} onMove={move} onMoveEnd={endGroup} expanded={expanded} onExpand={() => setExpanded(true)} onClose={() => setExpanded(false)} />)}
      </section>
      <section className="studio-panel" aria-label={p.eyebrow}>
        <div className="studio-zone-heading"><span>{z.zoneLabel}</span></div>
        {profile.zones.length > 1 ? <div className="studio-zone-picker" role="group" aria-label={z.zoneLabel}>{profile.zones.map(item => <button type="button" key={item.id} aria-pressed={zone.id === item.id} onClick={() => chooseZone(item.id)}><Icon name={item.id === 'panel' ? 'layers' : 'cube'} /><span>{z.zoneNames[item.id]}<small>{z.operations[item.operation]}</small></span>{zone.id === item.id && <Icon name="check" />}</button>)}</div> : <div className="studio-single-zone"><Icon name="layers" /><strong>{z.zoneNames[zone.id]}</strong><span>{z.operations[zone.operation]}</span></div>}
        <p className="studio-zone-hint">{z.zoneHints[zone.id]}</p>
        {zone.visualComposition && <p className="studio-pattern-note">{c.panelPreview}</p>}
        <StudioObjectTools objects={document.objects} selectedId={document.selectedId} onSelect={selectObject} canAddText={zone.tools.includes('text')} canAddImage={zone.tools.includes('image')} showAiGeneration={zone.showAiGeneration} imagePending={pendingImage} imageHintId={id + '-image-hint'} onAdd={kind => { if (kind === 'image') { pickImage('add'); return; } leaveZone(); setSelectedTool('text'); dispatch({ type: 'add', variant: target, kind: 'text' }); }} onDelete={() => { if (selected) { leaveZone(); dispatch({ type: 'delete', variant: target, id: selected.id }); } }} onReorder={direction => { if (selected) { endGroup(); dispatch({ type: 'reorder', variant: target, id: selected.id, direction }); setAppliedMessage(false); } }} />
        {canUseStudioTool(profile, zone.id, 'image') && <div className="studio-image-feedback">
          <input hidden type="file" ref={imageInput} accept="image/png,image/jpeg,image/webp,.png,.jpg,.jpeg,.webp" aria-label={e.uploadImage} disabled={pendingImage} onChange={event => { const file = event.currentTarget.files?.[0]; event.currentTarget.value = ''; void acceptImageFile(file); }} />
          <p id={id + '-image-hint'}>{e.imageFormats}. {privateStudio ? accountDesignNote[language] : permanentCatalogMode ? designLabels[language][1] : e.imageLocalOnly}</p>
          <div className="studio-upload-message" role={imageError ? 'alert' : 'status'}>{imageError || (pendingImage ? c.loadingImage : imageStatus === 'added' ? e.imageLoaded : imageStatus === 'replaced' ? e.imageReplaced : '')}</div>
        </div>}
        {zone.tools.length > 1 && <div className="studio-tool-tabs" role="group" aria-label={p.eyebrow}>{zone.tools.filter(tab => !onSave || tab !== 'ai').map(tab => <button type="button" key={tab} disabled={tab === 'image' && !document.objects.some(object => object.kind === 'image') && (pendingImage || document.objects.length >= MAX_STUDIO_OBJECTS)} aria-pressed={tool === tab} onClick={() => chooseTool(tab)}><Icon name={toolIcon(tab)} />{toolLabel(tab)}</button>)}</div>}
        <div className="studio-panel-heading"><h2>{tool === 'text' ? cutText ? z.cutTextLabel : p.textLabel : tool ? toolLabel(tool) : ''}</h2><div className="studio-history"><button type="button" className="product-icon-button" aria-label={p.undo} title={p.undo} disabled={!history.past.length} onClick={() => { leaveZone(); dispatch({ type: 'undo', variant: target }); }}><Icon name="rotateLeft" /></button><button type="button" className="product-icon-button" aria-label={p.redo} title={p.redo} disabled={!history.future.length} onClick={() => { leaveZone(); dispatch({ type: 'redo', variant: target }); }}><Icon name="rotateRight" /></button></div></div>
        <button type="button" className="studio-mobile-preview" onClick={() => setExpanded(true)} aria-label={p.preview}>{surface ? <FlatSurfaceSketch label={p.preview} fill={color.colors[0]} objects={baseInspected.objects}/> : example === 'lamp' ? <LampZoneSketch value={basePreview} panelText={panelPreview} baseObjects={baseInspected.objects} panelObjects={panelInspected.objects} draft={lampDraft} zone={zone.id === 'panel' ? 'panel' : 'base'} label={p.preview} /> : <LetteringSketch variant={variant} {...loopPreviewContent(shown, baseInspected.objects, firstArtwork(baseDocument), compare)} label={p.preview} />}<span>{p.preview}<Icon name="expand" /></span></button>
        {surface && expanded && <Modal title={p.preview} closeLabel={t.close} onClose={()=>setExpanded(false)}>{flat}</Modal>}
        {tool === 'pattern' && <>{patterns}<p className="studio-pattern-note">{z.panelRestriction}</p></>}
        {tool === 'text' && selected?.kind === 'text' && <>
        {cutText && <p className="studio-pattern-note">{z.cutTextHint}</p>}
        <label className="product-sr-only" htmlFor={id + '-text'}>{cutText ? z.cutTextLabel : p.textLabel}</label>
        <div className="studio-input-wrap"><textarea ref={input} id={id + '-text'} value={value.text} placeholder={p.textPlaceholder} rows={limits.maxLines} spellCheck={false} aria-describedby={id + '-count ' + id + '-error'} aria-invalid={!inspected.valid && inspected.issue !== 'loading'} onChange={event => { update({ text: event.target.value }, 'text'); setTextLimited(cleanLetteringText(event.target.value, limits) !== event.target.value); }} onBlur={endGroup} />
          <div><span id={id + '-count'}>{characters(value.text).length} / {limits.maxCharacters} {p.characters}{limits.maxLines === 2 ? ' · ' + p.lineLimit : ''}</span><button type="button" aria-label={p.clearText} disabled={!value.text} onClick={() => { setTextLimited(false); update({ text: '' }); input.current?.focus(); }}><Icon name="close" /></button></div></div>
        <p className="studio-field-message" id={id + '-error'} role={inspected.issue && inspected.issue !== 'loading' ? 'alert' : 'status'}>{inspected.issue === 'loading' && <Spinner />}{issueText(inspected.issue) || (textLimited ? limitMessage(selectedZone) : '\u00a0')}</p>
        <StudioFontTools key={creativeTarget + ':' + selected.id + ':' + fontEpoch} target={creativeTarget + ':' + selected.id} value={value.font} allowedFonts={zone.fonts} allowCustomFonts={zone.allowCustomFonts} onChange={font => { if (activeFontEpoch.current === fontEpoch) update({ font }); }} />
        {cutText && <p className="studio-pattern-note">{z.cutFontHint}</p>}
        {zone.inkColors.length > 1 && <fieldset className="studio-inks"><legend>{p.inkLabel}</legend><div>{zone.inkColors.map(ink => <button type="button" key={ink} aria-label={[p.lightInk, p.darkInk, p.goldInk][letteringColors.indexOf(ink)]} aria-pressed={value.color === ink} onClick={() => update({ color: ink })}><span style={{ background: ink, color: ink === '#20251f' ? '#fff' : '#20251f' }}>{value.color === ink && <Icon name="check" />}</span><small>{[p.lightInk, p.darkInk, p.goldInk][letteringColors.indexOf(ink)]}</small></button>)}</div></fieldset>}
        <div className="studio-range"><label htmlFor={id + '-size'}>{p.sizeLabel}<output>{Math.round(value.size)}</output></label><input id={id + '-size'} type="range" min={limits.minSize} max={limits.maxSize} step={1} value={value.size} onChange={event => update({ size: Number(event.target.value) }, 'size')} onBlur={endGroup} onPointerUp={endGroup} /></div>
        {zone.allowPosition && <details className="studio-position"><summary>{p.positionLabel}<Icon name="chevron" /></summary><div>
          <Range label={p.horizontal} value={shown.x} min={layout.minX} max={layout.maxX} onChange={x => move(x, shown.y)} end={endGroup} />
          <Range label={p.vertical} value={shown.y} min={layout.minY} max={layout.maxY} onChange={y => move(shown.x, y)} end={endGroup} />
          <button type="button" className="product-text-button" onClick={() => update({ x: 240, y: 278 })}><Icon name="reset" />{p.center}</button>
        </div></details>}
        </>}
        {tool === 'image' && selected?.kind === 'image' && <StudioArtworkTools pending={pendingImage} onReplace={() => pickImage('replace')} lamp={false} area={studioArea(target)} fit={selected.fit} onFitChange={changeFit} endGroup={endGroup} value={artwork} onChange={changeArtwork} />}
        {tool === 'ai' && <StudioIdeaTools key={creativeTarget} prompt={draft.prompt} onPrompt={prompt => creative.edit(creativeTarget, previous => ({ ...previous, prompt }))} />}
        <div className="studio-apply">
          {example === 'loop' && <div className="studio-price-summary" aria-live="polite" aria-atomic="true">
            {quote && <div><span>{priceCopy.unitTotal}</span><strong>{money(quote.unitCents, language)}</strong></div>}
            <small>{quote?.feeCents ? <>{priceCopy.base}: {money(quote.baseCents, language)} · {priceCopy.fee(money(quote.feeCents, language))}</> : clearingLine ? e.withoutDesign : priceCopy.from(money(surface?.offer.designFeeCents??loopDesignFee(), language))}</small>
          </div>}
          <button type="button" className="product-primary" disabled={resolvingImage || (!valid && !clearingLine) || !dirty} onClick={apply}><Icon name={applied && !dirty ? 'check' : 'spark'} />{clearingLine ? e.removeDesign : onSave ? cartCopy.save : applied && !dirty ? p.applied : p.apply}<Icon name="arrow" /></button>
          {libraryEligible && (privateStudio ? <SaveLibraryDesign admission={privateStudio} doc={baseDocument} original={libraryItem} onCreated={onCreated}
            enabled={valid&&!resolvingImage} snapshot={snapshotForLibrary}/> : <GuestDesignSave sku={dbVariant.sku} enabled={valid&&!resolvingImage} snapshot={snapshotForLibrary}/>)}
          <div className="studio-apply-status" role="status">{appliedMessage ? <><Icon name="check" />{example === 'lamp' ? e.artworkApplied : p.completed}</> : clearingLine ? p.changes : !valid ? wholeIssue || (zone.tools.includes('image') ? p.textRequired : p.textOnlyRequired) : applied && !dirty ? p.applied : p.changes}</div>
          {tool === 'text' && <div className="studio-panel-bottom"><button type="button" className="product-text-button" disabled={sameLettering(value, defaultLettering(target))} onClick={() => setResetOpen(true)}>{p.reset}</button></div>}
        </div>
      </section>
    </div>}
    <details className="studio-disclosure"><summary><Icon name="help" />{p.disclosureTitle}<Icon name="chevron" /></summary><p>{p.disclosureBody}</p><p>{privateStudio ? accountDesignNote[language] : permanentCatalogMode ? designLabels[language][1] : example === 'loop' ? cartCopy.localNote : p.savedNote}</p></details>
    {resetOpen && <Modal title={e.resetTextTitle} closeLabel={t.close} onClose={() => setResetOpen(false)}><div className="studio-reset"><p>{e.resetTextBody}</p><div><button type="button" className="product-secondary" onClick={() => setResetOpen(false)}>{p.cancel}</button><button type="button" className="product-primary" onClick={() => { supersedeFont(); dispatch({ type: 'reset', variant: target }); setResetOpen(false); setAppliedMessage(false); setTextLimited(false); }}>{p.confirmReset}</button></div></div></Modal>}
  </div>;
}

function Range({ label, value, min, max, onChange, end }: { label: string; value: number; min: number; max: number; onChange: (value: number) => void; end: () => void }) {
  const id = useId();
  return <div className="studio-range"><label htmlFor={id}>{label}</label><input id={id} type="range" min={min} max={max} step="any" value={value} disabled={min === max} onChange={event => onChange(Number(event.target.value))} onBlur={end} onPointerUp={end} /></div>;
}

function StudioPreview({ compare, onCompare, variant, value, artwork, objects, selectedId, onSelectObject, onMoveObject, fits, onMove, onMoveEnd, expanded, onExpand, onClose }: { compare: boolean; onCompare: () => void; variant: VariantId; value: Lettering; artwork: ImagePlacement; objects: StudioObject[]; selectedId: string | null; onSelectObject: (id: string) => void; onMoveObject: (id: string, x: number, y: number) => void; fits: boolean; onMove: (x: number, y: number) => void; onMoveEnd: () => void; expanded: boolean; onExpand: () => void; onClose: () => void }) {
  const language = useProductLocale(), p = personalizationCopy[language], t = productCopy[language];
  const e = studioExtensionCopy[language], compareLabel = artwork.image ? e.withoutDesign : p.original;
  const [mode, setMode] = useState<'sketch' | 'model'>('sketch');
  const hasText = objects.some(object => object.kind === 'text' && object.value.text.trim());
  const [started, setStarted] = useState(false), [failedText, setFailedText] = useState(false);
  const [inlineHost, setInlineHost] = useState<HTMLDivElement | null>(null), [modalHost, setModalHost] = useState<HTMLDivElement | null>(null);
  const content = loopPreviewContent(value, objects, artwork, compare);
  const target = expanded ? modalHost : inlineHost;
  const select = (next: 'sketch' | 'model') => { setMode(next); if (next === 'model') setStarted(true); };
  const tabs = <div className="studio-view-switch" role="group" aria-label={p.preview}><button type="button" aria-pressed={mode === 'sketch'} onClick={() => select('sketch')}><Icon name="image" />{p.edit}</button><button type="button" aria-pressed={mode === 'model'} onClick={() => select('model')}><Icon name="cube" />{p.model}</button></div>;
  const sketch = (detail = false) => <LetteringSketch variant={variant} {...content} selectedId={compare ? null : selectedId} onSelectObject={compare ? undefined : onSelectObject} onMoveObject={compare ? undefined : onMoveObject} label={compare ? `${p.edit} · ${e.withoutDesign}` : p.edit} areaLabel={p.area} guides={!compare} ghost={compare || artwork.image ? '' : 'Aa'} detail={detail} onMove={compare ? undefined : onMove} onMoveEnd={onMoveEnd} />;
  const fallback = <div className="studio-model-error" role="status"><Icon name="cube" /><p>{p.modelUnavailable}</p><button type="button" className="product-secondary" onClick={() => select('sketch')}>{p.edit}</button></div>;
  return <div className="studio-preview-card">
    <div className="studio-preview-toolbar"><span>Loop</span>{tabs}<button type="button" className="product-icon-button" aria-label={p.expand} onClick={onExpand}><Icon name="expand" /></button></div>
    <div className="studio-stage"><div className="studio-model-host" ref={setInlineHost} hidden={mode !== 'model' || expanded} />{mode === 'sketch' && sketch()}</div>
    <div className="studio-preview-bottom"><span>{compare ? e.withoutDesign : !fits ? p.textTooWide : mode === 'sketch' ? hasText ? p.positionHint : artwork.image ? e.imageTab : p.emptyHint : failedText ? p.modelUnavailable : p.modelHint}</span><button className="studio-compare" type="button" aria-pressed={compare} aria-label={compareLabel} disabled={!compare && !hasText && !artwork.image} onClick={onCompare}><Icon name="layers" />{compareLabel}</button></div>
    {expanded && <Modal title={p.preview + ' · Loop'} closeLabel={t.close} className="studio-expanded" actions={tabs} onClose={onClose}><div className="studio-stage"><div className="studio-model-host" ref={setModalHost} hidden={mode !== 'model'} />{mode === 'sketch' && sketch(true)}</div></Modal>}
    {started && <MediaErrorBoundary fallback={mode === 'model' && target ? createPortal(fallback, target) : null}><Suspense fallback={target ? createPortal(<div className="product-model-status" role="status"><Spinner />{t.loadingModel}</div>, target) : null}><ModelPreview source={`/product/studio-loop-${variant}.glb`} t={{ ...t, modelFailed: p.modelUnavailable }} target={target} expanded={expanded && mode === 'model'} decoration={{ ...content.value, text: fits ? content.value.text : '', variant, objects: content.objects, artwork: content.artwork, showArea: !compare }} onDecorationError={setFailedText} /></Suspense></MediaErrorBoundary>}
  </div>;
}

export function AppliedPersonalization({ variant }: { variant: VariantId }) {
  const locale = useProductLocale();
  const { state, dispatch } = usePersonalizationSession(), applied = state.applied[variant];
  const creative = useStudioCreative();
  if (!applied) return null;
  return <AppliedDesignSummary variant={variant} applied={applied} note={productCopy[locale].personalBody} onRemove={() => {
    dispatch({ type: 'remove', variant }); creative.remove(variant);
  }} />;
}

export function AppliedDesignSummary({ variant, applied, note, onRemove }: {
  variant: VariantId; applied: CompositionDocument; note: string; onRemove: () => void;
}) {
  const locale = useProductLocale(), p = personalizationCopy[locale], e = studioExtensionCopy[locale];
  const artwork = firstArtwork(applied), value = firstLettering(applied, variant);
  const lettering = applied.objects.find(object => object.kind === 'text' && object.value.text.trim());
  const summary = lettering?.kind === 'text' ? lettering.value.text.trim() : artwork.image ? e.imageTab : '';
  return <div className="product-applied-lettering"><LetteringSketch variant={variant} value={value} objects={applied.objects} artwork={artwork} label={summary ? p.applied + ' · ' + summary : p.applied} /><div><strong>{summary || p.applied}</strong><span>{p.applied}</span><small>{note}</small></div><button type="button" className="product-text-button" onClick={event => {
    event.currentTarget.closest('.product-personalisation')?.querySelector<HTMLAnchorElement>('a')?.focus({ preventScroll: true });
    onRemove();
  }}>{e.removeDesign}</button></div>;
}
