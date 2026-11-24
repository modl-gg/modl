import { useTranslation } from 'react-i18next';
import { ExternalLink } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Field } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Notice } from '@/components/ui/notice';
import { useConfirmation } from '@/hooks/use-confirmation';
import type { AiDraft } from '@/lib/ai-connection';

const API_KEY_MAX_LENGTH = 512;

interface AiKeyFieldProps {
  draft: AiDraft;
  disabled: boolean;
  onChange: (apiKey: string) => void;
  onClear: () => void;
}

export function AiKeyField({ draft, disabled, onChange, onClear }: AiKeyFieldProps) {
  const { t } = useTranslation();
  const confirmation = useConfirmation();
  const { apiKeyRequired, keyPageUrl } = draft.provider;
  const typed = draft.apiKey.length > 0;

  const confirmClear = async () => {
    const confirmed = await confirmation.confirm({
      title: t('settings.ai.apiKeyClearTitle'),
      description: t('settings.ai.apiKeyClearDesc', { provider: draft.provider.displayName }),
      action: t('settings.ai.apiKeyClear'),
      destructive: true,
    });
    if (confirmed) onClear();
  };

  const hint = (
    <>
      {t('settings.ai.apiKeyHint')}{' '}
      {keyPageUrl !== null && (
        <a href={keyPageUrl} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-1 text-primary hover:underline">
          {t('settings.ai.getKey')}
          <ExternalLink className="h-3 w-3" aria-hidden="true" />
        </a>
      )}
    </>
  );

  return (
    <Field label={apiKeyRequired ? t('settings.ai.apiKey') : t('settings.ai.apiKeyOptional')} hint={hint}>
      {(control) => (
        <>
          <div className="flex flex-wrap gap-2">
            <Input
              {...control}
              type="password"
              className="min-w-0 flex-1"
              value={draft.apiKey}
              disabled={disabled}
              autoComplete="off"
              spellCheck={false}
              maxLength={API_KEY_MAX_LENGTH}
              placeholder={draft.storedKeyApplies ? t('settings.ai.apiKeySavedPlaceholder') : t('settings.ai.apiKeyPlaceholder')}
              onChange={(event) => {
                onChange(event.target.value);
              }}
            />
            {!apiKeyRequired && draft.storedKeyApplies && !typed && (
              <Button type="button" variant="outline" disabled={disabled} onClick={() => { void confirmClear(); }}>
                {t('settings.ai.apiKeyClear')}
              </Button>
            )}
          </div>
          {draft.storedKeyDropped && !typed && <Notice variant="warning">{t('settings.ai.apiKeyReenter')}</Notice>}
          {confirmation.dialog}
        </>
      )}
    </Field>
  );
}
