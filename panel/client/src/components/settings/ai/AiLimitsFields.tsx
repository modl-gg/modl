import { useTranslation } from 'react-i18next';
import { Field } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { useUpdateAiSettings, type AiSettingsState } from '@/hooks/data/ai';
import { isConflictError } from '@/lib/http-error';
import { AI_LIMIT_BOUNDS, AI_LIMIT_FIELDS, limitsFrom, parsedLimit, type AiLimitField } from '@/lib/ai-connection';
import { aiErrorMessageKey } from './ai-messages';
import { FIELD_WIDTH, SettingsSection } from '../layout/SettingsSections';
import { useSettingsDraft } from '../save/use-settings-draft';

const FIELD_LABELS: Record<AiLimitField, string> = {
  callsPerHour: 'settings.ai.limits.callsPerHour',
  callsPerDay: 'settings.ai.limits.callsPerDay',
  reportsPerReporterPerHour: 'settings.ai.limits.perReporter',
  concurrentCalls: 'settings.ai.limits.concurrent',
  maxOutputTokens: 'settings.ai.limits.maxOutputTokens',
};

type VersionedLimits = Pick<AiSettingsState, 'limits' | 'limitsVersion'>;

function textOf({ limits }: VersionedLimits): Record<AiLimitField, string> {
  return {
    callsPerHour: limits.callsPerHour.toString(),
    callsPerDay: limits.callsPerDay.toString(),
    reportsPerReporterPerHour: limits.reportsPerReporterPerHour.toString(),
    concurrentCalls: limits.concurrentCalls.toString(),
    maxOutputTokens: limits.maxOutputTokens.toString(),
  };
}

export function AiLimitsFields({ settings }: { settings: VersionedLimits }) {
  const { t } = useTranslation();
  const update = useUpdateAiSettings();
  const draft = useSettingsDraft({
    snapshot: settings,
    fallback: textOf(settings),
    valueOf: textOf,
    isValid: (values) => limitsFrom(values) !== null,
    save: async (values, base): Promise<VersionedLimits | null> => {
      const limits = limitsFrom(values);
      if (limits === null) return null;
      return update.mutateAsync({ limitsWrite: { expectedVersion: base.limitsVersion, limits } });
    },
    describeRejection: (error) => (isConflictError(error) ? null : t(aiErrorMessageKey(error, 'settings.ai.limits.saveFailed'))),
  });
  const values = draft.value;

  return (
    <SettingsSection title={t('settings.ai.limits.title')} className="border-t pt-6">
      <div className="grid max-w-xl grid-cols-1 gap-x-6 gap-y-5 sm:grid-cols-2">
        {AI_LIMIT_FIELDS.map((field) => {
          const valid = parsedLimit(field, values[field]) !== null;
          return (
            <Field key={field} label={t(FIELD_LABELS[field])} error={valid ? null : t('settings.ai.limits.range', AI_LIMIT_BOUNDS[field])}>
              {(control) => (
                <Input
                  {...control}
                  inputMode="numeric"
                  autoComplete="off"
                  maxLength={6}
                  value={values[field]}
                  className={FIELD_WIDTH.number}
                  onChange={(event) => {
                    draft.update((current) => ({ ...current, [field]: event.target.value }));
                  }}
                />
              )}
            </Field>
          );
        })}
      </div>
    </SettingsSection>
  );
}
