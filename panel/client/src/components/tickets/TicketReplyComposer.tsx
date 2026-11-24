import { useTranslation } from 'react-i18next';
import { Loader2 } from 'lucide-react';
import type { PunishmentType, QuickResponseAction } from '@proto/modl/v1/settings_pb.ts';
import { Chip, ChipRemoveButton } from '@/components/ui/chip';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import MarkdownHelp from '@/components/ui/markdown-help';
import { keyByContent } from '@/components/ui/stable-keys';
import MediaUpload from '@/components/MediaUpload';
import { usePermissions } from '@/hooks/use-permissions';
import { useQuickResponses } from '@/hooks/data/settings';
import type { PanelTicket } from '@/hooks/data/tickets';
import { PendingFilePreview } from './PendingFilePreview';
import { ReduceDurationFields } from './ReduceDurationFields';
import { TicketPunishmentPanel } from './TicketPunishmentPanel';
import { truncateFileName } from './file-names';
import { appealDecisionNeedsPardon, showsAppealDecision } from '@/lib/punishment-actions';
import { appealDecisionOf, CLOSE_ACTION, COMMENT_ACTION, fillQuickResponseMessage, isReduceAction, quickActionsFor } from './quick-actions';
import { isReportCategory, type TicketCategory } from './ticket-view';
import type { TicketReply } from './use-ticket-reply';

function pendingFileKey(file: File): string {
  return `${file.name}|${String(file.size)}|${String(file.lastModified)}`;
}

interface TicketReplyComposerProps {
  ticket: PanelTicket;
  category: TicketCategory;
  punishmentTypes: readonly PunishmentType[];
  reply: TicketReply;
}

export function TicketReplyComposer({ ticket, category, punishmentTypes, reply }: TicketReplyComposerProps) {
  const { t } = useTranslation();
  const { hasPermission, canCloseTickets } = usePermissions();
  const { data: quickResponses } = useQuickResponses();
  const { draft, updateDraft, isSubmitting } = reply;
  const pendingFiles = draft.files;

  const quickActions = quickActionsFor(quickResponses?.data, category, (decision) => showsAppealDecision(hasPermission, decision));
  const needsPardon = (action: QuickResponseAction) => {
    const decision = appealDecisionOf(action);
    return decision !== undefined && appealDecisionNeedsPardon(hasPermission, decision);
  };
  const refusedDecisions = new Set(quickActions.filter(needsPardon).map((action) => appealDecisionOf(action)));
  const selectedConfig = quickActions.find((action) => action.name === draft.selectedAction);
  const isActionReply = draft.selectedAction !== '' && draft.selectedAction !== COMMENT_ACTION;
  const showReduceFields = isActionReply && isReduceAction(draft.selectedAction, selectedConfig);
  const showPunishment = isReportCategory(category) && selectedConfig?.showPunishment === true;
  const canSend = (selectedConfig === undefined || !needsPardon(selectedConfig))
    && (draft.text.trim() !== '' || pendingFiles.length > 0 || isActionReply);
  const placeholder = draft.selectedAction !== CLOSE_ACTION && selectedConfig?.message
    ? fillQuickResponseMessage(selectedConfig.message, ticket.reportedPlayer)
    : t('ticket.replyLabel');

  const selectAction = (action: string) => {
    const config = quickActions.find((candidate) => candidate.name === action);
    updateDraft((previous) => {
      if (action === COMMENT_ACTION) return { ...previous, selectedAction: action, text: '' };
      const text = action === CLOSE_ACTION
        ? t('ticket.closeMessage')
        : fillQuickResponseMessage(config?.message ?? '', ticket.reportedPlayer);
      return { ...previous, selectedAction: action, text: text || previous.text };
    });
  };

  const submit = () => {
    if (!canSend) return;
    reply.submit(selectedConfig);
  };

  return (
    <div className="border rounded-md p-3">
      <div className="mb-3 space-y-2">
        <div className="flex flex-wrap items-center gap-2">
          <Label htmlFor="ticket-reply-action">{t('ticket.replyAction')}</Label>
          <Select value={draft.selectedAction || COMMENT_ACTION} onValueChange={selectAction}>
            <SelectTrigger id="ticket-reply-action" className="h-9 w-auto min-w-48 max-w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={COMMENT_ACTION}>{t('ticket.actions.comment')}</SelectItem>
              {keyByContent(quickActions, (action) => `${action.id}|${action.name}`).map(({ key, item: action }) => (
                <SelectItem key={key} value={action.name} disabled={needsPardon(action)}>{action.name}</SelectItem>
              ))}
              {canCloseTickets && <SelectItem value={CLOSE_ACTION}>{t('common.close')}</SelectItem>}
            </SelectContent>
          </Select>
        </div>
        {refusedDecisions.has('pardon') && (
          <p className="text-xs text-muted-foreground">{t('ticket.appeal.approvalNeedsPardon')}</p>
        )}
        {refusedDecisions.has('reduce') && (
          <p className="text-xs text-muted-foreground">{t('ticket.appeal.reductionNeedsPardon')}</p>
        )}
      </div>

      {showReduceFields && (
        <ReduceDurationFields
          duration={draft.reduceDuration}
          onChange={(reduceDuration) => {
            updateDraft((previous) => ({ ...previous, reduceDuration }));
          }}
        />
      )}

      {showPunishment && (
        <TicketPunishmentPanel
          key={reply.sentCount}
          ticketId={ticket.id}
          playerUuid={ticket.reportedPlayerUuid}
          playerName={ticket.reportedPlayer}
          punishmentTypes={punishmentTypes}
        />
      )}

      <div className="space-y-3">
        <Textarea
          className="min-h-[120px] resize-none"
          aria-label={t('ticket.replyLabel')}
          placeholder={placeholder}
          value={draft.text}
          onChange={(event) => {
            const text = event.target.value;
            updateDraft((previous) => ({ ...previous, text }));
          }}
        />

        {pendingFiles.length > 0 && (
          <div className="flex flex-wrap gap-2">
            {keyByContent(pendingFiles, pendingFileKey).map(({ key, item: file }) => (
              <Chip key={key}>
                <PendingFilePreview file={file} />
                <span>{truncateFileName(file.name)}</span>
                <span className="text-[10px]">{t('ticket.pendingFile')}</span>
                <ChipRemoveButton
                  label={t('ticket.removeFile', { name: file.name })}
                  onClick={() => {
                    updateDraft((previous) => ({ ...previous, files: previous.files.filter((candidate) => candidate !== file) }));
                  }}
                />
              </Chip>
            ))}
          </div>
        )}

        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="flex flex-wrap items-center gap-2">
            <MediaUpload
              uploadType="ticket"
              autoUpload={false}
              onFilesSelected={(files) => {
                updateDraft((previous) => ({ ...previous, files: [...previous.files, ...files] }));
              }}
              metadata={{ ticketId: ticket.id, fieldId: 'reply' }}
              variant="button-only"
              maxFiles={5}
            />
            <MarkdownHelp />
          </div>
          <Button size="sm" onClick={submit} disabled={!canSend || isSubmitting}>
            {isSubmitting && <Loader2 className="h-4 w-4 mr-1.5 animate-spin" />}
            {t('ticket.send')}
          </Button>
        </div>
      </div>
    </div>
  );
}
