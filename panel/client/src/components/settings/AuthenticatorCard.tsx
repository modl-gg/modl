import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useQueryClient } from '@tanstack/react-query';
import { Button } from '@/components/ui/button';
import { Notice } from '@/components/ui/notice';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { CredentialProofDialog } from '@/components/auth/CredentialProofDialog';
import { RecoveryCodesPanel } from '@/components/auth/RecoveryCodesPanel';
import { TotpEnrollment } from '@/components/auth/TotpEnrollment';
import {
  ACCOUNT_SECURITY_QUERY_KEY,
  SIGN_IN_SETTINGS_QUERY_KEY,
  useAccountSecurity,
  useDisableTotp,
  useRegenerateRecoveryCodes,
  type AccountSecurity,
} from '@/hooks/data/sign-in';
import { useAuthFailureMessage } from '@/hooks/use-auth-failure-message';
import { useCredentialProof } from '@/hooks/use-credential-proof';
import { authenticatorOperation, proofKindFor } from '@/lib/reauthentication';

function EnabledAuthenticatorActions({ security }: { security: AccountSecurity }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const failureMessage = useAuthFailureMessage();
  const disable = useDisableTotp();
  const regenerate = useRegenerateRecoveryCodes();
  const [confirmingDisable, setConfirmingDisable] = useState(false);
  const [codes, setCodes] = useState<readonly string[] | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const { run, request } = useCredentialProof((error) => setFailure(failureMessage(error)));

  const turnOff = async () => {
    setFailure(null);
    await run(proofKindFor('manage-totp', security), async (proof) => {
      await disable.mutateAsync(proof);
    });
  };

  const renewCodes = async () => {
    setFailure(null);
    await run(proofKindFor('manage-totp', security), async (proof) => {
      const issued = await regenerate.mutateAsync(proof);
      setCodes(issued);
    });
  };

  if (codes !== null) {
    return (
      <RecoveryCodesPanel
        codes={codes}
        onSaved={() => {
          setCodes(null);
          void queryClient.invalidateQueries({ queryKey: ACCOUNT_SECURITY_QUERY_KEY });
        }}
      />
    );
  }

  return (
    <div className="space-y-2">
      <p className="text-xs text-muted-foreground">
        {t('settings.authenticator.codesRemaining', { count: security.recoveryCodesRemaining })}
      </p>
      <div className="flex flex-wrap gap-2">
        <Button type="button" size="sm" variant="outline" onClick={() => { void renewCodes(); }} disabled={regenerate.isPending}>
          {t('settings.authenticator.regenerate')}
        </Button>
        <Button type="button" size="sm" variant="destructive" onClick={() => setConfirmingDisable(true)} disabled={disable.isPending}>
          {t('settings.authenticator.disable')}
        </Button>
      </div>
      {failure !== null && <Notice variant="error" announce>{failure}</Notice>}
      <AlertDialog open={confirmingDisable} onOpenChange={setConfirmingDisable}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('settings.authenticator.disable')}</AlertDialogTitle>
            <AlertDialogDescription>{t('settings.authenticator.disableConfirm')}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t('common.cancel')}</AlertDialogCancel>
            <AlertDialogAction onClick={() => { void turnOff(); }}>{t('settings.authenticator.disable')}</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
      <CredentialProofDialog request={request} />
    </div>
  );
}

export function AuthenticatorCard() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { data: security } = useAccountSecurity();

  if (!security) return null;

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ACCOUNT_SECURITY_QUERY_KEY });
    void queryClient.invalidateQueries({ queryKey: SIGN_IN_SETTINGS_QUERY_KEY });
  };

  return (
    <section className="space-y-3">
      <h3 className="text-sm font-semibold">{t('settings.authenticator.title')}</h3>
      <TotpEnrollment
        proofKind={proofKindFor(authenticatorOperation(security), security)}
        startLabel={t(security.totpEnabled ? 'settings.authenticator.replace' : 'settings.authenticator.enable')}
        onDone={refresh}
      />
      {security.totpEnabled && <EnabledAuthenticatorActions security={security} />}
    </section>
  );
}
