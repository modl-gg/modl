import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { PageForm } from '@/components/ui/action-form';
import { Button } from '@/components/ui/button';
import { CheckButton } from '@/components/ui/check-button';
import { Notice } from '@/components/ui/notice';
import { LoadingSpinner } from '@/components/ui/loading-spinner';
import { useCheckOutcome } from '@/hooks/use-check-outcome';
import { useConfirmation } from '@/hooks/use-confirmation';
import { useBeforeContinue, useContinueGateActive, useContinueReady } from '@/hooks/use-before-continue';
import { PERMISSIONS, usePermissions } from '@/hooks/use-permissions';
import {
  useAiSettings,
  useAiStatus,
  useRemoveAiConnection,
  useSaveAiConnection,
  useTestAiConnection,
  type AiSettingsState,
  type AiTestResult,
} from '@/hooks/data/ai';
import { canSave, draftRequest, saveRequest } from '@/lib/ai-connection';
import { AiConnectionTestFailure, aiConnectionTestPassed } from './AiConnectionTestStatus';
import { AiEndpointField } from './AiEndpointField';
import { AiHealthBanner } from './AiHealthBanner';
import { AiKeyField } from './AiKeyField';
import { AiLimitsFields } from './AiLimitsFields';
import { AiModelPicker } from './AiModelPicker';
import { AiProviderSelect } from './AiProviderSelect';
import { aiErrorMessageKey, READINESS_MESSAGES } from './ai-messages';
import { useAiProviderDraft } from './use-ai-provider-draft';
import { useSettingsSaveSource } from '../save/settings-save-scope';

function AiConnectionForm({ state }: { state: AiSettingsState }) {
  const { t } = useTranslation();
  const { providerId, draft, edits, changed, choose, edit, reset } = useAiProviderDraft(state);
  const saveConnection = useSaveAiConnection();
  const removeConnection = useRemoveAiConnection();
  const testConnection = useTestAiConnection();
  const confirmation = useConfirmation();
  const [errorKey, setErrorKey] = useState<string | null>(null);

  const { managed } = state;
  const configured = managed.provider || managed.apiKey || managed.model || managed.baseUrl;
  const misconfigured = configured && !managed.provider;
  const busy = saveConnection.isPending || removeConnection.isPending || testConnection.isPending;
  const locked = busy || misconfigured;
  const complete = draft !== null && canSave(draft);
  const request = draft ? draftRequest(draft) : null;
  const check = useCheckOutcome<AiTestResult>(JSON.stringify([request, draft?.storedKeyApplies]));

  const save = async (): Promise<boolean> => {
    if (!draft) return false;
    setErrorKey(null);
    try {
      await saveConnection.mutateAsync(saveRequest(draft, edits, true));
      reset();
      return true;
    } catch (error) {
      setErrorKey(aiErrorMessageKey(error, 'settings.ai.saveFailed'));
      return false;
    }
  };

  useContinueReady(changed ? complete : state.activeProvider !== null);

  useSettingsSaveSource({ isDirty: changed && !misconfigured, isValid: complete, save, reset });

  useBeforeContinue(async () => {
    if (!changed) return true;
    if (complete) return save();
    setErrorKey('settings.ai.wizardIncomplete');
    return false;
  });

  const runTest = async () => {
    if (!request) return;
    setErrorKey(null);
    await check.run(async () => testConnection.mutateAsync(request));
  };

  const remove = async () => {
    if (!draft) return;
    const confirmed = await confirmation.confirm({
      title: t('settings.ai.removeTitle'),
      description: t('settings.ai.removeConfirm', { provider: draft.provider.displayName }),
      action: t('settings.ai.remove'),
      destructive: true,
    });
    if (!confirmed) return;
    setErrorKey(null);
    try {
      await removeConnection.mutateAsync(providerId);
      reset();
    } catch (error) {
      setErrorKey(aiErrorMessageKey(error, 'settings.ai.removeFailed'));
    }
  };

  return (
    <PageForm className="max-w-md space-y-4">
      <AiProviderSelect
        providers={state.providers}
        value={providerId}
        disabled={busy || configured}
        onChange={(chosen) => {
          setErrorKey(null);
          choose(chosen);
        }}
      />

      {draft && (
        <>
          <AiEndpointField
            draft={draft}
            disabled={locked || managed.baseUrl}
            onChange={(baseUrl) => {
              edit({ baseUrl });
            }}
          />
          <AiKeyField
            key={providerId}
            draft={draft}
            disabled={locked || managed.apiKey}
            onChange={(apiKey) => {
              edit({ apiKey, clearKey: false });
            }}
            onClear={() => {
              edit({ apiKey: '', clearKey: true });
            }}
          />
          <AiModelPicker
            key={`model-${providerId}`}
            draft={draft}
            disabled={locked || managed.model}
            onChange={(model) => {
              edit({ model });
            }}
          />
          <AiConnectionTestFailure outcome={check.outcome} />
        </>
      )}

      {errorKey !== null && <Notice variant="error" announce>{t(errorKey)}</Notice>}

      {draft && (
        <div className="flex flex-wrap items-center gap-2">
          <CheckButton
            label={t('settings.ai.test')}
            pending={testConnection.isPending}
            disabled={locked || !complete}
            passed={aiConnectionTestPassed(check.outcome, !draft.dirty && (draft.saved?.verifiedAt ?? null) !== null)}
            onClick={() => {
              void runTest();
            }}
          />
          {draft.saved && !configured && (
            <Button type="button" variant="ghost" disabled={busy} onClick={() => { void remove(); }}>
              {t('settings.ai.remove')}
            </Button>
          )}
        </div>
      )}
      {confirmation.dialog}
    </PageForm>
  );
}

