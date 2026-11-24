import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Gamepad2, Link, Mail, Trash2, UserCog, UserPlus, X, type LucideIcon } from 'lucide-react';
import { PERMISSIONS, SUPER_ADMIN_ROLE, usePermissions } from '@/hooks/use-permissions';
import { Badge } from '@/components/ui/badge';
import { GroupRow, GroupRows } from '@/components/ui/group-list';
import { IconAction } from '@/components/ui/icon-action';
import { Skeleton } from '@/components/ui/skeleton';
import ChangeRoleModal from './ChangeRoleModal';
import InviteStaffModal from './InviteStaffModal';
import { InviteLinksDialog } from './InviteLinksDialog';
import { isPendingInvitation, useRemoveStaffMember, useResendInvitation, useStaff, type InviteLink, type StaffMember } from '@/hooks/data/staff';
import { describeError } from '@/lib/http-error';
import { useEmailDelivery } from '@/hooks/use-public-settings';
import AssignMinecraftPlayerModal from './AssignMinecraftPlayerModal';
import { SettingsPageAction, SettingsPageActionButton } from './layout/SettingsPageFrame';
import { useToast } from '@/hooks/use-toast';
import { useConfirmation } from '@/hooks/use-confirmation';

interface MemberAction {
  id: string;
  icon: LucideIcon;
  label: string;
  destructive?: boolean;
  disabled?: boolean;
  run: () => void;
}

