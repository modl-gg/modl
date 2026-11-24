import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Switch } from '@/components/ui/switch';
import { Notice } from '@/components/ui/notice';
import { usePermissions } from '@/hooks/use-permissions';
import {
  useDataRetentionSettings,
  useReplayRetentionSettings,
  useUpdateDataRetentionSettings,
  useUpdateReplayRetentionSettings,
} from '@/hooks/data/settings';
import {
  usePatchRetentionLimits,
  useRetentionLimits,
  type OperatorLimitId,
  type OperatorLimitSetting,
  type VersionedLimits,
} from '@/hooks/data/operations';
import { FIELD_WIDTH, SettingsLoading, SettingsSection, SettingsSections } from './layout/SettingsSections';
import { useSettingsDraft } from './save/use-settings-draft';

interface DayRange {
  min: number;
  max: number;
}

const CHAT_LOG_DAYS: DayRange = { min: 1, max: 3650 };
const AUDIT_LOG_DAYS: DayRange = { min: 30, max: 3650 };
const REPLAY_DAYS: DayRange = { min: 1, max: 365 };

type DataRetentionEnvelope = NonNullable<ReturnType<typeof useDataRetentionSettings>['data']>;
type ReplayRetentionEnvelope = NonNullable<ReturnType<typeof useReplayRetentionSettings>['data']>;

interface LogRetentionDraft {
  chatLogDays: string;
  auditLogDays: string;
}

interface ReplayRetentionDraft {
  enabled: boolean;
  days: string;
}

type LimitDrafts = Partial<Record<OperatorLimitId, string>>;

const EMPTY_LOG_RETENTION: LogRetentionDraft = { chatLogDays: '', auditLogDays: '' };
const EMPTY_REPLAY_RETENTION: ReplayRetentionDraft = { enabled: true, days: '' };
const NO_LIMIT_DRAFTS: LimitDrafts = {};

function logRetentionOf(envelope: DataRetentionEnvelope): LogRetentionDraft {
  return { chatLogDays: String(envelope.data.chatLogDays), auditLogDays: String(envelope.data.auditLogDays) };
}

function replayRetentionOf(envelope: ReplayRetentionEnvelope): ReplayRetentionDraft {
  return { enabled: envelope.data.enabled, days: String(envelope.data.days) };
}

function limitDraftsOf(limits: VersionedLimits): LimitDrafts {
  return Object.fromEntries(limits.limits.filter((limit) => !limit.managed).map((limit) => [limit.id, String(limit.value)]));
}

function rangeOf(limit: OperatorLimitSetting): DayRange {
  return { min: limit.min, max: limit.max };
}

function parseDays(value: string, range: DayRange): number | null {
  if (!/^\d+$/.test(value.trim())) return null;
  const days = Number(value);
  return days >= range.min && days <= range.max ? days : null;
}

function parsedLimitValues(drafts: LimitDrafts, limits: VersionedLimits): Partial<Record<OperatorLimitId, number>> | null {
  const values: Partial<Record<OperatorLimitId, number>> = {};
  for (const limit of limits.limits) {
    if (limit.managed) continue;
    const parsed = parseDays(drafts[limit.id] ?? String(limit.value), rangeOf(limit));
    if (parsed === null) return null;
    if (parsed !== limit.value) values[limit.id] = parsed;
  }
  return values;
}

interface DaysFieldProps {
  id: string;
  label: string;
  hint?: string | undefined;
  value: string;
  range: DayRange;
  disabled: boolean;
  onChange: (value: string) => void;
}

function DaysField({ id, label, hint, value, range, disabled, onChange }: DaysFieldProps) {
  const { t } = useTranslation();
  const invalid = parseDays(value, range) === null;
  const hintId = `${id}-hint`;
  const message = invalid ? t('settings.dataRetention.invalidDays', { min: range.min, max: range.max }) : hint;
  return (
    <div className="space-y-2">
      <Label htmlFor={id}>{label}</Label>
      <div className="flex items-center gap-2">
        <Input
          id={id}
          type="number"
          inputMode="numeric"
          min={range.min}
          max={range.max}
          step={1}
          value={value}
          onChange={(event) => { onChange(event.target.value); }}
          disabled={disabled}
          aria-invalid={invalid}
          aria-describedby={message === undefined ? undefined : hintId}
          className={FIELD_WIDTH.number}
        />
        <span className="text-sm text-muted-foreground">{t('settings.dataRetention.days')}</span>
      </div>
      {message !== undefined && (
        <p id={hintId} className={invalid ? 'text-sm text-destructive-text' : 'text-xs text-muted-foreground'}>{message}</p>
      )}
    </div>
  );
}

