package gg.modl.backend.infrastructure.authorization;

import gg.modl.backend.role.service.PermissionService;

public enum PlayerAccessPolicy implements PanelAccessPolicy {
    INSTANCE;

    @Override
    public boolean permitsWithoutRole(PanelAccessRequest request) {
        return false;
    }

    @Override
    public boolean permitsWithRole(PanelAccessRequest request, PanelPrincipalPermissions permissions) {
        if (request.isReadOnly()) {
            return permissions.canViewPlayers();
        }
        return permissions.has(PermissionService.PUNISHMENT_MODIFY);
    }
}
