import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
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
import { useSignInMethod } from '@/hooks/use-public-settings';
import {
  useCreatePasswordResetLink,
  useResetSecondFactor,
  useStaffSecurity,
  type PasswordResetLink,
  type StaffSecurityMember,
} from '@/hooks/data/staff-security';
import { useAuthFailureMessage } from '@/hooks/use-auth-failure-message';
import { useToast } from '@/hooks/use-toast';
import { PasswordResetLinkDialog } from './PasswordResetLinkDialog';

function MemberActions({ staffId, member }: { staffId: string; member: StaffSecurityMember }) {
  const { t } = useTranslation();
  const { toast } = useToast();
  const failureMessage = useAuthFailureMessage();
  const signInMethod = useSignInMethod();
  const createLink = useCreatePasswordResetLink();
  const resetFactor = useResetSecondFactor();
  const [link, setLink] = useState<PasswordResetLink | null>(null);
  const [confirmingReset, setConfirmingReset] = useState(false);

  const showFailure = (error: unknown) => {
    toast({ title: t('toast.error'), description: failureMessage(error), variant: 'destructive' });
  };

  return (
    <div className="flex flex-wrap gap-2">
      {signInMethod === 'password' && (
        <Button
          type="button"
          size="sm"
          variant="outline"
          disabled={createLink.isPending}
          onClick={() => createLink.mutate(staffId, { onSuccess: setLink, onError: showFailure })}
        >
          {t('settings.staff.security.createResetLink')}
        </Button>
      )}
      {member.hasSecondFactor && (
        <Button type="button" size="sm" variant="outline" disabled={resetFactor.isPending} onClick={() => setConfirmingReset(true)}>
          {t('settings.staff.security.resetFactor')}
        </Button>
      )}
      <PasswordResetLinkDialog link={link} onClose={() => setLink(null)} />
      <AlertDialog open={confirmingReset} onOpenChange={setConfirmingReset}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('settings.staff.security.resetFactor')}</AlertDialogTitle>
            <AlertDialogDescription>{t('settings.staff.security.resetFactorDesc')}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t('common.cancel')}</AlertDialogCancel>
            <AlertDialogAction onClick={() => resetFactor.mutate(staffId, { onError: showFailure })}>
              {t('settings.staff.security.resetFactor')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

export function StaffSecurityList() {
  const { t } = useTranslation();
  const { data: members } = useStaffSecurity();

  if (!members) return null;

  return (
    <section className="space-y-3">
      <h3 className="text-sm font-semibold">{t('settings.staff.security.title')}</h3>
      <ul className="divide-y rounded-md border">
        {members.map((member) => (
          <li key={member.email} className="flex flex-wrap items-center justify-between gap-2 p-3">
            <div className="min-w-0 space-y-1">
              <p className="break-all text-sm">{member.email}</p>
              <div className="flex flex-wrap gap-1">
                <Badge variant="outline">
                  {t(member.hasPassword ? 'settings.staff.security.password' : 'settings.staff.security.noPassword')}
                </Badge>
                {member.hasSecondFactor && <Badge variant="outline">{t('settings.staff.security.twoFactor')}</Badge>}
              </div>
            </div>
            {!member.owner && member.staffId !== null && <MemberActions staffId={member.staffId} member={member} />}
          </li>
        ))}
      </ul>
    </section>
  );
}
