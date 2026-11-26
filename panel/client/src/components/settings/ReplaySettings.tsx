import { useTranslation } from 'react-i18next';
import { Check, Minus } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Field } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Notice } from '@/components/ui/notice';
import { Switch } from '@/components/ui/switch';
import {
  ANTICHEAT_NAME_MAX_LENGTH,
  REPLAY_SETTING_RANGES,
  useReplayServerStatuses,
  useReplaySettings,
  useSaveReplaySettings,
  type ReplayNumberSetting,
  type ReplayServerStatus,
  type ReplaySettings as ReplaySettingsValue,
  type VersionedReplaySettings,
} from '@/hooks/data/replay-settings';
import { describeError } from '@/lib/http-error';
import { formatList } from '@/lib/list-format';
import { wholeNumberInRange } from '@/lib/whole-number';
import { ReplayRetentionSection, useReplayRetentionDraft } from './ReplayRetentionSection';
import { useSettingsPageCanModify } from './layout/SettingsPageFrame';
import { FIELD_WIDTH, SettingsLoading, SettingsSection, SettingsSections } from './layout/SettingsSections';
import { useSettingsDraft } from './save/use-settings-draft';

type NumberDrafts = Record<ReplayNumberSetting, string>;

interface ReplaySettingsDraft {
  enabled: boolean;
  recordEveryPlayer: boolean;
  recordOnAnticheatFlag: boolean;
  anticheatIntegration: boolean;
  anticheatName: string;
  numbers: NumberDrafts;
}

interface NumberFieldDefinition {
  readonly setting: ReplayNumberSetting;
  readonly labelKey: string;
  readonly unitKey: string;
}

const NUMBER_FIELDS: readonly NumberFieldDefinition[] = [
  { setting: 'windowSeconds', labelKey: 'settings.replays.window', unitKey: 'settings.replays.seconds' },
  { setting: 'radiusBlocks', labelKey: 'settings.replays.radius', unitKey: 'settings.replays.blocks' },
  { setting: 'memoryBudgetMb', labelKey: 'settings.replays.memoryBudget', unitKey: 'settings.replays.megabytes' },
];

const FALLBACK_DRAFT: ReplaySettingsDraft = {
  enabled: false,
  recordEveryPlayer: true,
  recordOnAnticheatFlag: true,
  anticheatIntegration: false,
  anticheatName: '',
  numbers: { windowSeconds: '', radiusBlocks: '', memoryBudgetMb: '' },
};

function draftOf({ value }: VersionedReplaySettings): ReplaySettingsDraft {
  return {
    enabled: value.enabled,
    recordEveryPlayer: value.recordEveryPlayer,
    recordOnAnticheatFlag: value.recordOnAnticheatFlag,
    anticheatIntegration: value.anticheatIntegration,
    anticheatName: value.anticheatName,
    numbers: {
      windowSeconds: String(value.windowSeconds),
      radiusBlocks: String(value.radiusBlocks),
      memoryBudgetMb: String(value.memoryBudgetMb),
    },
  };
}

function numberOf(draft: ReplaySettingsDraft, setting: ReplayNumberSetting): number | null {
  return wholeNumberInRange(draft.numbers[setting], REPLAY_SETTING_RANGES[setting]);
}

function validAnticheatName(name: string): boolean {
  const trimmed = name.trim();
  return trimmed !== '' && trimmed.length <= ANTICHEAT_NAME_MAX_LENGTH;
}

function isValidDraft(draft: ReplaySettingsDraft): boolean {
  const numbersValid = !draft.enabled || NUMBER_FIELDS.every(({ setting }) => numberOf(draft, setting) !== null);
  return numbersValid && (!draft.anticheatIntegration || validAnticheatName(draft.anticheatName));
}

function visibleNumber(draft: ReplaySettingsDraft, base: ReplaySettingsValue, setting: ReplayNumberSetting): number | null {
  return draft.enabled ? numberOf(draft, setting) : base[setting];
}

function settingsOf(draft: ReplaySettingsDraft, base: ReplaySettingsValue): ReplaySettingsValue | null {
  if (!isValidDraft(draft)) return null;
  const windowSeconds = visibleNumber(draft, base, 'windowSeconds');
  const radiusBlocks = visibleNumber(draft, base, 'radiusBlocks');
  const memoryBudgetMb = visibleNumber(draft, base, 'memoryBudgetMb');
  if (windowSeconds === null || radiusBlocks === null || memoryBudgetMb === null) return null;
  return {
    enabled: draft.enabled,
    recordEveryPlayer: draft.recordEveryPlayer,
    recordOnAnticheatFlag: draft.recordOnAnticheatFlag,
    anticheatIntegration: draft.anticheatIntegration,
    anticheatName: draft.anticheatIntegration ? draft.anticheatName.trim() : base.anticheatName,
    windowSeconds,
    radiusBlocks,
    memoryBudgetMb,
  };
}