function OwnerSettings({ showLimits }: { showLimits: boolean }) {
  const { t } = useTranslation();
  const { data: state, isLoading, refetch } = useAiSettings(true);

  if (state) {
    return (
      <>
        <AiHealthBanner state={state} />
        <AiConnectionForm state={state} />
        {showLimits && <AiLimitsFields settings={state} />}
      </>
    );
  }

  if (isLoading) {
    return (
      <div className="flex justify-center py-4">
        <LoadingSpinner className="h-5 w-5 text-muted-foreground" />
      </div>
    );
  }

  return (
    <>
      <Notice variant="error" announce>{t('settings.ai.loadFailed')}</Notice>
      <Button
        type="button"
        variant="outline"
        size="sm"
        onClick={() => {
          void refetch();
        }}
      >
        {t('common.retry')}
      </Button>
    </>
  );
}

function ViewerStatus() {
  const { t } = useTranslation();
  const { data: status, isError } = useAiStatus(true);

  if (isError) return <Notice variant="error">{t('settings.ai.loadFailed')}</Notice>;
  if (!status) return null;

  return status.readiness === 'ready' ? (
    <Notice variant="success">
      {t('settings.ai.statusReady', { provider: status.providerName ?? '', model: status.model ?? '' })}
    </Notice>
  ) : (
    <Notice variant="warning">{t(READINESS_MESSAGES[status.readiness])}</Notice>
  );
}

export function AiProviderSettings({ showLimits = true }: { showLimits?: boolean }) {
  const { t } = useTranslation();
  const { hasPermission, isSuperAdmin } = usePermissions();

  const insideSetup = useContinueGateActive();

  if (!isSuperAdmin && !hasPermission(PERMISSIONS.ADMIN_SETTINGS_VIEW)) return null;

  return (
    <section className="space-y-4" aria-label={t('settings.ai.title')}>
      {!insideSetup && <h3 className="text-sm font-semibold">{t('settings.ai.title')}</h3>}
      {isSuperAdmin ? <OwnerSettings showLimits={showLimits} /> : <ViewerStatus />}
    </section>
  );
}
