import { useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useLocation } from 'wouter';
import { ArrowLeft, Link2 } from 'lucide-react';
import PageContainer from '@/components/layout/PageContainer';
import { AppealPunishmentCard } from '@/components/tickets/AppealPunishmentCard';
import { TicketAiAnalysis } from '@/components/tickets/TicketAiAnalysis';
import { TicketChatLog } from '@/components/tickets/TicketChatLog';
import { TicketMessageItem } from '@/components/tickets/TicketMessageItem';
import { TicketNotesPanel } from '@/components/tickets/TicketNotesPanel';
import { TicketReopenForm } from '@/components/tickets/TicketReopenForm';
import { TicketReplayCard } from '@/components/tickets/TicketReplayCard';
import { TicketReplyComposer } from '@/components/tickets/TicketReplyComposer';
import { TicketSummaryCard } from '@/components/tickets/TicketSummaryCard';
import { useTicketReopen } from '@/components/tickets/use-ticket-reopen';
import { useTicketReply } from '@/components/tickets/use-ticket-reply';
import {
  messageIdentityKey,
  originalCreatorIdentifierOf,
  ticketCategoryOf,
  ticketIdOfRouteSegment,
  ticketMessagesOf,
  ticketOpenStateOf,
} from '@/components/tickets/ticket-view';
import { Button } from '@/components/ui/button';
import { CopyField } from '@/components/ui/copy-button';
import { IconAction } from '@/components/ui/icon-action';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Skeleton } from '@/components/ui/skeleton';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { keyByContent } from '@/components/ui/stable-keys';
import { TooltipProvider } from '@/components/ui/tooltip';
import { useAuth } from '@/hooks/use-auth';
import { usePermissions } from '@/hooks/use-permissions';
import { ticketNoteAccess } from '@/lib/permissions';
import { usePanelTicket, type PanelTicket } from '@/hooks/data/tickets';
import { usePunishmentTypes } from '@/hooks/data/punishments';
import { useStaff } from '@/hooks/data/staff';
import { queryClient } from '@/lib/queryClient';

type TicketTab = 'conversation' | 'notes';

function ticketIdOfPath(path: string): string {
  return ticketIdOfRouteSegment(path.split('/').at(-1) ?? '');
}

function ShareTicketLink({ publicUrl }: { publicUrl: string }) {
  const { t } = useTranslation();
  return (
    <Popover>
      <PopoverTrigger asChild>
        <IconAction icon={Link2} label={t('ticket.shareLink')} />
      </PopoverTrigger>
        <PopoverContent className="p-3 w-96 max-w-[calc(100vw-2rem)]">
          <div className="space-y-2">
            <p className="text-xs text-muted-foreground">{t('ticket.shareLinkDesc')}</p>
            <CopyField
              id="ticket-public-link"
              fieldLabel={t('ticket.publicLinkLabel')}
              value={publicUrl}
              label={t('ticket.copyLink')}
              failureMessage={t('ticket.copyLinkFailed')}
            />
          </div>
        </PopoverContent>
    </Popover>
  );
}

function TicketLoadingSkeleton() {
  return (
    <div className="space-y-6 py-4">
      <div className="flex items-center gap-3">
        <Skeleton className="h-8 w-8 rounded" />
        <Skeleton className="h-6 w-64" />
        <Skeleton className="h-5 w-16 ml-auto" />
      </div>
      <div className="space-y-4">
        <div className="flex items-center gap-3">
          <Skeleton className="h-10 w-10 rounded-full" />
          <div className="space-y-1">
            <Skeleton className="h-4 w-32" />
            <Skeleton className="h-3 w-20" />
          </div>
        </div>
        <Skeleton className="h-20 w-full" />
        <Skeleton className="h-20 w-full" />
        <Skeleton className="h-20 w-3/4" />
      </div>
    </div>
  );
}

interface TicketProblemProps {
  title: string;
  description: string;
  actionLabel: string;
  onAction: () => void;
}

function TicketProblem({ title, description, actionLabel, onAction }: TicketProblemProps) {
  return (
    <div className="flex justify-center items-center py-16 text-center">
      <div className="flex flex-col items-center gap-1">
        <h3 className="text-base font-semibold">{title}</h3>
        <p className="text-sm text-muted-foreground">{description}</p>
        <Button variant="outline" size="sm" className="mt-3" onClick={onAction}>
          {actionLabel}
        </Button>
      </div>
    </div>
  );
}

