import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAddPunishmentNote } from '@/hooks/data/punishments';
import { Textarea } from '@/components/ui/textarea';
import { toast } from '@/hooks/use-toast';
import { describeError } from '@/lib/http-error';
import { ActionFormPanel } from './ActionFormPanel';

interface PunishmentNoteFormProps {
  readonly playerId: string;
  readonly punishmentId: string;
  readonly onClose: () => void;
}

export function PunishmentNoteForm({ playerId, punishmentId, onClose }: PunishmentNoteFormProps) {
  const { t } = useTranslation();
  const [text, setText] = useState('');
  const addNote = useAddPunishmentNote();
  const noteText = text.trim();

  const submit = () => {
    addNote.mutate({ uuid: playerId, punishmentId, noteText }, {
      onSuccess: () => {
        toast({ title: t('player.noteAdded') });
        onClose();
      },
      onError: (error) => {
        toast({ title: t('player.addNoteFailed'), description: describeError(error, t('common.unknownError')), variant: 'destructive' });
      },
    });
  };

  return (
    <ActionFormPanel
      title={t('player.addNoteToTitle')}
      submitLabel={t('player.addNote')}
      canSubmit={noteText !== '' && !addNote.isPending}
      onSubmit={submit}
      onCancel={onClose}
    >
      <Textarea
        className="h-16 resize-none"
        aria-label={t('player.addNoteToTitle')}
        placeholder={t('player.notePlaceholder')}
        value={text}
        onChange={(event) => setText(event.target.value)}
      />
    </ActionFormPanel>
  );
}
