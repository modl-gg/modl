import { useEffect, useRef, useState, type RefObject } from 'react';
import { useTranslation } from 'react-i18next';
import { Progress } from '@/components/ui/progress';
import { formatFileSize } from '@/utils/file-utils';
import type { LoadedAssets } from '../assets/loadAssets';
import type { ReplayPlayer } from '../engine/ReplayPlayer';
import type { PreparedReplay } from '../engine/PreparedReplay';
import { isRetryable, LOAD_FAILURE_LABELS, loadFailureOf, loadSession, type LoadFailure, type LoadProgress, type SessionSource } from './load/load-session';
import { FailureNotice } from './FailureNotice';
import { LabelPanel } from './labeling/LabelPanel';
import type { LabelDraftStore } from './labeling/label-drafts';
import { ReplayStage } from './stage/ReplayStage';
import { useViewport } from './stage/use-viewport';
import { REPLAY_WORLD_MARKER } from './stage/world-focus';
import { createReplayPlayer } from './viewer-core';

type Phase =
  | { readonly kind: 'loading'; readonly progress: LoadProgress | null }
  | { readonly kind: 'failed'; readonly failure: LoadFailure }
  | { readonly kind: 'ready'; readonly player: ReplayPlayer; readonly assets: LoadedAssets; readonly replay: PreparedReplay };

export interface ReplayLabeling {
  readonly replayId: string;
  readonly open: boolean;
  readonly store: LabelDraftStore;
  readonly onClose: () => void;
}

export interface ReplayViewerProps {
  readonly source: SessionSource;
  readonly shortcutScope: RefObject<HTMLElement | null>;
  readonly onRetry: () => void;
  readonly labeling: ReplayLabeling | null;
}

function throttledProgress(apply: (progress: LoadProgress) => void): { readonly report: (progress: LoadProgress) => void; readonly cancel: () => void } {
  let pending: LoadProgress | null = null;
  let frame = 0;
  let closed = false;
  return {
    report(progress) {
      if (closed) return;
      pending = progress;
      if (frame !== 0) return;
      frame = requestAnimationFrame(() => {
        frame = 0;
        if (pending !== null) apply(pending);
      });
    },
    cancel() {
      closed = true;
      cancelAnimationFrame(frame);
    },
  };
}

export default function ReplayViewer({ source, shortcutScope, onRetry, labeling }: ReplayViewerProps) {
  const { t } = useTranslation();
  const rootRef = useRef<HTMLDivElement>(null);
  const viewport = useViewport(rootRef);
  const [canvas, setCanvas] = useState<HTMLCanvasElement | null>(null);
  const [phase, setPhase] = useState<Phase>({ kind: 'loading', progress: null });
  const { replayUrl, access, minecraftVersion } = source;

  useEffect(() => {
    if (canvas === null) return undefined;
    const controller = new AbortController();
    const progress = throttledProgress((value) => { setPhase({ kind: 'loading', progress: value }); });
    let player: ReplayPlayer | null = null;
    let assets: LoadedAssets | null = null;
    loadSession({ replayUrl, access, minecraftVersion }, controller.signal, progress.report)
      .then((session) => {
        if (controller.signal.aborted) {
          session.assets.dispose();
          return;
        }
        assets = session.assets;
        player = createReplayPlayer(canvas, session.replay, session.assets);
        progress.cancel();
        setPhase({ kind: 'ready', player, assets: session.assets, replay: session.replay });
      })
      .catch((error: unknown) => {
        progress.cancel();
        const failure = loadFailureOf(error);
        if (failure !== null && !controller.signal.aborted) setPhase({ kind: 'failed', failure });
      });
    return () => {
      controller.abort();
      progress.cancel();
      player?.dispose();
      assets?.dispose();
    };
  }, [access, canvas, minecraftVersion, replayUrl]);

  useEffect(() => {
    if (phase.kind === 'ready') canvas?.focus({ preventScroll: true });
  }, [canvas, phase.kind]);

  return (
    <div className="relative flex h-full w-full">
      <div ref={rootRef} tabIndex={-1} className="relative h-full min-w-0 flex-1 select-none overflow-hidden bg-black outline-none">
        <canvas ref={setCanvas} {...REPLAY_WORLD_MARKER} tabIndex={0} aria-label={t('replayViewer.world')} className="absolute inset-0 block h-full w-full outline-none" />
        {phase.kind === 'ready' && viewport !== null && (
          <ReplayStage root={rootRef} shortcutScope={shortcutScope} viewport={viewport} player={phase.player} assets={phase.assets} markerMs={phase.replay.header.markerMs} onRetry={onRetry} />
        )}
        {phase.kind === 'loading' && <LoadingState progress={phase.progress} />}
        {phase.kind === 'failed' && <FailureNotice message={t(LOAD_FAILURE_LABELS[phase.failure])} onRetry={isRetryable(phase.failure) ? onRetry : null} />}
      </div>
      {phase.kind === 'ready' && labeling !== null && (
        <LabelPanel
          hidden={!labeling.open}
          replayId={labeling.replayId}
          player={phase.player}
          store={labeling.store}
          onClose={labeling.onClose}
        />
      )}
    </div>
  );
}

function LoadingState({ progress }: { readonly progress: LoadProgress | null }) {
  const { t } = useTranslation();
  const known = progress !== null && progress.totalBytes > 0;
  const percent = known ? (progress.loadedBytes / progress.totalBytes) * 100 : 0;
  return (
    <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 bg-black px-6">
      <Progress value={percent} aria-label={t('replayViewer.loading')} className="h-1.5 w-full max-w-xs bg-white/15 [&>div]:bg-white" />
      <p aria-hidden className="min-h-4 text-xs tabular-nums text-white/60">
        {known ? `${formatFileSize(progress.loadedBytes)} / ${formatFileSize(progress.totalBytes)}` : ''}
      </p>
    </div>
  );
}
