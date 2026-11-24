package gg.modl.backend.infrastructure.authorization;

import gg.modl.backend.role.service.PermissionService;

public enum PunishmentTypeAccessPolicy implements PanelAccessPolicy {
    INSTANCE;

    @Override
    public boolean permitsWithoutRole(PanelAccessRequest request) {
        return false;
    }

    @Override
    public boolean permitsWithRole(PanelAccessRequest request, PanelPrincipalPermissions permissions) {
        if (request.isReadOnly()) {
            return permissions.has(PermissionService.ADMIN_SETTINGS_VIEW_PUNISHMENTS)
                || permissions.canViewPlayers();
        }
        return permissions.has(PermissionService.ADMIN_SETTINGS_MODIFY_PUNISHMENTS);
    }
}