function useRetentionDrafts() {
  const dataRetention = useDataRetentionSettings();
  const replayRetention = useReplayRetentionSettings();
  const retentionLimits = useRetentionLimits();
  const updateDataRetention = useUpdateDataRetentionSettings();
  const updateReplayRetention = useUpdateReplayRetentionSettings();
  const patchRetentionLimits = usePatchRetentionLimits();
  const limits = retentionLimits.data;

  const logs = useSettingsDraft({
    snapshot: dataRetention.data ?? undefined,
    fallback: EMPTY_LOG_RETENTION,
    valueOf: logRetentionOf,
    isValid: (draft) => parseDays(draft.chatLogDays, CHAT_LOG_DAYS) !== null && parseDays(draft.auditLogDays, AUDIT_LOG_DAYS) !== null,
    save: async (draft, base) => {
      const chatLogDays = parseDays(draft.chatLogDays, CHAT_LOG_DAYS);
      const auditLogDays = parseDays(draft.auditLogDays, AUDIT_LOG_DAYS);
      if (chatLogDays === null || auditLogDays === null) return null;
      return updateDataRetention.mutateAsync({ expectedVersion: base._meta.version, chatLogDays, auditLogDays });
    },
  });

  const replays = useSettingsDraft({
    snapshot: replayRetention.data ?? undefined,
    fallback: EMPTY_REPLAY_RETENTION,
    valueOf: replayRetentionOf,
    isValid: (draft) => !draft.enabled || parseDays(draft.days, REPLAY_DAYS) !== null,
    save: async (draft, base) => {
      const days = draft.enabled ? parseDays(draft.days, REPLAY_DAYS) : base.data.days;
      if (days === null) return null;
      return updateReplayRetention.mutateAsync({ expectedVersion: base._meta.version, enabled: draft.enabled, days });
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

  const loading = dataRetention.isPending || replayRetention.isPending || retentionLimits.isPending;
  const failed = !loading && ((dataRetention.data ?? null) === null || (replayRetention.data ?? null) === null || limits === undefined);
  const retry = () => {
    void dataRetention.refetch();
    void replayRetention.refetch();
    void retentionLimits.refetch();
  };

  return { logs, replays, limitDrafts, limits, loading, failed, retry };
}

const DataRetentionSettings = () => {
  const { t } = useTranslation();
  const { isSuperAdmin } = usePermissions();
  const { logs, replays, limitDrafts, limits, loading, failed, retry } = useRetentionDrafts();
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

      <SettingsSection title={t('settings.dataRetention.replays')}>
        <div className="flex items-start justify-between gap-4">
          <div className="space-y-1">
            <Label htmlFor="replay-retention-enabled">{t('settings.dataRetention.replaysEnabled')}</Label>
            <p className="text-xs text-muted-foreground">{t('settings.dataRetention.replaysExemption')}</p>
          </div>
          <Switch
            id="replay-retention-enabled"
            checked={replays.value.enabled}
            onCheckedChange={(enabled) => { replays.update((current) => ({ ...current, enabled })); }}
            disabled={disabled}
          />
        </div>
        {replays.value.enabled ? (
          <DaysField
            id="replay-retention-days"
            label={t('settings.dataRetention.replaysKeep')}
            value={replays.value.days}
            range={REPLAY_DAYS}
            disabled={disabled}
            onChange={(days) => { replays.update((current) => ({ ...current, days })); }}
          />
        ) : (
          <Notice variant="warning">{t('settings.dataRetention.replaysDisabledWarning')}</Notice>
        )}
      </SettingsSection>
    </SettingsSections>
  );
};

export default DataRetentionSettings;
