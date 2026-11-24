import { type ChangeEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { FileInput } from '@/components/ui/file-input';
import { Label } from '@/components/ui/label';
import { Notice } from '@/components/ui/notice';
import { Progress } from '@/components/ui/progress';
import type { ImportState } from '@/hooks/data/hosted-import';
import { formatFileSize } from '@/utils/file-utils';
import { PHASE_KEYS, STATUS_KEYS } from './import-labels';
import type { ArchiveUploader } from './use-archive-uploader';
import { useImportDiscard } from './use-import-discard';

function percent(done: number, total: number): number {
  return total > 0 ? Math.floor((done / total) * 100) : 0;
}

export function ImportProgress({ state }: { state: ImportState }) {
  const { t } = useTranslation();
  const transferring = state.status === 'receiving' || state.status === 'downloading';
  const done = transferring ? state.receivedBytes : state.phaseDone;
  const total = transferring ? state.totalBytes : state.phaseTotal;
  return (
    <div className="space-y-2" aria-live="polite">
      <p className="text-sm font-medium">{t(STATUS_KEYS[state.status])}</p>
      {state.status === 'applying' && <p className="text-sm text-muted-foreground">{t(PHASE_KEYS[state.phase])}</p>}
      <Progress value={percent(done, total)} />
      <p className="text-xs text-muted-foreground">
        {transferring || state.status === 'verifying'
          ? t('pages.setup.import.progress.bytes', { done: formatFileSize(done), total: formatFileSize(total) })
          : t('pages.setup.import.progress.items', { done, total })}
      </p>
    </div>
  );
}

export function ImportTransfer({ state, uploader }: { state: ImportState; uploader: ArchiveUploader }) {
  const { t } = useTranslation();
  const discard = useImportDiscard(state.importId);
  const resumable = state.status === 'receiving' && !uploader.uploading;
  const sameFile = uploader.file?.size === state.totalBytes ? uploader.file : null;

  const chooseAgain = (event: ChangeEvent<HTMLInputElement>) => {
    const chosen = event.target.files?.item(0);
    if (chosen?.size === state.totalBytes) uploader.start(chosen, state);
  };

  return (
    <div className="space-y-4">
      <ImportProgress state={state} />
      {resumable && sameFile !== null && (
        <Button type="button" variant="outline" onClick={() => uploader.start(sameFile, state)}>{t('pages.setup.import.source.resumeUpload')}</Button>
      )}
      {resumable && sameFile === null && (
        <div className="space-y-1">
          <Label htmlFor="import-archive-again">{t('pages.setup.import.source.differentFile')}</Label>
          <FileInput id="import-archive-again" accept=".zip,application/zip" fileName={null} onChange={chooseAgain} />
        </div>
      )}
      {uploader.failedKey !== null && <Notice variant="error" announce>{t(uploader.failedKey)}</Notice>}
      {state.canDiscard && (
        <Button type="button" variant="outline" disabled={discard.pending} onClick={() => { void discard.confirmDiscard(uploader.cancel); }}>
          {t('pages.setup.import.review.discard')}
        </Button>
      )}
      {discard.dialog}
    </div>
  );
}
