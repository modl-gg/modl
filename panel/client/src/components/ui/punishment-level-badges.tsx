import { useTranslation } from 'react-i18next';
import { StatusBadge, type StatusIntent } from '@/components/ui/status-badge';

type LevelTone = 'low' | 'medium' | 'high';

const TONE_INTENTS = {
  low: 'success',
  medium: 'warning',
  high: 'destructive',
} as const satisfies Record<LevelTone, StatusIntent>;

const SEVERITY_LABEL_KEYS: ReadonlyMap<string, string> = new Map([
  ['lenient', 'punishment.severityLenient'],
  ['low', 'punishment.severityLenient'],
  ['regular', 'punishment.severityRegular'],
  ['medium', 'punishment.severityRegular'],
  ['aggravated', 'punishment.severityAggravated'],
  ['high', 'punishment.severityAggravated'],
]);

const OFFENSE_STATUS_LABEL_KEYS: ReadonlyMap<string, string> = new Map([
  ['low', 'punishment.offenseFirst'],
  ['first', 'punishment.offenseFirst'],
  ['medium', 'punishment.offenseMedium'],
  ['habitual', 'punishment.offenseHabitual'],
]);

const LOW_SEVERITIES: ReadonlySet<string> = new Set(['low', 'lenient']);
const MEDIUM_SEVERITIES: ReadonlySet<string> = new Set(['regular', 'medium']);
const LOW_OFFENSE_STATUSES: ReadonlySet<string> = new Set(['low', 'first']);

function severityTone(severity: string): LevelTone {
  if (LOW_SEVERITIES.has(severity)) return 'low';
  return MEDIUM_SEVERITIES.has(severity) ? 'medium' : 'high';
}

function offenseStatusTone(status: string): LevelTone {
  if (LOW_OFFENSE_STATUSES.has(status)) return 'low';
  return status === 'medium' ? 'medium' : 'high';
}

function LevelBadge({ value, tone, labelKey }: { value: string; tone: LevelTone; labelKey: string | undefined }) {
  const { t } = useTranslation();
  return <StatusBadge intent={TONE_INTENTS[tone]}>{labelKey === undefined ? value : t(labelKey)}</StatusBadge>;
}

export function SeverityBadge({ severity }: { severity: string }) {
  const normalized = severity.toLowerCase();
  return <LevelBadge value={severity} tone={severityTone(normalized)} labelKey={SEVERITY_LABEL_KEYS.get(normalized)} />;
}

export function OffenseStatusBadge({ status }: { status: string }) {
  const normalized = status.toLowerCase();
  return <LevelBadge value={status} tone={offenseStatusTone(normalized)} labelKey={OFFENSE_STATUS_LABEL_KEYS.get(normalized)} />;
}

export function PunishmentOptionBadges({ altBlocking, statWiping }: { altBlocking: boolean; statWiping: boolean }) {
  const { t } = useTranslation();
  return (
    <>
      {altBlocking && <StatusBadge intent="warning">{t('punishment.altBlocking')}</StatusBadge>}
      {statWiping && <StatusBadge intent="destructive">{t('punishment.statWiping')}</StatusBadge>}
    </>
  );
}
