package gg.modl.backend.infrastructure.authorization;

import gg.modl.backend.role.service.PermissionService;

public enum ReplaySharingPolicy implements PanelAccessPolicy {
    INSTANCE;

    @Override
    public boolean permitsWithoutRole(PanelAccessRequest request) {
        return false;
    }

    @Override
    public boolean permitsWithRole(PanelAccessRequest request, PanelPrincipalPermissions permissions) {
        return permissions.canViewPlayers() && permissions.has(PermissionService.REPLAY_SHARE);
    }
}
