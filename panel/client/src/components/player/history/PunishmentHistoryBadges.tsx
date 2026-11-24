import { useTranslation } from 'react-i18next';
import { PunishmentStatusBadge } from '@/components/ui/punishment-status-badge';
import { OffenseStatusBadge, PunishmentOptionBadges, SeverityBadge } from '@/components/ui/punishment-level-badges';
import type { PunishmentStatusKind } from '@/utils/punishment-status';
import { displayOffenseStatus, displaySeverity, type PunishmentHistoryEntry } from '../player-profile';

interface PunishmentHistoryBadgesProps {
  readonly entry: PunishmentHistoryEntry;
  readonly durationLabel: string | null;
  readonly status: PunishmentStatusKind | null;
}

export function PunishmentHistoryBadges({ entry, durationLabel, status }: PunishmentHistoryBadgesProps) {
  const { t } = useTranslation();
  const severity = displaySeverity(entry.punishment);
  const offenseStatus = displayOffenseStatus(entry.punishment);

  return (
    <div className="flex flex-wrap items-center gap-1.5">
      <span className="text-sm font-medium">
        {entry.typeName || t('player.unknownPunishmentType', { ordinal: entry.punishment.typeOrdinal })}
      </span>
      {durationLabel !== null && <span className="text-sm text-muted-foreground">{durationLabel}</span>}
      {status !== null && <PunishmentStatusBadge status={status} />}
      <PunishmentOptionBadges altBlocking={entry.punishment.altBlocking === true} statWiping={entry.punishment.statWiping === true} />
      {severity !== null && <SeverityBadge severity={severity} />}
      {offenseStatus !== null && <OffenseStatusBadge status={offenseStatus} />}
    </div>
  );
}
