import { Suspense, lazy, useRef, useState, useSyncExternalStore, type MouseEvent, type RefObject } from 'react';
import { useTranslation } from 'react-i18next';
import { Check, Link2, Share2, Tag } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogTitle } from '@/components/ui/dialog';
import { IconAction } from '@/components/ui/icon-action';
import { useConfirmation } from '@/hooks/use-confirmation';
import { useCopyToClipboard } from '@/hooks/use-copy-to-clipboard';
import { PERMISSIONS, usePermissions } from '@/hooks/use-permissions';
import { REPLAY_COMPLETE_STATUS, useReplayMetadata } from '@/hooks/data/replays';
import { useNavigationBlocker } from '@/lib/guarded-location';
import { ApiHttpError } from '@/lib/http-error';
import type { ReplayLabeling } from '@/replay-viewer/ui/ReplayViewer';
import { createLabelDraftStore, hasUnsubmittedLabels } from '@/replay-viewer/ui/labeling/label-drafts';
import { focusReplayWorld } from '@/replay-viewer/ui/stage/world-focus';
import { formatDateWithTime } from '@/utils/date-utils';
import { DarkSpinner, DarkSurface, ReplayViewerSurface } from './ReplayViewerSurface';
import { useReplayDialogParam } from './use-replay-dialog';

const ReplayShareDialog = lazy(async () => import('./ReplayShareDialog').then((module) => ({ default: module.ReplayShareDialog })));

const HTTP_FORBIDDEN = 403;

type LabelView = 'unmounted' | 'open' | 'hidden';

function keepFocus(event: MouseEvent): void {
  event.preventDefault();
}

function useCanLabel(replayId: string): boolean {
  const { hasPermission } = usePermissions();
  const { data } = useReplayMetadata(replayId);
  return hasPermission(PERMISSIONS.PUNISHMENT_MODIFY) && data?.status === REPLAY_COMPLETE_STATUS && !data.labeled;
}

function MetadataFailure({ error, onRetry }: { readonly error: unknown; readonly onRetry: () => void }) {
  const { t } = useTranslation();
  if (error instanceof ApiHttpError && error.status === HTTP_FORBIDDEN) return <DarkSurface>{t('replayViewer.failure.noAccess')}</DarkSurface>;
  return (
    <DarkSurface>
      {t('replayViewer.failure.failed')}
      <Button type="button" variant="secondary" size="sm" onClick={onRetry}>{t('common.retry')}</Button>
    </DarkSurface>
  );
}

interface ReplayBodyProps {
  readonly replayId: string;
  readonly shortcutScope: RefObject<HTMLElement | null>;
  readonly labeling: ReplayLabeling | null;
}

function ReplayBody({ replayId, shortcutScope, labeling }: ReplayBodyProps) {
  const { t } = useTranslation();
  const metadata = useReplayMetadata(replayId);
  const [attempt, setAttempt] = useState(0);
  const [retryPending, setRetryPending] = useState(false);
  const replay = metadata.data;
  const failedLast = metadata.isError && metadata.errorUpdatedAt >= metadata.dataUpdatedAt;

  const refetch = () => {
    setRetryPending(true);
    void metadata.refetch().then((result) => {
      setRetryPending(false);
      if (result.isSuccess) setAttempt((value) => value + 1);
    });
  };

  if (retryPending || (metadata.isFetching && !metadata.isFetchedAfterMount)) return <DarkSpinner />;
  if (failedLast) return <MetadataFailure error={metadata.error} onRetry={refetch} />;
  if (replay === undefined) return <DarkSpinner />;
  if (replay === null) return <DarkSurface>{t('replayViewer.failure.replayMissing')}</DarkSurface>;
  if (replay.status !== REPLAY_COMPLETE_STATUS) return <DarkSurface>{t('replayViewer.failure.processing')}</DarkSurface>;

  return (
    <ReplayViewerSurface
      attempt={attempt}
      resetKey={replayId}
      source={{ replayUrl: replay.replayUrl, access: 'panel', minecraftVersion: replay.mcVersion }}
      shortcutScope={shortcutScope}
      labeling={labeling}
      onRetry={refetch}
    />
  );
}

function ReplayHeading({ replayId }: { readonly replayId: string }) {
  const { t } = useTranslation();
  const { data } = useReplayMetadata(replayId);
  const replay = data ?? null;
  const details = replay === null ? [] : [formatDateWithTime(new Date(replay.createdAt)), replay.mcVersion].filter((part) => part !== '');

  return (
    <div className="flex min-w-0 items-baseline gap-3">
      <DialogTitle className="truncate text-base">{replay?.targetName || t('replayViewer.title')}</DialogTitle>
      {details.length > 0 && <p className="hidden truncate text-xs text-muted-foreground sm:block">{details.join(' · ')}</p>}
    </div>
  );
}

