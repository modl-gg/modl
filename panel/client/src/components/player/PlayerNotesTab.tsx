import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { PlayerNoteKind } from '@proto/modl/v1/player_pb.ts';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { ProfileEmpty } from './ProfileEmpty';
import { keyByContent } from '@/components/ui/stable-keys';
import { useAddPlayerNote } from '@/hooks/data/players';
import { usePermissions } from '@/hooks/use-permissions';
import { toast } from '@/hooks/use-toast';
import { formatDateWithTime } from '@/utils/date-utils';
import { describeError } from '@/lib/http-error';
import type { PlayerNoteRecord } from './player-profile';

function AddPlayerNoteForm({ playerId, onClose }: { readonly playerId: string; readonly onClose: () => void }) {
  const { t } = useTranslation();
  const [text, setText] = useState('');
  const addNote = useAddPlayerNote();
  const noteText = text.trim();

  const submit = () => {
    addNote.mutate({ uuid: playerId, text: noteText }, {
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
    <div className="space-y-2">
      <Textarea
        className="h-20"
        aria-label={t('player.addNote')}
        placeholder={t('player.notePlaceholder')}
        value={text}
        onChange={(event) => setText(event.target.value)}
      />
      <div className="flex justify-end gap-2">
        <Button size="sm" variant="outline" onClick={onClose}>
          {t('common.cancel')}
        </Button>
        <Button size="sm" disabled={noteText === '' || addNote.isPending} onClick={submit}>
          {t('player.saveNote')}
        </Button>
      </div>
    </div>
  );
}

export function PlayerNotesTab({ playerId, notes }: { readonly playerId: string; readonly notes: readonly PlayerNoteRecord[] }) {
  const { t } = useTranslation();
  const [isAdding, setIsAdding] = useState(false);
  const { canAddPlayerNotes } = usePermissions();

  return (
    <>
      {notes.length > 0 ? (
        <ul className="divide-y">
          {keyByContent(notes, (note) => note.id || `${note.date}:${note.text}`).map(({ key, item: note }) => (
            <li key={key} className="py-2 text-sm">
              <div>
                <p>
                  {note.kind === PlayerNoteKind.WARNING && (
                    <Badge variant="destructive" className="mr-2">{t('player.noteWarningBadge')}</Badge>
                  )}
                  {note.text}
                </p>
                <p className="text-xs text-muted-foreground">
                  {t('evidence.issuedBy', {
                    issuer: note.issuerName || t('common.unknown'),
                    date: note.date === '' ? t('common.unknown') : formatDateWithTime(note.date),
                  })}
                </p>
              </div>
            </li>
          ))}
        </ul>
      ) : (
        !isAdding && <ProfileEmpty>{t('player.noStaffNotes')}</ProfileEmpty>
      )}
      {isAdding && canAddPlayerNotes && <AddPlayerNoteForm playerId={playerId} onClose={() => setIsAdding(false)} />}
      {!isAdding && canAddPlayerNotes && (
        <Button size="sm" variant="outline" onClick={() => setIsAdding(true)}>
          {t('player.addNote')}
        </Button>
      )}
    </>
  );
}
