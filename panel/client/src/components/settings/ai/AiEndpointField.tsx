import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { Field } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import type { AiRegionId } from '@/hooks/data/ai';
import type { AiDraft } from '@/lib/ai-connection';

const BASE_URL_MAX_LENGTH = 2048;

const REGION_LABELS: Record<AiRegionId, string> = {
  global: 'settings.ai.region.global',
  china: 'settings.ai.region.china',
  singapore: 'settings.ai.region.singapore',
  us: 'settings.ai.region.us',
};

interface AiEndpointFieldProps {
  draft: AiDraft;
  disabled: boolean;
  onChange: (baseUrl: string) => void;
}

export function AiEndpointField({ draft, disabled, onChange }: AiEndpointFieldProps) {
  const { t } = useTranslation();
  const { endpoints, baseUrlEditable } = draft.provider;

  if (!baseUrlEditable && endpoints.length <= 1) return null;

  if (!baseUrlEditable) {
    return (
      <Field label={t('settings.ai.endpoint')}>
        {(control) => (
          <Select value={draft.baseUrl} onValueChange={onChange} disabled={disabled}>
            <SelectTrigger {...control}>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {endpoints.map((endpoint) => (
                <SelectItem key={endpoint.baseUrl} value={endpoint.baseUrl}>{t(REGION_LABELS[endpoint.region])}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        )}
      </Field>
    );
  }

  return (
    <Field label={t('settings.ai.baseUrl')} hint={t('settings.ai.baseUrlHint')}>
      {(control) => (
        <>
          <Input
            {...control}
            value={draft.baseUrl}
            disabled={disabled}
            autoComplete="off"
            spellCheck={false}
            maxLength={BASE_URL_MAX_LENGTH}
            onChange={(event) => {
              onChange(event.target.value);
            }}
          />
          {endpoints.length > 1 && (
            <div className="flex flex-wrap gap-2">
              {endpoints.map((endpoint) => (
                <Button
                  key={endpoint.baseUrl}
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={disabled}
                  onClick={() => {
                    onChange(endpoint.baseUrl);
                  }}
                >
                  {t(REGION_LABELS[endpoint.region])}
                </Button>
              ))}
            </div>
          )}
        </>
      )}
    </Field>
  );
}
