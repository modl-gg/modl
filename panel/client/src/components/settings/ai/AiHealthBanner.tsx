import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import { Notice } from '@/components/ui/notice';
import type { AiHealthState, AiModelWarningId, AiSettingsState } from '@/hooks/data/ai';
import { formatDateOnly, formatDateWithTime } from '@/utils/date-utils';
import { FAILURE_MESSAGES, READINESS_MESSAGES } from './ai-messages';

const WARNING_MESSAGES: Record<AiModelWarningId, string> = {
  deprecated: 'settings.ai.health.deprecated',
  retiring: 'settings.ai.health.retiring',
  notListed: 'settings.ai.health.notListed',
};

function failedSinceLastSuccess({ lastFailureAt, lastSuccessAt }: AiHealthState): boolean {
  if (lastFailureAt === null) return false;
  return lastSuccessAt === null || Date.parse(lastFailureAt) > Date.parse(lastSuccessAt);
}

function healthNotices(t: TFunction, { health }: AiSettingsState): string[] {
  const notices: string[] = [];
  if (health.pausedUntil !== null) notices.push(t('settings.ai.health.paused', { time: formatDateWithTime(health.pausedUntil) }));
  if (health.lastFailure !== null && failedSinceLastSuccess(health)) {
    notices.push(t('settings.ai.health.lastFailure', { reason: t(FAILURE_MESSAGES[health.lastFailure]) }));
  }
  if (health.modelWarning !== null) {
    notices.push(t(WARNING_MESSAGES[health.modelWarning], {
      date: health.modelRetiresAt === null ? '' : formatDateOnly(health.modelRetiresAt),
    }));
  }
  return notices;
}

export function AiHealthBanner({ state }: { state: AiSettingsState }) {
  const { t } = useTranslation();
  const { managed, readiness } = state;
  const isManaged = managed.provider || managed.apiKey || managed.model || managed.baseUrl;
  if (state.activeProvider === null && !isManaged) return null;

  const readinessWarning = readiness === 'ready' ? null : t(READINESS_MESSAGES[readiness]);
  const healthWarnings = readiness === 'ready' ? healthNotices(t, state) : [];
  if (readinessWarning === null && !isManaged && healthWarnings.length === 0) return null;

  return (
    <div className="space-y-2">
      {readinessWarning !== null && <Notice variant="warning">{readinessWarning}</Notice>}
      {isManaged && <Notice variant="info">{t('settings.ai.managed')}</Notice>}
      {healthWarnings.map((warning) => <Notice key={warning} variant="warning">{warning}</Notice>)}
    </div>
  );
}
