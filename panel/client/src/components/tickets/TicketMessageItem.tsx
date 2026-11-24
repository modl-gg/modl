import { useTranslation } from 'react-i18next';
import { Badge } from '@/components/ui/badge';
import { ChipButton } from '@/components/ui/chip';
import MarkdownRenderer from '@/components/ui/markdown-renderer';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { keyByContent } from '@/components/ui/stable-keys';
import { useTrustedMediaUrl } from '@/hooks/use-media-upload';
import type { StaffMember } from '@/hooks/data/staff';
import type { TicketAttachment } from '@/hooks/data/tickets';
import { openExternalUrl } from '@/lib/utils';
import { formatDate } from '@/utils/date-utils';
import { ActionIcon } from './ActionIcon';
import { TicketFileIcon } from './TicketFileIcon';
import { TicketMessageAvatar } from './TicketMessageAvatar';
import { actionBadgeOf } from './ticket-action-badge';
import { lastPathSegment, previewableTypeOfUrl, truncateFileName } from './file-names';
import { isStaffMessage, type TicketMessageView } from './ticket-view';

const DEFAULT_FILE_TYPE = 'application/octet-stream';

function attachmentContentKey(attachment: TicketAttachment): string {
  return `${attachment.url ?? ''}|${attachment.fileName ?? ''}`;
}

function MessageAttachment({ attachment }: { attachment: TicketAttachment }) {
  const { t } = useTranslation();
  const isTrustedMediaUrl = useTrustedMediaUrl();
  const url = attachment.url ?? '';
  const fileName = attachment.fileName ?? '';
  const fileType = attachment.fileType || (url ? previewableTypeOfUrl(url) : undefined) || DEFAULT_FILE_TYPE;
  const canPreview = isTrustedMediaUrl(url);

  if (canPreview && fileType.startsWith('image/')) {
    return (
      <div className="relative group max-w-sm mt-2">
        <a href={url} target="_blank" rel="noopener noreferrer">
          <img
            src={url}
            alt={fileName}
            className="max-w-full max-h-[300px] rounded-md border shadow-xs object-contain bg-background"
            loading="lazy"
          />
        </a>
      </div>
    );
  }

  if (canPreview && fileType.startsWith('video/')) {
    return (
      <div className="max-w-sm mt-1">
        <video
          src={url}
          controls
          className="max-w-full max-h-[300px] rounded-md border shadow-xs bg-black"
          preload="metadata"
        />
      </div>
    );
  }

  return (
    <ChipButton className="w-fit" onClick={() => openExternalUrl(url)}>
      <TicketFileIcon fileType={fileType} />
      <span>{truncateFileName(fileName || lastPathSegment(url) || t('ticket.defaultFileName'))}</span>
    </ChipButton>
  );
}

export function TicketAttachmentList({ attachments }: { attachments: readonly TicketAttachment[] }) {
  if (attachments.length === 0) return null;
  return (
    <div className="flex flex-col gap-2 mt-2">
      {keyByContent(attachments, attachmentContentKey).map(({ key, item }) => (
        <MessageAttachment key={key} attachment={item} />
      ))}
    </div>
  );
}

function ActionBadge({ action, content }: { action: string; content: string }) {
  const { t } = useTranslation();
  const badge = actionBadgeOf(action, content, t);
  return (
    <Badge variant={badge.variant} className="gap-1">
      <ActionIcon kind={badge.icon} className="h-3 w-3" />
      {badge.text}
    </Badge>
  );
}

function UnverifiedBadge() {
  const { t } = useTranslation();
  const explanation = t('playerTicket.unverifiedExplanation');
  return (
    <Tooltip delayDuration={300}>
      <TooltipTrigger asChild>
        <Badge variant="destructive" className="cursor-help" tabIndex={0}>
          {t('playerTicket.unverifiedBadge')}
        </Badge>
      </TooltipTrigger>
      <TooltipContent className="max-w-xs p-3 z-50" side="top">
        <p className="text-sm">{explanation}</p>
      </TooltipContent>
    </Tooltip>
  );
}

interface TicketMessageItemProps {
  message: TicketMessageView;
  isFirst: boolean;
  originalCreatorId: string | undefined;
  creatorUuid: string;
  staff: readonly StaffMember[] | undefined;
}

function senderLabelKey(message: TicketMessageView): string {
  if (isStaffMessage(message)) return 'playerTicket.staffBadge';
  return message.senderType === 'system' ? 'playerTicket.systemBadge' : 'ticket.playerLabel';
}

function isUnverifiedReply(message: TicketMessageView, isFirst: boolean, originalCreatorId: string | undefined): boolean {
  return message.senderType === 'user'
    && !isFirst
    && Boolean(originalCreatorId)
    && Boolean(message.creatorIdentifier)
    && message.creatorIdentifier !== originalCreatorId;
}

export function TicketMessageItem({ message, isFirst, originalCreatorId, creatorUuid, staff }: TicketMessageItemProps) {
  const { t } = useTranslation();
  const isNamed = message.sender !== '' && message.sender !== 'user';
  const senderName = isNamed ? message.sender : t(senderLabelKey(message));

  return (
    <div className="p-4">
      <div className="flex items-start gap-3">
        <TicketMessageAvatar message={message} creatorUuid={creatorUuid} staff={staff} />
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 mb-1">
            <span className="font-medium text-sm">{senderName}</span>
            {message.created > 0 && (
              <span className="text-xs text-muted-foreground">{formatDate(new Date(message.created).toISOString())}</span>
            )}
            {isNamed && isStaffMessage(message) && (
              <Badge variant="secondary">{t('playerTicket.staffBadge')}</Badge>
            )}
            {isNamed && message.senderType === 'system' && (
              <Badge variant="outline">{t('playerTicket.systemBadge')}</Badge>
            )}
            {message.closedAs !== undefined && <ActionBadge action={message.closedAs} content={message.content} />}
            {isUnverifiedReply(message, isFirst, originalCreatorId) && <UnverifiedBadge />}
          </div>

          <div className="text-sm">
            <MarkdownRenderer content={message.content} />
          </div>

          <TicketAttachmentList attachments={message.attachments} />
        </div>
      </div>
    </div>
  );
}
