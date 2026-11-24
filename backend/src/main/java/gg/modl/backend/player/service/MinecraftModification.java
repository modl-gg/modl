package gg.modl.backend.player.service;

import gg.modl.backend.role.service.PermissionService;

public enum MinecraftModification {
    PARDON(PermissionService.PUNISHMENT_MODIFY_PARDON),
    DURATION(PermissionService.PUNISHMENT_MODIFY_DURATION),
    OPTIONS(PermissionService.PUNISHMENT_MODIFY_OPTIONS),
    NOTE(PermissionService.PUNISHMENT_MODIFY_NOTE),
    EVIDENCE(PermissionService.PUNISHMENT_MODIFY_EVIDENCE),
    TICKETS(PermissionService.PUNISHMENT_MODIFY_TICKETS);

    private final String permission;

    MinecraftModification(String permission) {
        this.permission = permission;
    }

    public String permission() {
        return permission;
    }
}
