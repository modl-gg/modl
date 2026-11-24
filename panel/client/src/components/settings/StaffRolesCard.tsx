import { useCallback, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Plus } from 'lucide-react';
import { GroupRows } from '@/components/ui/group-list';
import { STAFF_PERMISSION_CATEGORIES, useDeleteRole, usePermissions, useReorderRoles, useRoles, type StaffPermission, type StaffRole } from '@/hooks/data/staff';
import { useAuth } from '@/hooks/use-auth';
import { useConfirmation } from '@/hooks/use-confirmation';
import { useFlipList } from '@/hooks/use-flip';
import { SUPER_ADMIN_ROLE, usePermissions as useUserPermissions } from '@/hooks/use-permissions';
import { useToast } from '@/hooks/use-toast';
import { describeError } from '@/lib/http-error';
import { indexRoleCatalog } from '@/lib/role-catalog';
import { canEditRole, editableRoleOrder, type RoleEditor } from '@/lib/role-order';
import { RoleCard } from './RoleCard';
import { RoleEditorDialog } from './RoleEditorDialog';
import { SettingsPageAction, SettingsPageActionButton, useSettingsPageCanModify } from './layout/SettingsPageFrame';
import { SettingsLoading } from './layout/SettingsSections';
import { SettingsDndProvider } from './save/SettingsDndProvider';
import { useSavedOrder } from './save/use-saved-order';

const NO_ROLES: readonly StaffRole[] = [];
const NO_PERMISSIONS: readonly StaffPermission[] = [];
const UNRANKED_ORDER = Number.MAX_SAFE_INTEGER;

interface RoleEditorTarget {
  role: StaffRole | null;
}

function isSuperAdminRole(role: StaffRole): boolean {
  return role.name === SUPER_ADMIN_ROLE || role.id === 'super-admin';
}

export default function StaffRolesCard() {
  const { t } = useTranslation();
  const { toast } = useToast();
  const { data: rolesData, isLoading: rolesLoading } = useRoles();
  const { data: permissionsData, isLoading: permissionsLoading } = usePermissions();
  const { user } = useAuth();
  const { hasPermission, isSuperAdmin } = useUserPermissions();
  const deleteRole = useDeleteRole();
  const { mutateAsync: reorderRoles } = useReorderRoles();
  const [editorTarget, setEditorTarget] = useState<RoleEditorTarget | null>(null);
  const confirmation = useConfirmation();
  const canModify = useSettingsPageCanModify();

  const roles = rolesData?.roles ?? NO_ROLES;
  const permissionCatalog = permissionsData?.permissions ?? NO_PERMISSIONS;
  const catalog = useMemo(() => indexRoleCatalog(permissionCatalog, STAFF_PERMISSION_CATEGORIES), [permissionCatalog]);
  const editorOrder = roles.find((role) => role.name === user?.role)?.order ?? UNRANKED_ORDER;
  const editor = useMemo<RoleEditor>(() => ({ superAdmin: isSuperAdmin, order: editorOrder }), [isSuperAdmin, editorOrder]);
  const rankedRoles = useMemo(
    () => roles.filter((role) => !isSuperAdminRole(role)).map((role) => ({ ...role, ordinal: role.order })),
    [roles],
  );
  const isRoleEditable = useCallback((role: StaffRole) => canModify && canEditRole(role, editor), [canModify, editor]);
  const roleOrder = useSavedOrder(rankedRoles, async (ordered) => {
    const positions = editableRoleOrder(ordered, editor);
    if (positions.length > 0) await reorderRoles(positions);
  }, isRoleEditable);
  const roleList = useFlipList<HTMLDivElement>(roleOrder.items.map((role) => role.id));

  if (user === null || rolesLoading || permissionsLoading) return <SettingsLoading />;

  const canGrant = (permissionId: string) => isSuperAdmin || hasPermission(permissionId);

  const removeRole = async (role: StaffRole) => {
    const confirmed = await confirmation.confirm({
      title: t('settings.roles.deleteRoleTitle'),
      description: t('settings.roles.deleteRoleConfirm', { name: role.name }),
      action: t('common.delete'),
      destructive: true,
    });
    if (!confirmed) return;
    deleteRole.mutate(role.id, {
      onError: (error) => {
        toast({ title: t('toast.error'), description: describeError(error, t('settings.roles.deleteRoleFailed')), variant: 'destructive' });
      },
    });
  };

  return (
    <>
      <SettingsPageAction>
        <SettingsPageActionButton icon={Plus} label={t('settings.roles.createRole')} onClick={() => { setEditorTarget({ role: null }); }} />
      </SettingsPageAction>
      <SettingsDndProvider>
        <GroupRows ref={roleList.ref}>
          {roleOrder.items.map((role, index) => (
            <RoleCard
              key={role.id}
              role={role}
              index={index}
              count={roleOrder.items.length}
              editable={isRoleEditable(role)}
              catalog={catalog}
              isSettling={roleList.isSettling}
              canMove={roleOrder.canMove}
              onMove={roleOrder.move}
              onEdit={(edited) => { setEditorTarget({ role: edited }); }}
              onDelete={(deleted) => { void removeRole(deleted); }}
            />
          ))}
        </GroupRows>
      </SettingsDndProvider>

      {editorTarget !== null && (
        <RoleEditorDialog role={editorTarget.role} catalog={catalog} canGrant={canGrant} onClose={() => { setEditorTarget(null); }} />
      )}

      {confirmation.dialog}
    </>
  );
}
