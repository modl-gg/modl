import { useTranslation } from 'react-i18next';
import { LoadingSpinner } from '@/components/ui/loading-spinner';
import { Notice } from '@/components/ui/notice';
import { StatusBadge, type StatusIntent } from '@/components/ui/status-badge';
import { ProfileEmpty } from '@/components/player/ProfileEmpty';
import { Button } from '@/components/ui/button';
import { usePlayerReplays } from '@/hooks/use-data';
import { formatDateWithTime } from '@/utils/date-utils';
import { formatFileSize } from '@/utils/file-utils';
import { replayIdFromReference, replayPagePath } from '@/lib/replay-reference';

interface PlayerReplaysListProps {
  playerId: string;
}

const getReplayIdFromReference = (replayUrl?: string) => replayIdFromReference(replayUrl, window.location.origin) ?? '';

const getReplayId = (replay: { replayId?: string; replayUrl?: string; matchSource?: string }) => {
  if (replay.matchSource === 'TICKET_FALLBACK') {
    return getReplayIdFromReference(replay.replayUrl) || replay.replayId || '';
  }

  return replay.replayId || getReplayIdFromReference(replay.replayUrl);
};

const REPLAY_STATUS_BADGES: ReadonlyMap<string, { readonly intent: StatusIntent; readonly labelKey: string }> = new Map([
  ['PENDING', { intent: 'warning', labelKey: 'status.pending' }],
  ['FAILED', { intent: 'destructive', labelKey: 'status.error' }],
]);

function ReplayStatusBadge({ status }: { readonly status: string }) {
  const { t } = useTranslation();
  const badge = REPLAY_STATUS_BADGES.get(status);
  if (badge === undefined) return null;
  return <StatusBadge intent={badge.intent}>{t(badge.labelKey)}</StatusBadge>;
}

const PlayerReplaysList = ({ playerId }: PlayerReplaysListProps) => {
  const { t } = useTranslation();
  const { data: replays, isLoading, error } = usePlayerReplays(playerId);

  if (isLoading) {
    return (
      <div className="flex justify-center py-8">
        <LoadingSpinner className="h-5 w-5 text-muted-foreground" />
      </div>
    );
  }

  if (error) {
    return (
      <Notice variant="error" announce>{t('player.replaysLoadFailed')}</Notice>
    );
  }

  if (!replays || replays.length === 0) {
    return (
      <ProfileEmpty>{t('player.noReplays')}</ProfileEmpty>
    );
  }

  return (
    <div className="divide-y">
      {replays.map((replay) => {
        const replayId = getReplayId(replay);
        const canOpenReplay = Boolean(replayId)
          && (replay.matchSource === 'TICKET_FALLBACK' || replay.status === 'COMPLETE');

        return (
          <div key={replay.replayId || replay.replayUrl} className="py-3">
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2 flex-wrap">
                  <span className="font-medium text-sm truncate">
                    {replay.targetName || replay.targetUuid || t('player.unknownPlayer')}
                  </span>
                  <ReplayStatusBadge status={replay.status} />
                </div>
                <p className="mt-1 text-xs text-muted-foreground">
                  {[
                    replay.createdAt ? formatDateWithTime(replay.createdAt) : '',
                    replay.mcVersion,
                    typeof replay.fileSize === 'number' ? formatFileSize(replay.fileSize) : '',
                    replayId,
                  ].filter((part) => part !== '').join(' · ')}
                </p>
              </div>
              <Button
                variant="outline"
                size="sm"
                onClick={() => window.open(replayPagePath(replayId), '_blank', 'noopener,noreferrer')}
                disabled={!canOpenReplay}
              >
                {t('player.openReplay')}
              </Button>
            </div>
          </div>
        );
      })}
    </div>
  );
};

export default PlayerReplaysList;
