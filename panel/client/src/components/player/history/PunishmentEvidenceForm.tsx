import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import MediaUpload from '@/components/MediaUpload';
import { Input } from '@/components/ui/input';
import { Chip, ChipRemoveButton } from '@/components/ui/chip';
import { useAddPunishmentEvidence, type PunishmentEvidenceSubmission } from '@/hooks/data/punishments';
import { toast } from '@/hooks/use-toast';
import { describeError } from '@/lib/http-error';
import { textEvidenceType } from '@/lib/punishment-request';
import { ActionFormPanel } from './ActionFormPanel';

interface UploadedEvidenceFile {
  readonly url: string;
  readonly fileName: string;
  readonly fileType: string;
  readonly fileSize: number;
}

interface PunishmentEvidenceFormProps {
  readonly playerId: string;
  readonly punishmentId: string;
  readonly onClose: () => void;
}

function evidenceSubmission(text: string, uploadedFile: UploadedEvidenceFile | null): PunishmentEvidenceSubmission {
  if (uploadedFile !== null) {
    return { type: 'file', text: uploadedFile.fileName, ...uploadedFile };
  }
  return textEvidenceType(text) === 'url' ? { type: 'url', text, url: text } : { type: 'text', text };
}

export function PunishmentEvidenceForm({ playerId, punishmentId, onClose }: PunishmentEvidenceFormProps) {
  const { t } = useTranslation();
  const [text, setText] = useState('');
  const [uploadedFile, setUploadedFile] = useState<UploadedEvidenceFile | null>(null);
  const addEvidence = useAddPunishmentEvidence();
  const evidenceText = text.trim();

  const submit = () => {
    addEvidence.mutate({ uuid: playerId, punishmentId, evidence: evidenceSubmission(evidenceText, uploadedFile) }, {
      onSuccess: () => {
        toast({ title: t('player.evidenceAdded') });
        onClose();
      },
      onError: (error) => {
        toast({ title: t('player.addEvidenceFailed'), description: describeError(error, t('common.unknownError')), variant: 'destructive' });
      },
    });
  };

  return (
    <ActionFormPanel
      title={t('player.addEvidenceToTitle')}
      submitLabel={t('player.addEvidence')}
      canSubmit={(evidenceText !== '' || uploadedFile !== null) && !addEvidence.isPending}
      onSubmit={submit}
      onCancel={onClose}
    >
      <div className="flex items-center gap-2">
        {uploadedFile === null ? (
          <Input
            className="flex-1"
            aria-label={t('player.addEvidenceToTitle')}
            placeholder={t('punishment.evidencePlaceholder')}
            value={text}
            onChange={(event) => { setText(event.target.value); }}
          />
        ) : (
          <Chip className="min-w-0 flex-1">
            <span className="truncate">{uploadedFile.fileName}</span>
            <ChipRemoveButton
              label={t('upload.removeNamed', { name: uploadedFile.fileName })}
              onClick={() => { setUploadedFile(null); }}
            />
          </Chip>
        )}
        <MediaUpload
          uploadType="evidence"
          onUploadComplete={(result, file) => {
            setUploadedFile({
              url: result.url,
              fileName: file?.name ?? t('evidence.unknownFile'),
              fileType: file?.type || 'application/octet-stream',
              fileSize: file?.size ?? 0,
            });
          }}
          metadata={{ playerId, category: 'punishment' }}
          variant="button-only"
          maxFiles={1}
        />
      </div>
    </ActionFormPanel>
  );
}
