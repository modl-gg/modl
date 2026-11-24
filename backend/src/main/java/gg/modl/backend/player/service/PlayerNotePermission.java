package gg.modl.backend.player.service;

import gg.modl.backend.player.data.PlayerNoteKind;
import gg.modl.backend.role.service.PermissionService;

public final class PlayerNotePermission {
    private PlayerNotePermission() {
    }

    public static String requiredFor(PlayerNoteKind kind) {
        return switch (kind) {
            case NOTE -> PermissionService.PLAYER_NOTE;
            case WARNING -> PermissionService.PLAYER_WARN;
        };
    }
}
