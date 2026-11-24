import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { LoadingSpinner } from '@/components/ui/loading-spinner';
import { PunishmentStatusBadge } from '@/components/ui/punishment-status-badge';
import { useLinkedBansForPunishment } from '@/hooks/data/punishments';

interface LinkedBansListProps {
  readonly punishmentId: string;
  readonly onOpenPlayer: (uuid: string, username: string) => void;
}

export function LinkedBansList({ punishmentId, onOpenPlayer }: LinkedBansListProps) {
  const { t } = useTranslation();
  const { data: linkedBans, isLoading } = useLinkedBansForPunishment(punishmentId);

  if (isLoading) return <LoadingSpinner className="h-4 w-4 text-muted-foreground" />;
  if (linkedBans === undefined || linkedBans.length === 0) return null;

  return (
    <div>
      <p className="text-xs font-medium text-muted-foreground mb-1">{t('player.linkedBans')}</p>
      <div className="flex flex-wrap gap-1">
        {linkedBans.map((linkedBan) => (
          <Button
            key={linkedBan.punishmentId}
            variant="outline"
            size="sm"
            className="h-7 px-2 text-xs"
            onClick={() => onOpenPlayer(linkedBan.playerUuid, linkedBan.playerName)}
          >
            {linkedBan.playerName}
            {linkedBan.active && <PunishmentStatusBadge status="active" />}
          </Button>
        ))}
      </div>
    </div>
  );
}
