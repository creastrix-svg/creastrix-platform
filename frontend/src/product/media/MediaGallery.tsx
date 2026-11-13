import { lazy, Suspense, useCallback, useId, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { mediaForVariant, variantById } from '../fixtures/loop';
import type { VariantId } from '../fixtures/loop';
import type { ProductCopy } from '../copy';
import { Icon, Spinner } from '../Icon';
import Modal from '../Modal';
import MediaErrorBoundary from './MediaErrorBoundary';
import MediaImage from './MediaImage';
import LetteringSketch from '../studio/preview/LetteringSketch';
import { appliedModelSource } from '../studio/preview/personalizationModel';
import type { ModelDecoration } from '../studio/preview/personalizationModel';
import { personalizationCopy } from '../studio/personalizationCopy';
import type { StorefrontLocale } from '../locale';
import { thumbnailFor } from '../catalog/photoRenditions';

const ModelPreview = lazy(() => new Promise<typeof import('./ModelPreview')>((resolve, reject) => {
  // Bound failure, not minimum latency; this cannot cancel the module fetch.
  // A late completion cannot revive this lazy instance's rejected promise.
  const deadline = setTimeout(() => reject(new Error('MODEL_PREVIEW_IMPORT_TIMEOUT')), 30_000);
  void import('./ModelPreview').then(module => {
    clearTimeout(deadline); resolve(module);
  }, error => {
    clearTimeout(deadline); reject(error);
  });
}));

type GalleryProps = {
  favorite: boolean; favoriteDisabled?:boolean; favoritePrivate?:boolean; toggleFavorite: () => void; t: ProductCopy; locale?: StorefrontLocale;
  initialMode?: 'photo' | 'model'; onModeChange?: (mode: 'photo' | 'model') => void;
};
type OfferGalleryProps = GalleryProps & {
  media: { photos: readonly string[]; model: string | null }; sku: string; finishName: string;
  unavailableModelReason?: string; imageSize?: readonly [number, number];
  decoration?: ModelDecoration; sketch?: ReactNode;
};

export default function MediaGallery({ variant, decoration, ...props }: GalleryProps & { variant: VariantId; decoration?: ModelDecoration }) {
  const locale = props.locale ?? 'en', p = personalizationCopy[locale];
  const media = mediaForVariant(variant);
  return <OfferMediaGallery {...props} sku={variant} finishName={variantById(variant).name[locale]}
    media={decoration ? { ...media, model: appliedModelSource(variant, media.model) } : media} decoration={decoration}
    sketch={decoration && <LetteringSketch variant={variant} value={decoration} objects={decoration.objects} label={p.applied} />} />;
}

// A photograph-only offer uses the same tabs, zoom dialog, loading states and thumbnails.
// The model import stays dormant when the offer does not supply a model.
export function OfferMediaGallery({ media, sku, finishName, favorite, favoriteDisabled, favoritePrivate, toggleFavorite, t: originalCopy, initialMode = 'photo', onModeChange, decoration, sketch, locale = 'en', unavailableModelReason, imageSize = [1200, 802] }: OfferGalleryProps) {
  const p = personalizationCopy[locale];
  const t = decoration ? { ...originalCopy, photo: p.edit, backToPhotos: p.edit } : originalCopy;
  const modelReasonId = useId();
  const [photo, setPhoto] = useState(0);
  const [mode, setMode] = useState<'photo' | 'model'>(initialMode);
  const [requestedOpen, setOpen] = useState(false);
  const [modelStarted, setModelStarted] = useState(initialMode === 'model');
  const [favoriteTick, setFavoriteTick] = useState(0);
  const [failedPhotos, setFailedPhotos] = useState<ReadonlySet<string>>(() => new Set());
  const [photoStatus, setPhotoStatus] = useState<{ source: string; phase: 'loading' | 'ready' | 'failed' } | null>(null);
  const [modelStatus, setModelStatus] = useState<{ source: string; phase: 'importing' | 'loading' | 'ready' | 'failed' } | null>(null);
  const [inlineHost, setInlineHost] = useState<HTMLDivElement | null>(null);
  const [modalHost, setModalHost] = useState<HTMLDivElement | null>(null);
  const photoTab = useRef<HTMLButtonElement>(null), enlarge = useRef<HTMLButtonElement>(null), wasOpen = useRef(false);
  const [decorationFailed, setDecorationFailed] = useState(false);
  const modelSource = media.model;
  const photos = media.photos, hasPhotos = photos.length > 0;
  const currentPhoto = Math.min(photo, Math.max(0, photos.length - 1));
  const rawPhoto = photos[currentPhoto];
  const displayMode = mode;
  const open = requestedOpen && (displayMode === 'model' ? !!media.model : hasPhotos || !!decoration);
  useLayoutEffect(() => {
    // Loading or a terminal media error may disable/remove the original opener.
    if (wasOpen.current && !open && (!enlarge.current || enlarge.current.disabled)) photoTab.current?.focus({ preventScroll: true });
    wasOpen.current = open;
  }, [open]);
  const target = media.model ? open ? modalHost : inlineHost : null;
  const photoKey = `${sku}:${rawPhoto}`;
  const photoFailed = failedPhotos.has(photoKey);
  const photoBusy = hasPhotos && !photoFailed && (photoStatus?.source !== photoKey || photoStatus.phase === 'loading');
  const modelPhase = !media.model ? 'failed' : modelStatus?.source === media.model ? modelStatus.phase : 'importing';
  const modelBusy = !!media.model && modelStarted && (modelPhase === 'importing' || modelPhase === 'loading');
  const photoPhase = useCallback((phase: 'loading' | 'ready' | 'failed') => setPhotoStatus(previous => previous?.source === photoKey && previous.phase === phase ? previous : { source: photoKey, phase }), [photoKey]);
  const modelPhaseChanged = (phase: 'importing' | 'loading' | 'ready' | 'failed') => { if (modelSource) setModelStatus(previous => previous?.source === modelSource && previous.phase === phase ? previous : { source: modelSource, phase }); };
  const modelFailed = () => modelPhaseChanged('failed');
  const failPhoto = (key: string) => setFailedPhotos(previous => previous.has(key) ? previous : new Set([...previous, key]));
  const retryPhoto = () => setFailedPhotos(previous => { const next = new Set(previous); next.delete(photoKey); return next; });
  function selectMode(next: 'photo' | 'model') { if (next === 'model') setModelStarted(true); setMode(next); onModeChange?.(next); }
  const unavailable = <div className="product-model-fallback"><p role="status">{t.modelFailed}</p><button type="button" className="product-secondary" onClick={() => selectMode('photo')}>{t.backToPhotos}</button></div>;
  const noPhoto = <div className="product-no-photo"><Icon name="layers" /><strong>{t.noPhoto}</strong>{photoFailed && <><p>{t.noPhotoBody}</p><button type="button" className="product-text-button" onClick={retryPhoto}>{t.retryPhoto}</button></>}</div>;
  const thumbnailPlaceholder = (key: string) => <span key={key} className="product-thumbnail-placeholder" role="img" aria-label={t.noPhoto}><Icon name="image" /><span>{t.noPhotoShort}</span></span>;
  const thumbnails = (expanded: boolean) => decoration ? null : <div className={expanded ? 'product-gallery-thumbnails' : 'product-thumbnails'} role="group" aria-label={t.photo}>
    {!hasPhotos && thumbnailPlaceholder('empty')}
    {photos.map((source, index) => {
      const key = `${sku}:${source}`;
      const thumb = thumbnailFor(source);
      return failedPhotos.has(key) ? thumbnailPlaceholder(key) : <button type="button" key={key} aria-label={`${t.photoNumber} ${index + 1}`} aria-pressed={currentPhoto === index && displayMode === 'photo'} onClick={() => { setPhoto(index); selectMode('photo'); }}><MediaImage src={thumb} alt="" width={expanded ? 64 : 76} height={expanded ? 48 : 52} loadingLabel={t.loadingPhoto} errorLabel={t.noPhotoShort} onFailure={thumb === source ? () => failPhoto(key) : undefined} /></button>;
    })}
  </div>;
  return <section className="product-gallery" aria-label={t.photo}>
    <div className="product-media-tabs" role="group" aria-label={t.photo + ' / ' + t.model}>
      <button ref={photoTab} type="button" aria-pressed={displayMode === 'photo'} onClick={() => selectMode('photo')}><Icon name="image" />{t.photo}</button>
      <button type="button" aria-pressed={displayMode === 'model'} disabled={!media.model} aria-describedby={unavailableModelReason ? modelReasonId : undefined} title={unavailableModelReason} aria-busy={modelBusy} onClick={() => selectMode('model')}>{modelBusy ? <Spinner /> : <Icon name="cube" />}{t.model}</button>
      {unavailableModelReason && <span id={modelReasonId} hidden>{unavailableModelReason}</span>}
    </div>
    <div className="product-media">
      <div className="product-model-host" ref={setInlineHost} hidden={!media.model || displayMode !== 'model' || open} />
      {displayMode === 'model' ? media.model ? <button ref={enlarge} type="button" className="product-model-expand product-icon-button" disabled={modelPhase !== 'ready'} aria-busy={modelBusy} aria-label={t.enlarge} onClick={() => setOpen(true)}>{modelBusy ? <Spinner /> : <Icon name="expand" />}</button> : unavailable : decoration ? <button type="button" ref={enlarge} className="product-photo-open" aria-label={t.enlarge} onClick={() => setOpen(true)}>{sketch}<span className="product-photo-expand"><Icon name="expand" /></span></button> : hasPhotos && !photoFailed ? <button ref={enlarge} type="button" className="product-photo-open" disabled={photoBusy} aria-busy={photoBusy} aria-label={t.enlarge} onClick={() => setOpen(true)}><MediaImage key={photoKey} src={rawPhoto} alt={t.photoAlt} width={imageSize[0]} height={imageSize[1]} priority="high" className="product-photo-change" loadingLabel={t.loadingPhoto} errorLabel={t.noPhotoShort} announce onPhase={photoPhase} onFailure={() => failPhoto(photoKey)} /><span className="product-photo-expand">{photoBusy ? <Spinner /> : <Icon name="expand" />}</span></button>
        : noPhoto}
      <button type="button" className="product-favorite" data-private-account={favoritePrivate||undefined} disabled={favoriteDisabled} aria-label={favorite ? t.unfavorite : t.favorite} aria-description={finishName} aria-pressed={favorite} onClick={() => { if (!favorite) setFavoriteTick(value => value + 1); toggleFavorite(); }}><span key={favoriteTick} className={favorite && favoriteTick > 0 ? 'product-heart-added' : undefined}><Icon name="heart" filled={favorite} /></span></button>
    </div>
    {thumbnails(false)}
    {decorationFailed && decoration && <p role="alert">{p.modelUnavailable}</p>}
    {open && <Modal title={t.gallery} closeLabel={t.close} className="product-media-dialog" onClose={() => setOpen(false)} actions={<><button type="button" className="product-media-dialog-toggle" disabled={displayMode === 'photo' && !media.model} aria-describedby={unavailableModelReason ? modelReasonId + '-dialog' : undefined} title={unavailableModelReason} onClick={() => selectMode(displayMode === 'photo' ? 'model' : 'photo')}>{modelBusy && displayMode === 'model' ? <Spinner /> : <Icon name={displayMode === 'photo' ? 'cube' : 'image'} />}<span>{displayMode === 'photo' ? t.model : t.photo}</span></button>{unavailableModelReason && <span id={modelReasonId + '-dialog'} className="product-sr-only">{unavailableModelReason}</span>}</>} onKeyDown={event => {
      if (!event.altKey && !event.ctrlKey && !event.metaKey && displayMode === 'photo' && photos.length > 1 && (event.key === 'ArrowRight' || event.key === 'ArrowLeft')) { event.preventDefault(); setPhoto(index => (index + (event.key === 'ArrowRight' ? 1 : photos.length - 1)) % photos.length); }
    }}>
      <div className="product-gallery-view">
        <div className="product-gallery-image"><div className="product-model-host" ref={setModalHost} hidden={displayMode !== 'model'} />{displayMode === 'photo' && <>{decoration ? sketch : photoFailed ? noPhoto : <MediaImage key={photoKey} src={rawPhoto} alt={`${t.photoAlt} · ${currentPhoto + 1}`} width={imageSize[0]} height={imageSize[1]} className="product-photo-change" loadingLabel={t.loadingPhoto} errorLabel={t.noPhotoShort} announce onFailure={() => failPhoto(photoKey)} />}{!decoration && photos.length > 1 && <><button type="button" className="product-gallery-arrow product-gallery-prev" aria-label={t.previous} onClick={() => setPhoto(index => (index + photos.length - 1) % photos.length)}><span className="product-flip"><Icon name="chevron" /></span></button><button type="button" className="product-gallery-arrow product-gallery-next" aria-label={t.next} onClick={() => setPhoto(index => (index + 1) % photos.length)}><Icon name="chevron" /></button></>}</>}</div>
        <div className="product-gallery-footer"><div className="product-gallery-bar">{displayMode === 'photo' ? thumbnails(true) : <strong>{t.fullRotation}</strong>}<span role="status">{displayMode === 'photo' ? decoration ? p.applied : `${currentPhoto + 1} / ${photos.length}` : t.model}</span></div>{displayMode === 'model' && <p>{t.modelHint}</p>}</div>
      </div>
    </Modal>}
    {modelStarted && media.model && <MediaErrorBoundary key={media.model} onFailure={modelFailed} fallback={displayMode === 'model' && target ? createPortal(unavailable, target) : null}><Suspense fallback={displayMode === 'model' && target ? createPortal(<p className="product-model-status" role="status"><Spinner />{t.loadingModel}</p>, target) : null}><ModelPreview source={media.model} t={t} target={target} expanded={open && displayMode === 'model'} onPhase={modelPhaseChanged} decoration={decoration} onDecorationError={setDecorationFailed} /></Suspense></MediaErrorBoundary>}
  </section>;
}
