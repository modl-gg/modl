import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Pencil, Plus, Trash2, Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { ActionForm } from '@/components/ui/action-form';
import { IconAction } from '@/components/ui/icon-action';
import { Input } from '@/components/ui/input';
import { useToast } from '@/hooks/use-toast';
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
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
import { arePasskeysAvailable } from '@/lib/passkeys';
import { CredentialProofDialog } from '@/components/auth/CredentialProofDialog';
import { useAccountSecurity } from '@/hooks/data/sign-in';
import { useCredentialProof } from '@/hooks/use-credential-proof';
import { proofKindFor, type ProofFactors } from '@/lib/reauthentication';
import { authFailureKey } from '@/lib/auth-errors';
import { apiErrorCode, describeError } from '@/lib/http-error';
import {
  classifyPasskeyRegistrationError,
  useDeletePasskey,
  useFinishPasskeyRegistration,
  usePasskeyCredentials,
  useRenamePasskey,
  useStartPasskeyRegistration,
  type PasskeyCredential,
  type PendingPasskeyRegistration,
} from '@/hooks/data/passkeys';

const PasskeyManager = () => {
  const { t } = useTranslation();
  const { toast } = useToast();
  const { data: credentials = [], isLoading: loading } = usePasskeyCredentials();
  const startRegistration = useStartPasskeyRegistration();
  const finishRegistration = useFinishPasskeyRegistration();
  const renamePasskey = useRenamePasskey();
  const deletePasskey = useDeletePasskey();
  const registering = startRegistration.isPending;
  const [nameDialogOpen, setNameDialogOpen] = useState(false);
  const [pendingRegistration, setPendingRegistration] = useState<PendingPasskeyRegistration | null>(null);
  const [credentialName, setCredentialName] = useState('');
  const [renameDialogOpen, setRenameDialogOpen] = useState(false);
  const [renameTarget, setRenameTarget] = useState<PasskeyCredential | null>(null);
  const [renameName, setRenameName] = useState('');
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<PasskeyCredential | null>(null);

  const { data: security } = useAccountSecurity();
  const { run, request: proofRequest } = useCredentialProof((error) => {
    switch (classifyPasskeyRegistrationError(error)) {
      case 'cancelled':
        return;
      case 'not-started':
        toast({ title: t('toast.error'), description: t('settings.passkey.startRegistrationFailed'), variant: 'destructive' });
        return;
      case 'failed':
        toast({ title: t('toast.error'), description: t('settings.passkey.registrationCancelledOrFailed'), variant: 'destructive' });
    }
  });

  const addPasskey = (factors: ProofFactors) => {
    void run(proofKindFor('add-credential', factors), async (proof) => {
      const registration = await startRegistration.mutateAsync(proof);
      setPendingRegistration(registration);
      setCredentialName('');
      setNameDialogOpen(true);
    });
  };

  const handleFinishRegistration = () => {
    if (!pendingRegistration) return;

    finishRegistration.mutate({
      ...pendingRegistration,
      name: credentialName || t('settings.passkey.defaultPasskeyName'),
    }, {
      onError: (error) => {
        toast({ title: t('toast.error'), description: describeError(error, t('settings.passkey.registerFailed')), variant: 'destructive' });
      },
    });
    setNameDialogOpen(false);
    setPendingRegistration(null);
  };

  const handleRename = () => {
    if (!renameTarget || !renameName.trim()) return;

    renamePasskey.mutate({ credentialId: renameTarget.id, name: renameName.trim() }, {
      onError: () => {
        toast({ title: t('toast.error'), description: t('settings.passkey.renameFailed'), variant: 'destructive' });
      },
    });
    setRenameDialogOpen(false);
    setRenameTarget(null);
  };

  const handleDelete = () => {
    if (!deleteTarget) return;

    deletePasskey.mutate(deleteTarget.id, {
      onError: (error) => {
        const key = authFailureKey(apiErrorCode(error), 'settings.passkey.removeFailed');
        toast({ title: t('toast.error'), description: t(key), variant: 'destructive' });
      },
    });
    setDeleteDialogOpen(false);
    setDeleteTarget(null);
  };

  const formatDate = (dateStr: string | null) => {
    if (!dateStr) return t('settings.passkey.never');
    return new Date(dateStr).toLocaleDateString(undefined, {
      year: 'numeric', month: 'short', day: 'numeric',
    });
  };

  return (
    <section className="space-y-3">
      <div className="flex items-center justify-between gap-4">
        <h3 className="text-sm font-semibold">{t('settings.passkey.passkeys')}</h3>
        <IconAction
          icon={registering ? Loader2 : Plus}
          iconClassName={registering ? 'animate-spin' : undefined}
          label={t('settings.passkey.addPasskey')}
          disabled={registering || !security}
          onClick={() => { if (security) addPasskey(security); }}
        />
      </div>

      {loading ? (
        <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" aria-hidden="true" />
      ) : credentials.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          {t('settings.passkey.noPasskeys')}
        </p>
      ) : (
        <div className="space-y-2">
          {credentials.map((cred) => (
            <div
              key={cred.id}
              className="flex items-center justify-between gap-3 rounded-md border px-3 py-2"
            >
              <div className="min-w-0">
                <p className="text-sm font-medium truncate">{cred.name}</p>
                <p className="text-xs text-muted-foreground">
                  {t('settings.passkey.addedDate', { date: formatDate(cred.createdAt) })} · {t('settings.passkey.lastUsed', { date: formatDate(cred.lastUsedAt) })}
                </p>
              </div>
              <div className="flex items-center gap-1">
                <IconAction
                  icon={Pencil}
                  label={t('settings.passkey.renamePasskey')}
                  onClick={() => {
                    setRenameTarget(cred);
                    setRenameName(cred.name);
                    setRenameDialogOpen(true);
                  }}
                />
                <IconAction
                  icon={Trash2}
                  label={t('settings.passkey.removePasskey')}
                  destructive
                  onClick={() => {
                    setDeleteTarget(cred);
                    setDeleteDialogOpen(true);
                  }}
                />
              </div>
            </div>
          ))}
        </div>
      )}

      <Dialog open={nameDialogOpen} onOpenChange={(open) => {
        if (!open) {
          setPendingRegistration(null);
          setCredentialName('');
        }
        setNameDialogOpen(open);
      }}>
        <DialogContent aria-describedby={undefined}>
          <ActionForm
            className="space-y-4"
            action={{ submit: handleFinishRegistration, canSubmit: pendingRegistration !== null && !finishRegistration.isPending }}
          >
            <DialogHeader>
              <DialogTitle>{t('settings.passkey.nameYourPasskey')}</DialogTitle>
            </DialogHeader>
            <Input
              value={credentialName}
              onChange={(e) => setCredentialName(e.target.value)}
              placeholder={t('settings.passkey.passkeyNamePlaceholder')}
              aria-label={t('settings.passkey.nameYourPasskey')}
            />
            <DialogFooter>
              <Button variant="outline" onClick={() => setNameDialogOpen(false)}>{t('common.cancel')}</Button>
              <Button type="submit" disabled={pendingRegistration === null || finishRegistration.isPending}>{t('common.save')}</Button>
            </DialogFooter>
          </ActionForm>
        </DialogContent>
      </Dialog>

      <Dialog open={renameDialogOpen} onOpenChange={setRenameDialogOpen}>
        <DialogContent aria-describedby={undefined}>
          <ActionForm className="space-y-4" action={{ submit: handleRename, canSubmit: renameTarget !== null && renameName.trim() !== '' }}>
            <DialogHeader>
              <DialogTitle>{t('settings.passkey.renamePasskey')}</DialogTitle>
            </DialogHeader>
            <Input
              value={renameName}
              aria-label={t('settings.passkey.renamePasskey')}
              onChange={(e) => setRenameName(e.target.value)}
            />
            <DialogFooter>
              <Button variant="outline" onClick={() => setRenameDialogOpen(false)}>{t('common.cancel')}</Button>
              <Button type="submit" disabled={renameTarget === null || renameName.trim() === ''}>{t('settings.passkey.rename')}</Button>
            </DialogFooter>
          </ActionForm>
        </DialogContent>
      </Dialog>

      <AlertDialog open={deleteDialogOpen} onOpenChange={setDeleteDialogOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('settings.passkey.removePasskey')}</AlertDialogTitle>
            <AlertDialogDescription>
              {t('settings.passkey.removePasskeyConfirm', { name: deleteTarget?.name })}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t('common.cancel')}</AlertDialogCancel>
            <AlertDialogAction onClick={handleDelete} className="bg-destructive text-destructive-foreground hover:bg-destructive/90">{t('common.remove')}</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
      <CredentialProofDialog request={proofRequest} />
    </section>
  );
};

const PasskeySettings = () => (arePasskeysAvailable() ? <PasskeyManager /> : null);

export default PasskeySettings;
