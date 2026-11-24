import { Fragment } from 'react';
import { useTranslation } from 'react-i18next';
import { Field } from '@/components/ui/field';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import type { AiProvider, AiProviderGroupId } from '@/hooks/data/ai';

const GROUP_ORDER: readonly AiProviderGroupId[] = ['cloud', 'gateway', 'local', 'custom'];

const GROUP_LABELS: Record<AiProviderGroupId, string> = {
  cloud: 'settings.ai.group.cloud',
  gateway: 'settings.ai.group.gateway',
  local: 'settings.ai.group.local',
  custom: 'settings.ai.group.custom',
};

interface AiProviderSelectProps {
  providers: readonly AiProvider[];
  value: string;
  disabled: boolean;
  onChange: (providerId: string) => void;
}

export function AiProviderSelect({ providers, value, disabled, onChange }: AiProviderSelectProps) {
  const { t } = useTranslation();

  return (
    <Field label={t('settings.ai.provider')}>
      {(control) => (
        <Select value={value} onValueChange={onChange} disabled={disabled}>
          <SelectTrigger {...control}>
            <SelectValue placeholder={t('settings.ai.providerPlaceholder')} />
          </SelectTrigger>
          <SelectContent>
            {GROUP_ORDER.map((group) => {
              const members = providers.filter((provider) => provider.group === group);
              if (members.length === 0) return null;
              return (
                <Fragment key={group}>
                  <div className="px-2 py-1.5 text-xs font-medium text-muted-foreground">{t(GROUP_LABELS[group])}</div>
                  {members.map((provider) => (
                    <SelectItem key={provider.id} value={provider.id}>{provider.displayName}</SelectItem>
                  ))}
                </Fragment>
              );
            })}
          </SelectContent>
        </Select>
      )}
    </Field>
  );
}
