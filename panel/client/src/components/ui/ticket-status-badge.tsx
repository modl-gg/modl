import { useTranslation } from 'react-i18next';
import { StatusBadge, type StatusIntent } from '@/components/ui/status-badge';
import type { NormalizedTicketStatus } from '@/lib/ticket-enums';

const STATUS_INTENTS = {
  open: 'success',
  closed: 'neutral',
  unfinished: 'warning',
} as const satisfies Record<NormalizedTicketStatus, StatusIntent>;

const STATUS_LABEL_KEYS = {
  open: 'status.open',
  closed: 'status.closed',
  unfinished: 'status.unfinished',
} as const satisfies Record<NormalizedTicketStatus, string>;

export function TicketStatusBadge({ status }: { status: NormalizedTicketStatus }) {
  const { t } = useTranslation();
  return <StatusBadge intent={STATUS_INTENTS[status]}>{t(STATUS_LABEL_KEYS[status])}</StatusBadge>;
}
