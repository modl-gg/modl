import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { QuickResponseAction } from '@proto/modl/v1/settings_pb.ts';
import { apiErrorCode } from '@/lib/http-error';
import { APPROVAL_NEEDS_PARDON_CODE, SHORTENING_NEEDS_PARDON_CODE } from '@/lib/punishment-actions';
import { useAuth } from '@/hooks/use-auth';
import { useMediaUpload } from '@/hooks/use-media-upload';
import { useToast } from '@/hooks/use-toast';
import { useModifyPunishment } from '@/hooks/data/punishments';
import { useUpdateTicket, type PanelTicket, type TicketAttachmentInput } from '@/hooks/data/tickets';
import { appealModificationOf, type AppealModification } from './appeal-decision';
import { appealDecisionOf, CLOSE_ACTION, COMMENT_ACTION } from './quick-actions';
import type { ReduceDuration } from './ReduceDurationFields';

const LARGE_UPLOAD_BYTES = 5 * 1024 * 1024;
const BYTES_PER_MEGABYTE = 1024 * 1024;

export interface ReplyDraft {
  text: string;
  selectedAction: string;
  reduceDuration: ReduceDuration;
  files: readonly File[];
}

export const INITIAL_REPLY_DRAFT: ReplyDraft = {
  text: '',
  selectedAction: COMMENT_ACTION,
  reduceDuration: { value: 0, unit: 'days', isPermanent: false },
  files: [],
};

export interface TicketReply {
  draft: ReplyDraft;
  updateDraft: (update: (draft: ReplyDraft) => ReplyDraft) => void;
  submit: (actionConfig: QuickResponseAction | undefined) => void;
  isSubmitting: boolean;
  sentCount: number;
}

function closesTicket(draft: ReplyDraft, actionConfig: QuickResponseAction | undefined): boolean {
  return draft.selectedAction === CLOSE_ACTION || actionConfig?.closeTicket === true;
}

export function useTicketReply(ticket: PanelTicket): TicketReply {
  const { t } = useTranslation();
  const { toast } = useToast();
  const { user } = useAuth();
  const { uploadMedia } = useMediaUpload();
  const updateTicket = useUpdateTicket();
  const modifyPunishment = useModifyPunishment();
  const [draft, setDraft] = useState<ReplyDraft>(INITIAL_REPLY_DRAFT);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [sentCount, setSentCount] = useState(0);

  const uploadFiles = async (files: readonly File[]): Promise<TicketAttachmentInput[]> => {
    const uploaded: TicketAttachmentInput[] = [];
    for (const file of files) {
      if (file.size > LARGE_UPLOAD_BYTES) {
        toast({
          title: t('upload.uploading'),
          description: t('ticket.uploadingFile', { name: file.name, size: Math.round(file.size / BYTES_PER_MEGABYTE) }),
        });
      }
      const result = await uploadMedia(file, 'ticket', { ticketId: ticket.id, fieldId: 'reply' });
      uploaded.push({
        id: result.key,
        url: result.url,
        key: result.key,
        fileName: file.name,
        fileType: file.type,
        fileSize: file.size,
        uploadedAt: new Date().toISOString(),
        uploadedBy: user?.username ?? '',
      });
    }
    return uploaded;
  };

  const appealRefusalMessage = (code: string | null): string => {
    if (code === APPROVAL_NEEDS_PARDON_CODE) return t('ticket.appeal.approvalNeedsPardon');
    if (code === SHORTENING_NEEDS_PARDON_CODE) return t('ticket.appeal.reductionNeedsPardon');
    return t('ticket.appealActionFailed');
  };

  const applyModification = async (modification: AppealModification): Promise<boolean> => {
    try {
      await modifyPunishment.mutateAsync({
        uuid: modification.playerUuid,
        punishmentId: modification.punishmentId,
        modificationType: modification.modificationType,
        reason: modification.reason,
        newDuration: modification.newDuration,
        appealTicketId: ticket.id,
      });
    } catch (error) {
      const description = appealRefusalMessage(apiErrorCode(error));
      toast({ title: t('toast.error'), description, variant: 'destructive' });
      return false;
    }
    toast({
      title: t(modification.successTitleKey),
      description: t(modification.successDescriptionKey, { id: modification.punishmentId }),
    });
    return true;
  };

  const send = async (
    sent: ReplyDraft,
    actionConfig: QuickResponseAction | undefined,
    modification: AppealModification | undefined,
  ): Promise<boolean> => {
    let attachments: TicketAttachmentInput[];
    try {
      attachments = await uploadFiles(sent.files);
    } catch {
      toast({ title: t('upload.uploadFailed'), description: t('ticket.uploadFailedDesc'), variant: 'destructive' });
      return false;
    }

    if (modification !== undefined && !(await applyModification(modification))) {
      return false;
    }

    await updateTicket.mutateAsync({
      id: ticket.id,
      author: user?.username ?? '',
      data: {
        ...(closesTicket(sent, actionConfig) ? { locked: true, status: 'Closed' } : {}),
        newReply: {
          content: sent.text.trim(),
          action: sent.selectedAction,
          attachments,
        },
      },
    });
    return true;
  };

  const markSent = (sent: ReplyDraft) => {
    setDraft((current) => (current === sent ? INITIAL_REPLY_DRAFT : current));
    setSentCount((count) => count + 1);
  };

  const submit = (actionConfig: QuickResponseAction | undefined) => {
    if (isSubmitting) return;
    const sent = draft;
    const decision = appealDecisionOf(actionConfig);
    const appealModification = decision === undefined
      ? undefined
      : appealModificationOf(decision, sent.reduceDuration, ticket);
    if (appealModification?.ok === false) {
      toast({ title: t('toast.error'), description: t(appealModification.issueKey), variant: 'destructive' });
      return;
    }

    setIsSubmitting(true);
    send(sent, actionConfig, appealModification?.modification)
      .then((delivered) => {
        if (delivered) markSent(sent);
      })
      .catch(() => {
        toast({ title: t('toast.error'), description: t('ticket.replyFailed'), variant: 'destructive' });
      })
      .finally(() => {
        setIsSubmitting(false);
      });
  };

  return { draft, updateDraft: setDraft, submit, isSubmitting, sentCount };
}
