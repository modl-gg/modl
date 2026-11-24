import { useTranslation } from 'react-i18next';
import { Loader2 } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { TicketStatusBadge } from '@/components/ui/ticket-status-badge';
import { Button } from '@/components/ui/button';
import { ClickablePlayer } from '@/components/ui/clickable-player';
import { LabelChip, LabelPicker } from '@/components/ui/label-chip';
import { keyByContent } from '@/components/ui/stable-keys';
import { getAvatarUrl } from '@/lib/api';
import { useAuth } from '@/hooks/use-auth';
import { usePermissions } from '@/hooks/use-permissions';
import { useToast } from '@/hooks/use-toast';
import { useEmailDelivery } from '@/hooks/use-public-settings';
import { useLabels } from '@/hooks/data/settings';
import { useAddTicketTag, useRemoveTicketTag, useUpdateTicket, type PanelTicket } from '@/hooks/data/tickets';
import { formatDate } from '@/utils/date-utils';
import { ClaimTicketNotice } from './ClaimTicketNotice';
import { isReportCategory, TICKET_CATEGORY_LABEL_KEYS, ticketOpenStateOf, type TicketCategory } from './ticket-view';

interface TicketSummaryCardProps {
  ticket: PanelTicket;
  category: TicketCategory;
}

function TicketLabels({ ticket }: { ticket: PanelTicket }) {
  const { t } = useTranslation();
  const { toast } = useToast();
  const { canTagTickets } = usePermissions();
  const { data: availableLabels = [] } = useLabels();
  const addTag = useAddTicketTag();
  const removeTag = useRemoveTicketTag();
  const unusedLabels = availableLabels.filter((label) => !ticket.tags.includes(label.name));

  const add = (tag: string) => {
    addTag.mutate({ id: ticket.id, tag }, {
      onError: () => {
        toast({ title: t('toast.error'), description: t('tickets.addLabelFailed'), variant: 'destructive' });
      },
    });
  };

  const remove = (tag: string) => {
    removeTag.mutate({ id: ticket.id, tag }, {
      onError: () => {
        toast({ title: t('toast.error'), description: t('tickets.removeLabelFailed'), variant: 'destructive' });
      },
    });
  };

  return (
    <>
      {keyByContent(ticket.tags, (tag) => tag).map(({ key, item: tag }) => (
        <LabelChip
          key={key}
          name={tag}
          color={availableLabels.find((label) => label.name === tag)?.color}
          onRemove={canTagTickets ? () => {
            remove(tag);
          } : undefined}
        />
      ))}
      {canTagTickets && <LabelPicker labels={unusedLabels} onPick={add} />}
    </>
  );
}

function isAssignedTo(ticket: PanelTicket, username: string): boolean {
  const normalized = username.toLowerCase();
  return normalized !== '' && ticket.assignedTo.some((assignee) => assignee.toLowerCase() === normalized);
}

function AssignmentToggle({ ticket, username }: { ticket: PanelTicket; username: string }) {
  const { t } = useTranslation();
  const { toast } = useToast();
  const updateTicket = useUpdateTicket();
  const isAssigned = isAssignedTo(ticket, username);

  const toggleAssignment = () => {
    const assignedTo = isAssigned
      ? ticket.assignedTo.filter((assignee) => assignee.toLowerCase() !== username.toLowerCase())
      : [...ticket.assignedTo, username];
    updateTicket.mutate({ id: ticket.id, data: { assignedTo } }, {
      onSuccess: (updated) => {
        if (isAssignedTo(updated, username) === isAssigned) {
          toast({ title: t('toast.error'), description: t('ticket.assignment.notChanged'), variant: 'destructive' });
          return;
        }
        toast({ title: isAssigned ? t('ticket.assignment.unassigned') : t('ticket.assignment.assigned') });
      },
      onError: () => {
        toast({ title: t('toast.error'), description: t('ticket.assignment.failed'), variant: 'destructive' });
      },
    });
  };

  return (
    <Button variant="outline" size="sm" className="h-7 text-xs" disabled={updateTicket.isPending} onClick={toggleAssignment}>
      {isAssigned ? t('ticket.assignment.unassignMe') : t('ticket.assignment.assignMe')}
    </Button>
  );
}

function TicketAssignment({ ticket }: { ticket: PanelTicket }) {
  const { t } = useTranslation();
  const { user } = useAuth();
  const { canAssignTickets: canManageAssignment } = usePermissions();
  const username = user?.username ?? '';

  return (
    <div className="flex flex-wrap items-center gap-2 text-sm">
      <span className="text-muted-foreground">{t('ticket.assignment.label')}:</span>
      <span>{ticket.assignedTo.length > 0 ? ticket.assignedTo.join(', ') : t('tickets.unassigned')}</span>
      {canManageAssignment && username !== '' && <AssignmentToggle ticket={ticket} username={username} />}
    </div>
  );
}

