package gg.modl.backend.infrastructure.authorization;

import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import org.jspecify.annotations.Nullable;

public record PanelPrincipalPermissions(Server server, @Nullable String roleId, boolean superAdmin, PermissionService permissionService) {
    public boolean has(String permission) {
        return superAdmin || permissionService.hasPermission(server, roleId, permission);
    }

    public boolean hasAnyWithPrefix(String prefix) {
        return superAdmin || permissionService.hasAnyPermissionWithPrefix(server, roleId, prefix);
    }

    public boolean canViewPlayers() {
        return has(PermissionService.PUNISHMENT_VIEW)
            || has(PermissionService.PUNISHMENT_MODIFY)
            || hasAnyWithPrefix(PermissionService.PUNISHMENT_APPLY_PREFIX);
    }
}