interface SwitchRowProps {
  id: string;
  label: string;
  checked: boolean;
  disabled: boolean;
  onChange: (checked: boolean) => void;
}

function SwitchRow({ id, label, checked, disabled, onChange }: SwitchRowProps) {
  return (
    <div className="flex items-center justify-between gap-4">
      <Label htmlFor={id}>{label}</Label>
      <Switch id={id} checked={checked} disabled={disabled} onCheckedChange={onChange} />
    </div>
  );
}

interface NumberFieldProps {
  definition: NumberFieldDefinition;
  value: string;
  disabled: boolean;
  onChange: (value: string) => void;
}

function NumberField({ definition, value, disabled, onChange }: NumberFieldProps) {
  const { t } = useTranslation();
  const range = REPLAY_SETTING_RANGES[definition.setting];
  const invalid = wholeNumberInRange(value, range) === null;
  return (
    <Field label={t(definition.labelKey)} error={invalid ? t('settings.replays.outOfRange', { min: range.min, max: range.max }) : null}>
      {(control) => (
        <div className="flex items-center gap-2">
          <Input
            {...control}
            type="number"
            inputMode="numeric"
            min={range.min}
            max={range.max}
            step={1}
            value={value}
            disabled={disabled}
            className={FIELD_WIDTH.number}
            onChange={(event) => { onChange(event.target.value); }}
          />
          <span className="text-sm text-muted-foreground">{t(definition.unitKey)}</span>
        </div>
      )}
    </Field>
  );
}

function StateMark({ active, label }: { active: boolean; label: string }) {
  const { t } = useTranslation();
  const Icon = active ? Check : Minus;
  return (
    <span className="flex items-center gap-1.5 text-xs">
      <Icon className={active ? 'h-3.5 w-3.5 text-success' : 'h-3.5 w-3.5 text-muted-foreground/60'} aria-hidden="true" />
      <span className={active ? 'text-foreground' : 'text-muted-foreground'}>{label}</span>
      <span className="sr-only">{t(active ? 'settings.replays.stateOn' : 'settings.replays.stateOff')}</span>
    </span>
  );
}

function LoweredBudget({ budgetMb }: { budgetMb: number }) {
  const { t, i18n } = useTranslation();
  const value = new Intl.NumberFormat(i18n.language, { style: 'unit', unit: 'megabyte' }).format(budgetMb);
  return (
    <span className="text-xs tabular-nums text-warning">
      <span className="sr-only">{`${t('settings.replays.memoryBudget')} `}</span>
      {value}
    </span>
  );
}

function ServerStatusRow({ server, configuredBudgetMb }: { server: ReplayServerStatus; configuredBudgetMb: number }) {
  const { t, i18n } = useTranslation();
  const budgetMb = server.memoryBudgetMb;
  const lowered = server.replaysSupported && budgetMb !== null && budgetMb < configuredBudgetMb;
  return (
    <li className="flex flex-wrap items-center gap-x-5 gap-y-1.5 px-4 py-3">
      <span className="min-w-0 flex-1 basis-32 break-all text-sm font-medium">{server.name || t('settings.servers.unnamed')}</span>
      {server.replaysSupported && <StateMark active={server.replaysActive} label={t('settings.replays.serverReplays')} />}
      {lowered && <LoweredBudget budgetMb={budgetMb} />}
      <StateMark active={server.anticheatIntegrationActive} label={t('settings.replays.serverAnticheat')} />
      {server.detectedAnticheats.length > 0 && (
        <span className="w-full text-xs text-muted-foreground sm:w-auto">{formatList(server.detectedAnticheats, i18n.language)}</span>
      )}
    </li>
  );
}

function ReplayRetention() {
  const { t } = useTranslation();
  const retention = useReplayRetentionDraft();
  const { query } = retention;
  if (query.isPending) return <SettingsLoading />;
  if ((query.data ?? null) === null) {
    return (
      <SettingsSection title={t('settings.replays.retention')}>
        <Notice variant="error" announce>{t('settings.dataRetention.loadFailed')}</Notice>
        <Button type="button" variant="outline" size="sm" onClick={() => { void query.refetch(); }}>{t('common.retry')}</Button>
      </SettingsSection>
    );
  }
  return <ReplayRetentionSection title={t('settings.replays.retention')} retention={retention} />;
}

