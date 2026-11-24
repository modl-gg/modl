import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useQueryClient } from '@tanstack/react-query';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { SignInBrand } from '@/components/brand/BrandLogo';
import { Notice } from '@/components/ui/notice';
import { useAuth } from '@/hooks/use-auth';
import { useFinishPasskeyRegistration, useStartPasskeyRegistration } from '@/hooks/data/passkeys';
import { useAccountSecurity } from '@/hooks/data/sign-in';
import { LoadingSpinner } from '@/components/ui/loading-spinner';
import { proofKindFor, type ProofFactors } from '@/lib/reauthentication';
import { useAuthFailureMessage } from '@/hooks/use-auth-failure-message';
import { useCredentialProof } from '@/hooks/use-credential-proof';
import { usePasskeySignIn } from '@/hooks/use-passkey-sign-in';
import { arePasskeysAvailable } from '@/lib/passkeys';
import { isWebAuthnCancellation } from '@/utils/webauthn';
import { CredentialProofDialog } from './CredentialProofDialog';
import { TotpEnrollment } from './TotpEnrollment';

function useContinueWithFullAccess(): () => void {
  const queryClient = useQueryClient();
  return () => {
    void queryClient.invalidateQueries();
  };
}

function EnrollmentOptions({ factors }: { factors: ProofFactors }) {
  const { t } = useTranslation();
  const proofKind = proofKindFor('add-credential', factors);
  const proceed = useContinueWithFullAccess();
  const failureMessage = useAuthFailureMessage();
  const start = useStartPasskeyRegistration();
  const finish = useFinishPasskeyRegistration();
  const [failure, setFailure] = useState<string | null>(null);
  const { run, request } = useCredentialProof((error) => {
    if (!isWebAuthnCancellation(error)) setFailure(failureMessage(error));
  });

  const addPasskey = async () => {
    setFailure(null);
    await run(proofKind, async (proof) => {
      const registration = await start.mutateAsync(proof);
      await finish.mutateAsync({ ...registration, name: '' });
      proceed();
    });
  };

  return (
    <div className="space-y-4">
      <TotpEnrollment proofKind={proofKind} startLabel={t('settings.authenticator.enable')} onDone={proceed} />
      {arePasskeysAvailable() ? (
        <Button type="button" variant="outline" onClick={() => { void addPasskey(); }} disabled={start.isPending || finish.isPending}>
          {t('pages.secondFactor.usePasskey')}
        </Button>
      ) : (
        <Notice variant="info">{t('pages.secondFactor.passkeyNeedsHttps')}</Notice>
      )}
      {failure !== null && <Notice variant="error" announce>{failure}</Notice>}
      <CredentialProofDialog request={request} />
    </div>
  );
}

function Enrollment() {
  const { data: security, isError, refetch } = useAccountSecurity();
  const { t } = useTranslation();

  if (security !== undefined) return <EnrollmentOptions factors={security} />;
  if (isError) {
    return (
      <Notice variant="error" announce>
        {t('common.unknownError')}{' '}
        <Button type="button" variant="link" className="h-auto p-0" onClick={() => { void refetch(); }}>{t('common.retry')}</Button>
      </Notice>
    );
  }
  return <LoadingSpinner className="mx-auto h-6 w-6 text-muted-foreground" />;
}

function VerificationOptions() {
  const { t } = useTranslation();
  const { user } = useAuth();
  const proceed = useContinueWithFullAccess();
  const passkey = usePasskeySignIn(proceed);

  if (!arePasskeysAvailable()) return <Notice variant="info">{t('pages.secondFactor.passkeyNeedsHttps')}</Notice>;

  return (
    <Button type="button" className="w-full" disabled={passkey.pending} onClick={() => { void passkey.signIn(user?.email ?? ''); }}>
      {t('pages.secondFactor.usePasskey')}
    </Button>
  );
}

export function SecondFactorGate() {
  const { t } = useTranslation();
  const { user, logout } = useAuth();
  const enrolling = user?.secondFactor === 'enrollment-required';

  return (
    <main className="flex min-h-screen items-center justify-center bg-background p-4">
      <div className="w-full max-w-md">
        <SignInBrand />
        <Card className="shadow-card">
          <CardHeader>
            <CardTitle>
              <h1>{t(enrolling ? 'pages.secondFactor.enrollTitle' : 'pages.secondFactor.verifyTitle')}</h1>
            </CardTitle>
            <CardDescription>{t(enrolling ? 'pages.secondFactor.enrollDesc' : 'pages.secondFactor.verifyDesc')}</CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            {enrolling ? <Enrollment /> : <VerificationOptions />}
            <Button type="button" variant="ghost" className="w-full" onClick={logout}>{t('pages.secondFactor.signOut')}</Button>
          </CardContent>
        </Card>
      </div>
    </main>
  );
}
