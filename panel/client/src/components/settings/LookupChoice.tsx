import { useId, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { PageForm } from '@/components/ui/action-form';
import { Button } from '@/components/ui/button';
import { CheckButton } from '@/components/ui/check-button';
import { Field } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Notice } from '@/components/ui/notice';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { useConfirmation } from '@/hooks/use-confirmation';
import { checkPassed, useCheckOutcome } from '@/hooks/use-check-outcome';
import {
  KEYED_IP_LOOKUP_PROVIDERS,
  KEYLESS_IP_LOOKUP_PROVIDER,
  isKeyedProvider,
  isProviderId,
  useRemovePlayerAddressKey,
  useSavePlayerAddressKey,
  useTestPlayerAddressLookup,
  useUpdatePlayerAddressSettings,
  type KeyedIpLookupProviderId,
  type LookupTestRequest,
  type LookupTestResult as LookupTestResultData,
  type PlayerAddressSettings,
} from '@/hooks/data/player-addresses';
import { LookupTestResult } from './LookupTestResult';
import { LOOKUP_PROVIDER_NAMES, LOOKUP_PROVIDER_SIGN_UP } from './lookup-providers';
import { usePlayerAddressFailureMessage, usePlayerAddressFailureToast } from './use-player-address-failure';
import { useSettingsDraft } from './save/use-settings-draft';
import { useBeforeContinue, useContinueReady } from '@/hooks/use-before-continue';

const LOOKUP_CHOICES = ['off', 'keyless', 'keyed'] as const;
type LookupChoiceId = (typeof LOOKUP_CHOICES)[number];

const CHOICE_LABEL_KEYS: Record<LookupChoiceId, string> = {
  off: 'settings.playerAddresses.choiceOff',
  keyless: 'settings.playerAddresses.choiceKeyless',
  keyed: 'settings.playerAddresses.choiceKeyed',
};

const CHOICE_HINT_KEYS: Partial<Record<LookupChoiceId, string>> = {
  keyless: 'settings.playerAddresses.keylessLimit',
};

const KEY_MAX_LENGTH = 256;

function chosenLookup(settings: PlayerAddressSettings): LookupChoiceId | null {
  if (settings.provider === KEYLESS_IP_LOOKUP_PROVIDER) return 'keyless';
  if (settings.provider !== null) return 'keyed';
  return settings.lookupChoicePending ? null : 'off';
}

function isLookupChoice(value: string): value is LookupChoiceId {
  return LOOKUP_CHOICES.some((choice) => choice === value);
}

function useLookupTest(request: LookupTestRequest) {
  const test = useTestPlayerAddressLookup();
  const check = useCheckOutcome<LookupTestResultData>(JSON.stringify(request));
  return {
    request,
    outcome: check.outcome,
    pending: test.isPending,
    run: () => { void check.run(async () => test.mutateAsync(request)); },
  };
}

type LookupTest = ReturnType<typeof useLookupTest>;

function LookupTestButton({ lookupTest, disabled }: { lookupTest: LookupTest; disabled: boolean }) {
  const { t } = useTranslation();
  return (
    <CheckButton
      label={t('settings.playerAddresses.test')}
      pending={lookupTest.pending}
      disabled={disabled}
      passed={checkPassed(lookupTest.outcome, (result) => result.outcome === 'working')}
      onClick={lookupTest.run}
    />
  );
}

function LookupTestFeedback({ lookupTest }: { lookupTest: LookupTest }) {
  const { t } = useTranslation();
  const { outcome } = lookupTest;
  if (outcome === null) return null;
  if (outcome.kind === 'error') return <Notice variant="error" announce>{t('settings.playerAddresses.testFailed')}</Notice>;
  return <LookupTestResult result={outcome.result} provider={lookupTest.request.provider} />;
}

interface LookupDraft {
  choice: LookupChoiceId | null;
  provider: KeyedIpLookupProviderId | null;
  key: string;
}

function activeKeyedProvider(settings: PlayerAddressSettings): KeyedIpLookupProviderId | null {
  return settings.provider !== null && isKeyedProvider(settings.provider) ? settings.provider : null;
}