const StaffManagementPanel = () => {
  const { t } = useTranslation();
  const { data: staff, isLoading, isError } = useStaff();
  const { hasPermission, isSuperAdmin, canModifyUserRole, canRemoveStaffUser, canAssignStaffMinecraftPlayer } = usePermissions();
  const [isInviteModalOpen, setIsInviteModalOpen] = useState(false);
  const [isChangeRoleModalOpen, setIsChangeRoleModalOpen] = useState(false);
  const [isAssignPlayerModalOpen, setIsAssignPlayerModalOpen] = useState(false);
  const [selectedStaffMember, setSelectedStaffMember] = useState<StaffMember | null>(null);
  const [inviteLinks, setInviteLinks] = useState<InviteLink[]>([]);
  const { toast } = useToast();
  const resendInvitation = useResendInvitation();
  const removeStaffMember = useRemoveStaffMember();
  const emailDelivery = useEmailDelivery();
  const confirmation = useConfirmation();

  const openChangeRoleModal = (member: StaffMember) => {
    setSelectedStaffMember(member);
    setIsChangeRoleModalOpen(true);
  };

  const openAssignPlayerModal = (member: StaffMember) => {
    setSelectedStaffMember(member);
    setIsAssignPlayerModalOpen(true);
  };

  const confirmRemove = async (member: StaffMember) => {
    const pending = isPendingInvitation(member);
    const confirmed = await confirmation.confirm({
      title: t('common.areYouSure'),
      description: pending
        ? t('settings.staff.cancelInvitationConfirm', { email: member.email })
        : t('settings.staff.removeStaffConfirm', { email: member.email }),
      action: pending ? t('settings.staff.cancelInvitation') : t('common.remove'),
      destructive: true,
    });
    if (!confirmed) return;
    removeStaffMember.mutate(member.id, {
      onError: (error) => {
        toast({
          title: t('toast.error'),
          description: describeError(error, t('settings.staff.removeStaffFailed')),
          variant: 'destructive',
        });
      },
    });
  };

  const handleResendInvitation = (staffId: string) => {
    resendInvitation.mutate(staffId, {
      onSuccess: (links) => {
        if (links.length > 0) {
          setInviteLinks(links);
          return;
        }
        toast({
          title: t('toast.success'),
          description: t('settings.staff.invitationResent'),
        });
      },
      onError: (error) => {
        const fallback = emailDelivery ? t('settings.staff.resendInvitationFailed') : t('settings.staff.createInvitationLinkFailed');
        toast({
          title: t('toast.error'),
          description: describeError(error, fallback),
          variant: 'destructive',
        });
      },
    });
  };

  const memberActions = (member: StaffMember): MemberAction[] => {
    if (!isSuperAdmin && !hasPermission(PERMISSIONS.ADMIN_STAFF_MANAGE_MEMBERS)) return [];
    if (isPendingInvitation(member)) {
      return [
        {
          id: 'resend',
          icon: emailDelivery ? Mail : Link,
          label: emailDelivery ? t('settings.staff.resendInvitation') : t('settings.staff.createInvitationLink'),
          disabled: resendInvitation.isPending,
          run: () => { handleResendInvitation(member.id); },
        },
        { id: 'cancel', icon: X, label: t('settings.staff.cancelInvitation'), destructive: true, run: () => { void confirmRemove(member); } },
      ];
    }
    const actions: MemberAction[] = [];
    if (canAssignStaffMinecraftPlayer(member.role)) {
      actions.push({
        id: 'minecraft',
        icon: Gamepad2,
        label: member.assignedMinecraftUsername ? t('settings.staff.changeMinecraftPlayer') : t('settings.staff.assignMinecraftPlayer'),
        run: () => { openAssignPlayerModal(member); },
      });
    }
    if (canModifyUserRole(member.role) && member.role !== SUPER_ADMIN_ROLE) {
      actions.push({ id: 'role', icon: UserCog, label: t('settings.staff.changeRole'), run: () => { openChangeRoleModal(member); } });
    }
    if (canRemoveStaffUser(member.role)) {
      actions.push({ id: 'remove', icon: Trash2, label: t('settings.staff.removeStaffMember'), destructive: true, run: () => { void confirmRemove(member); } });
    }
    return actions;
  };

  return (
    <>
      <div className="space-y-4">
        <SettingsPageAction>
          <SettingsPageActionButton icon={UserPlus} label={t('settings.staff.invite')} onClick={() => setIsInviteModalOpen(true)} />
        </SettingsPageAction>
        {isLoading ? (
          <div className="space-y-4">
            <Skeleton className="h-12 w-full" />
            <Skeleton className="h-12 w-full" />
            <Skeleton className="h-12 w-full" />
          </div>
        ) : isError ? (
          <p className="py-8 text-center text-sm text-destructive-text">{t('settings.staff.loadFailed')}</p>
        ) : staff && staff.length > 0 ? (
          <GroupRows>
            {staff.map((member) => (
              <GroupRow
                key={member.id}
                title={member.email}
                meta={(
                  <>
                    <span className="shrink-0 text-xs text-muted-foreground">{member.role}</span>
                    {isPendingInvitation(member) && <Badge variant="secondary">{t('settings.staff.pendingInvitation')}</Badge>}
                  </>
                )}
                detail={member.assignedMinecraftUsername}
                actions={memberActions(member).map((action) => (
                  <IconAction
                    key={action.id}
                    icon={action.icon}
                    label={action.label}
                    destructive={action.destructive ?? false}
                    disabled={action.disabled ?? false}
                    onClick={action.run}
                  />
                ))}
              />
            ))}
          </GroupRows>
        ) : (
          <p className="py-8 text-center text-sm text-muted-foreground">{t('settings.staff.noStaffFound')}</p>
        )}
      </div>
      <InviteStaffModal
        isOpen={isInviteModalOpen}
        onClose={() => setIsInviteModalOpen(false)}
        onInviteLinks={setInviteLinks}
      />
      <InviteLinksDialog links={inviteLinks} onClose={() => setInviteLinks([])} />
      <ChangeRoleModal
        isOpen={isChangeRoleModalOpen}
        onClose={() => {
          setIsChangeRoleModalOpen(false);
          setSelectedStaffMember(null);
        }}
        staffMember={selectedStaffMember}
      />
      <AssignMinecraftPlayerModal
        isOpen={isAssignPlayerModalOpen}
        onClose={() => {
          setIsAssignPlayerModalOpen(false);
          setSelectedStaffMember(null);
        }}
        staffMember={selectedStaffMember}
      />
      {confirmation.dialog}
    </>
  );
};

export default StaffManagementPanel;
