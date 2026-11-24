import { Check, Copy } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { Notice } from '@/components/ui/notice';
import { StatusBadge } from '@/components/ui/status-badge';
import { useCopyToClipboard } from '@/hooks/use-copy-to-clipboard';
import { useStatusReport, type StatusLevelId, type StatusReportEntry } from '@/hooks/data/operations';
import { SettingsLoading, SettingsSection, SettingsSections } from './layout/SettingsSections';
import { SettingsPageAction, SettingsPageActionButton } from './layout/SettingsPageFrame';

const LEVEL_INTENTS = {
  ok: 'success',
  warning: 'warning',
  problem: 'destructive',
} as const satisfies Record<StatusLevelId, 'success' | 'warning' | 'destructive'>;

function groupBySection(entries: readonly StatusReportEntry[]): [string, StatusReportEntry[]][] {
  const sections = new Map<string, StatusReportEntry[]>();
  for (const entry of entries) {
    sections.set(entry.section, [...(sections.get(entry.section) ?? []), entry]);
  }
  return [...sections.entries()];
}

const StatusSettings = () => {
  const { t } = useTranslation();
  const { data, isLoading, isError, refetch } = useStatusReport();
  const { copy, copiedValue } = useCopyToClipboard(t('settings.status.copyFailed'));

  if (isLoading) return <SettingsLoading />;

  if (isError || !data) {
    return (
      <div className="space-y-3">
        <Notice variant="error" announce>{t('settings.status.loadFailed')}</Notice>
        <Button type="button" variant="outline" size="sm" onClick={() => { void refetch(); }}>
          {t('common.retry')}
        </Button>
      </div>
    );
  }

  return (
    <SettingsSections>
      <SettingsPageAction>
        <SettingsPageActionButton
          icon={copiedValue === data.report ? Check : Copy}
          label={copiedValue === data.report ? t('common.copied') : t('settings.status.copyReport')}
          onClick={(event) => { void copy(data.report, event.currentTarget); }}
        />
      </SettingsPageAction>
      {groupBySection(data.entries).map(([section, entries]) => (
        <SettingsSection key={section} title={section}>
          <dl className="divide-y rounded-md border">
            {entries.map((entry) => (
              <div key={entry.key} className="flex flex-wrap items-center justify-between gap-2 px-3 py-2">
                <dt className="text-sm text-muted-foreground">{entry.label}</dt>
                <dd className="flex items-center gap-2 text-sm break-all">
                  {entry.value}
                  {entry.level !== 'ok' && (
                    <StatusBadge intent={LEVEL_INTENTS[entry.level]}>{t(`settings.status.level.${entry.level}`)}</StatusBadge>
                  )}
                </dd>
              </div>
            ))}
          </dl>
        </SettingsSection>
      ))}
    </SettingsSections>
  );
};

export default StatusSettings;
