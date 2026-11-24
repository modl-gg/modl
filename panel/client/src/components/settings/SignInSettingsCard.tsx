import { useTranslation } from 'react-i18next';
import { Label } from '@/components/ui/label';
import { Notice } from '@/components/ui/notice';
import { Switch } from '@/components/ui/switch';
import { useSignInSettings, useUpdateSignInSettings, type SignInMethodId, type SignInSettings } from '@/hooks/data/sign-in';
import { useAuthFailureMessage } from '@/hooks/use-auth-failure-message';
import { useEmailDelivery } from '@/hooks/use-public-settings';
import { SignInMethodField } from './SignInMethodField';
import { StaffSecurityList } from './StaffSecurityList';
import { SettingsLoading, SettingsSection, SettingsSections } from './layout/SettingsSections';
import { useConfirmation } from '@/hooks/use-confirmation';
import { useSettingsDraft } from './save/use-settings-draft';

interface SignInPolicy {
  method: SignInMethodId;
  secondFactorRequired: boolean;
}

const EMAIL_CODE_POLICY: SignInPolicy = { method: 'email-code', secondFactorRequired: false };

function policyOf(settings: SignInSettings): SignInPolicy {
  return { method: settings.method, secondFactorRequired: settings.secondFactorRequired };
}

export function SignInSettingsCard() {
  const { t } = useTranslation();
  const failureMessage = useAuthFailureMessage();
  const { data: settings } = useSignInSettings();
  const update = useUpdateSignInSettings();
  const confirmation = useConfirmation();
  const emailDelivery = useEmailDelivery();

  const draft = useSettingsDraft({
    snapshot: settings,
    fallback: EMAIL_CODE_POLICY,
    valueOf: policyOf,
    describeRejection: failureMessage,
    save: async (policy, base) => {
      if (policy.method === 'password' && base.method !== 'password') {
        const confirmed = await confirmation.confirm({
          title: t('settings.signIn.switchPasswordTitle'),
          description: t('settings.signIn.switchPasswordDesc', { number: base.withoutPasswordCount }),
          action: t('settings.saveBar.save'),
        });
        if (!confirmed) return null;
      }
      return update.mutateAsync({ expectedVersion: base.version, ...policy });
    },
  });

  if (settings === undefined) return <SettingsLoading />;

  return (
    <SettingsSections>
      <SettingsSection title={t('settings.signIn.title')}>
        <SignInMethodField
          value={draft.value.method}
          onChange={(method) => { draft.update((current) => ({ ...current, method })); }}
        />
        {!emailDelivery && <p className="text-xs text-muted-foreground">{t('settings.signIn.switchEmailDesc')}</p>}
      </SettingsSection>
      <SettingsSection>
        <div className="flex items-start justify-between gap-4">
          <div className="space-y-1">
            <Label htmlFor="require-second-factor">{t('settings.signIn.requireTitle')}</Label>
            <p className="text-xs text-muted-foreground">{t('settings.signIn.requireDesc', { number: settings.withoutSecondFactorCount })}</p>
          </div>
          <Switch
            id="require-second-factor"
            checked={draft.value.secondFactorRequired}
            onCheckedChange={(secondFactorRequired) => { draft.update((current) => ({ ...current, secondFactorRequired })); }}
          />
        </div>
        <Notice variant="warning">{t('settings.signIn.noRecovery')}</Notice>
      </SettingsSection>
      <StaffSecurityList />
      {confirmation.dialog}
    </SettingsSections>
  );
}