function lookupDraftOf(settings: PlayerAddressSettings): LookupDraft {
  return { choice: chosenLookup(settings), provider: activeKeyedProvider(settings), key: '' };
}

function hasStoredKey(settings: PlayerAddressSettings, provider: KeyedIpLookupProviderId): boolean {
  return settings.providersWithKeys.includes(provider);
}

function isCompleteChoice(draft: LookupDraft, settings: PlayerAddressSettings): boolean {
  if (draft.choice !== 'keyed') return draft.choice !== null;
  return draft.provider !== null && (hasStoredKey(settings, draft.provider) || draft.key.trim() !== '');
}

function useLookupDraft(settings: PlayerAddressSettings) {
  const update = useUpdatePlayerAddressSettings();
  const saveKey = useSavePlayerAddressKey();
  const describeFailure = usePlayerAddressFailureMessage();

  return useSettingsDraft({
    snapshot: settings,
    fallback: lookupDraftOf(settings),
    valueOf: lookupDraftOf,
    isValid: (draft) => isCompleteChoice(draft, settings),
    describeRejection: describeFailure,
    save: async (draft, base) => {
      switch (draft.choice) {
        case null:
          return null;
        case 'off':
          return update.mutateAsync({ provider: null });
        case 'keyless':
          return update.mutateAsync({ provider: KEYLESS_IP_LOOKUP_PROVIDER });
        case 'keyed': {
          const provider = draft.provider;
          if (provider === null) return null;
          const typedKey = draft.key.trim();
          const withKey = typedKey === '' ? base : await saveKey.mutateAsync({ provider, apiKey: typedKey });
          return withKey.provider === provider ? withKey : update.mutateAsync({ provider });
        }
      }
    },
  });
}

interface ProviderKeyRowProps {
  provider: KeyedIpLookupProviderId;
  hasKey: boolean;
  typedKey: string;
  onKeyChange: (key: string) => void;
}

function ProviderKeyRow({ provider, hasKey, typedKey, onKeyChange }: ProviderKeyRowProps) {
  const { t } = useTranslation();
  const removeKey = useRemovePlayerAddressKey();
  const showFailure = usePlayerAddressFailureToast();
  const confirmation = useConfirmation();
  const trimmedKey = typedKey.trim();
  const lookupTest = useLookupTest(trimmedKey.length > 0 ? { provider, apiKey: trimmedKey } : { provider });

  const confirmRemoveKey = async () => {
    const confirmed = await confirmation.confirm({
      title: t('settings.playerAddresses.removeKeyTitle'),
      description: t('settings.playerAddresses.removeKeyDesc', { provider: LOOKUP_PROVIDER_NAMES[provider] }),
      action: t('settings.playerAddresses.removeKey'),
      destructive: true,
    });
    if (confirmed) removeKey.mutate(provider, { onError: showFailure });
  };

  return (
    <PageForm className="space-y-2">
      <Field label={t('settings.playerAddresses.keyLabel')}>
        {(control) => (
          <div className="flex max-w-xl flex-wrap gap-2">
            <Input
              {...control}
              type="password"
              className="min-w-0 flex-1 basis-56"
              value={typedKey}
              autoComplete="off"
              spellCheck={false}
              maxLength={KEY_MAX_LENGTH}
              placeholder={hasKey ? t('settings.playerAddresses.keyPlaceholder') : undefined}
              onChange={(event) => {
                onKeyChange(event.target.value);
              }}
            />
            <LookupTestButton lookupTest={lookupTest} disabled={!hasKey && trimmedKey.length === 0} />
            {hasKey && (
              <Button type="button" variant="ghost" disabled={removeKey.isPending} onClick={() => { void confirmRemoveKey(); }}>
                {t('settings.playerAddresses.removeKey')}
              </Button>
            )}
          </div>
        )}
      </Field>
      <a
        href={LOOKUP_PROVIDER_SIGN_UP[provider]}
        target="_blank"
        rel="noopener noreferrer"
        className="inline-block text-xs text-primary hover:underline"
      >
        {t('settings.playerAddresses.getKey')}
      </a>
      <LookupTestFeedback lookupTest={lookupTest} />
      {confirmation.dialog}
    </PageForm>
  );
}

