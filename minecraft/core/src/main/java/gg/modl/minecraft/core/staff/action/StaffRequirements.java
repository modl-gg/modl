package gg.modl.minecraft.core.staff.action;

import gg.modl.minecraft.core.staff.PermissionUtil;
import gg.modl.minecraft.core.util.Permissions;

import java.util.function.Predicate;

public final class StaffRequirements {
    public static final StaffRequirement STAFF = StaffRequirement.builder().staffOnly(true).staff2fa(true).build();
    public static final StaffRequirement VIEW_PLAYERS =
            StaffRequirement.builder().staffOnly(true).playerView(true).staff2fa(true).build();

    public static final StaffRequirement ADD_PLAYER_NOTE = withPermission(Permissions.PLAYER_NOTE);
    public static final StaffRequirement PARDON_PUNISHMENT = withPermission(Permissions.PUNISHMENT_MODIFY_PARDON);
    public static final StaffRequirement CHANGE_PUNISHMENT_DURATION = withPermission(Permissions.PUNISHMENT_MODIFY_DURATION);
    public static final StaffRequirement ADD_PUNISHMENT_NOTE = withPermission(Permissions.PUNISHMENT_MODIFY_NOTE);
    public static final StaffRequirement MANAGE_PUNISHMENT_EVIDENCE = withPermission(Permissions.PUNISHMENT_MODIFY_EVIDENCE);
    public static final StaffRequirement CHANGE_PUNISHMENT_OPTIONS = withPermission(Permissions.PUNISHMENT_MODIFY_OPTIONS);
    public static final StaffRequirement LINK_PUNISHMENT_TICKETS = withPermission(Permissions.PUNISHMENT_MODIFY_TICKETS);
    public static final StaffRequirement CLOSE_REPORTS = withPermission(Permissions.TICKET_CLOSE_ALL);
    public static final StaffRequirement INTERCEPT_CHAT = withPermission(Permissions.INTERCEPT);
    public static final StaffRequirement RELOAD_CONFIGURATION =
            StaffRequirement.builder().adminOnly(true).staff2fa(true).build();
    public static final StaffRequirement MANAGE_STAFF_MEMBERS = withPermission(Permissions.STAFF_MANAGE_MEMBERS);
    public static final StaffRequirement MANAGE_ROLES = withPermission(Permissions.STAFF_MANAGE_ROLES);

    private StaffRequirements() {
    }

    public static boolean allowsShortening(Predicate<StaffRequirement> allows) {
        return allows.test(CHANGE_PUNISHMENT_DURATION) && allows.test(PARDON_PUNISHMENT);
    }

    public static StaffRequirement applyPunishment(int typeOrdinal) {
        return withPermission(PermissionUtil.punishmentApplyPermission(typeOrdinal));
    }

    private static StaffRequirement withPermission(String permission) {
        return StaffRequirement.builder().permission(permission).staff2fa(true).build();
    }
}
