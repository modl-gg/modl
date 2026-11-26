import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { LoadingSpinner } from '@/components/ui/loading-spinner';
import { Notice } from '@/components/ui/notice';
import {
  issueCodeName,
  useAbandonImport,
  useFinishImport,
  useHostedImportReport,
  useResumeImport,
  type ImportState,
} from '@/hooks/data/hosted-import';
import { ImportCounts, ImportIssues } from './ImportIssues';
import { ImportProgress } from './ImportTransfer';
import { reasonKey } from './import-labels';

const FOLLOW_UP_KEYS: Readonly<Record<string, string>> = {
  apiKey: 'pages.setup.import.followUp.apiKey',
  passkeys: 'pages.setup.import.followUp.passkeys',
  invitations: 'pages.setup.import.followUp.invitations',
  address: 'pages.setup.import.followUp.address',
  mail: 'pages.setup.import.followUp.mail',
  webhooks: 'pages.setup.import.followUp.webhooks',
  ai: 'pages.setup.import.followUp.ai',
  minecraft: 'pages.setup.import.followUp.minecraft',
  oldLinks: 'pages.setup.import.followUp.oldLinks',
};

const DOWNLOAD_URL_LIFETIME_MS = 60_000;

type PausedExit = 'abandon' | 'finish';

function knownFollowUps(codes: readonly string[]): { code: string; key: string }[] {
  return codes.flatMap((code) => {
    const key = FOLLOW_UP_KEYS[code];
    return key === undefined ? [] : [{ code, key }];
  });
}

export function ImportPaused({ state }: { state: ImportState }) {
  const { t } = useTranslation();
  const resume = useResumeImport();
  const abandon = useAbandonImport();
  const finish = useFinishImport();
  const [confirming, setConfirming] = useState<PausedExit | null>(null);
  return (
    <div className="space-y-3">
      <ImportProgress state={state} />
      <Notice variant="warning" announce>{t('pages.setup.import.paused.title', { reason: t(reasonKey(state.reasonCode)) })}</Notice>
      <div className="flex flex-wrap gap-2">
        {state.canResume && (
          <Button type="button" disabled={resume.isPending} onClick={() => resume.mutate(state.importId)}>
            {t('pages.setup.import.paused.resume')}
          </Button>
        )}
        {state.canAbandon && confirming === null && (
          <Button type="button" variant="outline" onClick={() => setConfirming('abandon')}>{t('pages.setup.import.paused.abandon')}</Button>
        )}
        {state.canFinish && confirming === null && (
          <Button type="button" variant="outline" onClick={() => setConfirming('finish')}>{t('pages.setup.import.paused.finish')}</Button>
        )}
      </div>
      {confirming === 'abandon' && (
        <div className="space-y-2">
          <Notice variant="error">{t('pages.setup.import.paused.abandonConsequence')}</Notice>
          <Button type="button" variant="destructive" disabled={abandon.isPending} onClick={() => abandon.mutate(state.importId)}>
            {t('pages.setup.import.paused.abandonConfirm')}
          </Button>
        </div>
      )}
      {confirming === 'finish' && (
        <div className="space-y-2">
          <Notice variant="warning">{t('pages.setup.import.paused.finishConsequence')}</Notice>
          <Button type="button" disabled={finish.isPending} onClick={() => finish.mutate(state.importId)}>
            {t('pages.setup.import.paused.finishConfirm')}
          </Button>
        </div>
      )}
    </div>
  );
}

export function ImportReport() {
  const { t } = useTranslation();
  const report = useHostedImportReport(true);
  if (report.isError) return <Notice variant="error">{t('pages.setup.import.errors.unknown')}</Notice>;
  if (!report.data) return <LoadingSpinner className="h-5 w-5" />;
  const view = report.data;
  const followUps = knownFollowUps(view.followUps);

  const download = () => {
    const record = { ...view, issues: view.issues.map((issue) => ({ ...issue, code: issueCodeName(issue.code) })) };
    const blob = new Blob([JSON.stringify(record, null, 2)], { type: 'application/json' });
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = `modl-import-report-${view.exportId}.json`;
    link.click();
    window.setTimeout(() => { URL.revokeObjectURL(link.href); }, DOWNLOAD_URL_LIFETIME_MS);
  };

  return (
    <div className="space-y-4">
      <Notice variant={view.status === 'completed' ? 'success' : 'warning'}>
        {t(view.status === 'completed' ? 'pages.setup.import.report.title' : 'pages.setup.import.report.abandonedTitle')}
      </Notice>
      <ImportCounts counts={view.counts} />
      <ImportIssues issues={view.issues} />
      {followUps.length > 0 && (
        <div className="space-y-1">
          <h3 className="text-sm font-medium">{t('pages.setup.import.report.actions')}</h3>
          <ul className="list-disc space-y-1 pl-5 text-sm">
            {followUps.map(({ code, key }) => <li key={code}>{t(key)}</li>)}
          </ul>
        </div>
      )}
      <p className="text-xs text-muted-foreground">{t('pages.setup.import.report.cannotPreserve')}</p>
      <Button type="button" variant="outline" onClick={download}>{t('pages.setup.import.report.download')}</Button>
    </div>
  );
}
