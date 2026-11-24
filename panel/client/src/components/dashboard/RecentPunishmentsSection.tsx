import { useTranslation } from 'react-i18next';
import { Badge } from '@/components/ui/badge';
import { StatusBadge } from '@/components/ui/status-badge';
import { usePlayerWindow } from '@/contexts/PlayerWindowContext';
import { formatTimeAgo } from '@/utils/date-utils';
import type { RecentPunishment } from '@/hooks/data/dashboard';
import { DashboardMeta, DashboardSection, type DashboardFeedState } from './DashboardSection';

interface RecentPunishmentsSectionProps {
  feed: DashboardFeedState;
  punishments: RecentPunishment[];
}

export function RecentPunishmentsSection({ feed, punishments }: RecentPunishmentsSectionProps) {
  const { t } = useTranslation();
  const { openPlayerWindow } = usePlayerWindow();

  return (
    <DashboardSection
      title={t('dashboard.recentPunishments.title')}
      emptyText={t('dashboard.recentPunishments.empty')}
      isEmpty={punishments.length === 0}
      feed={feed}
    >
      {punishments.map((punishment) => (
        <li key={punishment.id}>
          <button
            type="button"
            className="block w-full rounded-md px-2 py-3 text-left hover:bg-muted/50 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-ring"
            onClick={() => openPlayerWindow(punishment.playerUuid, punishment.playerName)}
          >
            <div className="flex items-center justify-between gap-2">
              <div className="flex min-w-0 items-center gap-2">
                <span className="truncate text-sm font-medium">{punishment.playerName}</span>
                {punishment.type !== '' && <Badge variant="secondary" className="shrink-0">{punishment.type}</Badge>}
                {punishment.active && <StatusBadge intent="destructive" className="shrink-0">{t('status.active')}</StatusBadge>}
              </div>
              <span className="shrink-0 text-xs text-muted-foreground">{formatTimeAgo(punishment.issued)}</span>
            </div>
            <DashboardMeta
              parts={[
                punishment.reason,
                t('dashboard.recentPunishments.by', { issuer: punishment.issuerName || t('search.unknown') }),
              ]}
            />
          </button>
        </li>
      ))}
    </DashboardSection>
  );
}
