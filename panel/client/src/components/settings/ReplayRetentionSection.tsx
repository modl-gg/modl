import { useTranslation } from 'react-i18next';
import { Label } from '@/components/ui/label';
import { Notice } from '@/components/ui/notice';
import { Switch } from '@/components/ui/switch';
import { usePermissions } from '@/hooks/use-permissions';
import { useReplayRetentionSettings, useUpdateReplayRetentionSettings } from '@/hooks/data/settings';
import { wholeNumberInRange, type WholeNumberRange } from '@/lib/whole-number';
import { DaysField } from './DaysField';
import { SettingsSection } from './layout/SettingsSections';
import { useSettingsDraft } from './save/use-settings-draft';

const REPLAY_DAYS: WholeNumberRange = { min: 1, max: 365 };

type ReplayRetentionEnvelope = NonNullable<ReturnType<typeof useReplayRetentionSettings>['data']>;

interface ReplayRetentionDraft {
  enabled: boolean;
  days: string;
}

const EMPTY_REPLAY_RETENTION: ReplayRetentionDraft = { enabled: true, days: '' };

function replayRetentionOf(envelope: ReplayRetentionEnvelope): ReplayRetentionDraft {
  return { enabled: envelope.data.enabled, days: String(envelope.data.days) };
}

export function useReplayRetentionDraft() {
  const replayRetention = useReplayRetentionSettings();
  const updateReplayRetention = useUpdateReplayRetentionSettings();
  const draft = useSettingsDraft({
    snapshot: replayRetention.data ?? undefined,
    fallback: EMPTY_REPLAY_RETENTION,
    valueOf: replayRetentionOf,
    isValid: (value) => !value.enabled || wholeNumberInRange(value.days, REPLAY_DAYS) !== null,
    save: async (value, base) => {
      const days = value.enabled ? wholeNumberInRange(value.days, REPLAY_DAYS) : base.data.days;
      if (days === null) return null;
      return updateReplayRetention.mutateAsync({ expectedVersion: base._meta.version, enabled: value.enabled, days });
    },
  });
  return { query: replayRetention, draft };
}

type ReplayRetention = ReturnType<typeof useReplayRetentionDraft>;

export function ReplayRetentionSection({ title, retention }: { title: string; retention: ReplayRetention }) {
  const { t } = useTranslation();
  const { isSuperAdmin } = usePermissions();
  const { draft } = retention;
  const disabled = !isSuperAdmin;

  return (
    <SettingsSection title={title}>
      <div className="flex items-start justify-between gap-4">
        <div className="space-y-1">
          <Label htmlFor="replay-retention-enabled">{t('settings.dataRetention.replaysEnabled')}</Label>
          <p className="text-xs text-muted-foreground">{t('settings.dataRetention.replaysExemption')}</p>
        </div>
        <Switch
          id="replay-retention-enabled"
          checked={draft.value.enabled}
          onCheckedChange={(enabled) => { draft.update((current) => ({ ...current, enabled })); }}
          disabled={disabled}
        />
      </div>
      {draft.value.enabled ? (
        <DaysField
          id="replay-retention-days"
          label={t('settings.dataRetention.replaysKeep')}
          value={draft.value.days}
          range={REPLAY_DAYS}
          disabled={disabled}
          onChange={(days) => { draft.update((current) => ({ ...current, days })); }}
        />
      ) : (
        <Notice variant="warning">{t('settings.dataRetention.replaysDisabledWarning')}</Notice>
      )}
    </SettingsSection>
  );
}
