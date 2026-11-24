import { useTranslation } from 'react-i18next';
import { StatusBadge, type StatusIntent } from '@/components/ui/status-badge';
import type { PunishmentStatusKind } from '@/utils/punishment-status';

const STATUS_INTENTS: Record<PunishmentStatusKind, StatusIntent> = {
  pardoned: 'success',
  rolledBack: 'neutral',
  unstarted: 'warning',
  active: 'destructive',
  inactive: 'neutral',
};

const STATUS_LABEL_KEYS: Record<PunishmentStatusKind, string> = {
  pardoned: 'status.pardoned',
  rolledBack: 'status.rolledBack',
  unstarted: 'player.unstarted',
  active: 'status.active',
  inactive: 'status.inactive',
};

export function PunishmentStatusBadge({ status }: { status: PunishmentStatusKind }) {
  const { t } = useTranslation();
  return <StatusBadge intent={STATUS_INTENTS[status]}>{t(STATUS_LABEL_KEYS[status])}</StatusBadge>;
}
