import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
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
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Label } from '@/components/ui/label';
import { useToast } from '@/hooks/use-toast';
import { useChangeStaffRole, useRoles } from '@/hooks/data/staff';
import { describeError } from '@/lib/http-error';

interface StaffMember {
  id: string;
  email: string;
  role: string;
}

const SUPER_ADMIN_ROLE = 'Super Admin';

interface RoleSelection {
  staffId: string;
  baseRole: string;
  role: string;
}

interface ChangeRoleModalProps {
  isOpen: boolean;
  onClose: () => void;
  staffMember: StaffMember | null;
}

const ChangeRoleModal: React.FC<ChangeRoleModalProps> = ({ isOpen, onClose, staffMember }) => {
  const { t } = useTranslation();
  const { toast } = useToast();
  const changeStaffRole = useChangeStaffRole();
  const [roleSelection, setRoleSelection] = useState<RoleSelection | null>(null);

  const { data: rolesData } = useRoles();

  const availableRoles = (rolesData?.roles ?? []).filter((role) => role.name !== SUPER_ADMIN_ROLE);
  const selectedRole = roleSelection !== null
    && roleSelection.staffId === staffMember?.id
    && roleSelection.baseRole === staffMember.role
    ? roleSelection.role
    : staffMember?.role ?? '';

  const handleClose = () => {
    setRoleSelection(null);
    onClose();
  };

  const handleRoleChange = () => {
    if (!staffMember || !selectedRole) return;

    if (staffMember.role === SUPER_ADMIN_ROLE) {
      toast({
        title: t('toast.error'),
        description: t('settings.staff.superAdminRoleCannotBeChanged'),
        variant: 'destructive',
      });
      return;
    }

    changeStaffRole.mutate({ staffId: staffMember.id, role: selectedRole }, {
      onSuccess: handleClose,
      onError: (error) => {
        toast({
          title: t('toast.error'),
          description: describeError(error, t('settings.staff.changeRoleFailed')),
          variant: 'destructive',
        });
      },
    });
  };

  if (!staffMember) return null;

  return (
    <AlertDialog open={isOpen} onOpenChange={handleClose}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{t('settings.staff.changeRoleFor', { email: staffMember.email })}</AlertDialogTitle>
          <AlertDialogDescription>
            {staffMember.role === SUPER_ADMIN_ROLE
              ? t('settings.staff.superAdminRoleCannotBeChanged')
              : t('settings.staff.selectNewRole')}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <div className="grid gap-4 py-4">
          <div className="grid grid-cols-4 items-center gap-4">
            <Label htmlFor="role" className="text-right">
              {t('settings.staff.role')}
            </Label>
            <Select
              value={selectedRole}
              onValueChange={(value) => setRoleSelection({ staffId: staffMember.id, baseRole: staffMember.role, role: value })}
              disabled={availableRoles.length === 0 || staffMember.role === SUPER_ADMIN_ROLE}
            >
              <SelectTrigger className="col-span-3">
                <SelectValue placeholder={t('settings.staff.selectRole')} />
              </SelectTrigger>
              <SelectContent>
                {availableRoles.map((role) => (
                  <SelectItem key={role.id} value={role.name}>
                    {role.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        </div>
        <AlertDialogFooter>
          <AlertDialogCancel onClick={handleClose}>{t('common.cancel')}</AlertDialogCancel>
          <AlertDialogAction
            onClick={handleRoleChange}
            disabled={!selectedRole || selectedRole === staffMember.role || staffMember.role === SUPER_ADMIN_ROLE}
          >
            {t('common.confirm')}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
};

export default ChangeRoleModal;