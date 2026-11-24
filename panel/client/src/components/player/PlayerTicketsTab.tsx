import { useTranslation } from 'react-i18next';
import { useLocation } from 'wouter';
import { Badge } from '@/components/ui/badge';
import { LoadingSpinner } from '@/components/ui/loading-spinner';
import { StatusBadge } from '@/components/ui/status-badge';
import { ProfileEmpty } from './ProfileEmpty';
import { keyByContent } from '@/components/ui/stable-keys';
import type { PlayerTicket } from '@/hooks/data/tickets';
import { normalizeTicketStatus } from '@/lib/ticket-enums';
import { TicketStatusBadge } from '@/components/ui/ticket-status-badge';
import { formatDateWithTime } from '@/utils/date-utils';
import { ticketRoutePath } from '@/components/tickets/ticket-view';

function TicketCard({ playerId, ticket }: { readonly playerId: string; readonly ticket: PlayerTicket }) {
  const { t } = useTranslation();
  const [, navigate] = useLocation();
  const ticketId = ticket.id ?? '';
  const isCreator = ticket.creatorUuid === playerId;
  const isReported = ticket.reportedPlayerUuid === playerId;
  const assignees = ticket.assignedTo.join(', ');

  return (
    <button
      type="button"
      className="block w-full rounded-md px-2 py-3 text-left hover:bg-muted/50 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-ring"
      disabled={ticketId === ''}
      onClick={() => { navigate(ticketRoutePath(ticketId)); }}
    >
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-sm font-medium">{ticket.subject ?? ticketId}</span>
        <TicketStatusBadge status={normalizeTicketStatus(ticket.status)} />
        {isReported && <StatusBadge intent="warning">{t('player.ticketRoleReported')}</StatusBadge>}
      </div>
      <p className="mt-1 text-xs text-muted-foreground">
        {[
          ticketId,
          ticket.created === null ? '' : formatDateWithTime(ticket.created),
          isCreator && ticket.reportedPlayer !== undefined ? t('player.ticketReportedPlayer', { name: ticket.reportedPlayer }) : '',
          !isCreator && ticket.creatorName !== undefined ? t('player.ticketCreator', { name: ticket.creatorName }) : '',
          assignees === '' ? '' : t('player.ticketAssignedTo', { staff: assignees }),
        ].filter((part) => part !== '').join(' · ')}
      </p>
      {ticket.tags.length > 0 && (
        <div className="mt-2 flex gap-1">
          {keyByContent(ticket.tags, (tag) => tag).map(({ key, item: tag }) => (
            <Badge key={key} variant="outline">{tag}</Badge>
          ))}
        </div>
      )}
    </button>
  );
}

interface PlayerTicketsTabProps {
  readonly playerId: string;
  readonly tickets: readonly PlayerTicket[];
  readonly isLoading: boolean;
}

export function PlayerTicketsTab({ playerId, tickets, isLoading }: PlayerTicketsTabProps) {
  const { t } = useTranslation();

  return (
    isLoading ? (
      <div className="flex justify-center py-8">
        <LoadingSpinner className="h-5 w-5 text-muted-foreground" />
      </div>
    ) : tickets.length > 0 ? (
      <div className="divide-y">
        {keyByContent(tickets, (ticket) => ticket.id ?? '').map(({ key, item: ticket }) => (
          <TicketCard key={key} playerId={playerId} ticket={ticket} />
        ))}
      </div>
    ) : (
      <ProfileEmpty>{t('player.noTickets')}</ProfileEmpty>
    )
  );
}