interface KeyedProviderSectionProps {
  settings: PlayerAddressSettings;
  draft: LookupDraft;
  onChange: (changes: Partial<LookupDraft>) => void;
}

function KeyedProviderSection({ settings, draft, onChange }: KeyedProviderSectionProps) {
  const { t } = useTranslation();
  const picked = draft.provider;

  return (
    <div className="space-y-4 pl-6">
      <Field label={t('settings.playerAddresses.providerLabel')}>
        {(control) => (
          <Select
            value={picked ?? ''}
            onValueChange={(value) => {
              if (isProviderId(value) && isKeyedProvider(value)) onChange({ provider: value, key: '' });
            }}
          >
            <SelectTrigger {...control} className="max-w-xs">
              <SelectValue placeholder={t('settings.playerAddresses.providerPlaceholder')} />
            </SelectTrigger>
            <SelectContent>
              {KEYED_IP_LOOKUP_PROVIDERS.map((provider) => (
                <SelectItem key={provider} value={provider}>{LOOKUP_PROVIDER_NAMES[provider]}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        )}
      </Field>
      {picked !== null && (
        <ProviderKeyRow
          key={picked}
          provider={picked}
          hasKey={hasStoredKey(settings, picked)}
          typedKey={draft.key}
          onKeyChange={(key) => { onChange({ key }); }}
        />
      )}
    </div>
  );
}

function KeylessSection() {
  const lookupTest = useLookupTest({ provider: KEYLESS_IP_LOOKUP_PROVIDER });
  return (
    <div className="space-y-2 pl-6">
      <LookupTestButton lookupTest={lookupTest} disabled={false} />
      <LookupTestFeedback lookupTest={lookupTest} />
    </div>
  );
}

function LookupChoiceOption({ id, choice, children }: { id: string; choice: LookupChoiceId; children: ReactNode }) {
  const { t } = useTranslation();
  const hintKey = CHOICE_HINT_KEYS[choice];
  const hintId = `${id}-hint`;
  return (
    <div className="space-y-3">
      <div className="space-y-1">
        <div className="flex items-center gap-2">
          <RadioGroupItem id={id} value={choice} aria-describedby={hintKey === undefined ? undefined : hintId} />
          <Label htmlFor={id}>{t(CHOICE_LABEL_KEYS[choice])}</Label>
        </div>
        {hintKey !== undefined && <p id={hintId} className="pl-6 text-xs text-muted-foreground">{t(hintKey)}</p>}
      </div>
      {children}
    </div>
  );
}

export function LookupChoice({ settings }: { settings: PlayerAddressSettings }) {
  const { t } = useTranslation();
  const groupId = useId();
  const draft = useLookupDraft(settings);
  const chosen = draft.value.choice;
  const change = (changes: Partial<LookupDraft>) => {
    draft.update((current) => ({ ...current, ...changes }));
  };

  useContinueReady(draft.isDirty ? draft.isValid : !settings.lookupChoicePending);

  useBeforeContinue(async () => (draft.isDirty ? draft.save() : true));

  return (
    <div className="space-y-4">
      <RadioGroup
        aria-label={t('settings.playerAddresses.choiceTitle')}
        value={chosen ?? ''}
        onValueChange={(value) => {
          if (isLookupChoice(value)) change({ choice: value });
        }}
      >
        {LOOKUP_CHOICES.map((choice) => (
          <LookupChoiceOption key={choice} id={`${groupId}-${choice}`} choice={choice}>
            {chosen === choice && choice === 'keyless' && <KeylessSection />}
            {chosen === choice && choice === 'keyed' && <KeyedProviderSection settings={settings} draft={draft.value} onChange={change} />}
          </LookupChoiceOption>
        ))}
      </RadioGroup>
      <p className="text-xs text-muted-foreground">{t('settings.playerAddresses.thirdPartyNote')}</p>
    </div>
  );
}
