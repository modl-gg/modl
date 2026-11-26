import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { Notice } from '@/components/ui/notice';
import { usePermissions } from '@/hooks/use-permissions';
import { useDataRetentionSettings, useUpdateDataRetentionSettings } from '@/hooks/data/settings';
import {
  usePatchRetentionLimits,
  useRetentionLimits,
  type OperatorLimitId,
  type OperatorLimitSetting,
  type VersionedLimits,
} from '@/hooks/data/operations';
import { wholeNumberInRange, type WholeNumberRange } from '@/lib/whole-number';
import { DaysField } from './DaysField';
import { ReplayRetentionSection, useReplayRetentionDraft } from './ReplayRetentionSection';
import { SettingsLoading, SettingsSection, SettingsSections } from './layout/SettingsSections';
import { useSettingsDraft } from './save/use-settings-draft';

const CHAT_LOG_DAYS: WholeNumberRange = { min: 1, max: 3650 };
const AUDIT_LOG_DAYS: WholeNumberRange = { min: 30, max: 3650 };

type DataRetentionEnvelope = NonNullable<ReturnType<typeof useDataRetentionSettings>['data']>;

interface LogRetentionDraft {
  chatLogDays: string;
  auditLogDays: string;
}

type LimitDrafts = Partial<Record<OperatorLimitId, string>>;

const EMPTY_LOG_RETENTION: LogRetentionDraft = { chatLogDays: '', auditLogDays: '' };
const NO_LIMIT_DRAFTS: LimitDrafts = {};

function logRetentionOf(envelope: DataRetentionEnvelope): LogRetentionDraft {
  return { chatLogDays: String(envelope.data.chatLogDays), auditLogDays: String(envelope.data.auditLogDays) };
}

function limitDraftsOf(limits: VersionedLimits): LimitDrafts {
  return Object.fromEntries(limits.limits.filter((limit) => !limit.managed).map((limit) => [limit.id, String(limit.value)]));
}

function rangeOf(limit: OperatorLimitSetting): WholeNumberRange {
  return { min: limit.min, max: limit.max };
}

function parsedLimitValues(drafts: LimitDrafts, limits: VersionedLimits): Partial<Record<OperatorLimitId, number>> | null {
  const values: Partial<Record<OperatorLimitId, number>> = {};
  for (const limit of limits.limits) {
    if (limit.managed) continue;
    const parsed = wholeNumberInRange(drafts[limit.id] ?? String(limit.value), rangeOf(limit));
    if (parsed === null) return null;
    if (parsed !== limit.value) values[limit.id] = parsed;
  }
  return values;
}

function useRetentionDrafts() {
  const dataRetention = useDataRetentionSettings();
  const replayRetention = useReplayRetentionDraft();
  const retentionLimits = useRetentionLimits();
  const updateDataRetention = useUpdateDataRetentionSettings();
  const patchRetentionLimits = usePatchRetentionLimits();
  const limits = retentionLimits.data;

  const logs = useSettingsDraft({
    snapshot: dataRetention.data ?? undefined,
    fallback: EMPTY_LOG_RETENTION,
    valueOf: logRetentionOf,
    isValid: (draft) => wholeNumberInRange(draft.chatLogDays, CHAT_LOG_DAYS) !== null && wholeNumberInRange(draft.auditLogDays, AUDIT_LOG_DAYS) !== null,
    save: async (draft, base) => {
      const chatLogDays = wholeNumberInRange(draft.chatLogDays, CHAT_LOG_DAYS);
      const auditLogDays = wholeNumberInRange(draft.auditLogDays, AUDIT_LOG_DAYS);
      if (chatLogDays === null || auditLogDays === null) return null;
      return updateDataRetention.mutateAsync({ expectedVersion: base._meta.version, chatLogDays, auditLogDays });
    },
  });

  const limitDrafts = useSettingsDraft({
    snapshot: limits,
    fallback: NO_LIMIT_DRAFTS,
    valueOf: limitDraftsOf,
    isValid: (drafts) => limits === undefined || parsedLimitValues(drafts, limits) !== null,
    save: async (drafts, base) => {
      const values = parsedLimitValues(drafts, base);
      if (values === null) return null;
      return patchRetentionLimits.mutateAsync({ expectedVersion: base.version, values });
    },
  });

  const loading = dataRetention.isPending || replayRetention.query.isPending || retentionLimits.isPending;
  const failed = !loading && ((dataRetention.data ?? null) === null || (replayRetention.query.data ?? null) === null || limits === undefined);
  const retry = () => {
    void dataRetention.refetch();
    void replayRetention.query.refetch();
    void retentionLimits.refetch();
  };

  return { logs, replayRetention, limitDrafts, limits, loading, failed, retry };
}

const DataRetentionSettings = () => {
  const { t } = useTranslation();
  const { isSuperAdmin } = usePermissions();
  const { logs, replayRetention, limitDrafts, limits, loading, failed, retry } = useRetentionDrafts();
  const disabled = !isSuperAdmin;

  if (loading) return <SettingsLoading />;
  if (failed || limits === undefined) {
    return (
      <div className="space-y-3">
        <Notice variant="error" announce>{t('settings.dataRetention.loadFailed')}</Notice>
        <Button type="button" variant="outline" size="sm" onClick={retry}>{t('common.retry')}</Button>
      </div>
    );
  }

  return (
    <SettingsSections>
      <SettingsSection>
        <p className="text-sm text-muted-foreground">{t('settings.dataRetention.description')}</p>
        <DaysField
          id="chat-log-retention-days"
          label={t('settings.dataRetention.chatLogs')}
          value={logs.value.chatLogDays}
          range={CHAT_LOG_DAYS}
          disabled={disabled}
          onChange={(chatLogDays) => { logs.update((current) => ({ ...current, chatLogDays })); }}
        />
        <DaysField
          id="audit-log-retention-days"
          label={t('settings.dataRetention.auditLog')}
          value={logs.value.auditLogDays}
          range={AUDIT_LOG_DAYS}
          disabled={disabled}
          onChange={(auditLogDays) => { logs.update((current) => ({ ...current, auditLogDays })); }}
        />
        {limits.limits.map((limit) => (
          <DaysField
            key={limit.id}
            id={`retention-limit-${limit.id}`}
            label={t(`settings.dataRetention.limits.${limit.id}.label`)}
            hint={limit.managed ? t('settings.operatorLimits.managedBy', { variable: limit.environmentVariable }) : undefined}
            value={limit.managed ? String(limit.value) : limitDrafts.value[limit.id] ?? String(limit.value)}
            range={rangeOf(limit)}
            disabled={disabled || limit.managed}
            onChange={(value) => { limitDrafts.update((current) => ({ ...current, [limit.id]: value })); }}
          />
        ))}
      </SettingsSection>

      <ReplayRetentionSection title={t('settings.dataRetention.replays')} retention={replayRetention} />
    </SettingsSections>
  );
};

export default DataRetentionSettings;
