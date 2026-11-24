import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { CredentialProofDialog } from '@/components/auth/CredentialProofDialog';
import { useAccountSecurity } from '@/hooks/data/sign-in';
import { useConfirmEmailChange, useSendEmailChangeCode } from '@/hooks/data/account';
import { useAuth } from '@/hooks/use-auth';
import { useCredentialProof } from '@/hooks/use-credential-proof';
import { useEmailDelivery } from '@/hooks/use-public-settings';
import { useToast } from '@/hooks/use-toast';
import { codeDeliveryHintKey } from '@/lib/code-delivery';
import { proofKindFor, type ProofFactors } from '@/lib/reauthentication';
import { navigationAllowed } from '@/lib/guarded-location';
import { describeError } from '@/lib/http-error';
import { PANEL_SIGN_IN_PATH } from '@/lib/return-path';
import { SUPPORTED_LANGUAGES } from '@/lib/languages';
import { PRETTY_DATE_FORMAT, formatPrettyDateTime } from '@/utils/date-utils';
import MinecraftAccountSettings from './MinecraftAccountSettings';
import { FIELD_WIDTH, SettingsSection, SettingsSections } from './layout/SettingsSections';
import { useProfilePreferencesDraft } from './use-account-profile-state';

const DATE_FORMAT_PATTERNS = ['MM/DD/YYYY', 'DD/MM/YYYY', 'YYYY-MM-DD'] as const;

const DATE_FORMAT_SAMPLE = new Date(2026, 0, 15, 12, 0);

const EMAIL_CHANGE_RELOAD_DELAY_MS = 1000;

function EmailField() {
  const { t } = useTranslation();
  const { toast } = useToast();
  const { user } = useAuth();
  const [emailOverride, setEmailOverride] = useState<string | null>(null);
  const [codeSent, setCodeSent] = useState(false);
  const [code, setCode] = useState('');
  const [awaitingReload, setAwaitingReload] = useState(false);
  const emailDelivery = useEmailDelivery();
  const sendCode = useSendEmailChangeCode();
  const confirmChange = useConfirmEmailChange();
  const { data: security } = useAccountSecurity();
  const email = emailOverride ?? user?.email ?? '';
  const changed = email.trim() !== '' && email.trim() !== user?.email;
  const confirming = confirmChange.isPending || awaitingReload;
  const canConfirm = security !== undefined && !confirming && code !== '';

  const showError = (error: unknown) => {
    toast({ title: t('toast.error'), description: describeError(error, t('settings.emailUpdateFailed')), variant: 'destructive' });
  };
  const { run, request: proofRequest } = useCredentialProof(showError);

  const cancel = () => {
    setCodeSent(false);
    setCode('');
  };

  const requestCode = () => {
    sendCode.mutate(email.trim(), {
      onSuccess: () => {
        setCodeSent(true);
      },
      onError: showError,
    });
  };

  const confirm = (factors: ProofFactors) => {
    void run(proofKindFor('change-email', factors), async (proof) => {
      await confirmChange.mutateAsync({ newEmail: email.trim(), code, proof });
      toast({ title: t('settings.emailUpdated') });
      cancel();
      setAwaitingReload(true);
      setTimeout(() => { window.location.reload(); }, EMAIL_CHANGE_RELOAD_DELAY_MS);
    });
  };

  return (
    <div className="space-y-2">
      <Label htmlFor="email-address">{t('settings.email')}</Label>
      <div className={`flex items-center gap-2 ${FIELD_WIDTH.text}`}>
        <Input
          id="email-address"
          type="email"
          value={email}
          onChange={(event) => {
            setEmailOverride(event.target.value);
            cancel();
          }}
          disabled={codeSent}
        />
        {!codeSent && (
          <Button variant="outline" onClick={requestCode} disabled={!changed || sendCode.isPending}>
            {t('common.update')}
          </Button>
        )}
      </div>
      {codeSent && (
        <div className="space-y-2">
          <div className="flex flex-wrap items-center gap-2">
            <Input
              value={code}
              onChange={(event) => { setCode(event.target.value); }}
              placeholder={t('settings.emailCodePlaceholder')}
              aria-label={t('settings.emailCodePlaceholder')}
              className={FIELD_WIDTH.number}
              onKeyDown={(event) => { if (event.key === 'Enter' && canConfirm) confirm(security); }}
            />
            <Button variant="outline" onClick={() => { if (security) confirm(security); }} disabled={!canConfirm}>
              {t('settings.verifyEmailCode')}
            </Button>
            <Button variant="ghost" onClick={cancel}>{t('common.cancel')}</Button>
          </div>
          <p className={`text-xs text-muted-foreground ${FIELD_WIDTH.text}`}>{t(codeDeliveryHintKey(emailDelivery))}</p>
          <p className={`text-xs text-amber-600 dark:text-amber-500 ${FIELD_WIDTH.text}`}>{t('settings.emailChangeWarning')}</p>
        </div>
      )}
      <CredentialProofDialog request={proofRequest} />
    </div>
  );
}

function PreferenceFields() {
  const { t } = useTranslation();
  const preferences = useProfilePreferencesDraft();
  const { username, language, dateFormat } = preferences.value;

  return (
    <>
      <div className="space-y-2">
        <Label htmlFor="username">{t('settings.panelDisplayName')}</Label>
        <Input
          id="username"
          value={username}
          onChange={(event) => { preferences.update((current) => ({ ...current, username: event.target.value })); }}
          placeholder={t('settings.displayNamePlaceholder')}
          aria-invalid={username.trim() === ''}
          className={FIELD_WIDTH.text}
        />
      </div>
      <EmailField />
      <div className="space-y-2">
        <Label htmlFor="language">{t('settings.language')}</Label>
        <Select value={language} onValueChange={(next) => { preferences.update((current) => ({ ...current, language: next })); }}>
          <SelectTrigger id="language" className={FIELD_WIDTH.short}>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {SUPPORTED_LANGUAGES.map((supported) => (
              <SelectItem key={supported.code} value={supported.code}>{supported.nativeName}</SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
      <div className="space-y-2">
        <Label htmlFor="date-format">{t('settings.dateFormat')}</Label>
        <Select value={dateFormat} onValueChange={(next) => { preferences.update((current) => ({ ...current, dateFormat: next })); }}>
          <SelectTrigger id="date-format" className={FIELD_WIDTH.short}>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={PRETTY_DATE_FORMAT}>{formatPrettyDateTime(DATE_FORMAT_SAMPLE)}</SelectItem>
            {DATE_FORMAT_PATTERNS.map((pattern) => (
              <SelectItem key={pattern} value={pattern}>{`${pattern} HH:mm`}</SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
    </>
  );
}

export default function ProfileSettings() {
  const { t } = useTranslation();
  const { logout } = useAuth();

  return (
    <SettingsSections>
      <SettingsSection>
        <PreferenceFields />
      </SettingsSection>
      <SettingsSection title={t('settings.minecraftAccount.title')}>
        <MinecraftAccountSettings />
      </SettingsSection>
      <SettingsSection>
        <div>
          <Button variant="outline" onClick={() => { if (navigationAllowed(PANEL_SIGN_IN_PATH)) logout(); }}>{t('common.signOut')}</Button>
        </div>
      </SettingsSection>
    </SettingsSections>
  );
}
