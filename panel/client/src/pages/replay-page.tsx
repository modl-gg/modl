import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Loader2 } from 'lucide-react';
import { useSearch } from 'wouter';
import { ReplayViewer } from '@/replay-viewer/components/ReplayViewer';
import type { PlayerLabel } from '@/replay-viewer/types/labeling';
import { submitReplayLabels } from '@/lib/replay-api';
import { REPLAY_COMPLETE_STATUS, useReplayMetadata } from '@/hooks/data/replays';
import { describeError } from '@/lib/http-error';
import { readSearchParam } from '@/hooks/use-search-param';

const ATLAS_BASE = import.meta.env.VITE_REPLAY_ATLAS_BASE_URL || '/atlas';

function ReplayUnavailable({ message }: { message: string }) {
  const { t } = useTranslation();
  return (
    <div className="flex flex-col items-center justify-center w-screen h-screen bg-black text-white gap-2">
      <p className="text-lg font-medium">{t('pages.replay.unableToLoad')}</p>
      <p className="text-sm text-gray-400">{message}</p>
    </div>
  );
}

export default function ReplayPage() {
  const { t } = useTranslation();
  const replayId = readSearchParam(useSearch(), 'id');
  const metadataQuery = useReplayMetadata(replayId);
  const [viewerError, setViewerError] = useState<string | null>(null);

  if (replayId === '') {
    return <ReplayUnavailable message={t('pages.replay.noReplayId')} />;
  }

  if (metadataQuery.isPending) {
    return (
      <div className="flex items-center justify-center w-screen h-screen bg-black">
        <Loader2 className="h-8 w-8 animate-spin text-white" />
      </div>
    );
  }

  if (metadataQuery.isError) {
    return <ReplayUnavailable message={describeError(metadataQuery.error, t('pages.replay.loadFailed'))} />;
  }

  const metadata = metadataQuery.data;
  if (metadata.status !== REPLAY_COMPLETE_STATUS) {
    return <ReplayUnavailable message={t('pages.replay.stillProcessing')} />;
  }

  if (viewerError !== null) {
    return <ReplayUnavailable message={viewerError} />;
  }

  const handleLabelSubmit = async (labels: PlayerLabel[]) => {
    await submitReplayLabels(metadata.replayId, labels);
  };

  return (
    <div style={{ width: '100vw', height: '100vh' }}>
      <ReplayViewer
        replayUrl={metadata.replayUrl}
        mcVersion={metadata.mcVersion}
        atlasBaseUrl={ATLAS_BASE}
        replayId={metadata.replayId}
        onLabelSubmit={handleLabelSubmit}
        onError={(err) => setViewerError(describeError(err, t('pages.replay.loadFailed')))}
      />
    </div>
  );
}
