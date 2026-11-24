import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { Notice } from '@/components/ui/notice';
import { CredentialProofDialog } from '@/components/auth/CredentialProofDialog';
import { NewPasswordFields } from '@/components/auth/NewPasswordFields';
import { useAccountSecurity, useSetPassword, type AccountSecurity } from '@/hooks/data/sign-in';
import { useAuth } from '@/hooks/use-auth';
import { useAuthFailureMessage } from '@/hooks/use-auth-failure-message';
import { useCredentialProof } from '@/hooks/use-credential-proof';
import { passwordOperation, proofKindFor } from '@/lib/reauthentication';
import { useNewPassword } from '@/hooks/use-new-password';
import { useToast } from '@/hooks/use-toast';

function PasswordEditor({ security }: { security: AccountSecurity }) {
  const { t } = useTranslation();
  const { toast } = useToast();
  const { user } = useAuth();
  const failureMessage = useAuthFailureMessage();
  const savePassword = useSetPassword();
  const [editing, setEditing] = useState(false);
  const newPassword = useNewPassword();
  const [failure, setFailure] = useState<string | null>(null);
  const { run, request } = useCredentialProof((error) => setFailure(failureMessage(error)));

  const close = () => {
    setEditing(false);
    newPassword.clear();
    setFailure(null);
  };

  const save = async () => {
    setFailure(null);
    await run(proofKindFor(passwordOperation(security), security), async (proof) => {
      await savePassword.mutateAsync({ newPassword: newPassword.password, proof });
      close();
      toast({ title: t('settings.password.saved') });
    });
  };

  return (
    <>
      {editing ? (
        <form
          className="space-y-3"
          onSubmit={(event) => {
            event.preventDefault();
            void save();
          }}
        >
          <NewPasswordFields control={newPassword.fields} account={user?.email} />
          {failure !== null && <Notice variant="error" announce>{failure}</Notice>}
          <div className="flex gap-2">
            <Button type="submit" size="sm" disabled={savePassword.isPending || !newPassword.ready}>
              {t('common.save')}
            </Button>
            <Button type="button" size="sm" variant="ghost" onClick={close}>{t('common.cancel')}</Button>
          </div>
        </form>
      ) : (
        <Button type="button" size="sm" variant="outline" onClick={() => setEditing(true)}>
          {t(security.hasPassword ? 'settings.password.change' : 'settings.password.set')}
        </Button>
      )}
      <CredentialProofDialog request={request} />
    </>
  );
}

export function PasswordCard() {
  const { t } = useTranslation();
  const { data: security } = useAccountSecurity();

  if (!security) return null;

  return (
    <section className="space-y-3">
      <h3 className="text-sm font-semibold">{t('settings.password.title')}</h3>
      {security.method !== 'password' && <p className="text-xs text-muted-foreground">{t('settings.password.inactive')}</p>}
      <PasswordEditor security={security} />
    </section>
  );
}
