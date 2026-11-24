package gg.modl.backend.infrastructure.authorization;

import gg.modl.backend.role.service.PermissionService;
import java.util.List;

public enum PunishmentModificationAccessPolicy implements PanelAccessPolicy {
    INSTANCE;

    private static final List<String> MODIFICATION_PERMISSIONS = List.of(
        PermissionService.PUNISHMENT_MODIFY_PARDON,
        PermissionService.PUNISHMENT_MODIFY_DURATION,
        PermissionService.PUNISHMENT_MODIFY_OPTIONS);

    @Override
    public boolean permitsWithoutRole(PanelAccessRequest request) {
        return false;
    }

    @Override
    public boolean permitsWithRole(PanelAccessRequest request, PanelPrincipalPermissions permissions) {
        return !request.isReadOnly() && MODIFICATION_PERMISSIONS.stream().anyMatch(permissions::has);
    }
}
