import { useMemo, useCallback } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useAuth } from './use-auth';
import { PanelPermissionsResponseSchema } from '@proto/modl/v1/auth_pb.ts';
import { buildRoleHierarchy, canModifyRole, canRemoveUser, type RoleHierarchyInfo } from '@/utils/role-hierarchy';
import { protoFetch } from '@/lib/proto-fetch';
import { RATE_LIMIT_AWARE_RETRY } from '@/lib/query-retry';
import { PERMISSIONS, canViewPlayers } from '@/lib/permissions';
import { shouldLoadSettingsData, type SettingsAccess, type SettingsData } from '@/lib/settings-access';
import { useRoles } from '@/hooks/data/staff';

export { PERMISSIONS };

export const SUPER_ADMIN_ROLE = 'Super Admin';

const NO_PERMISSIONS: readonly string[] = [];

export function usePermissions() {
  const { user } = useAuth();

  const role = user?.role ?? '';
  const {
    data: serverPermissions,
    isPending: isPermissionsQueryPending,
    isError: isPermissionsQueryError,
    refetch: refetchPermissions,
  } = useQuery({
    queryKey: ['userPermissions', user?.role],
    queryFn: async () => {
      const response = await protoFetch(PanelPermissionsResponseSchema, '/v1/panel/auth/permissions');
      return response.permissions;
    },
    enabled: role.length > 0,
    staleTime: 5 * 60 * 1000,
    ...RATE_LIMIT_AWARE_RETRY,
  });

  const isPermissionsLoading = role.length > 0 && isPermissionsQueryPending;
  const isPermissionsError = role.length > 0 && isPermissionsQueryError && serverPermissions === undefined;
  const retryPermissions = useCallback(() => {
    void refetchPermissions();
  }, [refetchPermissions]);

  const userPermissions = serverPermissions ?? NO_PERMISSIONS;

  const grantedPermissions = useMemo(() => new Set(userPermissions), [userPermissions]);

  const isSuperAdmin = user?.role === SUPER_ADMIN_ROLE;

  const hasPermission = useCallback((permission: string): boolean => {
    if (!user) return false;
    return grantedPermissions.has(permission);
  }, [user, grantedPermissions]);

  const hasAllPermissions = useCallback((permissions: readonly string[]): boolean => {
    if (!user) return false;
    return permissions.every(permission => hasPermission(permission));
  }, [user, hasPermission]);

  const hasAnyPermission = useCallback((permissions: readonly string[]): boolean => {
    if (!user) return false;
    return permissions.some(permission => hasPermission(permission));
  }, [user, hasPermission]);

  const settingsAccess = useMemo<SettingsAccess>(
    () => ({ isSuperAdmin, hasPermission }),
    [isSuperAdmin, hasPermission]
  );


  const canLoadSettingsData = useCallback(
    (data: SettingsData): boolean => shouldLoadSettingsData(data, settingsAccess),
    [settingsAccess]
  );

  const canReadPunishmentTypes = useMemo(
    () => hasPermission(PERMISSIONS.ADMIN_SETTINGS_VIEW_PUNISHMENTS) || canViewPlayers(userPermissions),
    [hasPermission, userPermissions]
  );

  const canListStaff = hasPermission(PERMISSIONS.ADMIN_STAFF_MANAGE_MEMBERS);
  const canTagTickets = hasPermission(PERMISSIONS.TICKET_MANAGE_TAGS);
  const canHideTickets = hasPermission(PERMISSIONS.TICKET_MANAGE_HIDE);
  const canCloseTickets = hasPermission(PERMISSIONS.TICKET_CLOSE_ALL);
  const canAssignTickets = hasPermission(PERMISSIONS.TICKET_REPLY_ALL);
  const canAddPlayerNotes = hasPermission(PERMISSIONS.PLAYER_NOTE);
  const canModifyPunishments = hasPermission(PERMISSIONS.PUNISHMENT_MODIFY);

  const { data: rolesData } = useRoles({ enabled: hasPermission(PERMISSIONS.ADMIN_STAFF_MANAGE_ROLES) });

  const roles = rolesData?.roles;
  const roleHierarchy = useMemo(
    () => (roles ? buildRoleHierarchy(roles) : new Map<string, RoleHierarchyInfo>()),
    [roles]
  );

  const canModifyUserRole = (targetUserRole: string, newRole?: string, _targetUserId?: string): boolean => {
    if (!user) return false;
    if (!newRole) {
      if (isSuperAdmin && targetUserRole !== SUPER_ADMIN_ROLE) {
        return true;
      }
      const currentRoleInfo = roleHierarchy.get(user.role);
      const targetRoleInfo = roleHierarchy.get(targetUserRole);
      if (!currentRoleInfo || !targetRoleInfo) return false;
      return currentRoleInfo.order < targetRoleInfo.order;
    }
    return canModifyRole(user.role, targetUserRole, newRole, roleHierarchy);
  };

  const canRemoveStaffUser = (targetUserRole: string): boolean => {
    if (!user) return false;
    return canRemoveUser(user.role, targetUserRole, roleHierarchy);
  };

  const canAssignStaffMinecraftPlayer = (targetUserRole: string): boolean =>
    isSuperAdmin || canModifyUserRole(targetUserRole);

  return {
    userPermissions,
    isPermissionsLoading,
    isPermissionsError,
    retryPermissions,
    isSuperAdmin,
    hasPermission,
    hasAllPermissions,
    hasAnyPermission,
    settingsAccess,
    canLoadSettingsData,
    canReadPunishmentTypes,
    canListStaff,
    canTagTickets,
    canHideTickets,
    canCloseTickets,
    canAssignTickets,
    canAddPlayerNotes,
    canModifyPunishments,
    canModifyUserRole,
    canRemoveStaffUser,
    canAssignStaffMinecraftPlayer,
    roleHierarchy,
  };
}
