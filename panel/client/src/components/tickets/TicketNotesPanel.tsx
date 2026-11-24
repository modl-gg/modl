import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import MarkdownHelp from '@/components/ui/markdown-help';
import MarkdownRenderer from '@/components/ui/markdown-renderer';
import { keyByContent } from '@/components/ui/stable-keys';
import MediaUpload from '@/components/MediaUpload';
import { useAuth } from '@/hooks/use-auth';
import { useToast } from '@/hooks/use-toast';
import { useUpdateTicket, type PanelTicketNote } from '@/hooks/data/tickets';
import { formatDate } from '@/utils/date-utils';
import { TicketFileIcon } from './TicketFileIcon';
import { TicketAttachmentList } from './TicketMessageItem';

const DEFAULT_FILE_TYPE = 'application/octet-stream';
const NOTE_TEXT_MAX_LENGTH = 4000;

interface NoteAttachment {
  key: string;
  url: string;
  fileName: string;
  fileType: string;
}

function noteTextOf(text: string, attachments: readonly NoteAttachment[]): string {
  return text.trim() || attachments.map((attachment) => attachment.fileName).join(', ').slice(0, NOTE_TEXT_MAX_LENGTH);
}

function NoteItem({ note }: { note: PanelTicketNote }) {
  const { t } = useTranslation();
  return (
    <div className="bg-muted/20 p-4 rounded-lg">
      <div className="flex justify-between items-start mb-3">
        <span className="font-medium text-sm text-foreground">{note.issuerName || t('playerTicket.staffBadge')}</span>
        {note.date > 0 && (
          <span className="text-xs text-muted-foreground shrink-0">{formatDate(new Date(note.date).toISOString())}</span>
        )}
      </div>
      <div className="note-content">
        <MarkdownRenderer content={note.text} className="text-sm leading-relaxed" />
      </div>
      <TicketAttachmentList attachments={note.attachments} />
    </div>
  );
}

interface NoteComposerProps {
  ticketId: string;
  onClose: () => void;
}

function NoteComposer({ ticketId, onClose }: NoteComposerProps) {
  const { t } = useTranslation();
  const { toast } = useToast();
  const { user } = useAuth();
  const updateTicket = useUpdateTicket();
  const [text, setText] = useState('');
  const [attachments, setAttachments] = useState<NoteAttachment[]>([]);
  const body = noteTextOf(text, attachments);
  const canAdd = body !== '' && body.length <= NOTE_TEXT_MAX_LENGTH && !updateTicket.isPending;

  const addNote = () => {
    if (!canAdd) return;
    updateTicket.mutate(
      {
        id: ticketId,
        data: {
          newNote: {
            text: body,
            attachments: attachments.map(({ url, fileName, fileType }) => ({ url, fileName, fileType })),
          },
        },
        author: user?.username ?? '',
      },
      {
        onSuccess: onClose,
        onError: () => {
          toast({ title: t('toast.error'), description: t('ticket.notes.addFailed'), variant: 'destructive' });
        },
      },
    );
  };

  return (
    <div className="border rounded-md p-3">
      <div className="mb-2 flex justify-end">
        <MarkdownHelp />
      </div>
      <textarea
        className="min-h-[120px] w-full resize-none rounded-lg border border-input bg-background p-3 text-sm shadow-xs focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-ring mb-3"
        placeholder={t('ticket.notes.placeholder')}
        maxLength={NOTE_TEXT_MAX_LENGTH}
        value={text}
        onChange={(event) => {
          setText(event.target.value);
        }}
      />

      {attachments.length > 0 && (
        <div className="mb-3 p-2 bg-muted/30 rounded-md">
          <p className="text-xs text-muted-foreground mb-2">{t('upload.attachments')}:</p>
          <div className="flex flex-wrap gap-2">
            {attachments.map((attachment) => (
              <div key={attachment.key} className="flex items-center gap-1.5 px-2 py-1 bg-background border rounded text-xs">
                <TicketFileIcon fileType={attachment.fileType} />
                <span className="truncate max-w-[120px]">{attachment.fileName}</span>
                <button
                  type="button"
                  onClick={() => {
                    setAttachments((previous) => previous.filter((candidate) => candidate.key !== attachment.key));
                  }}
                  className="ml-1 text-muted-foreground hover:text-destructive-text"
                  aria-label={t('upload.removeAttachment')}
                >
                  <X className="h-3 w-3" />
                </button>
              </div>
            ))}
          </div>
        </div>
      )}

      <div className="flex justify-between items-center gap-2">
        <MediaUpload
          uploadType="ticket"
          onUploadComplete={(result, file) => {
            setAttachments((previous) => [...previous, {
              key: result.key || result.url,
              url: result.url,
              fileName: file?.name ?? t('evidence.unknownFile'),
              fileType: file?.type || DEFAULT_FILE_TYPE,
            }]);
          }}
          metadata={{ ticketId, category: 'note' }}
          variant="button-only"
          maxFiles={5}
        />
        <div className="flex gap-2">
          <Button variant="outline" size="sm" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button size="sm" onClick={addNote} disabled={!canAdd}>
            {t('player.addNote')}
          </Button>
        </div>
      </div>
    </div>
  );
}

function noteContentKey(note: PanelTicketNote): string {
  return `${String(note.date)}|${note.issuerName}|${note.text}`;
}

interface TicketNotesPanelProps {
  ticketId: string;
  notes: readonly PanelTicketNote[];
  canView: boolean;
  canAdd: boolean;
}

export function TicketNotesPanel({ ticketId, notes, canView, canAdd }: TicketNotesPanelProps) {
  const { t } = useTranslation();
  const [isAdding, setIsAdding] = useState(false);

  return (
    <div className="space-y-4">
      {canView && (
        <div className="space-y-4 mb-5 max-h-[480px] overflow-y-auto p-2">
          {keyByContent(notes, noteContentKey).map(({ key, item }) => (
            <NoteItem key={key} note={item} />
          ))}

          {notes.length === 0 && (
            <p className="py-8 text-center text-sm text-muted-foreground">{t('ticket.notes.empty')}</p>
          )}
        </div>
      )}

      {canAdd && (isAdding ? (
        <NoteComposer
          ticketId={ticketId}
          onClose={() => {
            setIsAdding(false);
          }}
        />
      ) : (
        <Button
          variant="outline"
          onClick={() => {
            setIsAdding(true);
          }}
        >
          {t('ticket.notes.addStaffNote')}
        </Button>
      ))}
    </div>
  );
}
