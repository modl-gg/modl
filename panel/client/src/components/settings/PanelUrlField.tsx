import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Field } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import type { PanelUrlSaveOutcome } from '@/hooks/data/settings';
import { useBeforeContinue, useContinueReady } from '@/hooks/use-before-continue';
import { normalizeHttpOrigin } from '@/lib/http-origin';
import { useConfirmation, type ConfirmationRequest } from '@/hooks/use-confirmation';

interface PanelUrlInputProps {
  value: string;
  onChange: (value: string) => void;
  managedByConfig: boolean;
  invalid: boolean;
  disabled?: boolean;
  action?: ReactNode;
}

export function PanelUrlInput({ value, onChange, managedByConfig, invalid, disabled = false, action }: PanelUrlInputProps) {
  const { t } = useTranslation();

  return (
    <Field
      label={t('settings.general.panelUrl')}
      error={invalid ? t('settings.general.panelUrlInvalid') : null}
      managed={managedByConfig ? t('settings.general.panelUrlManagedDesc') : null}
    >
      {(control) => (
        <div className="flex flex-wrap items-center gap-2">
          <Input
            {...control}
            type="url"
            inputMode="url"
            placeholder={t('settings.general.panelUrlPlaceholder')}
            value={value}
            onChange={(event) => {
              onChange(event.target.value);
            }}
            disabled={managedByConfig || disabled}
            className="max-w-md"
          />
          {action}
        </div>
      )}
    </Field>
  );
}

interface PanelUrlFieldProps {
  publicUrl: string;
  managedByConfig: boolean;
  onSave: (origin: string) => Promise<PanelUrlSaveOutcome>;
  status?: ReactNode;
}

interface PanelUrlDraft {
  basis: string;
  value: string;
  invalid: boolean;
}

const pristineDraft = (publicUrl: string): PanelUrlDraft => ({ basis: publicUrl, value: publicUrl, invalid: false });

export function panelUrlChangeRequest(t: TFunction, url: string): ConfirmationRequest {
  return {
    title: t('settings.general.panelUrlConfirmTitle'),
    description: t('settings.general.panelUrlConfirmDesc', { url }),
    action: t('settings.general.panelUrlConfirm'),
  };
}

export function PanelUrlField({ publicUrl, managedByConfig, onSave, status }: PanelUrlFieldProps) {
  const { t } = useTranslation();
  const confirmation = useConfirmation();
  const [draftState, setDraftState] = useState<PanelUrlDraft>(() => pristineDraft(publicUrl));
  const [saving, setSaving] = useState(false);

  const currentDraft = draftState.basis === publicUrl ? draftState : pristineDraft(publicUrl);
  const draft = currentDraft.value;
  const invalid = currentDraft.invalid;
  const changed = draft.trim() !== publicUrl;

  const updateDraft = (changes: Partial<Pick<PanelUrlDraft, 'value' | 'invalid'>>) => {
    setDraftState({ ...currentDraft, ...changes });
  };

  const save = async (): Promise<boolean> => {
    const origin = normalizeHttpOrigin(draft);
    if (origin === null) {
      updateDraft({ invalid: true });
      return false;
    }
    updateDraft({ value: origin, invalid: false });
    if (origin === publicUrl) return true;
    if (!(await confirmation.confirm(panelUrlChangeRequest(t, origin)))) return false;
    setSaving(true);
    const outcome = await onSave(origin);
    setSaving(false);
    setDraftState((previous) => ({ ...previous, invalid: outcome === 'invalid' }));
    return outcome === 'saved';
  };

  useContinueReady(!changed || normalizeHttpOrigin(draft) !== null);

  useBeforeContinue(async () => (changed ? save() : true));

  return (
    <div className="space-y-2">
      <form
        onSubmit={(event) => {
          event.preventDefault();
          void save();
        }}
      >
        <PanelUrlInput
          value={draft}
          onChange={(value) => {
            updateDraft({ value, invalid: false });
          }}
          managedByConfig={managedByConfig}
          invalid={invalid}
          disabled={saving}
          action={(
            <>
              {!changed && status}
              {!managedByConfig && (
                <Button type="submit" variant="outline" disabled={saving || !changed}>
                  {saving && <Loader2 className="h-4 w-4 mr-2 animate-spin" aria-hidden="true" />}
                  {t('settings.general.panelUrlSave')}
                </Button>
              )}
            </>
          )}
        />
      </form>
      {confirmation.dialog}
    </div>
  );
}
