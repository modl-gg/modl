import { FileText, Image, Video, File } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Chip, ChipRemoveButton } from '@/components/ui/chip';
import { truncateFileName } from '@/components/tickets/file-names';
import { useToast } from '@/hooks/use-toast';
import MediaUpload from './MediaUpload';
import { useMediaUpload } from '@/hooks/use-media-upload';

const MAX_CHIP_FILE_NAME_LENGTH = 20;

export interface TicketAttachment {
  id: string;
  url: string;
  key: string;
  fileName: string;
  fileType: string;
  fileSize: number;
  uploadedAt: string;
}

interface TicketAttachmentsProps {
  ticketId: string;
  ticketType?: string;
  attachments: readonly TicketAttachment[];
  onAdd: (attachment: TicketAttachment) => void;
  onRemove: (attachment: TicketAttachment) => void;
}

function FileIcon({ type }: { type: string }) {
  if (type.startsWith('image/')) return <Image className="h-4 w-4" />;
  if (type.startsWith('video/')) return <Video className="h-4 w-4" />;
  if (type === 'application/pdf') return <FileText className="h-4 w-4" />;
  return <File className="h-4 w-4" />;
}

export function TicketAttachments({ ticketId, ticketType = 'general', attachments, onAdd, onRemove }: TicketAttachmentsProps) {
  const { t } = useTranslation();
  const { config } = useMediaUpload();
  const { toast } = useToast();

  const handleUploadComplete = (result: { url: string; key: string }, file?: File) => {
    onAdd({
      id: result.key,
      url: result.url,
      key: result.key,
      fileName: file?.name || result.url.split('/').pop() || result.key,
      fileType: file?.type || 'application/octet-stream',
      fileSize: file?.size ?? 0,
      uploadedAt: new Date().toISOString(),
    });
  };

  const remove = (attachment: TicketAttachment) => {
    onRemove(attachment);
    toast({
      title: t('upload.attachmentRemoved'),
      description: t('upload.attachmentRemovedDesc', { name: attachment.fileName }),
    });
  };

  if (!config?.storageConfigured) {
    return (
      <div className="p-4 bg-muted/50 rounded-lg">
        <p className="text-sm text-muted-foreground">
          {t('upload.attachmentsNotAvailable')}
        </p>
      </div>
    );
  }

  return (
    <div className="flex items-center gap-2">
      <MediaUpload
        uploadType="ticket"
        onUploadComplete={handleUploadComplete}
        metadata={{ ticketId, ticketType }}
        variant="button-only"
        maxFiles={10}
      />
      {attachments.length > 0 && (
        <div className="flex items-center gap-2 flex-wrap">
          {attachments.map((attachment) => (
            <Chip key={attachment.id}>
              <FileIcon type={attachment.fileType} />
              <span>{truncateFileName(attachment.fileName, MAX_CHIP_FILE_NAME_LENGTH)}</span>
              <ChipRemoveButton
                label={t('upload.removeNamed', { name: attachment.fileName })}
                onClick={() => { remove(attachment); }}
              />
            </Chip>
          ))}
        </div>
      )}
    </div>
  );
}

export default TicketAttachments;
