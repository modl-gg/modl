import { useTranslation } from 'react-i18next';
import type { ImportCount, ImportIssue } from '@/hooks/data/hosted-import';
import { SEVERITY_KEYS, issueKey, subjectKey } from './import-labels';

export function ImportCounts({ counts }: { counts: readonly ImportCount[] }) {
  const { t } = useTranslation();
  if (counts.length === 0) return null;
  return (
    <ul className="grid grid-cols-1 gap-1 sm:grid-cols-2">
      {counts.map((count) => (
        <li key={count.subject} className="flex justify-between rounded-md border px-3 py-1.5 text-sm">
          <span>{t(subjectKey(count.subject))}</span>
          <span className="tabular-nums text-muted-foreground">
            {t('pages.setup.import.report.countLine', { imported: count.imported, total: count.total, skipped: count.skipped })}
          </span>
        </li>
      ))}
    </ul>
  );
}

export function ImportIssues({ issues }: { issues: readonly ImportIssue[] }) {
  const { t } = useTranslation();
  if (issues.length === 0) return null;
  return (
    <ul className="space-y-1">
      {issues.map((issue) => (
        <li key={issue.code} className="rounded-md border px-3 py-1.5 text-sm">
          <span className="font-medium">{t(SEVERITY_KEYS[issue.severity])}</span>{' '}
          <span>{t(issueKey(issue.code), { count: issue.count, code: issue.code })}</span>
          {issue.samples.length > 0 && <span className="block truncate text-xs text-muted-foreground">{issue.samples.join(', ')}</span>}
        </li>
      ))}
    </ul>
  );
}