function ServerStatuses({ configuredBudgetMb }: { configuredBudgetMb: number }) {
  const { t } = useTranslation();
  const { statuses } = useReplayServerStatuses();
  if (statuses.length === 0) return null;
  return (
    <SettingsSection title={t('settings.replays.servers')}>
      <ul className="divide-y rounded-md border">
        {statuses.map((server) => <ServerStatusRow key={server.key} server={server} configuredBudgetMb={configuredBudgetMb} />)}
      </ul>
    </SettingsSection>
  );
}

const ReplaySettings = () => {
  const { t } = useTranslation();
  const canModify = useSettingsPageCanModify();
  const settings = useReplaySettings();
  const save = useSaveReplaySettings();
  const draft = useSettingsDraft({
    snapshot: settings.data ?? undefined,
    fallback: FALLBACK_DRAFT,
    valueOf: draftOf,
    isValid: isValidDraft,
    save: async (value, base) => {
      const next = settingsOf(value, base.value);
      if (next === null) return null;
      return save.mutateAsync({ expectedVersion: base.version, value: next });
    },
  });

  if (settings.isPending) return <SettingsLoading />;
  if (settings.isError || settings.data === null) {
    return (
      <div className="space-y-3">
        <Notice variant="error" announce>{describeError(settings.error, t('settings.replays.loadFailed'))}</Notice>
        <Button type="button" variant="outline" size="sm" onClick={() => { void settings.refetch(); }}>{t('common.retry')}</Button>
      </div>
    );
  }

  const value = draft.value;
  const disabled = !canModify;
  const change = (patch: Partial<ReplaySettingsDraft>) => {
    draft.update((current) => ({ ...current, ...patch }));
  };
  const changeNumber = (setting: ReplayNumberSetting, text: string) => {
    draft.update((current) => ({ ...current, numbers: { ...current.numbers, [setting]: text } }));
  };
  const nameInvalid = !validAnticheatName(value.anticheatName);

  return (
    <SettingsSections>
      <SettingsSection title={t('settings.replays.recording')}>
        <SwitchRow
          id="replays-enabled"
          label={t('settings.replays.enabled')}
          checked={value.enabled}
          disabled={disabled}
          onChange={(enabled) => { change({ enabled }); }}
        />
        {value.enabled && (
          <SwitchRow
            id="replays-record-every-player"
            label={t('settings.replays.recordEveryPlayer')}
            checked={value.recordEveryPlayer}
            disabled={disabled}
            onChange={(recordEveryPlayer) => { change({ recordEveryPlayer }); }}
          />
        )}
        {value.enabled && NUMBER_FIELDS.map((definition) => (
          <NumberField
            key={definition.setting}
            definition={definition}
            value={value.numbers[definition.setting]}
            disabled={disabled}
            onChange={(text) => { changeNumber(definition.setting, text); }}
          />
        ))}
      </SettingsSection>

      <SettingsSection title={t('settings.replays.anticheat')}>
        <SwitchRow
          id="replays-anticheat-integration"
          label={t('settings.replays.anticheatIntegration')}
          checked={value.anticheatIntegration}
          disabled={disabled}
          onChange={(anticheatIntegration) => { change({ anticheatIntegration }); }}
        />
        {value.enabled && value.anticheatIntegration && (
          <SwitchRow
            id="replays-record-on-flag"
            label={t('settings.replays.recordOnAnticheatFlag')}
            checked={value.recordOnAnticheatFlag}
            disabled={disabled}
            onChange={(recordOnAnticheatFlag) => { change({ recordOnAnticheatFlag }); }}
          />
        )}
        {value.anticheatIntegration && (
          <Field label={t('settings.replays.anticheatName')} error={nameInvalid ? t('settings.replays.anticheatNameInvalid', { max: ANTICHEAT_NAME_MAX_LENGTH }) : null}>
            {(control) => (
              <Input
                {...control}
                value={value.anticheatName}
                maxLength={ANTICHEAT_NAME_MAX_LENGTH}
                disabled={disabled}
                className={FIELD_WIDTH.short}
                onChange={(event) => { change({ anticheatName: event.target.value }); }}
              />
            )}
          </Field>
        )}
      </SettingsSection>

      <ReplayRetention />

      <ServerStatuses configuredBudgetMb={settings.data.value.memoryBudgetMb} />
    </SettingsSections>
  );
};

export default ReplaySettings;
