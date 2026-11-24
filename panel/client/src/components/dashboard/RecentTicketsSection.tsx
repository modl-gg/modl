import { Link } from 'wouter';
import { useTranslation } from 'react-i18next';
import { formatTimeAgo } from '@/utils/date-utils';
import { stripMarkdown } from '@/utils/markdown-utils';
import { StatusBadge } from '@/components/ui/status-badge';
import { normalizeTicketStatus } from '@/lib/ticket-enums';
import { TicketStatusBadge } from '@/components/ui/ticket-status-badge';
import { ticketRoutePath } from '@/components/tickets/ticket-view';
import type { RecentTicket } from '@/hooks/data/dashboard';
import { DashboardMeta, DashboardSection, type DashboardFeedState } from './DashboardSection';

const PRIORITY_LABEL_KEYS: ReadonlyMap<string, string> = new Map([
  ['high', 'dashboard.recentTickets.priorityHigh'],
  ['urgent', 'dashboard.recentTickets.priorityUrgent'],
]);

interface RecentTicketsSectionProps {
  feed: DashboardFeedState;
  tickets: RecentTicket[];
}

function hasKnownTime(date: Date): boolean {
  return !Number.isNaN(date.getTime()) && date.getTime() !== 0;
}

export function RecentTicketsSection({ feed, tickets }: RecentTicketsSectionProps) {
  const { t } = useTranslation();

  return (
    <DashboardSection
      title={t('dashboard.recentTickets.title')}
      emptyText={t('dashboard.recentTickets.empty')}
      isEmpty={tickets.length === 0}
      feed={feed}
    >
      {tickets.map((ticket) => {
        const priorityLabelKey = PRIORITY_LABEL_KEYS.get(ticket.priority);
        const status = normalizeTicketStatus(ticket.status);
        return (
        <li key={ticket.id}>
          <Link href={ticketRoutePath(ticket.id)} className="block rounded-md px-2 py-3 hover:bg-muted/50 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-ring">
            <div className="flex items-start justify-between gap-2">
              <p className="truncate text-sm font-medium">{ticket.title}</p>
              <div className="flex shrink-0 gap-1">
                {priorityLabelKey !== undefined && <StatusBadge intent="warning">{t(priorityLabelKey)}</StatusBadge>}
                {status !== 'open' && <TicketStatusBadge status={status} />}
              </div>
            </div>
            {ticket.initialMessage !== '' && (
              <p className="mt-0.5 line-clamp-2 text-sm text-muted-foreground">{stripMarkdown(ticket.initialMessage)}</p>
            )}
            <DashboardMeta
              parts={[
                ticket.playerName || t('search.unknown'),
                hasKnownTime(ticket.createdAt) ? formatTimeAgo(ticket.createdAt) : '',
              ]}
            />
          </Link>
        </li>
        );
      })}
    </DashboardSection>
  );
}
