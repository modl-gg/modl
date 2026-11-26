import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import type { ImportCount, ImportIssue } from '@/hooks/data/hosted-import';
import { formatList } from '@/lib/list-format';
import { cn } from '@/lib/utils';
import { ISSUE_LABELS, subjectKey } from './import-labels';
import { settingKey } from './import-setting-labels';

export function ImportTotals({ counts }: { counts: readonly ImportCount[] }) {
  const { t, i18n } = useTranslation();
  if (counts.length === 0) return null;
  return (
    <ul className="grid grid-cols-1 gap-1 sm:grid-cols-2">
      {counts.map((count) => (
        <li key={count.subject} className="flex justify-between rounded-md border px-3 py-1.5 text-sm">
          <span>{t(subjectKey(count.subject))}</span>
          <span className="tabular-nums text-muted-foreground">{count.total.toLocaleString(i18n.language)}</span>
        </li>
      ))}
    </ul>
  );
}

export function ImportCounts({ counts }: { counts: readonly ImportCount[] }) {
  const { t } = useTranslation();
  if (counts.length === 0) return null;
  return (
    <ul className="grid grid-cols-1 gap-1 sm:grid-cols-2">
      {counts.map((count) => (
        <li key={count.subject} className="flex justify-between rounded-md border px-3 py-1.5 text-sm">
          <span>{t(subjectKey(count.subject))}</span>
          <span className="tabular-nums text-muted-foreground">
            {count.skipped > 0
              ? t('pages.setup.import.report.countLineSkipped', { imported: count.imported, total: count.total, count: count.skipped })
              : t('pages.setup.import.report.countLine', { imported: count.imported, total: count.total })}
          </span>
        </li>
      ))}
    </ul>
  );
}

function detailItems(issue: ImportIssue, t: TFunction): string[] {
  switch (ISSUE_LABELS[issue.code].details) {
    case 'none':
      return [];
    case 'list':
      return issue.samples;
    case 'settings':
      return issue.samples.flatMap((setting) => {
        const key = settingKey(setting);
        return key === undefined ? [] : [t(key)];
      });
  }
}

function issueDetails(issue: ImportIssue, t: TFunction, language: string): string | null {
  const items = detailItems(issue, t);
  return items.length === 0 ? null : formatList(items, language);
}

export function ImportIssues({ issues }: { issues: readonly ImportIssue[] }) {
  const { t, i18n } = useTranslation();
  if (issues.length === 0) return null;
  return (
    <ul className="space-y-1">
      {issues.map((issue) => {
        const details = issueDetails(issue, t, i18n.language);
        return (
          <li
            key={issue.code}
            className={cn('rounded-md border px-3 py-1.5 text-sm', issue.severity === 'action' && 'border-warning/20 bg-warning/10 text-warning')}
          >
            <span className="block">{t(ISSUE_LABELS[issue.code].messageKey, { count: issue.count })}</span>
            {details !== null && <span className="block break-words text-xs text-muted-foreground">{details}</span>}
          </li>
        );
      })}
    </ul>
  );
}