function TicketConversation({ ticket }: { ticket: PanelTicket }) {
  const { canListStaff, canReadPunishmentTypes } = usePermissions();
  const { data: staff } = useStaff({ enabled: canListStaff });
  const { data: punishmentTypes = [] } = usePunishmentTypes({ enabled: canReadPunishmentTypes });
  const messageListRef = useRef<HTMLDivElement>(null);
  const category = ticketCategoryOf(ticket);
  const messages = useMemo(() => ticketMessagesOf(ticket), [ticket]);
  const originalCreatorId = originalCreatorIdentifierOf(messages);
  const messageCount = messages.length;
  const reopen = useTicketReopen(ticket.id);
  const reply = useTicketReply(ticket);

  useEffect(() => {
    if (messageListRef.current) {
      messageListRef.current.scrollTop = 0;
    }
  }, [messageCount, ticket.id]);

  return (
    <div className="space-y-4">
      <div ref={messageListRef} className="max-h-[480px] overflow-y-auto divide-y">
        {keyByContent(messages, messageIdentityKey).map(({ key, item }, index) => (
          <TicketMessageItem
            key={key}
            message={item}
            isFirst={index === 0}
            originalCreatorId={originalCreatorId}
            creatorUuid={ticket.creatorUuid}
            staff={staff}
          />
        ))}
      </div>

      {ticket.locked ? (
        <TicketReopenForm reopen={reopen} />
      ) : (
        <TicketReplyComposer ticket={ticket} category={category} punishmentTypes={punishmentTypes} reply={reply} />
      )}
    </div>
  );
}

function TicketWorkspace({ ticket }: { ticket: PanelTicket }) {
  const { t } = useTranslation();
  const { user } = useAuth();
  const { canReadPunishmentTypes, userPermissions } = usePermissions();
  const { data: punishmentTypes = [] } = usePunishmentTypes({ enabled: canReadPunishmentTypes });
  const [activeTab, setActiveTab] = useState<TicketTab>('conversation');
  const noteAccess = ticketNoteAccess(userPermissions);
  const showsNotes = noteAccess.canView || noteAccess.canAdd;
  const category = ticketCategoryOf(ticket);

  return (
    <>
      <TicketSummaryCard ticket={ticket} category={category} />

      {category === 'appeal' && ticket.appealPunishmentId !== undefined && (
        <AppealPunishmentCard punishmentId={ticket.appealPunishmentId} />
      )}

      {ticket.replayUrl && <TicketReplayCard replayUrl={ticket.replayUrl} />}

      {category === 'chat_report' && ticket.chatMessages.length > 0 && <TicketChatLog lines={ticket.chatMessages} />}

      {ticket.aiAnalysis !== null && (
        <TicketAiAnalysis
          ticketId={ticket.id}
          reportedPlayerUuid={ticket.reportedPlayerUuid}
          analysis={ticket.aiAnalysis}
          isClosed={ticketOpenStateOf(ticket) === 'Closed'}
          staffName={user?.username}
          punishmentTypes={punishmentTypes}
        />
      )}

      <div className="space-y-4">
        {showsNotes && (
          <Tabs value={activeTab} onValueChange={(value) => { if (value === 'conversation' || value === 'notes') setActiveTab(value); }}>
            <TabsList>
              <TabsTrigger value="conversation">{t('ticket.tabs.conversation')}</TabsTrigger>
              <TabsTrigger value="notes">{t('ticket.tabs.notes')}</TabsTrigger>
            </TabsList>
          </Tabs>
        )}

        <div hidden={activeTab !== 'conversation'}>
          <TicketConversation ticket={ticket} />
        </div>
        {showsNotes && (
          <div hidden={activeTab !== 'notes'}>
            <TicketNotesPanel ticketId={ticket.id} notes={ticket.notes} canView={noteAccess.canView} canAdd={noteAccess.canAdd} />
          </div>
        )}
      </div>
    </>
  );
}

const TicketDetail = () => {
  const { t } = useTranslation();
  const [path, setLocation] = useLocation();
  const ticketId = ticketIdOfPath(path);
  const { data: ticket, isLoading, isError, refetch } = usePanelTicket(ticketId);
  const loadedTicketId = ticket?.id;
  const backToTickets = () => {
    setLocation('/panel/tickets');
  };

  useEffect(() => {
    if (loadedTicketId) {
      void queryClient.invalidateQueries({ queryKey: ['/v1/panel/ticket-subscriptions/updates'] });
      void queryClient.invalidateQueries({ queryKey: ['/v1/panel/ticket-subscriptions/assigned-updates'] });
    }
  }, [loadedTicketId]);

  return (
    <TooltipProvider>
      <PageContainer>
        <div className="flex flex-col space-y-4">
          <div className="flex items-center justify-between w-full">
            <Button variant="ghost" size="sm" className="gap-1" onClick={backToTickets}>
              <ArrowLeft className="h-4 w-4" />
              {t('ticket.backToTickets')}
            </Button>
            {ticket?.publicUrl && <ShareTicketLink publicUrl={ticket.publicUrl} />}
          </div>

          {isLoading && <TicketLoadingSkeleton />}

          {isError && ticket === undefined && (
            <TicketProblem
              title={t('ticket.loadFailed')}
              description={t('ticket.loadFailedDesc')}
              actionLabel={t('common.tryAgain')}
              onAction={() => {
                void refetch();
              }}
            />
          )}

          {!isLoading && !isError && ticket === null && (
            <TicketProblem
              title={t('ticket.notFound')}
              description={t('ticket.notFoundDesc', { id: ticketId })}
              actionLabel={t('ticket.backToTickets')}
              onAction={backToTickets}
            />
          )}

          {ticket && <TicketWorkspace key={ticket.id} ticket={ticket} />}
        </div>
      </PageContainer>
    </TooltipProvider>
  );
};

export default TicketDetail;
