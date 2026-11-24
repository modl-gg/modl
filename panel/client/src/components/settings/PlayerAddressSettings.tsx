import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { Field } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Notice } from '@/components/ui/notice';
import {
  usePlayerAddressSettings,
  useUpdatePlayerAddressSettings,
  type PlayerAddressSettings as PlayerAddressSettingsData,
} from '@/hooks/data/player-addresses';
import { ForwardingNoticeBanner } from './ForwardingNoticeBanner';
import { LookupChoice } from './LookupChoice';
import { usePlayerAddressFailureMessage } from './use-player-address-failure';
import { FIELD_WIDTH, SettingsLoading, SettingsSection, SettingsSections } from './layout/SettingsSections';
import { useSettingsDraft } from './save/use-settings-draft';

function parseLimit(value: string, settings: PlayerAddressSettingsData): number | null {
  if (!/^\d+$/.test(value.trim())) return null;
  const limit = Number(value);
  return limit >= settings.minSharedAccountLimit && limit <= settings.maxSharedAccountLimit ? limit : null;
}

function limitTextOf(settings: PlayerAddressSettingsData): string {
  return String(settings.sharedAccountLimit);
}

function SharedAccountLimitField({ settings }: { settings: PlayerAddressSettingsData }) {
  const { t } = useTranslation();
  const update = useUpdatePlayerAddressSettings();
  const describeFailure = usePlayerAddressFailureMessage();
  const draft = useSettingsDraft({
    snapshot: settings,
    fallback: limitTextOf(settings),
    valueOf: limitTextOf,
    isValid: (value) => parseLimit(value, settings) !== null,
    describeRejection: describeFailure,
    save: async (value, base) => {
      const sharedAccountLimit = parseLimit(value, base);
      return sharedAccountLimit === null ? null : update.mutateAsync({ sharedAccountLimit });
    },
  });
  const invalid = !draft.isValid;
  const range = { min: settings.minSharedAccountLimit, max: settings.maxSharedAccountLimit };

  return (
    <Field
      label={t('settings.playerAddresses.sharedLimitLabel')}
      error={invalid ? t('settings.playerAddresses.sharedLimitInvalid', range) : null}
    >
      {(control) => (
        <div className="flex items-center gap-2">
          <Input
            {...control}
            type="number"
            inputMode="numeric"
            min={range.min}
            max={range.max}
            step={1}
            value={draft.value}
            className={FIELD_WIDTH.number}
            onChange={(event) => { draft.update(event.target.value); }}
          />
          <span className="text-sm text-muted-foreground">{t('settings.playerAddresses.accounts')}</span>
        </div>
      )}
    </Field>
  );
}

const PlayerAddressSettings = () => {
  const { t } = useTranslation();
  const settings = usePlayerAddressSettings(true);

  if (settings.isLoading) return <SettingsLoading />;

  const data = settings.data;
  if (data === undefined) {
    return (
      <div className="space-y-3">
        <Notice variant="error" announce>{t('settings.playerAddresses.loadFailed')}</Notice>
        <Button type="button" variant="outline" size="sm" onClick={() => { void settings.refetch(); }}>
          {t('common.retry')}
        </Button>
      </div>
    );
  }

  return (
    <SettingsSections>
      <SettingsSection>
        <ForwardingNoticeBanner notice={data.forwardingNotice} />
        <LookupChoice settings={data} />
      </SettingsSection>
      <SettingsSection>
        <SharedAccountLimitField settings={data} />
      </SettingsSection>
    </SettingsSections>
  );
};

export default PlayerAddressSettings;
