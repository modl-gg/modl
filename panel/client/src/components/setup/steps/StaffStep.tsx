import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Trash2 } from 'lucide-react';
import { IconAction } from '@/components/ui/icon-action';
import { InviteLinksDialog } from '@/components/settings/InviteLinksDialog';
import { InviteStaffForm } from '@/components/settings/InviteStaffForm';
import { useConfirmation } from '@/hooks/use-confirmation';
import { useAuth } from '@/hooks/use-auth';
import { useToast } from '@/hooks/use-toast';
import { isPendingInvitation, useRemoveStaffMember, useStaff, type InviteLink, type StaffMember } from '@/hooks/data/staff';
import { describeError } from '@/lib/http-error';
import { StepChoice, useStepChoice, type StepOption } from './StepChoice';
import type { SetupStepProps } from './step-props';

type StaffChoice = 'invite' | 'none';

const OPTIONS: readonly StepOption<StaffChoice>[] = [
  { value: 'invite', labelKey: 'pages.setup.staff.optionInvite' },
  { value: 'none', labelKey: 'pages.setup.staff.optionNone' },
];

function PendingInvitations({ invitations }: { invitations: StaffMember[] }) {
  const { t } = useTranslation();
  const { toast } = useToast();
  const revoke = useRemoveStaffMember();
  const confirmation = useConfirmation();

  const confirmRevoke = async (invitation: StaffMember) => {
    const confirmed = await confirmation.confirm({
      title: t('pages.setup.staff.revokeTitle'),
      description: t('pages.setup.staff.revokeDescription', { email: invitation.email }),
      action: t('pages.setup.staff.revoke'),
      destructive: true,
    });
    if (!confirmed) return;
    revoke.mutate(invitation.id, {
      onError: (error) => {
        toast({ title: t('toast.error'), description: describeError(error, t('pages.setup.staff.revokeFailed')), variant: 'destructive' });
      },
    });
  };

  return (
    <>
      {invitations.length > 0 && (
        <ul className="divide-y rounded-md border">
          {invitations.map((invitation) => (
            <li key={invitation.id} className="flex items-center justify-between gap-3 px-3 py-2">
              <div className="min-w-0">
                <p className="truncate text-sm">{invitation.email}</p>
                <p className="text-xs text-muted-foreground">{invitation.role}</p>
              </div>
              <IconAction
                icon={Trash2}
                label={t('pages.setup.staff.revoke')}
                destructive
                disabled={revoke.isPending && revoke.variables === invitation.id}
                onClick={() => { void confirmRevoke(invitation); }}
              />
            </li>
          ))}
        </ul>
      )}
      {confirmation.dialog}
    </>
  );
}

export function StaffStep({ done }: SetupStepProps) {
  const [inviteLinks, setInviteLinks] = useState<InviteLink[]>([]);
  const staff = useStaff();
  const invitations = (staff.data ?? []).filter(isPendingInvitation);
  const { user } = useAuth();
  const others = (staff.data ?? []).filter((member) => member.email !== user?.email);
  const choice = useStepChoice<StaffChoice>({ step: 'staff', options: OPTIONS, saved: others.length > 0 ? 'invite' : null, done, none: 'none' });

  return (
    <div className="max-w-md space-y-6">
      <StepChoice options={OPTIONS} {...choice} />
      {choice.value === 'invite' && (
        <>
          <InviteStaffForm onInvited={setInviteLinks} />
          <PendingInvitations invitations={invitations} />
        </>
      )}
      <InviteLinksDialog links={inviteLinks} onClose={() => setInviteLinks([])} />
    </div>
  );
}
