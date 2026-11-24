import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { LoadingSpinner } from '@/components/ui/loading-spinner';
import { Notice } from '@/components/ui/notice';
import { useHostedImport, type ImportState } from '@/hooks/data/hosted-import';
import { ImportOutcomeView } from './ImportOutcomeView';
import { ImportSourceChoice } from './ImportSourceChoice';
import { ImportTransfer } from './ImportTransfer';
import { blockerKey, reasonKey } from './import-labels';
import { useArchiveUploader, type ArchiveUploader } from './use-archive-uploader';
import { useImportDiscard } from './use-import-discard';
import { WIKI_MIGRATING_URL } from '@/lib/project-links';
import { StepChoice, useStepChoice, type StepOption } from '../StepChoice';
import type { SetupStepProps } from '../step-props';

function ImportStart({ state, uploader }: { state: ImportState; uploader: ArchiveUploader }) {
  const { t } = useTranslation();
  const discard = useImportDiscard(state.importId);
  return (
    <div className="space-y-4">
      {state.status === 'failed' && (
        <div className="space-y-2">
          <Notice variant="error" announce>{t('pages.setup.import.failed', { reason: t(reasonKey(state.reasonCode)) })}</Notice>
          <Button type="button" variant="outline" size="sm" disabled={discard.pending} onClick={discard.discardNow}>
            {t('pages.setup.import.source.differentFile')}
          </Button>
        </div>
      )}
      {state.eligible ? (
        <ImportSourceChoice onUploadStarted={uploader.start} />
      ) : (
        <Notice variant="info">
          <span className="block">{t('pages.setup.import.blocked.help')}</span>
          {state.blockers.map((code) => <span key={code} className="block">{t(blockerKey(code))}</span>)}
        </Notice>
      )}
    </div>
  );
}

type ImportChoice = 'import' | 'fresh';

const OPTIONS: readonly StepOption<ImportChoice>[] = [
  { value: 'import', labelKey: 'pages.setup.import.optionImport' },
  { value: 'fresh', labelKey: 'pages.setup.import.startFresh' },
];

function importFinished(state: ImportState): boolean {
  return state.status === 'completed' || state.status === 'abandoned';
}

function ImportFlow({ state, uploader }: { state: ImportState; uploader: ArchiveUploader }) {
  switch (state.status) {
    case 'none':
    case 'failed':
      return <ImportStart state={state} uploader={uploader} />;
    case 'receiving':
    case 'downloading':
    case 'verifying':
      return <ImportTransfer state={state} uploader={uploader} />;
    case 'review':
    case 'applying':
    case 'paused':
    case 'completed':
    case 'abandoned':
      return <ImportOutcomeView state={state} />;
  }
}

const PLUGIN_IMPORT_COMMAND = '/modl import <source>';

function OtherPluginsLine() {
  const { t } = useTranslation();
  return (
    <p className="text-sm text-muted-foreground">
      {t('pages.setup.import.otherPlugins', { command: PLUGIN_IMPORT_COMMAND })}{' '}
      <a href={WIKI_MIGRATING_URL} target="_blank" rel="noopener noreferrer" className="underline hover:text-foreground">
        {t('pages.setup.import.otherPluginsLink')}
      </a>
    </p>
  );
}

function ImportDecision({ state, done }: { state: ImportState; done: boolean }) {
  const uploader = useArchiveUploader();
  const started = state.status !== 'none' && state.status !== 'failed';
  const choice = useStepChoice<ImportChoice>({
    step: 'import',
    options: OPTIONS,
    saved: started ? 'import' : null,
    done,
    none: 'fresh',
    configured: importFinished(state),
  });

  return (
    <div className="space-y-6">
      <StepChoice options={OPTIONS} {...choice} />
      {choice.value === 'import' && <ImportFlow state={state} uploader={uploader} />}
      <OtherPluginsLine />
    </div>
  );
}

export function ImportStep({ done }: SetupStepProps) {
  const { t } = useTranslation();
  const hostedImport = useHostedImport();
  const state = hostedImport.data;
  if (hostedImport.isError) return <Notice variant="error" announce>{t('pages.setup.stepLoadFailed')}</Notice>;
  if (!state) return <LoadingSpinner className="h-5 w-5" />;
  return <ImportDecision state={state} done={done} />;
}
