package gg.modl.minecraft.core.staff;

import gg.modl.minecraft.core.StaffAudience;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.util.Permissions;

public final class StaffAudiences {
    private final StaffAudience staff;
    private final Cache cache;

    public StaffAudiences(StaffAudience staff, Cache cache) {
        this.staff = staff;
        this.cache = cache;
    }

    public StaffAudience playerViewers() {
        return uuid -> staff.includes(uuid) && PermissionUtil.canViewPlayers(uuid, cache);
    }

    public StaffAudience ticketViewers() {
        return uuid -> staff.includes(uuid) && cache.hasPermission(uuid, Permissions.TICKET_VIEW_ALL);
    }
}