function CopyLinkAction({ panelLink }: { readonly panelLink: string }) {
  const { t } = useTranslation();
  const { copy, copiedValue } = useCopyToClipboard(t('replayViewer.copyLinkFailed'));
  return (
    <IconAction
      icon={copiedValue === panelLink ? Check : Link2}
      label={copiedValue === panelLink ? t('common.copied') : t('replayViewer.copyLink')}
      onMouseDown={keepFocus}
      onClick={(event) => { void copy(panelLink, event.currentTarget); }}
    />
  );
}

function ShareReplayAction({ replayId, shortcutScope }: { readonly replayId: string; readonly shortcutScope: RefObject<HTMLElement | null> }) {
  const { t } = useTranslation();
  const { hasPermission } = usePermissions();
  const { data } = useReplayMetadata(replayId);
  const [open, setOpen] = useState(false);
  if (!hasPermission(PERMISSIONS.REPLAY_SHARE) || data?.status !== REPLAY_COMPLETE_STATUS) return null;
  return (
    <>
      <IconAction icon={Share2} label={t('replayViewer.share.title')} onMouseDown={keepFocus} onClick={() => { setOpen(true); }} />
      {open && (
        <Suspense fallback={null}>
          <ReplayShareDialog
            replayId={replayId}
            open
            onOpenChange={setOpen}
            onCloseAutoFocus={(event) => {
              event.preventDefault();
              focusReplayWorld(shortcutScope.current);
            }}
          />
        </Suspense>
      )}
    </>
  );
}

function LabelToggle({ open, onToggle }: { readonly open: boolean; readonly onToggle: () => void }) {
  const { t } = useTranslation();
  return (
    <IconAction
      icon={Tag}
      label={t('replayViewer.labels.title')}
      aria-pressed={open}
      className={open ? 'bg-muted text-foreground' : undefined}
      onMouseDown={keepFocus}
      onClick={onToggle}
    />
  );
}

function suppressWhileCaptured(event: KeyboardEvent): void {
  if (document.fullscreenElement instanceof Element || document.pointerLockElement instanceof Element) event.preventDefault();
}

interface ReplayDialogProps {
  readonly replayId: string;
  readonly panelLink: string;
  readonly onClose: () => void;
}

function ReplayDialog({ replayId, panelLink, onClose }: ReplayDialogProps) {
  const { t } = useTranslation();
  const contentRef = useRef<HTMLDivElement>(null);
  const canLabel = useCanLabel(replayId);
  const [labelView, setLabelView] = useState<LabelView>('unmounted');
  const [draftStore] = useState(createLabelDraftStore);
  const unsubmitted = useSyncExternalStore(draftStore.subscribe, () => hasUnsubmittedLabels(draftStore.snapshot()));
  const guarded = canLabel && unsubmitted;
  const confirmation = useConfirmation();

  const hideLabels = () => {
    setLabelView('hidden');
    focusReplayWorld(contentRef.current);
  };
  const toggleLabels = () => {
    if (labelView === 'open') hideLabels();
    else setLabelView('open');
  };
  const labeling: ReplayLabeling | null = canLabel && labelView !== 'unmounted'
    ? { replayId, open: labelView === 'open', store: draftStore, onClose: hideLabels }
    : null;

  const requestClose = async () => {
    if (guarded) {
      const confirmed = await confirmation.confirm({
        title: t('replayViewer.labels.discardTitle'),
        description: t('replayViewer.labels.discardDescription'),
        action: t('replayViewer.labels.discard'),
        destructive: true,
      });
      if (!confirmed) return;
    }
    onClose();
  };

  useNavigationBlocker(guarded, () => { void requestClose(); });

  return (
    <Dialog open onOpenChange={(next) => { if (!next) void requestClose(); }}>
      <DialogContent
        ref={contentRef}
        aria-describedby={undefined}
        className="flex h-dvh max-h-dvh w-screen max-w-none flex-col gap-0 overflow-hidden rounded-none border-0 p-0 sm:h-[88vh] sm:w-[90vw] sm:rounded-lg sm:border"
        onOpenAutoFocus={(event) => {
          event.preventDefault();
          contentRef.current?.focus({ preventScroll: true });
        }}
        onEscapeKeyDown={suppressWhileCaptured}
      >
        <header className="flex h-12 shrink-0 items-center justify-between gap-3 border-b pl-4 pr-12">
          <ReplayHeading replayId={replayId} />
          <div className="flex shrink-0 items-center gap-1">
            {canLabel && <LabelToggle open={labelView === 'open'} onToggle={toggleLabels} />}
            <CopyLinkAction panelLink={panelLink} />
            <ShareReplayAction replayId={replayId} shortcutScope={contentRef} />
          </div>
        </header>
        <div className="min-h-0 flex-1">
          <ReplayBody replayId={replayId} shortcutScope={contentRef} labeling={labeling} />
        </div>
        {confirmation.dialog}
      </DialogContent>
    </Dialog>
  );
}

export function ReplayDialogHost() {
  const { replayId, close, panelLink } = useReplayDialogParam();
  if (replayId === '') return null;
  return <ReplayDialog key={replayId} replayId={replayId} panelLink={panelLink} onClose={close} />;
}
