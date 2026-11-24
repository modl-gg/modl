import { useState } from 'react';
import { Notice } from '@/components/ui/notice';
import { PageForm } from '@/components/ui/action-form';
import { CredentialProofDialog } from '@/components/auth/CredentialProofDialog';
import { NewPasswordFields } from '@/components/auth/NewPasswordFields';
import {
  useAccountSecurity,
  useSetPassword,
  useSignInSettings,
  useUpdateSignInSettings,
  type AccountSecurity,
  type CredentialProofInput,
  type SignInMethodId,
  type SignInSettings,
} from '@/hooks/data/sign-in';
import { useAuth } from '@/hooks/use-auth';
import { useAuthFailureMessage } from '@/hooks/use-auth-failure-message';
import { useBeforeContinue, useContinueReady } from '@/hooks/use-before-continue';
import { useCredentialProof } from '@/hooks/use-credential-proof';
import { passwordOperation, proofKindFor } from '@/lib/reauthentication';
import { useNewPassword } from '@/hooks/use-new-password';
import { SignInMethodField } from '@/components/settings/SignInMethodField';
import { StepLoadState } from './StepLoadState';

function SignInChoice({ email, security, settings }: { email: string | undefined; security: AccountSecurity; settings: SignInSettings }) {
  const failureMessage = useAuthFailureMessage();
  const savePassword = useSetPassword();
  const saveMethod = useUpdateSignInSettings();
  const [method, setMethod] = useState<SignInMethodId>(settings.method);
  const newPassword = useNewPassword();
  const [failure, setFailure] = useState<string | null>(null);
  const { run, request } = useCredentialProof((error) => setFailure(failureMessage(error)));
  const needsNewPassword = method === 'password' && !security.hasPassword;

  const apply = async (proof: CredentialProofInput) => {
    if (needsNewPassword) {
      await savePassword.mutateAsync({ newPassword: newPassword.password, proof });
    }
    if (method !== settings.method) {
      await saveMethod.mutateAsync({ expectedVersion: settings.version, method, secondFactorRequired: settings.secondFactorRequired });
    }
  };

  useContinueReady(!needsNewPassword || newPassword.ready);

  useBeforeContinue(async () => {
    setFailure(null);
    return run(proofKindFor(passwordOperation(security), security), apply);
  });

  return (
    <PageForm className="max-w-md space-y-6">
      <SignInMethodField value={method} disabled={savePassword.isPending || saveMethod.isPending} described onChange={setMethod} />
      {needsNewPassword && (
        <NewPasswordFields control={newPassword.fields} account={email} />
      )}
      {failure !== null && <Notice variant="error" announce>{failure}</Notice>}
      <CredentialProofDialog request={request} />
    </PageForm>
  );
}

export function AccountStep() {
  const { user } = useAuth();
  const security = useAccountSecurity();
  const settings = useSignInSettings();

  if (!security.data || !settings.data) {
    return (
      <StepLoadState
        isError={security.isError || settings.isError}
        onRetry={() => {
          void security.refetch();
          void settings.refetch();
        }}
      />
    );
  }
  return <SignInChoice email={user?.email} security={security.data} settings={settings.data} />;
}