function TicketVisibilityToggle({ ticket }: { ticket: PanelTicket }) {
  const { t } = useTranslation();
  const { toast } = useToast();
  const updateTicket = useUpdateTicket();

  const toggleHidden = () => {
    const hidden = !ticket.hidden;
    updateTicket.mutate({ id: ticket.id, data: { hidden } }, {
      onError: () => {
        toast({ title: t('toast.error'), description: t('ticket.visibility.failed'), variant: 'destructive' });
      },
      onSuccess: () => {
        toast({ title: hidden ? t('ticket.visibility.hidden') : t('ticket.visibility.unhidden') });
      },
    });
  };

  return (
    <div>
      <Button variant="outline" size="sm" disabled={updateTicket.isPending} onClick={toggleHidden}>
        {updateTicket.isPending && <Loader2 className="h-4 w-4 mr-2 animate-spin" />}
        {ticket.hidden ? t('ticket.visibility.unhide') : t('ticket.visibility.hide')}
      </Button>
    </div>
  );
}

function PlayerAvatar({ uuid }: { uuid: string }) {
  return <img src={getAvatarUrl(uuid, 16, true)} alt="" className="h-4 w-4 rounded-sm" />;
}

export function TicketSummaryCard({ ticket, category }: TicketSummaryCardProps) {
  const { t } = useTranslation();
  const { canHideTickets } = usePermissions();
  const openState = ticketOpenStateOf(ticket);
  const reportedBy = ticket.reportedBy || t('common.unknown');
  const emailDelivery = useEmailDelivery();
  const requiresVerification = emailDelivery && ticket.emailAuthEnabled && ticket.creatorEmail !== undefined;
  const isWebUser = !ticket.creatorUuid || ticket.creatorEmail !== undefined;

  return (
    <div className="flex flex-col gap-3">
        <div className="space-y-2">
          <h1 className="text-xl font-semibold">{ticket.subject || t('ticket.noSubject')}</h1>
          <div className="flex flex-wrap items-center gap-2">
            <Badge variant="outline">
              {t(TICKET_CATEGORY_LABEL_KEYS[category])}
            </Badge>
            <TicketStatusBadge status={openState === 'Open' ? 'open' : 'closed'} />
            {ticket.hidden && (
              <Badge variant="warning">
                {t('tickets.hidden')}
              </Badge>
            )}
            <TicketLabels ticket={ticket} />
          </div>
        </div>

        <div className="flex flex-wrap gap-x-6 gap-y-1 text-sm">
          <div className="flex items-center">
            <span className="text-muted-foreground">{t('ticket.summary.openedBy')}:</span>
            <span className="ml-1 inline-flex items-center gap-1.5">
              {ticket.creatorUuid ? (
                <>
                  <PlayerAvatar uuid={ticket.creatorUuid} />
                  <ClickablePlayer playerText={reportedBy} uuid={ticket.creatorUuid} className="text-sm">
                    {reportedBy}
                  </ClickablePlayer>
                </>
              ) : (
                <span className="text-sm">{reportedBy}</span>
              )}
              {isWebUser && <span className="text-xs text-muted-foreground">{t('ticket.summary.webUser')}</span>}
            </span>
          </div>
          {!ticket.creatorUuid && ticket.creatorEmail !== undefined && (
            <div className="flex items-center">
              <span className="text-muted-foreground">{t('ticket.summary.email')}:</span>
              <span className="ml-1 text-sm">{ticket.creatorEmail}</span>
            </div>
          )}
          <div>
            <span className="text-muted-foreground">{t('ticket.summary.date')}:</span>
            <span className="ml-1">{ticket.date > 0 ? formatDate(new Date(ticket.date).toISOString()) : t('common.unknown')}</span>
          </div>
          {isReportCategory(category) && ticket.reportedPlayer && (
            <div className="flex items-center">
              <span className="text-muted-foreground">{t('ticket.summary.reported')}:</span>
              <span className="ml-1 inline-flex items-center gap-1.5">
                {ticket.reportedPlayerUuid && <PlayerAvatar uuid={ticket.reportedPlayerUuid} />}
                <ClickablePlayer
                  playerText={ticket.reportedPlayerUuid || ticket.reportedPlayer}
                  uuid={ticket.reportedPlayerUuid || undefined}
                  className="text-sm"
                >
                  {ticket.reportedPlayer}
                </ClickablePlayer>
              </span>
            </div>
          )}
        </div>

        <TicketAssignment ticket={ticket} />

        {!ticket.creatorUuid && (
          <ClaimTicketNotice
            title={t('ticketClaim.staffTitle')}
            commandIntro={t('ticketClaim.staffCommandIntro')}
            ticketId={ticket.id}
            accessKey={requiresVerification ? null : ticket.publicAccessKey}
          />
        )}

        {ticket.locked && canHideTickets && <TicketVisibilityToggle ticket={ticket} />}
    </div>
  );
}
