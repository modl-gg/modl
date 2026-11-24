import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Plus, X, Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Label } from '@/components/ui/label';
import { StatusBadge } from '@/components/ui/status-badge';
import { useModifyPunishmentTickets } from '@/hooks/data/punishments';
import type { PlayerTicket } from '@/hooks/data/tickets';
import { toast } from '@/hooks/use-toast';
import { describeError } from '@/lib/http-error';
import type { PunishmentHistoryEntry } from '../player-profile';
import { ActionFormPanel } from './ActionFormPanel';

interface PunishmentTicketsFormProps {
  readonly playerId: string;
  readonly entry: PunishmentHistoryEntry;
  readonly tickets: readonly PlayerTicket[];
  readonly onClose: () => void;
}

const SHORT_TICKET_ID_LENGTH = 8;

function shortTicketId(ticketId: string): string {
  return ticketId.slice(-SHORT_TICKET_ID_LENGTH);
}

function AttachedTicketRow({ ticketId, ticket, isNew, onRemove }: {
  readonly ticketId: string;
  readonly ticket: PlayerTicket | undefined;
  readonly isNew: boolean;
  readonly onRemove: () => void;
}) {
  const { t } = useTranslation();
  return (
    <div className={`flex items-center justify-between gap-2 rounded-md px-2 py-1 text-sm ${isNew ? 'bg-success/10' : ''}`}>
      <span className="flex min-w-0 items-center gap-2 truncate">
        {ticket === undefined ? shortTicketId(ticketId) : t('player.ticketWithType', { id: shortTicketId(ticketId), type: ticket.type ?? '' })}
        {ticket !== undefined && (
          <StatusBadge>{ticket.locked ? t('player.ticketClosed') : t('player.ticketOpen')}</StatusBadge>
        )}
      </span>
      <Button variant="ghost" size="icon" className="h-7 w-7 shrink-0" aria-label={t('common.remove')} onClick={onRemove}>
        <X className="h-4 w-4" />
      </Button>
    </div>
  );
}

export function PunishmentTicketsForm({ playerId, entry, tickets, onClose }: PunishmentTicketsFormProps) {
  const { t } = useTranslation();
  const modifyTickets = useModifyPunishmentTickets();
  const [added, setAdded] = useState<readonly string[]>([]);
  const [removed, setRemoved] = useState<readonly string[]>([]);
  const [modifyAssociated, setModifyAssociated] = useState(true);
  const fieldId = useId();
  const effectiveIds = [...entry.punishment.attachedTicketIds.filter((id) => !removed.includes(id)), ...added];
  const available = tickets.flatMap((ticket) => (
    ticket.id !== undefined && !effectiveIds.includes(ticket.id) && ticket.reportedPlayerUuid === playerId && !ticket.locked
      ? [{ id: ticket.id, type: ticket.type ?? '' }]
      : []
  ));

  const detach = (ticketId: string) => {
    if (added.includes(ticketId)) {
      setAdded(added.filter((id) => id !== ticketId));
    } else {
      setRemoved([...removed, ticketId]);
    }
  };

  const submit = () => {
    modifyTickets.mutate({
      uuid: playerId,
      punishmentId: entry.punishment.id,
      addTicketIds: [...added],
      removeTicketIds: [...removed],
      modifyAssociatedTickets: modifyAssociated,
    }, {
      onSuccess: () => {
        toast({ title: t('player.ticketsUpdated') });
        onClose();
      },
      onError: (error) => {
        toast({ title: t('player.modifyTicketsFailed'), description: describeError(error, t('common.unknownError')), variant: 'destructive' });
      },
    });
  };

  return (
    <ActionFormPanel
      title={t('player.modifyLinkedTickets')}
      submitLabel={modifyTickets.isPending
        ? <><Loader2 className="h-4 w-4 animate-spin" />{t('punishment.applying')}</>
        : t('player.applyChanges')}
      canSubmit={(added.length > 0 || removed.length > 0) && !modifyTickets.isPending}
      onSubmit={submit}
      onCancel={onClose}
    >
      <div className="space-y-1">
        <p className="text-xs font-medium text-muted-foreground">{t('player.attachedTickets')}</p>
        {effectiveIds.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t('player.noTicketsAttached')}</p>
        ) : (
          <div className="space-y-1">
            {effectiveIds.map((ticketId) => (
              <AttachedTicketRow
                key={ticketId}
                ticketId={ticketId}
                ticket={tickets.find((ticket) => ticket.id === ticketId)}
                isNew={added.includes(ticketId)}
                onRemove={() => detach(ticketId)}
              />
            ))}
          </div>
        )}
      </div>

      <div className="space-y-1">
        <p className="text-xs font-medium text-muted-foreground">{t('player.addTickets')}</p>
        {available.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t('player.noOpenReports')}</p>
        ) : (
          <div className="space-y-1 max-h-32 overflow-y-auto">
            {available.map((ticket) => (
              <button
                type="button"
                key={ticket.id}
                className="flex w-full items-center justify-between gap-2 rounded-md px-2 py-1 text-left text-sm hover:bg-muted/50"
                aria-label={t('player.addTicketNamed', { ticket: shortTicketId(ticket.id) })}
                onClick={() => setAdded([...added, ticket.id])}
              >
                <span className="truncate">{t('player.ticketWithType', { id: shortTicketId(ticket.id), type: ticket.type })}</span>
                <Plus className="h-4 w-4 shrink-0 text-muted-foreground" />
              </button>
            ))}
          </div>
        )}
      </div>

      <div className="flex items-center gap-2">
        <Checkbox
          id={`${fieldId}-modify-associated`}
          checked={modifyAssociated}
          onCheckedChange={(checked) => setModifyAssociated(checked === true)}
        />
        <Label htmlFor={`${fieldId}-modify-associated`} className="font-normal">{t('player.modifyAssociatedTickets')}</Label>
      </div>
    </ActionFormPanel>
  );
}
